# ClipLiteSync 配对与连接流程说明

> 本文档基于当前最新代码（服务端 `server/` + PC 客户端 `src/`）梳理，说明设备如何接入服务器、如何配对，以及「配对码」「创始码」「开放注册」三者的关系。
>
> 更新日期：2026-10-08

---

## 一、核心结论（先看这里）

1. **客户端正常配对连接，完全不需要「创始码」。**
   - 客户端「生成配对码」按钮走的是 **开放注册**（`POST /v1/device/register`），本机在服务端**自动注册并创建一个新房间**，随后生成房间的**永久配对码**。
   - 对方设备填这个配对码 → 「加入」→ 进入房间。整条链路从头到尾都不涉及管理接口（admin）。

2. **「创始码」是管理接口（admin）里的一个可选备选路径，不是客户端流程的一部分。**
   - 它只在一种场景下才需要：服务端用 `CLIPSYNC_DISABLE_REGISTER=1` **关闭了开放注册**时，才必须由管理员登录生成创始码来引导第一台设备。
   - 默认（开放注册开启）下，**根本用不到创始码**。

3. **`CLIPSYNC_ADMIN_PASSWORD`（管理密码）与客户端配对无直接关系。**
   - 它只用于登录管理接口（`/v1/admin/*`），做设备列表查看、撤销、生成创始码等管理操作。
   - 客户端通过「生成配对码」开放注册 + 对方「加入」完成配对，**不需要管理密码**。
   - 唯一例外：关闭开放注册后，才需要管理密码去生成创始码。

---

## 二、三种「码」的区别

| 名称 | 由谁生成 | 接口 | 有效期 | 能否复用 | 用途 |
| --- | --- | --- | --- | --- | --- |
| **配对码（验证码）** | 已配对设备（房主） | `POST /v1/device/invite` | 永久（99 年，视为长期） | 可复用 | 让新设备填码申请加入本房间 |
| **创始码** | 管理员 | `POST /v1/admin/invite` | 永久（同上） | 可复用 | 关闭开放注册时，引导首台设备入组（无确认人，直接放行） |
| **开放注册** | 新设备自己 | `POST /v1/device/register` | — | — | 本机自行注册成一个新房间（无需任何码） |

> 说明：配对码与创始码底层都是 `InviteStore` 里的同一套「8 位永久码」，区别仅在于 `createdBy` 标记：
> - `createdBy = 设备ID` → 普通配对码（加入需房主确认）
> - `createdBy = "admin"` → 创始码（加入直接放行，无确认人）

---

## 三、客户端连接服务器的完整流程

### 场景 A：第一台设备（创建房间）—— 推荐默认路径

```
用户 A（第一台设备）
  │
  │ 1. 在设置里填写服务器地址（如 ws://192.168.1.10:8765）
  │
  │ 2. 点击「生成配对码」
  ▼
  POST /v1/device/register
    body: { deviceName, machineId }
  │   （无鉴权，受每 IP 每分钟 5 次限速）
  ▼
  服务端：创建新设备 + 新房间（RoomID = 本机 ID）
    → 返回 { deviceId, deviceToken }
  │
  │ 3. 客户端持 deviceToken 调用
  ▼
  POST /v1/device/invite
    header: Authorization: Bearer <deviceToken>
    body: {}   （或 {"rotate":true} 表示变更验证码）
  │
  ▼
  服务端：生成 8 位永久配对码
    → 返回 { code: "XXXXXXXX", expiresAt }
  │
  │ 4. 客户端保存 deviceId/deviceToken，展示配对码（默认 ●●●● 掩码，眼睛图标切换显示）
  │
  ▼
  用户 A 已是一个房间的「房主」，等待其他设备加入
```

### 场景 B：第二台及后续设备（加入房间）

```
用户 B（新设备）
  │
  │ 1. 填写服务器地址
  │ 2. 在「对方配对码」处填入 A 给的 8 位配对码，点「加入」
  ▼
  POST /v1/device/join
    body: { inviteCode, deviceName, machineId }
  │   （无鉴权，受限速 + 连续失败锁定保护）
  ▼
  服务端判定：
    ├─ 码是「创始码」(createdBy=admin) → 直接建号/复用，返回 approved + token
    └─ 码是「普通配对码」→ 挂起，进入待确认队列，返回 pending + requestId
  │
  │ （普通配对码分支）
  ▼
  B 轮询 GET /v1/device/join/status?requestId=xxx
  │
  │ 同时 A（房主）收到待确认申请
  │ A 点「允许」→ POST /v1/device/pending/decide { requestId, approve:true }
  ▼
  服务端创建新设备（归入 A 的房间，CreatedBy=A），返回 approved + token
  │
  ▼
  B 轮询拿到 deviceId/deviceToken，建立 WebSocket 连接，同步自动开启
```

