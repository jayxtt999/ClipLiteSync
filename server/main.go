package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// ---------- 配置 ----------

type Config struct {
	Port            string
	DataDir         string
	AdminPassword   string
	MaxMsgBytes     int64 // 单条 WS 消息上限
	BufferEvents    int   // 环形缓冲条数上限
	BufferBytes     int64 // 环形缓冲总字节上限
	BundleMaxBytes  int64 // 文件清单打包上限（协议 §4.7）
	InviteTTL       time.Duration
	DisableRegister bool // CLIPSYNC_DISABLE_REGISTER=1 时关闭开放注册
}

func loadConfig() Config {
	c := Config{
		Port:            envOr("CLIPSYNC_PORT", "8765"),
		DataDir:         envOr("CLIPSYNC_DATA_DIR", "./data"),
		AdminPassword:   os.Getenv("CLIPSYNC_ADMIN_PASSWORD"),
		MaxMsgBytes:     int64(envOrInt("CLIPSYNC_MAX_MSG_MB", 64)) << 20,
		BufferEvents:    envOrInt("CLIPSYNC_BUFFER_EVENTS", 200),
		BufferBytes:     int64(envOrInt("CLIPSYNC_BUFFER_BYTES_MB", 256)) << 20,
		BundleMaxBytes:  int64(envOrInt("CLIPSYNC_BUNDLE_MAX_MB", 20)) << 20,
		InviteTTL:       10 * time.Minute,
		DisableRegister: os.Getenv("CLIPSYNC_DISABLE_REGISTER") == "1",
	}
	if c.AdminPassword == "" {
		// 安全基线：不再提供默认密码。未设置时管理接口不可用（也无"创始码"，
		// 全新部署必须显式设置 CLIPSYNC_ADMIN_PASSWORD 才能引导第一台设备）。
		log.Println("[warn] CLIPSYNC_ADMIN_PASSWORD 未设置：管理接口与创始码不可用，无法引导首台设备")
	}
	return c
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func envOrInt(k string, d int) int {
	if v := os.Getenv(k); v != "" {
		n := 0
		if _, err := fmt.Sscanf(v, "%d", &n); err == nil && n > 0 {
			return n
		}
	}
	return d
}

// ---------- 设备存储（JSON 文件） ----------

type Device struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Token     string `json:"token"`
	CreatedAt int64  `json:"createdAt"`
	Revoked   bool   `json:"revoked"`
	// CreatedBy 记录该设备由谁引入：邀请设备的 ID、"admin"（创始码）或 "register"（开放注册）。
	// 房主只能列表/踢出自己引入的设备。
	CreatedBy string `json:"createdBy,omitempty"`
	// RoomID 房间标识 = 房间根设备（首台设备）的 ID。同一房间的设备才能收到彼此的同步事件。
	RoomID string `json:"roomId,omitempty"`
	// InviteCode 记录本设备是通过哪个验证码加入的（用于变更验证码时踢出旧设备）。
	// "register"/"admin" 来源无码，留空。
	InviteCode string `json:"inviteCode,omitempty"`
	// MachineID 硬件机器码（主板序列号/机器GUID等），用于设备去重：
	// 同一机器重连/重装后复用已有设备记录，避免列表出现重复终端。
	MachineID string `json:"machineId,omitempty"`
}

// NormalizeRooms 迁移：为没有 RoomID 的设备按 CreatedBy 树计算房间归属。
// 规则：admin 创始码引导的设备归房间 "admin"；无 createdBy 的老数据与 register
// 注册的设备自成一个房间；其余设备沿 CreatedBy 向上追溯到房间根。返回是否有变更。
func (ds *DeviceStore) NormalizeRooms() bool {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	changed := false
	byID := map[string]*Device{}
	for _, d := range ds.Devices {
		byID[d.ID] = d
	}
	var root func(d *Device) string
	root = func(d *Device) string {
		switch d.CreatedBy {
		case "admin":
			return "admin"
		case "", "register":
			return d.ID
		}
		parent := byID[d.CreatedBy]
		if parent == nil || parent.Revoked {
			return d.ID // 引入人已吊销/不存在：独立成房间，不并入其他房间
		}
		return root(parent)
	}
	for _, d := range ds.Devices {
		want := root(d)
		if d.Revoked {
			want = d.RoomID // 已吊销设备不再参与广播，保留原值即可
		}
		if d.RoomID != want {
			d.RoomID = want
			changed = true
		}
	}
	if changed {
		if err := ds.saveLocked(); err != nil {
			log.Printf("[warn] 保存房间迁移结果失败: %v", err)
		}
	}
	return changed
}

