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
	Port           string
	DataDir        string
	AdminPassword  string
	MaxMsgBytes    int64 // 单条 WS 消息上限
	BufferEvents   int   // 环形缓冲条数上限
	BufferBytes    int64 // 环形缓冲总字节上限
	BundleMaxBytes int64 // 文件清单打包上限（协议 §4.7）
	InviteTTL      time.Duration
}

func loadConfig() Config {
	c := Config{
		Port:           envOr("CLIPSYNC_PORT", "8765"),
		DataDir:        envOr("CLIPSYNC_DATA_DIR", "./data"),
		AdminPassword:  os.Getenv("CLIPSYNC_ADMIN_PASSWORD"),
		MaxMsgBytes:    int64(envOrInt("CLIPSYNC_MAX_MSG_MB", 64)) << 20,
		BufferEvents:   envOrInt("CLIPSYNC_BUFFER_EVENTS", 200),
		BufferBytes:    int64(envOrInt("CLIPSYNC_BUFFER_BYTES_MB", 256)) << 20,
		BundleMaxBytes: int64(envOrInt("CLIPSYNC_BUNDLE_MAX_MB", 20)) << 20,
		InviteTTL:      10 * time.Minute,
	}
	if c.AdminPassword == "" {
		c.AdminPassword = "admin123"
		log.Println("[warn] CLIPSYNC_ADMIN_PASSWORD 未设置，使用默认密码 admin123，请尽快修改")
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

func (ds *DeviceStore) Create(name string) (*Device, error) {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	id, _ := randomToken(8)
	tok, _ := randomToken(32)
	d := &Device{ID: "dev-" + id, Name: name, Token: tok, CreatedAt: time.Now().UnixMilli()}
	ds.Devices = append(ds.Devices, d)
	return d, ds.saveLocked()
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

func randomToken(nBytes int) (string, error) {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// ---------- 邀请码 ----------

type Invite struct {
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expiresAt"`
	Used      bool      `json:"used"`
}

type InviteStore struct {
	mu      sync.Mutex
	invites map[string]*Invite
}

func NewInviteStore() *InviteStore {
	return &InviteStore{invites: map[string]*Invite{}}
}

func (is *InviteStore) New(ttl time.Duration) string {
	is.mu.Lock()
	defer is.mu.Unlock()
	// 6 位数字码
	b := make([]byte, 4)
	rand.Read(b)
	code := fmt.Sprintf("%06d", int(b[0])<<24|int(b[1])<<16|int(b[2])<<8|int(b[3]))[:6]
	is.invites[code] = &Invite{Code: code, ExpiresAt: time.Now().Add(ttl)}
	return code
}

func (is *InviteStore) Take(code string) bool {
	is.mu.Lock()
	defer is.mu.Unlock()
	inv, ok := is.invites[code]
	if !ok || inv.Used || time.Now().After(inv.ExpiresAt) {
		return false
	}
	inv.Used = true
	return true
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
	hub := NewHub(cfg)

	srv := &Server{cfg: cfg, devices: devices, invites: NewInviteStore(), admin: &AdminTokens{tokens: map[string]bool{}}, hub: hub}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200); w.Write([]byte("ok")) })
	mux.HandleFunc("/v1/admin/login", srv.handleAdminLogin)
	mux.HandleFunc("/v1/admin/invite", srv.handleAdminInvite)
	mux.HandleFunc("/v1/admin/devices", srv.handleAdminDevices)
	mux.HandleFunc("/v1/admin/devices/", srv.handleAdminRevoke)
	mux.HandleFunc("/v1/device/join", srv.handleDeviceJoin)
	mux.HandleFunc("/ws", srv.handleWS)

	log.Printf("ClipSync server 监听 :%s（数据目录 %s）", cfg.Port, cfg.DataDir)
	if err := http.ListenAndServe(":"+cfg.Port, mux); err != nil {
		log.Fatal(err)
	}
}

type Server struct {
	cfg     Config
	devices *DeviceStore
	invites *InviteStore
	admin   *AdminTokens
	hub     *Hub
}
