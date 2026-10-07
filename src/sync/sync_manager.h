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
    // 文件清单打包（clipType=2 且未超限时；协议 §3.3 bundle）。
    // 发送端填 zip 原始字节；接收端收到的也是解 base64 后的 zip 原始字节。
    std::string bundleZip;
    int bundleFileCount = 0;
    std::uint64_t bundleTotalBytes = 0;
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
    using DebugLog = std::function<void(const char*)>;

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
    void SetDebugLog(DebugLog log) { debugLog_ = std::move(log); }

    // 启动/停止同步线程（幂等）
    void Start();
    void Stop();

    // 本机剪贴板入库成功后调用（线程安全；内部排队由发送线程发出）
    // 返回 false 表示未运行或内容超限被拒。
    bool Publish(const SyncEvent& event);

    // 元数据事件（本地删除/置顶操作时调用；远端来的不需要再发布）
    bool PublishMeta(const std::string& kind, const std::string& syncId,
                     std::uint64_t hash, bool pinned);

    // ---------- 房间制配对（REST，独立于 WS 数据面，均可在后台线程调用） ----------
    //
    // 流程（协议 v0.3）：
    //   已配对设备：DeviceInvite 生成 8 位一次性配对码（10 分钟有效）；
    //   新设备：    DeviceJoinRequest 填码提交申请（得到 requestId，状态 pending），
    //               轮询 DeviceJoinPoll 直到 approved（拿 token）/ rejected / expired；
    //   房主：      DevicePendingList 拉取待确认申请，DevicePendingDecide 允许/拒绝。
    // 创始码（服务端管理员生成）在 DeviceJoinRequest 中直接返回 approved + token。

    enum class JoinPhase { Requested, Approved, Rejected, Expired, NotFound };

    struct PendingJoinInfo {
        std::string requestId;
        std::string deviceName;
        std::int64_t createdAt = 0; // Unix 毫秒
    };

    // 已配对设备生成配对码；成功返回 true 并填 code（8 位）/ expiresAtMs
    static bool DeviceInvite(const std::wstring& serverUrl, const std::string& deviceToken,
                             std::string& code, std::int64_t& expiresAtMs, std::string& errorUtf8);

    // 新设备提交加入申请。返回 true 表示请求本身成功（按 phase 区分）：
    // Approved = 创始码直接配对成功（deviceId/deviceToken 有效）；
    // Requested = 已提交，等待房主确认（requestId 有效）。
    static bool DeviceJoinRequest(const std::wstring& serverUrl, const std::string& code,
                                  const std::string& deviceName, JoinPhase& phase,
                                  std::string& requestId, std::string& deviceId,
                                  std::string& deviceToken, std::string& errorUtf8);

    // 轮询加入申请状态；Approved 时填 deviceId/deviceToken
    static bool DeviceJoinPoll(const std::wstring& serverUrl, const std::string& requestId,
                               JoinPhase& phase, std::string& deviceId,
                               std::string& deviceToken, std::string& errorUtf8);

    // 房主拉取待确认申请列表
    static bool DevicePendingList(const std::wstring& serverUrl, const std::string& deviceToken,
                                  std::vector<PendingJoinInfo>& out, std::string& errorUtf8);

    // 房主允许/拒绝某个申请
    static bool DevicePendingDecide(const std::wstring& serverUrl, const std::string& deviceToken,
                                    const std::string& requestId, bool approve,
                                    std::string& errorUtf8);

    // 房间成员（经由本机配对码加入的设备）
    struct PeerInfo {
        std::string deviceId;
        std::string name;
        std::int64_t createdAt = 0; // Unix 毫秒
    };

    // 列出经由本机配对码加入的设备（不含已移除）
    static bool DevicePeerList(const std::wstring& serverUrl, const std::string& deviceToken,
                               std::vector<PeerInfo>& out, std::string& errorUtf8);

    // 移除（踢出）经由本机配对码加入的设备：吊销 token 并断开其连接
    static bool DevicePeerRevoke(const std::wstring& serverUrl, const std::string& deviceToken,
                                 const std::string& deviceId, std::string& errorUtf8);

    // 服务器可达性探测（GET /healthz，用于未配对时区分"连不上"和"没配对"）
    static bool ServerPing(const std::wstring& serverUrl, std::string& errorUtf8);

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
    void log(const char* message) const;

    static std::string buildEventJson(const SyncEvent& e);
    static bool parseUrl(const std::wstring& url, std::wstring& host, int& port, bool& secure,
                         std::wstring& path);
    // REST 基础请求（WinHTTP，8s 超时；bearer 可为空）
    static bool restCall(const std::wstring& serverUrl, const wchar_t* method, const wchar_t* path,
                         const std::string& bodyJson, const std::string& bearer,
                         int& status, std::string& response, std::string& errorUtf8);

    SyncConfig config_;
    RemoteClipHandler remoteHandler_;
    LastSeqLoader loadLastSeq_;
    LastSeqSaver saveLastSeq_;
    StateHandler stateHandler_;
    DebugLog debugLog_;

    std::atomic<SyncState> state_{SyncState::Stopped};
    std::atomic<bool> running_{false};
    std::thread connectionThread_;
    std::thread senderThread_;

    std::mutex outboxMutex_;
    std::condition_variable outboxCv_;
    std::deque<SyncEvent> outbox_;

    std::mutex seqMutex_;
    std::atomic<std::int64_t> lastSeq_{0}; // 读侧无锁（UI 线程轮询状态）

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
