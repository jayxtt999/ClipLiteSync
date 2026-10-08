# ClipSync Server

ClipLite 多设备剪贴板同步服务端。单二进制、无数据库（设备表为 JSON 文件）、近乎无状态。

协议见 [docs/clip-sync-protocol.md](../docs/clip-sync-protocol.md)（v0.2）。

## 构建

```pwsh
cd server
go build -o clipsync-server.exe .
```

要求 Go 1.20+。国内环境拉依赖使用 `GOPROXY=https://goproxy.cn,direct`。

## 运行

```pwsh
$env:CLIPSYNC_PORT = "8765"
$env:CLIPSYNC_ADMIN_PASSWORD = "<你的管理密码>"
$env:CLIPSYNC_DATA_DIR = "./data"
.\clipsync-server.exe
```

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CLIPSYNC_PORT` | 8765 | 监听端口 |
| `CLIPSYNC_ADMIN_PASSWORD` | （无默认值，不设置则管理面不可用） | 管理员密码 |
| `CLIPSYNC_DATA_DIR` | ./data | 设备表 JSON 存放目录 |
| `CLIPSYNC_MAX_MSG_MB` | 64 | 单条消息上限（MB） |
| `CLIPSYNC_BUFFER_EVENTS` | 200 | 重连补发缓冲条数 |
| `CLIPSYNC_BUFFER_BYTES_MB` | 256 | 补发缓冲总字节上限 |
| `CLIPSYNC_BUNDLE_MAX_MB` | 20 | 文件清单打包上传上限（协议 §4.7） |
| `CLIPSYNC_DISABLE_REGISTER` | （关闭） | 设为 1 时关闭开放注册（POST /v1/device/register），公网部署建议关闭 |

## 部署形态

- **局域网**：任一台常开的 Windows/NAS 运行 exe，客户端填 `ws://<内网IP>:8765/ws`。
- **公网**：VPS 运行（Linux 版 `GOOS=linux go build`），前面挂 Caddy/Nginx 做 TLS，客户端填 `wss://<域名>/ws`。**公网明文 HTTP 不可用**。

## 设备接入流程

推荐**开放注册**（无需管理员参与）：设备在客户端填好服务器地址后点「生成配对码」，即自动 `POST /v1/device/register` 创建新房间并成为房主。其他设备填该房间的 8 位永久配对码申请加入，房主确认后发放 device_token。

管理员创始码方式（备选，用于关闭开放注册时引导首台设备）：

1. `POST /v1/admin/login`（密码）→ adminToken；
2. `POST /v1/admin/invite`（Bearer adminToken）→ 8 位永久创始码；
3. `POST /v1/device/join` `{inviteCode, deviceName, machineId}` → `deviceToken`（创始码直接放行）；
4. 客户端持 deviceToken 连接 `ws(s)://.../ws`。
5. 吊销：`POST /v1/admin/devices/{deviceId}`，吊销后该设备下次握手即被拒。

`cmd/smokews` 是一条链路的冒烟客户端（连接→发布→打印收到的帧），可用于部署后自检。

## 测试

```pwsh
go test ./...
```

覆盖：登录/邀请码一次性、发布→广播→重连按序补发、发布者不收自己的事件、吊销 token 立即失效、环形缓冲淘汰。
