package com.cliplite.sync.engine

import android.content.Context
import com.cliplite.sync.data.AppDatabase
import com.cliplite.sync.data.ClipEntity
import com.cliplite.sync.data.DedupEntity
import com.cliplite.sync.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 同步引擎：连接状态机 + WS 数据面 + 上行队列 + 去重 + 剪贴板桥。
 * 与 UI 解耦，状态经 StateFlow/SharedFlow 暴露。
 */
class SyncEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val db: AppDatabase,
) {
    enum class ConnState { OFFLINE, CONNECTING, ONLINE, WAITING }

    sealed class EngineEvent {
        data class Toast(val msg: String) : EngineEvent()
        data class Unpaired(val reason: String) : EngineEvent()
    }

    val connState = MutableStateFlow(ConnState.OFFLINE)
    val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)

    /** 最近一次 WS 失败原因（连接成功时清空），供 UI 在"重连中"状态下展示 */
    val lastWsError = MutableStateFlow<String?>(null)

    val bridge = ClipboardBridge(context)
    private val clipDao = db.clipDao()
    private val dedupDao = db.dedupDao()
    private val clipsDir get() = File(context.filesDir, "clips")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)   // 协议层保活+断线检测
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 剪贴板同步以局域网直连为主：禁用系统 HTTP 代理，避免代理对
        // ws://非标端口 的 CONNECT 隧道拒绝/掐断（表现为 EOFException）
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    @Volatile private var running = false
    private var loopJob: Job? = null
    private var outboxJob: Job? = null
    private var cleanupJob: Job? = null
    @Volatile private var activeWs: WebSocket? = null
    @Volatile private var backoffMs = 1_000L

    // ---------- 上行队列 ----------
    private val outbox = Channel<Pair<String, Protocol.ClipAdd>>(Channel.UNLIMITED)
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Long>>()

    // lastSeq 落盘防抖
    @Volatile private var lastSeqValue = 0L
    private var seqDirty = 0

    // ================= 生命周期 =================

    fun start() {
        AppLog.i("engine", "start (running=$running)")
        if (running) {
            // 连接循环可能因 token 为空等过早退出而没复位 running；
            // 若 loopJob 已结束（非 active），显式重启，避免"start 被拦"导致永远 OFFLINE
            if (loopJob?.isActive == true) return
            running = false
        }
        running = true
        loopJob = scope.launch { connectLoop() }
        // 幂等启动：避免反复 start 累积多个 outboxLoop/orphanCleanup 协程并发消费同一 Channel
        if (outboxJob?.isActive != true) outboxJob = scope.launch { outboxLoop() }
        if (cleanupJob?.isActive != true) cleanupJob = scope.launch { orphanCleanup() }
    }

    fun stop() {
        AppLog.i("engine", "stop (running=$running)")
        running = false
        activeWs?.cancel()
        activeWs = null
        loopJob?.cancel()
        connState.value = ConnState.OFFLINE
        flushLastSeq()
    }

    /** 立即重连：用户手动触发，重置退避并重启连接循环 */
    fun reconnectNow() {
        AppLog.i("engine", "reconnectNow (manual)")
        stop()
        backoffMs = 1_000L
        start()
    }

    /** 进前台：连接 + 读剪贴板上行 */
    suspend fun onForeground() {
        start()
        try {
            if (!prefs.settings.first().upSync) return
            delay(600) // 等焦点稳定、剪贴板可读
            val now = System.currentTimeMillis()
            when (val c = bridge.read()) {
                is ClipboardBridge.ClipboardContent.Text -> {
                    val hash = Protocol.hash64(c.text.toByteArray(Charsets.UTF_8))
                    // 防回环：若当前剪贴板内容正是某条「远端注入」的历史（autoCopy 写入），
                    // 说明它来自对端，绝不再上行，避免 A→B→A 无限循环。
                    val isRemoteEcho = clipDao.findByHash(hash)?.originRemote == true
                    if (!isRemoteEcho && bridge.shouldSend(hash, now)) {
                        val ev = Protocol.ClipAdd(
                            syncId = UUID.randomUUID().toString(), clipType = 1, hash = Protocol.u64(hash),
                            payloadB64 = ClipboardBridge.b64(c.text.toByteArray(Charsets.UTF_8)),
                            ts = now.toString(),
                        )
                        if (publish(ev)) {
                            bridge.markSent(hash, now)
                            insertLocal(ev)
                            events.tryEmit(EngineEvent.Toast("已同步本机剪贴板"))
                        }
                    }
                }
                is ClipboardBridge.ClipboardContent.Image -> {
                    val hash = Protocol.hash64(c.bytes)
                    val limitBytes = prefs.settings.first().sizeLimitMb * 1024L * 1024L
                    val isRemoteEcho = clipDao.findByHash(hash)?.originRemote == true
                    if (c.bytes.size > limitBytes) {
                        events.tryEmit(EngineEvent.Toast("图片 ${c.bytes.size / 1048576}MB 超过上限，未发送"))
                    } else if (!isRemoteEcho && bridge.shouldSend(hash, now)) {
                        val ev = Protocol.ClipAdd(
                            syncId = UUID.randomUUID().toString(),
                            clipType = if (c.isPng) 3 else 3, // 3=Image（PNG/JPEG 原格式，解码端自适应）
                            hash = Protocol.u64(hash),
                            payloadB64 = ClipboardBridge.b64(c.bytes),
                            ts = now.toString(),
                        )
                        if (publish(ev)) {
                            bridge.markSent(hash, now)
                            insertLocal(ev)
                            events.tryEmit(EngineEvent.Toast("已同步剪贴板图片"))
                        }
                    }
                }
                ClipboardBridge.ClipboardContent.Empty -> Unit
            }
        } catch (e: Exception) {
            // 前台剪贴板上行属尽力而为：任何异常都不能导致主线程 crash（lifecycleScope 会传播异常）
            AppLog.e("engine", "onForeground 上行异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun onBackground() { stop() }

    // ================= 连接循环 =================

    private suspend fun connectLoop() {
        try {
            while (running) {
                val token = prefs.currentToken()
                if (token == null) {
                    AppLog.w("engine", "connectLoop 退出：token 为空")
                    break
                }
                val addr = prefs.currentAddr()
                val deviceId = prefs.currentDeviceId() ?: ""
                lastSeqValue = prefs.lastSeq.first()
                AppLog.i("engine", "连接 $addr (deviceId=$deviceId, lastSeq=$lastSeqValue)")
                val stopSession = connectOnce(addr, token, deviceId)
                if (!running || stopSession) {
                    AppLog.w("engine", "connectLoop 退出：running=$running stopSession=$stopSession")
                    break
                }
                connState.value = ConnState.WAITING
                AppLog.i("engine", "${backoffMs}ms 后重试")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        } finally {
            // 无论何种原因退出，复位 running，保证下次 start() 能真正重启连接循环
            running = false
            connState.value = ConnState.OFFLINE
            AppLog.i("engine", "connectLoop 结束（running 已复位）")
        }
    }

    /** 单次连接会话；返回 true 表示会话被服务端终止（吊销/无效 token），不应重试 */
    private suspend fun connectOnce(addr: String, token: String, deviceId: String): Boolean {
        connState.value = ConnState.CONNECTING
        val closed = CompletableDeferred<Unit>()
        val incoming = Channel<String>(Channel.UNLIMITED)
        val url = RestApi.wsUrl(addr)

        val ws = okHttp.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.i("ws", "onOpen HTTP ${response.code}，发送 hello")
                webSocket.send(Protocol.encode(Protocol.HelloFrame(token = token, lastSeq = lastSeqValue)))
            }
            override fun onMessage(webSocket: WebSocket, text: String) { incoming.trySend(text) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.w("ws", "onClosed code=$code reason=$reason")
                closed.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = buildString {
                    append(t.javaClass.simpleName)
                    append(": ")
                    append(t.message ?: "(无消息)")
                    response?.let { append(" (HTTP ${it.code})") }
                }
                AppLog.e("ws", "onFailure $detail")
                lastWsError.value = detail
                closed.complete(Unit)
            }
        })
        activeWs = ws

        // 应用层心跳：30s ping（服务端 120s 读超时）
        val pinger = scope.launch {
            while (isActive) {
                delay(30_000)
                try { ws.send(Protocol.encode(Protocol.PingFrame())) }
                catch (_: Exception) { /* ws 已关闭，忽略（finally 会 cancel 本协程） */ }
            }
        }

        var stopSession = false
        try {
            loop@ while (true) {
                val done = select {
                    incoming.onReceiveCatching { result ->
                        val text = result.getOrNull() ?: return@onReceiveCatching true
                        if (handleFrame(text, deviceId)) { stopSession = true; true } else false
                    }
                    closed.onAwait { true }
                }
                if (done) break@loop
            }
        } finally {
            pinger.cancel()
            ws.cancel()
            if (activeWs === ws) activeWs = null
            incoming.close()
        }
        return stopSession
    }

    /** 处理一帧；返回 true = 会话应终止（token 失效类错误） */
    private suspend fun handleFrame(text: String, deviceId: String): Boolean {
        when (val f = Protocol.decode(text)) {
            is Protocol.ServerFrame.HelloOk -> {
                AppLog.i("ws", "hello.ok seq=${f.seq} replayed=${f.replayed}")
                connState.value = ConnState.ONLINE
                lastWsError.value = null
                backoffMs = 1_000L
                // 缓冲窗口缺口检查（窗口 200 条）
                if (f.seq - lastSeqValue > 200) {
                    AppLog.w("ws", "补发缺口：lastSeq=$lastSeqValue serverSeq=${f.seq}，接受设计性丢失")
                }
            }
            is Protocol.ServerFrame.Event -> applyEvent(f, deviceId)
            is Protocol.ServerFrame.PublishOk -> pendingAcks.remove(f.clientEventId)?.complete(f.seq)
            is Protocol.ServerFrame.Error -> when (f.code) {
                "revoked", "bad_token" -> {
                    AppLog.e("ws", "error ${f.code}: ${f.detail}")
                    connState.value = ConnState.OFFLINE
                    events.tryEmit(EngineEvent.Unpaired("设备已被移除或 token 失效（${f.detail}），请重新配对"))
                    return true
                }
                "too_big" -> events.tryEmit(EngineEvent.Toast("内容超过服务端大小上限，未发送"))
                "rate_limited" -> delay(1_000)
                else -> AppLog.w("ws", "error ${f.code}: ${f.detail}")
            }
            Protocol.ServerFrame.Pong -> Unit
            is Protocol.ServerFrame.Unknown -> AppLog.w("ws", "未知帧 type=${f.type}")
        }
        return false
    }

    // ================= 下行应用 =================

    private suspend fun applyEvent(f: Protocol.ServerFrame.Event, selfDeviceId: String) {
        val kind = f.event["kind"]?.toString()?.trim('"')
        when (kind) {
            "clip.add" -> {
                val ev = Protocol.decodeClipEvent(f.event)
                if (ev == null) {
                    AppLog.w("engine", "event seq=${f.seq} 缺 kind/解析失败，跳过")
                } else if (f.byDevice != selfDeviceId && dedupDao.count(ev.syncId) == 0) {
                    AppLog.i("engine", "收到 clip.add seq=${f.seq} type=${ev.clipType} from=${f.byDevice}")
                    dedupDao.insert(DedupEntity(ev.syncId, f.seq, System.currentTimeMillis()))
                    insertRemote(ev, f.byDevice)
                }
            }
            "clip.pin" -> {
                val syncId = f.event["syncId"]?.toString()?.trim('"')
                val pinned = f.event["pinned"]?.toString()?.trim('"')?.toBoolean() ?: true
                if (syncId != null) clipDao.setPinned(syncId, pinned)
            }
            "clip.delete" -> {
                val syncId = f.event["syncId"]?.toString()?.trim('"')
                if (syncId != null) clipDao.delete(syncId)
            }
        }
        advanceSeq(f.seq)
    }

    private suspend fun insertRemote(ev: Protocol.ClipAdd, byDevice: String) {
        val s = prefs.settings.first()
        if (!s.downSync) return
        val entity = buildEntity(ev, seq = 0, byDevice, remote = true) ?: return
        clipDao.upsert(entity)
        trimHistory(s.historyCap)
        // 自动写剪贴板：最新条目（按 seq 顺序应用，最后一条即最新）
        if (s.autoCopy && !ev.localOnly) {
            when (ev.clipType) {
                1, 5 -> ev.payloadB64?.let { p ->
                    val text = String(ClipboardBridge.unb64(p), Charsets.UTF_8)
                    if (bridge.writeText(text)) {
                        bridge.markSent(ev.hashLong, System.currentTimeMillis()) // 防回环
                        events.tryEmit(EngineEvent.Toast("已写入剪贴板：${text.take(24)}…"))
                    }
                }
                // 图片写入系统剪贴板需 content uri（M2 接 FileProvider），M1 先入历史
            }
        }
    }

    private suspend fun insertLocal(ev: Protocol.ClipAdd) {
        val s = prefs.settings.first()
        val selfName = prefs.deviceName.first().ifEmpty { "本机" }
        val entity = buildEntity(ev, seq = 0, selfName, remote = false) ?: return
        clipDao.upsert(entity)
        trimHistory(s.historyCap)
    }

    private suspend fun buildEntity(ev: Protocol.ClipAdd, seq: Long, byDevice: String, remote: Boolean): ClipEntity? {
        val now = System.currentTimeMillis()
        val hashLong = ev.hashLong
        val tsLong = if (ev.tsLong > 0) ev.tsLong else now
        return when (ev.clipType) {
            1, 5 -> ClipEntity(   // Text / Html（Html 取 payload 降级纯展示）
                syncId = ev.syncId, seq = seq, clipType = ev.clipType, hash = hashLong,
                textContent = ev.payloadB64?.let { String(ClipboardBridge.unb64(it), Charsets.UTF_8) },
                imageFile = null, thumbFile = null,
                originDevice = byDevice, sourceApp = ev.sourceApp,
                localOnly = ev.localOnly, ts = tsLong, originRemote = remote,
            )
            3, 4 -> {              // Image / ImageV5
                val bytes = ev.payloadB64?.let { ClipboardBridge.unb64(it) } ?: return null
                val img = ClipboardBridge.writeFile(clipsDir, "${ev.syncId}.img", bytes)
                val thumbBytes = ClipboardBridge.makeThumb(bytes)
                val thumb = thumbBytes?.let { ClipboardBridge.writeFile(clipsDir, "${ev.syncId}.thumb.jpg", it) }
                ClipEntity(
                    syncId = ev.syncId, seq = seq, clipType = 3, hash = hashLong,
                    textContent = "图片 · ${bytes.size / 1024} KB",
                    imageFile = img.absolutePath, thumbFile = thumb?.absolutePath,
                    originDevice = byDevice, sourceApp = ev.sourceApp,
                    localOnly = ev.localOnly, ts = tsLong, originRemote = remote,
                )
            }
            2 -> ClipEntity(       // Files：清单文本 + 可选 bundle
                syncId = ev.syncId, seq = seq, clipType = 2, hash = hashLong,
                textContent = ev.payloadB64?.let { String(ClipboardBridge.unb64(it), Charsets.UTF_8) },
                imageFile = ev.bundle?.let { saveBundle(it, ev.syncId) },
                thumbFile = null,
                originDevice = byDevice, sourceApp = ev.sourceApp,
                localOnly = ev.localOnly, ts = tsLong, originRemote = remote,
            )
            else -> null
        }
    }

    /** Files bundle（zip）落盘解压；返回解压目录 */
    private fun saveBundle(bundle: Protocol.ClipBundle, syncId: String): String? = try {
        val bytes = ClipboardBridge.unb64(bundle.payloadB64)
        val zipFile = ClipboardBridge.writeFile(clipsDir, "$syncId.zip", bytes)
        val dir = File(clipsDir, syncId)
        dir.mkdirs()
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dir, entry.name.replace('\\', '/').substringAfterLast('/'))
                if (!entry.isDirectory) out.outputStream().use { zis.copyTo(it) }
                entry = zis.nextEntry
            }
        }
        zipFile.delete()
        dir.absolutePath
    } catch (e: Exception) {
        android.util.Log.w("SyncEngine", "bundle 解压失败: ${e.message}")
        null
    }

    private suspend fun trimHistory(cap: Int) {
        try { clipDao.trimTo(cap) } catch (e: Exception) {
            android.util.Log.w("SyncEngine", "trim 失败（老版本 SQLite 不支持负 OFFSET 时忽略）: ${e.message}")
        }
    }

    private suspend fun advanceSeq(seq: Long) {
        if (seq > lastSeqValue) {
            lastSeqValue = seq
            if (++seqDirty >= 10) flushLastSeq()
        }
    }

    private fun flushLastSeq() {
        seqDirty = 0
        if (lastSeqValue > 0) scope.launch { prefs.setLastSeq(lastSeqValue) }
    }

    // ================= 上行 =================

    /** 发布事件，阻塞至 publish.ok（15s 超时）；返回是否成功 */
    suspend fun publish(ev: Protocol.ClipAdd): Boolean {
        val id = UUID.randomUUID().toString()
        val ack = CompletableDeferred<Long>()
        pendingAcks[id] = ack
        outbox.trySend(id to ev)
        return try {
            withTimeout(15_000) { ack.await(); true }
        } catch (_: TimeoutCancellationException) {
            pendingAcks.remove(id)
            AppLog.w("engine", "publish 超时（connState=${connState.value}）")
            events.tryEmit(EngineEvent.Toast("发送超时，已放弃（连接恢复后可手动重发）"))
            false
        }
    }

    private suspend fun outboxLoop() = supervisorScope {
        for ((id, ev) in outbox) {
            var attempt = 0
            while (true) {
                val ws = activeWs
                if (ws != null && connState.value == ConnState.ONLINE) {
                    val eventsJson = listOf(
                        // 必须用 Protocol.json（encodeDefaults=true）：默认 Json 实例会把
                        // ClipAdd.kind="clip.add" 省略掉，导致对端事件无 kind 不落地
                        Protocol.json.encodeToJsonElement(Protocol.ClipAdd.serializer(), ev)
                            .let { it as kotlinx.serialization.json.JsonObject }
                    )
                    val sent = try {
                        ws.send(Protocol.encode(Protocol.PublishFrame(clientEventId = id, events = eventsJson)))
                        true
                    } catch (e: Exception) {
                        // ws 已 cancel/关闭（stop 竞态）：send 会抛 IllegalStateException，
                        // 不能让它冒泡崩掉 outboxLoop
                        AppLog.w("engine", "outbox send 失败（ws 已关闭）: ${e.javaClass.simpleName}")
                        false
                    }
                    if (!sent) {
                        pendingAcks.remove(id)
                        break
                    }
                    val ack = pendingAcks[id]
                    if (ack != null) {
                        try {
                            withTimeout(20_000) { ack.await() }
                            break
                        } catch (_: TimeoutCancellationException) {
                            if (++attempt >= 2) {
                                pendingAcks.remove(id)
                                events.tryEmit(EngineEvent.Toast("发送失败，请稍后重发"))
                                break
                            }
                        }
                    } else break
                } else {
                    // 离线：等连接恢复（最长 10 分钟）
                    val online = withTimeoutOrNullCompat(600_000) { connState.first { it == ConnState.ONLINE } }
                    if (online == null) { pendingAcks.remove(id); break }
                }
            }
        }
    }

    private suspend fun <T> withTimeoutOrNullCompat(ms: Long, block: suspend () -> T): T? =
        try { withTimeout(ms) { block() } } catch (_: TimeoutCancellationException) { null }

    // ================= 历史操作（UI 调用） =================

    suspend fun deleteLocal(syncId: String) {
        clipDao.find(syncId)?.let { clip ->
            clip.imageFile?.let { File(it).delete() }
            clip.thumbFile?.let { File(it).delete() }
            if (clip.clipType == 2) File(clipsDir, syncId).takeIf { it.isDirectory }?.deleteRecursively()
        }
        clipDao.delete(syncId)
    }

    suspend fun togglePin(syncId: String) {
        val clip = clipDao.find(syncId) ?: return
        clipDao.setPinned(syncId, !clip.pinned)
    }

    suspend fun clearHistory() {
        clipDao.allImageFiles().forEach {
            it.imageFile?.let { p -> File(p).delete() }
            it.thumbFile?.let { p -> File(p).delete() }
        }
        clipsDir.takeIf { it.isDirectory }?.listFiles()?.forEach { it.deleteRecursively() }
        clipDao.deleteAll()
        dedupDao.clear()
    }

    /** 复制历史条目到系统剪贴板（并 markSent 防回环：本机重放历史内容不应再上行） */
    suspend fun copyToClipboard(syncId: String): Boolean {
        val clip = clipDao.find(syncId) ?: return false
        return when (clip.clipType) {
            1, 5 -> clip.textContent?.let { text ->
                if (bridge.writeText(text)) {
                    bridge.markSent(Protocol.hash64(text.toByteArray(Charsets.UTF_8)), System.currentTimeMillis())
                    true
                } else false
            } ?: false
            else -> false // 图片/文件复制走查看/下载路径，不进系统剪贴板
        }
    }

    suspend fun reSend(syncId: String): Boolean {
        val clip = clipDao.find(syncId) ?: return false
        when (clip.clipType) {
            1, 5 -> clip.textContent?.let { text ->
                val now = System.currentTimeMillis()
                val hash = Protocol.hash64(text.toByteArray(Charsets.UTF_8))
                return publish(Protocol.ClipAdd(
                    syncId = UUID.randomUUID().toString(), clipType = 1, hash = Protocol.u64(hash),
                    payloadB64 = ClipboardBridge.b64(text.toByteArray(Charsets.UTF_8)), ts = now.toString(),
                ))
            }
            3 -> clip.imageFile?.let { f ->
                val bytes = File(f).readBytes()
                return publish(Protocol.ClipAdd(
                    syncId = UUID.randomUUID().toString(), clipType = 3,
                    hash = Protocol.u64(Protocol.hash64(bytes)),
                    payloadB64 = ClipboardBridge.b64(bytes), ts = System.currentTimeMillis().toString(),
                ))
            }
        }
        return false
    }

    /** 退出同步组 */
    suspend fun unpair() {
        AppLog.i("engine", "unpair（手动退出同步组）")
        stop()
        prefs.clearPairing()
        pendingAcks.clear()
    }

    // ================= 维护 =================

    private suspend fun orphanCleanup() {
        // 历史里的合法文件名集合
        val keep = clipDao.allImageFiles().flatMap { listOfNotNull(it.imageFile, it.thumbFile) }.toHashSet()
        val dir = clipsDir
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                // bundle 解压目录以 syncId 命名：交给下一次清理（M2 精确化），先只清孤儿文件
                if (f.isFile && f.absolutePath !in keep) f.delete()
            }
        }
        dedupDao.trim(System.currentTimeMillis() - 7L * 24 * 3600_000)
    }
}
