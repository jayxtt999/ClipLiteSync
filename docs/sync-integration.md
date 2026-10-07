# ClipLite 客户端接入指南（sync 模块 → main.cpp）

> 状态：P0 骨架已完成，main.cpp 尚未接线（P1 任务）。
> 模块位置：`src/sync/`（sync_manager.h/.cpp、sync_json.h、sync_util.h）
> 协议：[docs/clip-sync-protocol.md](clip-sync-protocol.md) v0.2

## 模块能力（已实现）

- `SyncManager`：连接线程 + 发送线程分离；WinHTTP WebSocket 客户端（Win8+ 系统库，零依赖）；
- 首帧 hello（带 lastSeq）、publish/ping 发送、event/publish.ok/error 处理、指数退避重连；
- `lastSeq` 读写由宿主注入（对接 settings.ini）；
- syncId FIFO 去重表 + byDevice 自事件跳过；
- outbox 离线暂存（上限 256 条），发送失败自动回队重发。

## main.cpp 接线点（P1 待做）

| 步骤 | 位置 | 内容 |
| --- | --- | --- |
| 1. 捕获后发布 | main.cpp:12157 `WM_CLIPBOARDUPDATE` → capture worker（main.cpp:2213）入库成功处 | 组装 `SyncEvent{kind:"clip.add", clipType, hash=clipLiteHash(payload), payload, sourceApp, ts}` → `sync.Publish(ev)`；`clipType==Files` 时按协议 §4.7 先统计文件总大小（≤20MB 打包 zip 进 payload，超限 `localOnly=true`、payload 为空） |
| 2. 远端注入 | 设置 `SetRemoteClipHandler` 回调 | `clip.add`：`ClipStore::appendOrUpdate(...)`（clip_store.h:63，hash 查重防重复）；`clip.delete/pin`：按 syncId 定位记录后调用 remove/togglePinned。注意回调运行在同步网络线程，需向 storeWorker（main.cpp:13749）投递任务，不得直接操作 store |
| 3. lastSeq 持久化 | settings.ini（main.cpp:1268 附近） | `sync.lastSeq` 键，Loader/Saver 两个 lambda 注入 |
| 4. 存储格式 v5 | clip_store.cpp:17 `kVersion` 4→5 | 新增 per-record 字段：`syncId`（幂等键）、`originDevice`（回环判断）。`appendOrUpdate` 增加带 syncId 的重载 |
| 5. 设置 UI | 设置页（main.cpp:6604 卡片体系） | 「同步」页签：服务地址、token、开关、状态显示（复用 `SyncState`） |
| 6. 托盘菜单 | 托盘初始化处 | 「暂停同步」开关项 |

## 构建注意

- 已加入 CMakeLists（ClipLite 主目标 + `ClipLiteSyncJsonTests` 测试目标）；
- **sync 模块尚未在 MSVC 下编译过**（本开发机无 VS 工具链）。首次构建可能需要小修：`#pragma comment(lib, "winhttp")` 已带，CMake 也已链 winhttp；
- 优先跑 `ctest -R ClipLiteSyncJson` 验证 JSON/base64 层，再做真机联调。

## 联调步骤（P1）

1. 本机起服务端：`server/clipsync-server.exe`（README 有完整流程）；
2. 邀请码换 token，填入两台设备；
3. 验证：A 复制 → B 历史窗口出现（带来源）；B 睡眠唤醒 → 补发；B 断网 10 分钟 → 自动重连继续；
4. 压测：连续复制大图（接近 64MB 上限）观察服务端环形缓冲淘汰日志。
