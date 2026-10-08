# ClipLiteSync 移动端开发文档（Android 技术方案）

> 版本：v1.0（2026-10-08）　配套：[mobile-requirements.md](mobile-requirements.md)（需求书）、[clip-sync-protocol.md](clip-sync-protocol.md)（协议）
> 代码位置（建议）：`repo/mobile-android/`

## 1. 技术选型

| 项 | 选择 | 理由 |
| --- | --- | --- |
| 语言/UI | Kotlin + Jetpack Compose（Material 3） | 现代默认栈；深色模式/大屏适配零成本；单 Activity 结构简单 |
| 最低 SDK | minSdk 26（Android 8.0）/ targetSdk 34 | 覆盖主流机型；Compose 要求 26+ |
| 网络 | **OkHttp WebSocket + kotlinx.serialization** | 与服务端 JSON/WS 协议直接对接，零额外框架；HTTP REST 同库复用 |
| 本地存储 | Room | 剪贴板历史持久化、按 seq/hash/syncId 建索引 |
| 协程 | kotlinx-coroutines | 连接状态机、重连退避、IO 调度 |
| 依赖注入 | 手写单例（AppContainer） | 项目规模不需要 Hilt |
| 构建 | Gradle Kotlin DSL | — |

**明确不引入**：Retrofit（OkHttp 裸用即可）、Hilt、Firebase、跨平台框架（Flutter/RN/KMP）。桌面端 sync 模块是 C++/WinHTTP，移动端无法复用其代码，只复用**协议与行为规范**。

## 2. 总体架构

```
┌─────────────────── 单 Activity (Compose) ───────────────────┐
│  配对向导 │ 历史列表 │ 房间管理 │ 设置                        │
└──────────────┬──────────────────────────────────────────────┘
               │ ViewModel (StateFlow)
┌──────────────┴──────────── SyncEngine（核心，与 UI 解耦）─────┐
│ ConnectionStateMachine   连接/重连/补发（指数退避 1s→60s）    │
│ WsChannel                OkHttp WS：hello/publish/event/pong  │
│ Outbox                   上行暂存队列（≤256 条，失败回队）      │
│ Dedup                    syncId LRU + hash 查重（Room 表）     │
│ ClipboardBridge          系统剪贴板读/写（前台触发）           │
│ RestApi                  register/join/status/invite/pending… │
└──────────────┬───────────────────────────────────────────────┘
               │
        ┌──────┴──────┐   ┌─────────────┐
        │ Room DB     │   │ EncryptedSP │ token / lastSeq / 配置
        │ (历史+去重表)│   └─────────────┘
        └─────────────┘
```

### 2.1 与桌面端的行为对齐（协议 §4 逐条落实）

| 协议条款 | 移动端实现 |
| --- | --- |
| 发布条件 | 前台 ClipboardBridge 捕获 → 去重（本地指纹）→ Outbox → WsChannel；`origin=remote` 注入的条目不再发布（防回环） |
| 去重 | Dedup：`syncId` 处理集合（Room 表，LRU 淘汰）优先，`hash` 查重其次 |
| 顺序 | 按 `seq` 升序入队应用（单协程消费） |
| last_seq | 随 token 存 EncryptedSharedPreferences；`hello.lastSeq` 上报补发 |
| 重连 | 1s、2s、4s… 上限 60s；`hello.ok.seq - lastSeq > 窗口` 记 WARN 接受缺口 |
| 大文件清单 | clipType=2 `localOnly` 条目只展示清单文本，标记不可粘贴 |

## 3. 协议层设计

### 3.1 WS 数据面（与桌面端同一条 `/ws`）

- 连接：`wss?://host[:port]/ws`，**路径为空时默认 `/ws`**（桌面端刚修过的坑，移动端一开始就做对）；
- 握手头：`Authorization: Bearer <device_token>`；
- 帧模型：`hello` / `hello.ok` / `publish` / `publish.ok` / `event` / `error` / `ping` / `pong`，30s 心跳 ping，服务端 2 个周期无响应判死重连；
- `error.code` 处理：`revoked` → 清 token 回配对页；`bad_token` → 重配对；`rate_limited` → 提示稍后再试；其余 → 记日志 + 重连。

### 3.2 REST 管理面（OkHttp 同引擎）

| 接口 | 用途（移动端视角） |
| --- | --- |
| `POST /v1/device/register` | 一键建房（需求 4.1-1 路径 B） |
| `POST /v1/device/join` + `GET /v1/device/join/status` | 填码加入 + 申请状态轮询（2s 间隔，收到终态停止） |
| `POST /v1/device/invite` | 生成配对码（房间管理页） |
| `GET /v1/device/pending` + `POST /v1/device/pending/decide` | 审批 |
| `GET /v1/device/peers` + `POST /v1/device/peers/revoke` | 成员管理 |
| `GET /healthz` | 设置页"测试连接" |

错误体 `{error}` 统一映射为用户可读文案（中英双语，沿用桌面端措辞）。

### 3.3 事件类型映射

| clipType | 移动端处理 |
| --- | --- |
| 1 Text | 全支持 |
| 2 Files | 只读：清单文本展示 + "仅本机"标记 |
| 3 Image | 全支持：`payloadB64` → Bitmap（解码上限 4096px，超限降采样）；上行默认 JPEG/PNG 原格式 |
| 4 ImageV5 | 按图片处理 |
| 5 Html | 取纯文本降级 |