type DeviceStore struct {
	mu      sync.Mutex
	path    string
	Devices []*Device `json:"devices"`
}

func NewDeviceStore(path string) (*DeviceStore, error) {
	ds := &DeviceStore{path: path}
	b, err := os.ReadFile(path)
	if err == nil {
		if err := json.Unmarshal(b, ds); err != nil {
			return nil, fmt.Errorf("设备文件损坏: %w", err)
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	return ds, nil
}

func (ds *DeviceStore) saveLocked() error {
	b, err := json.MarshalIndent(ds, "", "  ")
	if err != nil {
		return err
	}
	tmp := ds.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, ds.path)
}

// Create 创建设备。rootID 传入其所在房间的根设备 ID；传空时按 createdBy 推导：
// admin 创始码引导的设备同属房间 "admin"，register 注册的设备自成一个新房间，其余自成房间。
func (ds *DeviceStore) Create(name, createdBy, rootID string) (*Device, error) {
	return ds.create(name, createdBy, rootID, "", "")
}

// CreateWithMachine 创建设备并记录其引入验证码与硬件机器码。
// 用于通过验证码加入的设备，便于「变更验证码踢出旧设备」「机器码去重」。
func (ds *DeviceStore) CreateWithMachine(name, createdBy, rootID, inviteCode, machineID string) (*Device, error) {
	return ds.create(name, createdBy, rootID, inviteCode, machineID)
}

func (ds *DeviceStore) create(name, createdBy, rootID, inviteCode, machineID string) (*Device, error) {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	id, _ := randomToken(8)
	tok, _ := randomToken(32)
	if rootID == "" {
		if createdBy == "admin" {
			rootID = "admin"
		} else {
			rootID = "dev-" + id
		}
	}
	d := &Device{ID: "dev-" + id, Name: name, Token: tok, CreatedAt: time.Now().UnixMilli(),
		CreatedBy: createdBy, RoomID: rootID, InviteCode: inviteCode, MachineID: machineID}
	ds.Devices = append(ds.Devices, d)
	return d, ds.saveLocked()
}

// FindByID 按 ID 查找设备（含已吊销）
func (ds *DeviceStore) FindByID(id string) *Device {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for _, d := range ds.Devices {
		if d.ID == id {
			return d
		}
	}
	return nil
}

// ListByCreator 返回某设备引入的全部未吊销设备（不含其自身）
func (ds *DeviceStore) ListByCreator(creatorID string) []*Device {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	var out []*Device
	for _, d := range ds.Devices {
		if d.CreatedBy == creatorID && !d.Revoked {
			out = append(out, d)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt < out[j].CreatedAt })
	return out
}

// ListByRoom 返回某同步组（房间）内全部未吊销设备（含本机），供成员列表展示。
func (ds *DeviceStore) ListByRoom(roomID string) []*Device {
	if roomID == "" {
		return nil
	}
	ds.mu.Lock()
	defer ds.mu.Unlock()
	var out []*Device
	for _, d := range ds.Devices {
		if d.RoomID == roomID && !d.Revoked {
			out = append(out, d)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt < out[j].CreatedAt })
	return out
}

func (ds *DeviceStore) FindByToken(token string) *Device {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for _, d := range ds.Devices {
		if d.Token == token && !d.Revoked {
			return d
		}
	}
	return nil
}

func (ds *DeviceStore) List() []*Device {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	out := make([]*Device, len(ds.Devices))
	copy(out, ds.Devices)
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt < out[j].CreatedAt })
	return out
}

// SetCreatedBy 更新设备的引入人（测试/管理用途）
func (ds *DeviceStore) SetCreatedBy(id, createdBy string) bool {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for _, d := range ds.Devices {
		if d.ID == id {
			d.CreatedBy = createdBy
			return ds.saveLocked() == nil
		}
	}
	return false
}

func (ds *DeviceStore) Revoke(id string) bool {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for _, d := range ds.Devices {
		if d.ID == id {
			d.Revoked = true
			return ds.saveLocked() == nil
		}
	}
	return false
}

