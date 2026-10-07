#ifndef CLIPSYNC_SYNC_MANAGER_H
#define CLIPSYNC_SYNC_MANAGER_H

// ClipSync 客户端同步管理器（协议见 docs/clip-sync-protocol.md v0.2）
//
// 设计要点：
// - 独立线程组运行（连接/收包线程 + 发包线程），全部网络 IO 不触碰 UI 线程；
// - 对外仅暴露：Start/Stop、Publish（本机剪贴板入库后调用）、OnRemoteClip 回调；
// - last_seq 的持久化由宿主（ClipLite settings）通过 std::function 注入；
// - WinHTTP WebSocket 客户端（Win8+ 系统库，零第三方依赖）；
// - 不做端到端加密（一期），事件信封保留 v 字段供二期升级。

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <windows.h>

namespace clipsync {

struct SyncConfig {
    std::wstring serverUrl;   // 例：ws://192.168.1.10:8765/ws 或 wss://sync.example.com/ws
    std::wstring deviceToken; // 设备 token（邀请码换取）
    std::wstring deviceId;    // 本机设备 ID（服务端分配，用于跳过自己广播的事件）
    int maxPayloadBytes = 64 * 1024 * 1024; // 单条上限
};

// 与服务端事件体对应的本机事件
struct SyncEvent {
    std::string kind;       // clip.add / clip.delete / clip.pin
    std::string syncId;     // 全局唯一幂等键
    int clipType = 1;       // 与 ClipLite ClipType 对应：1 Text, 2 Files, 3 Image, 4 ImageV5, 5 Html
    std::uint64_t hash = 0; // 内容哈希（clipLiteHash）
    std::string payload;    // 原始字节（内部做 base64）
    bool localOnly = false; // true=仅本机标记（如超限文件清单）
    bool pinned = false;    // kind=clip.pin 时的置顶状态
    std::string sourceApp;
    std::uint64_t ts = 0;   // Unix 毫秒
};

enum class SyncState { Stopped, Connecting, Online, Offline };

class SyncManager {
public:
    using RemoteClipHandler = std::function<void(const SyncEvent&)>;
    using LastSeqLoader = std::function<std::int64_t()>;
    using LastSeqSaver = std::function<void(std::int64_t)>;
    using StateHandler = std::function<void(SyncState)>;

    SyncManager() = default;
    ~SyncManager() { Stop(); }

    SyncManager(const SyncManager&) = delete;
    SyncManager& operator=(const SyncManager&) = delete;

    void SetConfig(const SyncConfig& config) { config_ = config; }
    void SetRemoteClipHandler(RemoteClipHandler handler) { remoteHandler_ = std::move(handler); }
    void SetLastSeqIo(LastSeqLoader loader, LastSeqSaver saver) {
        loadLastSeq_ = std::move(loader);
        saveLastSeq_ = std::move(saver);
    }
    void SetStateHandler(StateHandler handler) { stateHandler_ = std::move(handler); }

    // 启动/停止同步线程（幂等）
    void Start();
    void Stop();

    // 本机剪贴板入库成功后调用（线程安全；内部排队由发送线程发出）
    // 返回 false 表示未运行或内容超限被拒。
    bool Publish(const SyncEvent& event);

    // 元数据事件（本地删除/置顶操作时调用；远端来的不需要再发布）
    bool PublishMeta(const std::string& kind, const std::string& syncId, bool pinned);

    SyncState state() const { return state_.load(); }
    std::int64_t lastSeq() const { return lastSeq_.load(); }

private:
    void connectionLoop();
    void senderLoop();
    bool connectAndSession();
    void closeSocket();
    bool sendFrameText(const std::string& text);
    bool receiveFrame(std::string& text, bool& closed);
    bool sendHelloAndConsumeAck();
    void handleServerFrame(const std::string& text);
    void applyRemoteEvent(const std::string& byDevice, const std::string& eventJson);
    bool seenSyncId(const std::string& syncId);
    void rememberSyncId(const std::string& syncId);
    void setState(SyncState s);

    static std::string buildEventJson(const SyncEvent& e);
    static bool parseUrl(const std::wstring& url, std::wstring& host, int& port, bool& secure,
                         std::wstring& path);

    SyncConfig config_;
    RemoteClipHandler remoteHandler_;
    LastSeqLoader loadLastSeq_;
    LastSeqSaver saveLastSeq_;
    StateHandler stateHandler_;

    std::atomic<SyncState> state_{SyncState::Stopped};
    std::atomic<bool> running_{false};
    std::thread connectionThread_;
    std::thread senderThread_;

    std::mutex outboxMutex_;
    std::condition_variable outboxCv_;
    std::deque<SyncEvent> outbox_;

    std::mutex seqMutex_;
    std::int64_t lastSeq_ = 0;

    std::mutex seenMutex_;
    std::deque<std::string> seenIds_; // FIFO 去重表（容量 512）

    std::mutex sendMutex_; // WinHTTP WS 同方向不允许并发 Send

    // WinHTTP 句柄（连接线程创建/销毁，发送线程只读引用；由 closeSocket 收敛）
    void* session_ = nullptr;
    void* connection_ = nullptr;
    void* request_ = nullptr;
    void* socket_ = nullptr;
};

} // namespace clipsync

#endif // CLIPSYNC_SYNC_MANAGER_H
