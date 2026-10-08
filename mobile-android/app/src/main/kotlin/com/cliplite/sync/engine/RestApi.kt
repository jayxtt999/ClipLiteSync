package com.cliplite.sync.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** REST 管理面（server/main.go 路由，Bearer 设备 token 鉴权） */
class RestApi(baseAddr: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val baseUrl: String = normalizeHttp(baseAddr)

    // ---------- 响应模型 ----------

    @Serializable
    data class RegisterResp(val status: String, val deviceId: String, val deviceToken: String)

    @Serializable
    data class JoinResp(val status: String, val requestId: String? = null,
                        val deviceId: String? = null, val deviceToken: String? = null)

    @Serializable
    data class JoinStatusResp(val status: String, val deviceId: String? = null, val deviceToken: String? = null)

    @Serializable
    data class InviteResp(val code: String, val expiresAt: Long)

    @Serializable
    data class PeerItem(val deviceId: String, val name: String, val createdAt: Long)

    @Serializable
    data class PeersResp(val peers: List<PeerItem>)

    @Serializable
    data class RoomItem(val deviceId: String, val name: String, val createdAt: Long,
                        val self: Boolean = false, val mine: Boolean = false)

    @Serializable
    data class RoomResp(val room: List<RoomItem>)

    @Serializable
    data class PendingItem(val requestId: String, val deviceName: String, val createdAt: Long)

    @Serializable
    data class PendingResp(val pending: List<PendingItem>)

    class ApiError(val code: Int, message: String) : Exception(message)

    // ---------- 接口 ----------

    suspend fun healthz(): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(Request.Builder().url("$baseUrl/healthz").get().build()).execute()
            resp.use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    /** 开放注册：本机创建新同步组（服务端 /v1/device/register，带机器码用于去重） */
    suspend fun register(deviceName: String, machineId: String): RegisterResp =
        post("/v1/device/register",
            """{"deviceName":"${escaped(deviceName)}","machineId":"${escaped(machineId)}"}""",
            RegisterResp.serializer())

    /** 填码加入：创始码立即 approved，设备码 pending 等房主确认（带机器码用于去重） */
    suspend fun join(inviteCode: String, deviceName: String, machineId: String): JoinResp =
        post("/v1/device/join",
            """{"inviteCode":"${escaped(inviteCode)}","deviceName":"${escaped(deviceName)}","machineId":"${escaped(machineId)}"}""",
            JoinResp.serializer())

    suspend fun joinStatus(requestId: String): JoinStatusResp = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/v1/device/join/status?requestId=${escaped(requestId)}").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiError(resp.code, errText(body))
            json.decodeFromString(JoinStatusResp.serializer(), body)
        }
    }

    suspend fun invite(token: String): InviteResp =
        post("/v1/device/invite", "{}", InviteResp.serializer(), token)

    suspend fun peers(token: String): PeersResp =
        get("/v1/device/peers", PeersResp.serializer(), token)

    /** 同步组全体设备列表（含本机；self=本机，mine=经由本机配对码加入、可移除） */
    suspend fun room(token: String): RoomResp =
        get("/v1/device/room", RoomResp.serializer(), token)

    suspend fun pending(token: String): PendingResp =
        get("/v1/device/pending", PendingResp.serializer(), token)

    suspend fun decide(token: String, requestId: String, approve: Boolean): Boolean =
        postOk("/v1/device/pending/decide",
            """{"requestId":"${escaped(requestId)}","approve":$approve}""", token)

    suspend fun revokePeer(token: String, deviceId: String): Boolean =
        postOk("/v1/device/peers/revoke", """{"deviceId":"${escaped(deviceId)}"}""", token)

    // ---------- 内部 ----------

    /** 无响应体的 POST：2xx 即成功，返回 true */
    private suspend fun postOk(path: String, body: String, token: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url("$baseUrl$path")
                .post(body.toRequestBody("application/json".toMediaType()))
            token?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiError(resp.code, errText(resp.body?.string() ?: ""))
                true
            }
        }

    private suspend fun <T> post(path: String, body: String, serializer: kotlinx.serialization.DeserializationStrategy<T>,
                                 token: String? = null): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody("application/json".toMediaType()))
        token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiError(resp.code, errText(text))
            json.decodeFromString(serializer, text)
        }
    }

    private suspend fun <T> get(path: String, serializer: kotlinx.serialization.DeserializationStrategy<T>,
                                token: String): T = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl$path").get()
            .header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiError(resp.code, errText(text))
            json.decodeFromString(serializer, text)
        }
    }

    private fun errText(body: String): String = try {
        json.parseToJsonElement(body).jsonObject["error"]?.toString()?.trim('"') ?: body.take(120)
    } catch (_: Exception) { body.take(120) }

    private fun escaped(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        /** ws://host:8765 → http://host:8765；地址不带路径时保持根路径 */
        fun normalizeHttp(addr: String): String {
            var a = addr.trim().trimEnd('/')
            if (a.startsWith("ws://")) a = "http://" + a.removePrefix("ws://")
            else if (a.startsWith("wss://")) a = "https://" + a.removePrefix("wss://")
            if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
            return a
        }

        /** 反向：REST 地址 → WS 地址，路径为空时默认 /ws（桌面端踩过的坑，一开始就做对） */
        fun wsUrl(addr: String): String {
            var a = addr.trim().trimEnd('/')
            if (a.startsWith("http://")) a = "ws://" + a.removePrefix("http://")
            else if (a.startsWith("https://")) a = "wss://" + a.removePrefix("https://")
            else if (!a.startsWith("ws://") && !a.startsWith("wss://")) a = "ws://$a"
            // 去掉已有路径再补 /ws
            val schemeEnd = a.indexOf("://") + 3
            val pathStart = a.indexOf('/', schemeEnd)
            if (pathStart >= 0) a = a.substring(0, pathStart)
            return "$a/ws"
        }
    }
}
