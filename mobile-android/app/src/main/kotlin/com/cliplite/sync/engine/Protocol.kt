package com.cliplite.sync.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * ClipSync 协议 v0.3 帧/事件模型（docs/clip-sync-protocol.md 冻结稿）。
 * 服务端帧先解析为 JsonObject 再按 type 分发，事件体单独反序列化。
 */
object Protocol {
    // encodeDefaults 必须为 true：HelloFrame/PingFrame/ClipAdd 的 type/v/kind 均为
    // 默认值字段，false 时会被整体省略——服务端收到无 type 的 hello 直接 bad_json
    // 断开（表现为 EOFException 重连死循环），事件体缺 kind 则对端不落地。
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---------- 客户端 → 服务端 ----------

    @Serializable
    data class HelloFrame(val v: Int = 1, val type: String = "hello", val token: String, val lastSeq: Long)

    @Serializable
    data class PingFrame(val v: Int = 1, val type: String = "ping")

    /** publish 帧：events 保持原始 JsonObject，避免二次编解码 */
    @Serializable
    data class PublishFrame(
        val v: Int = 1,
        val type: String = "publish",
        val clientEventId: String,
        val events: List<JsonObject>,
    )

    // ---------- 事件体 ----------

    @Serializable
    data class ClipBundle(val fileCount: Int, val totalBytes: Long, val payloadB64: String)

    /**
     * 跨端 uint64 兼容序列化器：
     * - 序列化（发送）：输出 JSON 数字（PC 端 C++ 用 asInt() 解析，必须是 Number 类型）
     * - 反序列化（接收）：读原始 content 文本，绝不做 Long 数值解析（PC 端 uint64 可能超
     *   Long.MAX_VALUE，若走严格 Long 反序列化，kotlinx.serialization 会把整个 event 的
     *   JsonObject toString 尝试转换，触发 OOM 闪退）
     */
    object U64Serializer : KSerializer<String> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("U64String", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: String) {
            val n = value.toLongOrNull() ?: value.toULongOrNull()?.toLong() ?: 0L
            // 输出为 JSON 数字（长整型），保持与 PC 端 uint64 位模式一致
            encoder.encodeLong(n)
        }
        override fun deserialize(decoder: Decoder): String {
            // 读原始 JsonElement 的 content（数字文本），避免数值溢出
            val el = (decoder as? JsonDecoder)?.decodeJsonElement()
            if (el is JsonPrimitive) return el.content
            return el?.toString()?.trim('"') ?: ""
        }
    }

    @Serializable
    data class ClipAdd(
        val kind: String = "clip.add",
        val syncId: String,
        val clipType: Int,
        @Serializable(with = U64Serializer::class)
        val hash: String,
        val payloadB64: String? = null,
        val bundle: ClipBundle? = null,
        val localOnly: Boolean = false,
        val sourceApp: String? = null,
        @Serializable(with = U64Serializer::class)
        val ts: String,
    ) {
        /** hash 转 Long：保留 64 位位模式（PC 端 uint64 可能超 Long.MAX，仅用于去重比对/落库） */
        val hashLong: Long get() = hash.toLongOrNull() ?: hash.toULongOrNull()?.toLong() ?: 0L
        /** ts 转 Long：毫秒时间戳，正常在 Long 范围内 */
        val tsLong: Long get() = ts.toLongOrNull() ?: 0L
    }

    @Serializable
    data class ClipDelete(val kind: String = "clip.delete", val syncId: String)

    @Serializable
    data class ClipPin(val kind: String = "clip.pin", val syncId: String, val pinned: Boolean)

    // ---------- 服务端帧的解析结果 ----------

    sealed class ServerFrame {
        data class HelloOk(val seq: Long, val replayed: Int) : ServerFrame()
        data class Event(val seq: Long, val byDevice: String, val event: JsonObject) : ServerFrame()
        data class PublishOk(val clientEventId: String, val seq: Long) : ServerFrame()
        data class Error(val code: String, val detail: String) : ServerFrame()
        object Pong : ServerFrame()
        data class Unknown(val type: String) : ServerFrame()
    }

    fun encode(frame: HelloFrame): String = json.encodeToString(HelloFrame.serializer(), frame)
    fun encode(frame: PingFrame): String = json.encodeToString(PingFrame.serializer(), frame)
    fun encode(frame: PublishFrame): String = json.encodeToString(PublishFrame.serializer(), frame)

    fun decode(text: String): ServerFrame {
        val obj = json.parseToJsonElement(text).jsonObject
        fun seq(): Long = obj["seq"]?.jsonPrimitive?.long ?: 0L
        return when (val type = obj["type"]?.toString()?.trim('"')) {
            "hello.ok" -> ServerFrame.HelloOk(
                seq = seq(),
                replayed = obj["replayed"]?.toString()?.trim('"')?.toIntOrNull() ?: 0,
            )
            "event" -> ServerFrame.Event(
                seq = seq(),
                byDevice = obj["byDevice"]?.toString()?.trim('"') ?: "",
                event = obj["event"]?.jsonObject ?: buildJsonObject { },
            )
            "publish.ok" -> ServerFrame.PublishOk(
                clientEventId = obj["clientEventId"]?.toString()?.trim('"') ?: "",
                seq = seq(),
            )
            "error" -> ServerFrame.Error(
                code = obj["code"]?.toString()?.trim('"') ?: "",
                detail = obj["detail"]?.toString()?.trim('"') ?: "",
            )
            "pong" -> ServerFrame.Pong
            else -> ServerFrame.Unknown(type ?: "")
        }
    }

    fun decodeClipEvent(event: JsonObject): ClipAdd? {
        return try {
            if (event["kind"]?.toString()?.trim('"') != "clip.add") return null
            // 手动取字段：hash/ts 用 jsonPrimitive 原始内容（数字可能超 Long 范围），
            // 绝不走 decodeFromJsonElement 的严格 Long 反序列化（会 toString 整个对象 → OOM）
            fun str(key: String): String = event[key]?.jsonPrimitive?.content ?: ""
            val hash = str("hash")
            val ts = str("ts")
            if (hash.isEmpty() || ts.isEmpty()) {
                // 缺失关键字段：跳过，避免后续 NPE/异常
                return null
            }
            ClipAdd(
                syncId = str("syncId"),
                clipType = event["clipType"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                hash = hash,
                payloadB64 = event["payloadB64"]?.jsonPrimitive?.content,
                bundle = event["bundle"]?.jsonObject?.let { b ->
                    ClipBundle(
                        fileCount = b["fileCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        totalBytes = b["totalBytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                        payloadB64 = b["payloadB64"]?.jsonPrimitive?.content ?: "",
                    )
                },
                localOnly = event["localOnly"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                sourceApp = event["sourceApp"]?.jsonPrimitive?.content,
                ts = ts,
            )
        } catch (_: Exception) { null }
    }

    /** 64 位 FNV-1a（本地指纹用；跨端 hash 对齐留待 M2 与桌面端统一算法） */
    fun hash64(bytes: ByteArray): Long {
        var h = 0xcbf29ce484222325UL
        for (b in bytes) {
            h = h xor (b.toULong() and 0xffUL)
            h *= 0x100000001b3UL
        }
        return h.toLong()
    }

    /** hash（Long 位模式）→ 无符号十进制字符串，与 PC 端 uint64 的 JSON 输出一致 */
    fun u64(hash: Long): String = hash.toULong().toString()
}