// ListByInviteCode 返回通过某验证码加入、且尚未吊销的设备（用于变更验证码时踢出旧设备）。
func (ds *DeviceStore) ListByInviteCode(code string) []*Device {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	var out []*Device
	for _, d := range ds.Devices {
		if d.InviteCode == code && !d.Revoked {
			out = append(out, d)
		}
	}
	return out
}

// FindByMachineID 按机器码查找未吊销设备（设备去重：同机器重连复用已有记录）。
func (ds *DeviceStore) FindByMachineID(machineID string) *Device {
	if machineID == "" {
		return nil
	}
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for _, d := range ds.Devices {
		if d.MachineID == machineID && !d.Revoked {
			return d
		}
	}
	return nil
}

func randomToken(nBytes int) (string, error) {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// ---------- 邀请码（永久验证码） ----------

// permanentTTL 永久验证码的到期时长：99 年，服务端/客户端统一按"几乎永久"处理。
const permanentTTL = 99 * 365 * 24 * time.Hour

type Invite struct {
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expiresAt"`
	Used      bool      `json:"used"`
	CreatedBy string    `json:"createdBy"` // 设备 ID 或 "admin"
}

type InviteStore struct {
	mu      sync.Mutex
	path    string
	invites map[string]*Invite
}

// NewInviteStore 从磁盘加载配对码（invites.json），不存在则新建空表。
// 配对码持久化：服务端重启后仍可继续用旧码配对，避免"永久码"因重启失效。
func NewInviteStore(path string) (*InviteStore, error) {
	is := &InviteStore{path: path, invites: map[string]*Invite{}}
	b, err := os.ReadFile(path)
	if err == nil {
		var raw map[string]*Invite
		if e := json.Unmarshal(b, &raw); e != nil {
			return nil, fmt.Errorf("配对码文件损坏: %w", e)
		}
		is.invites = raw
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	return is, nil
}

// saveLocked 将配对码写盘（调用方须持锁）。
func (is *InviteStore) saveLocked() {
	if is.path == "" {
		return
	}
	b, err := json.MarshalIndent(is.invites, "", "  ")
	if err != nil {
		log.Printf("[warn] 序列化配对码失败: %v", err)
		return
	}
	tmp := is.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		log.Printf("[warn] 写入配对码失败: %v", err)
		return
	}
	if err := os.Rename(tmp, is.path); err != nil {
		log.Printf("[warn] 落盘配对码失败: %v", err)
	}
}

// New 生成 8 位随机永久验证码（大写字母+数字，去掉易混淆的 0/1/I/O）。
// 到期给 99 年（视为永久），可重复使用。
func (is *InviteStore) New(createdBy string) (*Invite, error) {
	is.mu.Lock()
	defer is.mu.Unlock()
	is.cleanupLocked()
	var code string
	for {
		code = randomCode(8)
		if _, exists := is.invites[code]; !exists {
			break
		}
	}
	inv := &Invite{Code: code, ExpiresAt: time.Now().Add(permanentTTL), CreatedBy: createdBy}
	is.invites[code] = inv
	is.saveLocked()
	return inv, nil
}

// Revoke 使某设备生成的全部验证码失效（变更验证码时调用）。
func (is *InviteStore) RevokeByCreator(createdBy string) {
	is.mu.Lock()
	defer is.mu.Unlock()
	changed := false
	for _, inv := range is.invites {
		if inv.CreatedBy == createdBy {
			inv.Used = true
			inv.ExpiresAt = time.Now().Add(-time.Second)
			changed = true
		}
	}
	if changed {
		is.saveLocked()
	}
}

// Peek 只读查看（join 限速判定用，不消耗）
func (is *InviteStore) Peek(code string) *Invite {
	is.mu.Lock()
	defer is.mu.Unlock()
	return is.invites[code]
}

// Take 领取验证码（成功返回 true）。永久码可重复使用，不消耗。
func (is *InviteStore) Take(code string) bool {
	is.mu.Lock()
	defer is.mu.Unlock()
	inv, ok := is.invites[code]
	if !ok || inv.Used || time.Now().After(inv.ExpiresAt) {
		return false
	}
	return true
}

// ListByCreator 某设备生成的全部码（含已失效，用于变更验证码时踢出旧设备）
func (is *InviteStore) ListByCreator(createdBy string) []*Invite {
	is.mu.Lock()
	defer is.mu.Unlock()
	var out []*Invite
	for _, inv := range is.invites {
		if inv.CreatedBy == createdBy {
			out = append(out, inv)
		}
	}
	return out
}

// CurrentValid 返回某设备当前有效（未失效、未过期）的配对码；无则返回 nil。
// 用于客户端"查询本机当前码"，保证显示码与服务端实际生效码一致。
func (is *InviteStore) CurrentValid(createdBy string) *Invite {
	is.mu.Lock()
	defer is.mu.Unlock()
	now := time.Now()
	for _, inv := range is.invites {
		if inv.CreatedBy == createdBy && !inv.Used && now.Before(inv.ExpiresAt) {
			return inv
		}
	}
	return nil
}

func (is *InviteStore) cleanupLocked() {
	now := time.Now()
	changed := false
	for code, inv := range is.invites {
		// 已失效/已过期 1 小时后清理
		if inv.Used || now.After(inv.ExpiresAt.Add(time.Hour)) {
			delete(is.invites, code)
			changed = true
		}
	}
	if changed {
		is.saveLocked()
	}
}

// ---------- 管理员 token（内存态，重启失效） ----------

type AdminTokens struct {
	mu     sync.Mutex
	tokens map[string]bool
}

func (a *AdminTokens) New() string {
	t, _ := randomToken(32)
	a.mu.Lock()
	a.tokens[t] = true
	a.mu.Unlock()
	return t
}

func (a *AdminTokens) Check(t string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.tokens[t]
}

// ---------- 入口 ----------

func main() {
	cfg := loadConfig()
	if err := os.MkdirAll(cfg.DataDir, 0700); err != nil {
		log.Fatalf("创建数据目录失败: %v", err)
	}
	devices, err := NewDeviceStore(filepath.Join(cfg.DataDir, "devices.json"))
	if err != nil {
		log.Fatalf("%v", err)
	}
	if devices.NormalizeRooms() {
		log.Println("[migrate] 设备房间归属已按引入关系计算并写回 devices.json")
	}
	hub := NewHub(cfg)

	invites, err := NewInviteStore(filepath.Join(cfg.DataDir, "invites.json"))
	if err != nil {
		log.Fatalf("%v", err)
	}

	srv := &Server{cfg: cfg, devices: devices, invites: invites, pending: NewPendingStore(),
		joinGuard: NewJoinGuard(), admin: &AdminTokens{tokens: map[string]bool{}}, hub: hub}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200); w.Write([]byte("ok")) })
	mux.HandleFunc("/v1/admin/login", srv.handleAdminLogin)
	mux.HandleFunc("/v1/admin/invite", srv.handleAdminInvite)
	mux.HandleFunc("/v1/admin/devices", srv.handleAdminDevices)
	mux.HandleFunc("/v1/admin/devices/", srv.handleAdminRevoke)
	mux.HandleFunc("/v1/device/invite", srv.handleDeviceInvite)
	mux.HandleFunc("/v1/device/join", srv.handleDeviceJoin)
	mux.HandleFunc("/v1/device/register", srv.handleDeviceRegister)
	mux.HandleFunc("/v1/device/join/status", srv.handleJoinStatus)
	mux.HandleFunc("/v1/device/pending", srv.handleDevicePending)
	mux.HandleFunc("/v1/device/pending/decide", srv.handlePendingDecide)
	mux.HandleFunc("/v1/device/peers", srv.handleDevicePeers)
	mux.HandleFunc("/v1/device/room", srv.handleDeviceRoom)
	mux.HandleFunc("/v1/device/peers/revoke", srv.handleDevicePeerRevoke)
	mux.HandleFunc("/ws", srv.handleWS)

	log.Printf("ClipSync server 监听 :%s（数据目录 %s）", cfg.Port, cfg.DataDir)
	if err := http.ListenAndServe(":"+cfg.Port, mux); err != nil {
		log.Fatal(err)
	}
}

type Server struct {
	cfg       Config
	devices   *DeviceStore
	invites   *InviteStore
	pending   *PendingStore
	joinGuard *JoinGuard
	admin     *AdminTokens
	hub       *Hub
}
