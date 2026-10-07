// ClipSync 客户端同步管理器实现。
// 注意：本模块尚未在 MSVC 下编译验证（当前开发机无 VS/CMake 工具链），
// 接入前先跑 tests/sync_json_tests.cpp 与一次真实连接联调。

#include "sync_manager.h"

#include <chrono>
#include <cstring>
#include <sstream>

#include <winhttp.h>

#include "sync_json.h"
#include "sync_util.h"

#pragma comment(lib, "winhttp")

namespace clipsync {

namespace {

constexpr DWORD kConnectTimeoutMs = 8000;
constexpr DWORD kReceiveTimeoutMs = 120000; // 服务端 120s 无消息断开，客户端读超时略长
constexpr int kHeartbeatSeconds = 30;
constexpr std::size_t kSeenCapacity = 512;
constexpr std::size_t kOutboxLimit = 256;

void closeHandleSafe(void** handle) {
    if (handle && *handle) {
        WinHttpCloseHandle(static_cast<HINTERNET>(*handle));
        *handle = nullptr;
    }
}

} // namespace

// ---------- 生命周期 ----------

void SyncManager::Start() {
    if (running_.exchange(true)) return;
    if (loadLastSeq_) {
        std::lock_guard<std::mutex> lock(seqMutex_);
        lastSeq_ = loadLastSeq_();
    }
    setState(SyncState::Connecting);
    connectionThread_ = std::thread(&SyncManager::connectionLoop, this);
    senderThread_ = std::thread(&SyncManager::senderLoop, this);
}

void SyncManager::Stop() {
    if (!running_.exchange(false)) return;
    {
        std::lock_guard<std::mutex> lock(outboxMutex_);
        outbox_.clear();
    }
    outboxCv_.notify_all();
    closeSocket();
    if (connectionThread_.joinable()) connectionThread_.join();
    if (senderThread_.joinable()) senderThread_.join();
    setState(SyncState::Stopped);
}

bool SyncManager::Publish(const SyncEvent& event) {
    if (!running_.load()) return false;
    if (static_cast<int>(event.payload.size()) > config_.maxPayloadBytes) return false;
    {
        std::lock_guard<std::mutex> lock(outboxMutex_);
        if (outbox_.size() >= kOutboxLimit) outbox_.pop_front(); // 极端背压下丢最旧
        outbox_.push_back(event);
    }
    outboxCv_.notify_one();
    return true;
}

bool SyncManager::PublishMeta(const std::string& kind, const std::string& syncId, bool pinned) {
    SyncEvent e;
    e.kind = kind;
    e.syncId = syncId;
    e.ts = unixNowMs();
    e.pinned = pinned;
    return Publish(e);
}

// ---------- 线程 ----------

void SyncManager::connectionLoop() {
    int backoffSeconds = 1;
    while (running_.load()) {
        setState(SyncState::Connecting);
        if (connectAndSession() && sendHelloAndConsumeAck()) {
            backoffSeconds = 1;
            setState(SyncState::Online);
            // 读循环：收到帧交给 handleServerFrame；服务端静默/断开则退出重连
            while (running_.load()) {
                std::string text;
                bool closed = false;
                if (!receiveFrame(text, closed) || closed) break;
                if (!text.empty()) handleServerFrame(text);
            }
        }
        closeSocket();
        if (!running_.load()) break;
        setState(SyncState::Offline);
        for (int i = 0; i < backoffSeconds && running_.load(); ++i) Sleep(1000);
        backoffSeconds = backoffSeconds < 60 ? backoffSeconds * 2 : 60;
    }
}

void SyncManager::senderLoop() {
    while (running_.load()) {
        SyncEvent event;
        bool sendPing = false;
        {
            std::unique_lock<std::mutex> lock(outboxMutex_);
            outboxCv_.wait_for(lock, std::chrono::seconds(kHeartbeatSeconds), [&] {
                return !running_.load() || (state_.load() == SyncState::Online && !outbox_.empty());
            });
            if (!running_.load()) break;
            if (state_.load() != SyncState::Online) continue; // 离线：事件留在队列，不消费
            if (outbox_.empty()) {
                sendPing = true; // 心跳
            } else {
                event = outbox_.front();
                outbox_.pop_front();
            }
        }
        if (sendPing) {
            sendFrameText("{\"v\":1,\"type\":\"ping\"}");
            continue;
        }

        // 组帧：单条事件一个 publish 帧（失败放回队首，重连后重发）
        std::string frame = "{\"v\":1,\"type\":\"publish\",\"clientEventId\":\"";
        frame += jsonEscape(event.syncId);
        frame += "\",\"events\":[";
        frame += buildEventJson(event);
        frame += "]}";
        if (sendFrameText(frame)) {
            rememberSyncId(event.syncId);
        } else {
            std::lock_guard<std::mutex> lock(outboxMutex_);
            outbox_.push_front(event);
        }
    }
}

// ---------- 连接（WinHTTP WebSocket） ----------

bool SyncManager::parseUrl(const std::wstring& url, std::wstring& host, int& port, bool& secure,
                           std::wstring& path) {
    URL_COMPONENTS parts{};
    parts.dwStructSize = sizeof(parts);
    parts.dwSchemeLength = (DWORD)-1;
    parts.dwHostNameLength = (DWORD)-1;
    parts.dwUrlPathLength = (DWORD)-1;
    if (!WinHttpCrackUrl(url.c_str(), 0, 0, &parts)) return false;
    host.assign(parts.lpszHostName, parts.dwHostNameLength);
    port = parts.nPort;
    secure = parts.nScheme == INTERNET_SCHEME_HTTPS || parts.nScheme == INTERNET_SCHEME_WSS;
    path.assign(parts.lpszUrlPath && parts.dwUrlPathLength ? parts.lpszUrlPath : L"/");
    if (path.empty() || path[0] != L'/') path = L"/" + path;
    return true;
}

bool SyncManager::connectAndSession() {
    std::wstring host, path;
    int port = 0;
    bool secure = false;
    if (!parseUrl(config_.serverUrl, host, port, secure, path)) return false;

    session_ = WinHttpOpen(L"ClipLite-Sync/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                           WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session_) return false;
    WinHttpSetTimeouts(static_cast<HINTERNET>(session_), kConnectTimeoutMs, kConnectTimeoutMs,
                       kConnectTimeoutMs, kReceiveTimeoutMs);

    connection_ = WinHttpConnect(static_cast<HINTERNET>(session_), host.c_str(),
                                 static_cast<INTERNET_PORT>(port), 0);
    if (!connection_) return false;

    DWORD flags = secure ? WINHTTP_FLAG_SECURE : 0;
    request_ = WinHttpOpenRequest(static_cast<HINTERNET>(connection_), L"GET", path.c_str(),
                                  nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!request_) return false;

    // WebSocket 升级头（Key 由应用生成，WinHTTP 负责 Accept 校验与协议升级）
    char keyRaw[16];
    for (auto& b : keyRaw) b = static_cast<char>(rand() & 0xFF);
    std::string keyB64 = base64Encode(std::string(keyRaw, sizeof(keyRaw)));
    std::wstring headers = L"Connection: Upgrade\r\nUpgrade: websocket\r\n"
                           L"Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " +
                           std::wstring(keyB64.begin(), keyB64.end()) + L"\r\n";
    BOOL upgrade = TRUE;
    WinHttpSetOption(static_cast<HINTERNET>(request_), WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET,
                     &upgrade, sizeof(upgrade));

    std::wstring tokenHeader = L"Authorization: Bearer " + config_.deviceToken + L"\r\n";
    if (!WinHttpSendRequest(static_cast<HINTERNET>(request_), tokenHeader.c_str(), -1,
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
        !WinHttpReceiveResponse(static_cast<HINTERNET>(request_), nullptr)) {
        return false;
    }

    DWORD status = 0, size = sizeof(status);
    WinHttpQueryHeaders(static_cast<HINTERNET>(request_),
                        WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX);
    if (status != HTTP_STATUS_SWITCH_PROTOCOLS && status != 200) return false;

    socket_ = WinHttpWebSocketCompleteUpgrade(static_cast<HINTERNET>(request_), 0);
    return socket_ != nullptr;
}

void SyncManager::closeSocket() {
    if (socket_) {
        WinHttpWebSocketClose(static_cast<HWEB_SOCKET>(socket_), WINHTTP_WEB_SOCKET_SUCCESS_CLOSE_STATUS,
                              nullptr, 0);
        WinHttpCloseHandle(static_cast<HINTERNET>(socket_));
        socket_ = nullptr;
    }
    closeHandleSafe(&request_);
    closeHandleSafe(&connection_);
    closeHandleSafe(&session_);
}

bool SyncManager::sendFrameText(const std::string& text) {
    void* sock = socket_;
    if (!sock) return false;
    std::lock_guard<std::mutex> lock(sendMutex_);
    return WinHttpWebSocketSend(static_cast<HWEB_SOCKET>(sock),
                                WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE,
                                reinterpret_cast<void*>(const_cast<char*>(text.data())),
                                static_cast<DWORD>(text.size())) != FALSE;
}

bool SyncManager::receiveFrame(std::string& text, bool& closed) {
    text.clear();
    void* sock = socket_;
    if (!sock) return false;

    DWORD done = 0;
    WINHTTP_WEB_SOCKET_BUFFER_TYPE type = WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE;
    char chunk[8192];
    for (;;) {
        if (!WinHttpWebSocketReceive(static_cast<HWEB_SOCKET>(sock), chunk, sizeof(chunk), &done, &type)) {
            return false;
        }
        switch (type) {
        case WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE:
        case WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE:
            text.append(chunk, done);
            if (type == WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE) return true;
            break;
        case WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE:
            closed = true;
            return true;
        case WINHTTP_WEB_SOCKET_PONG_BUFFER_TYPE:
            continue; // 服务端 pong，忽略
        default:
            return false; // 二进制帧不在协议内
        }
    }
}

bool SyncManager::sendHelloAndConsumeAck() {
    std::int64_t last = 0;
    {
        std::lock_guard<std::mutex> lock(seqMutex_);
        last = lastSeq_;
    }
    std::ostringstream os;
    os << "{\"v\":1,\"type\":\"hello\",\"token\":\""
       << jsonEscape(std::string(config_.deviceToken.begin(), config_.deviceToken.end()))
       << "\",\"lastSeq\":" << last << "}";
    if (!sendFrameText(os.str())) return false;

    // 阻塞等 hello.ok（期间可能有补发 event 帧，先处理）
    for (int i = 0; i < 10; ++i) { // 最多等 10 帧（补发计入）
        std::string text;
        bool closed = false;
        if (!receiveFrame(text, closed) || closed) return false;
        JValue f;
        if (!parseJson(text, f) || !f.isObject()) continue;
        const std::string type = f.at("type").asString();
        if (type == "hello.ok") {
            const std::int64_t serverSeq = f.at("seq").asInt();
            const std::int64_t replayed = f.at("replayed").asInt();
            if (replayed > 64) {
                // 缺口超出预期窗口：接受缺口（协议 §4.6），仅推进 last_seq
            }
            std::lock_guard<std::mutex> lock(seqMutex_);
            if (serverSeq > lastSeq_) {
                lastSeq_ = serverSeq;
                if (saveLastSeq_) saveLastSeq_(lastSeq_);
            }
            return true;
        }
        if (type == "error") return false;
        if (type == "event") {
            applyRemoteEvent(f.at("byDevice").asString(), text);
        }
    }
    return false;
}

// ---------- 帧处理 ----------

void SyncManager::handleServerFrame(const std::string& text) {
    JValue f;
    if (!parseJson(text, f) || !f.isObject()) return;
    const std::string type = f.at("type").asString();
    if (type == "event") {
        applyRemoteEvent(f.at("byDevice").asString(), text);
    } else if (type == "publish.ok") {
        const std::int64_t seq = f.at("seq").asInt();
        std::lock_guard<std::mutex> lock(seqMutex_);
        if (seq > lastSeq_) {
            lastSeq_ = seq;
            if (saveLastSeq_) saveLastSeq_(lastSeq_);
        }
    } else if (type == "error") {
        // bad_token / revoked：宿主应提示重新配对；这里简单断线退避
        closeSocket();
    }
    // pong：忽略
}

void SyncManager::applyRemoteEvent(const std::string& byDevice, const std::string& frameText) {
    // 自己广播的事件：服务端已不回发；补发窗口内可能出现，跳过
    if (!config_.deviceId.empty() &&
        byDevice == std::string(config_.deviceId.begin(), config_.deviceId.end())) {
        return;
    }

    // 帧内 event 节点
    JValue f;
    if (!parseJson(frameText, f) || !f.isObject()) return;
    const JValue& ev = f.at("event");
    if (!ev.isObject()) return;

    const std::int64_t seq = f.at("seq").asInt();
    SyncEvent e;
    e.kind = ev.at("kind").asString();
    e.syncId = ev.at("syncId").asString();
    if (e.kind.empty() || e.syncId.empty()) return;
    if (seenSyncId(e.syncId)) { // 已处理过（补发重复）
        std::lock_guard<std::mutex> lock(seqMutex_);
        if (seq > lastSeq_) {
            lastSeq_ = seq;
            if (saveLastSeq_) saveLastSeq_(lastSeq_);
        }
        return;
    }

    if (e.kind == "clip.add") {
        e.clipType = static_cast<int>(ev.at("clipType").asInt(1));
        e.hash = static_cast<std::uint64_t>(ev.at("hash").asInt());
        e.localOnly = ev.at("localOnly").asBool(false);
        e.sourceApp = ev.at("sourceApp").asString();
        e.ts = static_cast<std::uint64_t>(ev.at("ts").asInt());
        if (!e.localOnly) {
            const std::string b64 = ev.at("payloadB64").asString();
            if (!base64Decode(b64, e.payload)) return;
        }
        if (remoteHandler_) remoteHandler_(e); // 宿主负责 hash 查重 + ClipStore 注入
    } else if (e.kind == "clip.delete" || e.kind == "clip.pin") {
        e.pinned = ev.at("pinned").asBool(false);
        if (remoteHandler_) remoteHandler_(e);
    } else {
        return;
    }

    rememberSyncId(e.syncId);
    std::lock_guard<std::mutex> lock(seqMutex_);
    if (seq > lastSeq_) {
        lastSeq_ = seq;
        if (saveLastSeq_) saveLastSeq_(lastSeq_);
    }
}

bool SyncManager::seenSyncId(const std::string& syncId) {
    std::lock_guard<std::mutex> lock(seenMutex_);
    for (const auto& id : seenIds_) {
        if (id == syncId) return true;
    }
    return false;
}

void SyncManager::rememberSyncId(const std::string& syncId) {
    std::lock_guard<std::mutex> lock(seenMutex_);
    seenIds_.push_back(syncId);
    while (seenIds_.size() > kSeenCapacity) seenIds_.pop_front();
}

void SyncManager::setState(SyncState s) {
    state_.store(s);
    if (stateHandler_) stateHandler_(s);
}

// ---------- 事件组帧 ----------

std::string SyncManager::buildEventJson(const SyncEvent& e) {
    std::ostringstream os;
    os << "{\"kind\":\"" << jsonEscape(e.kind) << "\",\"syncId\":\"" << jsonEscape(e.syncId) << "\"";
    if (e.kind == "clip.add") {
        os << ",\"clipType\":" << e.clipType << ",\"hash\":" << e.hash
           << ",\"localOnly\":" << (e.localOnly ? "true" : "false")
           << ",\"sourceApp\":\"" << jsonEscape(e.sourceApp) << "\",\"ts\":" << e.ts;
        if (!e.localOnly) {
            os << ",\"payloadB64\":\"" << base64Encode(e.payload) << "\"";
        }
    } else if (e.kind == "clip.pin") {
        os << ",\"pinned\":" << (e.pinned ? "true" : "false");
    }
    // clip.delete 无附加字段
    os << "}";
    return os.str();
}

} // namespace clipsync
