# ClipSync 协议 v0.2（冻结稿）

> 日期：2026-10-07　状态：**v0.2 已冻结**，v0.3 起需双方确认再改
> 服务端：Go 单文件（本仓库 `server/`）；客户端：ClipLite（`src/sync/`）、Android（二期）
> 设计基线：《ClipLite多设备同步-需求分析与可行性方案.md》§11

## 1. 模型

- 一个**组（group）**= 一套配对设备 + 一条全局事件日志（仅内存环形缓冲）。
- 服务端为每条事件分配全局单调递增 **seq**（从 1 开始，单写者串行，天然全序）。
- 客户端本地持久化 **last_seq**（已应用的最新 seq），重连时上报以补发。
- **语义边界**：只向在线设备推送；离线缺口在服务端缓冲窗口内则补发，超出窗口则**永久放弃**（设计性丢失）。

## 2. 传输与鉴权

| 项 | 规定 |
| --- | --- |
| 传输 | WebSocket（`/ws`）；局域网可 `ws://`，公网必须经 TLS 反代为 `wss://` |
| 鉴权 | WS 握手时带 `Authorization: Bearer <device_token>`，或连接后首帧 `hello` 内带 token |
| token | 服务端建组时生成管理员 token；新设备凭**一次性邀请码**（6 位、10 分钟有效）`POST /v1/device/join` 换取长期 device_token；服务端可吊销 |
| 上限 | 单条消息 ≤ 64MB（服务端可配）；缓冲 ≤ 200 条且 ≤ 256MB（可配，超限淘汰最旧） |

## 3. 帧格式（JSON，UTF-8）

### 3.1 客户端 → 服务端

```json
// 首帧（握手后 5 秒内必须发）
{ "v": 1, "type": "hello", "token": "<device_token>", "lastSeq": 123 }

// 发布（服务端按收到顺序串行落日志并广播）
{ "v": 1, "type": "publish", "clientEventId": "uuid-...", "events": [ <event>, ... ] }

// 心跳
{ "v": 1, "type": "ping" }
```

### 3.2 服务端 → 客户端

```json
// hello 应答（先于补发事件到达）；replayed=N 表示随后将按 seq 升序补发 N 条 event
{ "v": 1, "type": "hello.ok", "seq": 125, "replayed": 4 }

// 事件（广播与补发同格式）
{ "v": 1, "type": "event", "seq": 124, "byDevice": "devA", "event": <event> }

// publish 应答（仅发给发布者）
{ "v": 1, "type": "publish.ok", "clientEventId": "uuid-...", "seq": 126 }

// 错误（收到 error 后客户端应停止发送并按 code 处理）
{ "v": 1, "type": "error", "code": "bad_token|too_big|rate_limited|bad_json|revoked", "detail": "..." }

{ "v": 1, "type": "pong" }
```

### 3.3 事件体（event）

```json
// clip.add
{
  "kind": "clip.add",
  "syncId": "<uuid>",            // 全局唯一，幂等键
  "clipType": 1,                  // 1=Text 2=Files 3=Image 4=ImageV5 5=Html（与 ClipLite ClipType 一致）
  "hash": 12345678901234567,      // 内容 64 位哈希（与 ClipLite clipLiteHash 一致）
  "payloadB64": "...",            // 内容 base64（内联时）
  "bundle": {                     // clipType=2 且允许打包时出现
     "fileCount": 2, "totalBytes": 1048576, "payloadB64": "<zip 的 base64>"
  },
  "localOnly": false,             // true=仅本机（如超限大文件清单）
  "sourceApp": "chrome.exe",      // 来源应用（可空）
  "ts": 1728283021123             // 毫秒时间戳（来源设备时钟）
}

// clip.delete / clip.pin
{ "kind": "clip.delete", "syncId": "<uuid>" }
{ "kind": "clip.pin", "syncId": "<uuid>", "pinned": true }
```

## 4. 客户端行为规范

1. **发布条件**：本机剪贴板捕获 → ClipStore 入库成功 → outbox 队列 → WS publish。远端注入条目（origin=remote）**不再发布**（防回环）。
2. **去重**：接收侧先查 `syncId` 已处理集合（内存 LRU + 本地文件），再查 `hash`（复用 `ClipStore::findHash`），命中则只应用元数据不重复入库。
3. **顺序**：按 `seq` 升序应用；服务端保证同组全序。
4. **幂等**：`publish.ok` 按 `clientEventId` 匹配；补发重复投递靠 `syncId`/`seq` 去重，无副作用。
5. **last_seq 持久化**：随 settings 落盘（`sync.lastSeq`），断电重启后可用于补发。
6. **重连**：指数退避（1s、2s、4s…上限 60s）；重连后 `hello.lastSeq` 触发服务端补发；若 `hello.ok.seq - lastSeq > 缓冲窗口`，客户端静默接受缺口（本地日志记一条 WARN）。
7. **文件列表规则**（一期已实现，`src/sync/zip_util.h` store 模式零依赖编解码）：
   `clipType=2` 时统计清单内文件总大小：
   - 总大小 ≤ **20MB**（服务端配置 `CLIPSYNC_BUNDLE_MAX_MB`，默认 20）→ 打包 zip（仅保留文件名，重名加序号）→ `bundle` 携带；接收端解压到本机数据目录 `synced\<syncId>\` 后以落地路径清单入库（"粘贴时解压"留作二期优化）；
   - 缺失文件/目录/超限 → `localOnly=true` 仅同步清单文本，远端标记"仅本机"不可粘贴。
   - 已知局限：delete/pin 事件以发送端 hash 匹配条目，文件清单的 hash 基于本地路径，跨设备路径不同时 delete/pin 不生效（文本/图片不受影响）。
8. **暂停**：设置里可暂停上行 / 下行（托盘菜单同步开关）。

## 5. REST 接口（管理面，非数据面）

| 方法/路径 | 说明 |
| --- | --- |
| `POST /v1/admin/login` | 管理员密码 → admin token（`.env`/配置文件设初始密码） |
| `POST /v1/device/join` | `{inviteCode, deviceName}` → `{deviceToken, groupId}`（一次性邀请码） |
| `POST /v1/admin/invite` | 生成一次性邀请码 |
| `GET  /v1/admin/devices` | 设备列表 |
| `DELETE /v1/admin/devices/{id}` | 吊销设备（吊销后 token 立即失效） |
| `GET  /healthz` | 存活检查 |

数据面不走 REST（仅 WS）；预留 HTTP `POST /v1/publish`（同等鉴权与格式）供二期 Android 简化接入。

## 6. 一期安全基线

TLS 由部署层承担（局域网明文可接受；公网必须 `wss://`，推荐 Caddy 自动证书）。应用层不加密内容（服务端可见明文，自用）。事件信封保留 `"v":1` 与 `enc` 预留位：二期叠加 AES-GCM 端到端加密时不破坏协议结构。

## 7. 版本

- v0.2（2026-10-07）：冻结。相比 v0.1 删除快照/持久化日志/保留策略，改为内存环形缓冲补发。