## 4. 关键流程

### 4.1 连接状态机

```
Offline → Connecting → Authenticating(hello) → Online
   ↑              │失败                        │断开/后台
   └── 退避重连 ←──┘←───────────────────────────┘
```

- **进前台**：立即触发连接（若未连）；
- **退后台**：主动断开 WS（省电，不保活）；离线期间上行条目进 Outbox，下次上线按序补发；
- 状态经 StateFlow 暴露给 UI（连接角标、历史页顶部横幅）。

### 4.2 上行（发送）

1. 触发源：a) 进前台自动读剪贴板；b) 历史条目重发；c) 快捷磁贴；d) 分享菜单（`ACTION_SEND` 文本/图片，经 `ContentResolver` 读流）。
2. 剪贴板指纹（文本 hash + 时间戳）与"最近已发送"比对，5 分钟内同内容不重发。
3. 组装 `clip.add`（`syncId=UUID`，`sourceApp=包名`，图片 base64）→ 大小校验（默认 8MB）→ Outbox → publish。

### 4.3 下行（接收）

1. `event` 帧 → Dedup（syncId/hash）→ Room 入库（含缩略图/降采样图）→ 更新 UI。
2. 若为本房间**最新**条目且类型可粘贴：写入系统剪贴板（`ClipboardManager.setPrimaryClip`，Android 10+ 前台合法），发系统提示条 + 可选通知。
3. `seq` 单调推进，`lastSeq` 持久化（防抖：每 10 条或退后台时落盘一次）。

### 4.4 配对向导（首启流程）

```
填服务器地址 → [测试连接 healthz]
   ├─ 创建新房间：POST /register {deviceName} → 得 token → 入历史/连 WS
   └─ 加入房间：填 8 位码 → POST /join
        ├─ approved → 得 token → 连 WS
        └─ pending → 轮询 status 页（可取消）→ approved/rejected/expired
```

## 5. 数据模型（Room）

```kotlin
@Entity(tableName = "clips")
data class ClipEntity(
  @PrimaryKey val syncId: String,
  val seq: Long, val clipType: Int, val hash: Long,
  val textContent: String?,        // 文本 or 图片占位说明
  val imageFile: String?,          // 图片落盘路径（不存 BLOB，避免 DB 膨胀）
  val thumbFile: String?,          // 缩略图路径（≤256px）
  val originDevice: String, val sourceApp: String?,
  val localOnly: Boolean, val pinned: Boolean,
  val ts: Long, val originRemote: Boolean  // 防回环
)
@Entity(tableName = "dedup")      // syncId 已处理集合
```

图片落盘 `files/<syncId>.img`，随历史淘汰/手动清理删除；启动时做孤儿文件清理。

## 6. 工程结构

```
mobile-android/
├── app/src/main/kotlin/com/cliplite/sync/
│   ├── MainActivity.kt / AppContainer.kt
│   ├── ui/      pairing/ history/ room/ settings/（Compose 屏 + ViewModel）
│   ├── engine/  SyncEngine.kt ConnectionStateMachine.kt WsChannel.kt
│   │            Outbox.kt Dedup.kt ClipboardBridge.kt
│   ├── net/     RestApi.kt Protocol.kt（帧/事件 @Serializable 模型）
│   ├── data/    AppDb.kt ClipEntity.kt ClipDao.kt Prefs.kt
│   └── tile/    SendClipboardTileService.kt   // 快捷磁贴
└── app/src/test/                               // 协议层单测
```

## 7. 测试计划

| 层 | 内容 |
| --- | --- |
| 单元测试 | 帧编解码、去重逻辑、退避状态机、大小限制（JVM 本地跑，不依赖模拟器） |
| 协议联调 | 直连本机 `clipsync-server.exe`（局域网 adb reverse `tcp:8765 tcp:8765`，免部署公网） |
| 集成验收 | 对照需求书 §8 的 5 条验收标准，重点：与桌面端双端互发、双房间隔离、断网补发 |
| 兼容性 | 你的真机优先；Android 8 模拟器跑一遍低版本回归 |

## 8. 风险与对策

| 风险 | 对策 |
| --- | --- |
| Android 10+ 读剪贴板仅前台 | 产品形态即"前台准同步"（需求 §1 已对齐）；磁贴/分享覆盖不打开 App 的发送场景 |
| 大图片 OOM | 解码降采样 + `inSampleSize`；下行图片落盘不驻留内存；上行 8MB 上限 |
| 厂商杀后台导致磁贴失效 | 磁贴点击时冷启动服务处理，不依赖常驻进程 |
| 服务端公网明文 | 设置页检测 `ws://` + 非私网地址时给出"建议 wss://"警示 |
| 系统剪贴板写入频率限制 | Android 10+ 对后台写有限制——仅在应用前台写，符合平台语义 |

## 9. 开发顺序（对齐里程碑）

1. **M1**：工程骨架 → Protocol.kt + 单测 → RestApi（register/join/status）→ 连接状态机 → 配对向导 UI 可用；
2. **M2**：ClipboardBridge + Outbox + 历史列表 + 自动复制（此时文本闭环可日常自用）；
3. **M3**：图片链路、深色模式、设置页、Wi-Fi 限制；
4. **M4**：磁贴/分享入口、房间管理页、真机打磨、出签名 APK。

每阶段结束在 Windows 桌面端 + 真机做一次双端联调，问题当阶段清零。