### 场景 C：变更验证码（踢出旧设备）

```
A（房主）点击「变更验证码」
  │
  ▼
  POST /v1/device/invite
    body: { "rotate": true }
  │
  ▼
  服务端：
   1. 撤销 A 之前生成的所有旧码
   2. 踢出通过旧码加入的全部设备（Revoke + 断开 WS）
   3. 生成新永久配对码
  │
  ▼
  A 拿到新码；旧设备被踢下线且无法重连
```

---

## 四、机器码去重

所有注册/加入请求都会上报 `machineId`（客户端读取 `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`，回退系统盘卷序列号）。

服务端在以下三处按机器码去重，**同一台机器重连/重装后复用已有设备身份，避免设备列表出现重复终端**：

1. 开放注册（`register`）—— 同机器码已注册则返回原身份；
2. 创始码加入（`join` 的 admin 分支）—— 同机器码复用已有设备；
3. 房主允许加入（`pending/decide`）—— 同机器码复用已有设备。

---

## 五、管理接口（admin）与创始码 —— 仅备选路径

管理接口需要 `CLIPSYNC_ADMIN_PASSWORD` 密码登录，默认**与客户端配对无关**：

| 接口 | 作用 |
| --- | --- |
| `POST /v1/admin/login` | 密码换 adminToken |
| `POST /v1/admin/invite` | 生成创始码（引导首台设备，无确认人直接放行） |
| `GET /v1/admin/devices` | 列出所有设备 |
| `POST /v1/admin/devices/{id}` | 撤销（踢出）某设备 |

**什么时候才用创始码？** 仅当服务端设置了 `CLIPSYNC_DISABLE_REGISTER=1`（关闭开放注册，公网部署常见）时：
- 此时「生成配对码」的开放注册会被拒绝（403：服务端已关闭开放注册）；
- 必须由管理员登录生成**创始码**，第一台设备填创始码「加入」完成入组。

**默认局域网自用场景，不设 `CLIPSYNC_DISABLE_REGISTER`，则全程不需要管理密码、也不需要创始码。**

---

## 六、端点总览

| 端点 | 鉴权 | 说明 |
| --- | --- | --- |
| `GET /healthz` | 无 | 健康检查 / 服务器可达性探测 |
| `POST /v1/device/register` | 无 | 开放注册（自建新房间） |
| `POST /v1/device/invite` | 设备 token | 生成/变更永久配对码 |
| `POST /v1/device/join` | 无 | 填码申请加入（admin 码直接放行，设备码挂起） |
| `GET /v1/device/join/status` | 无 | 申请方轮询结果 |
| `GET /v1/device/pending` | 设备 token | 房主拉取待确认申请 |
| `POST /v1/device/pending/decide` | 设备 token | 房主允许/拒绝 |
| `GET /v1/device/peers` | 设备 token | 房主列出自己引入的设备 |
| `POST /v1/device/peers/revoke` | 设备 token | 房主移除自己引入的设备 |
| `POST /v1/admin/login` | 密码 | 管理登录 |
| `POST /v1/admin/invite` | admin token | 生成创始码 |
| `GET /v1/admin/devices` | admin token | 列出设备 |
| `POST /v1/admin/devices/{id}` | admin token | 撤销设备 |
| `GET /ws` | 设备 token | WebSocket 同步通道 |

---

## 七、已知文档不一致（待清理）

以下旧文档仍保留「一次性/10 分钟/创始码引导首台设备」的过时描述，与当前「永久码 + 机器码去重」实现不符：

- `docs/clip-sync-protocol.md`：第 20、96、100、101、103 行
- `docs/sync-user-guide.md`：第 14、15、31-37、102、104 行
- `server/README.md`：第 28、44、46 行（"admin123 默认密码""6 位一次性邀请码""DELETE /v1/admin/devices/{id}"等）

这些文档需按本文档的「永久码」语义同步更新（另行处理）。
