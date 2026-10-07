package main

// 房间制配对（协议 v0.3）：
// - 已配对设备可生成 8 位一次性配对码（10 分钟有效）；
// - 新设备填码提交申请（不直接发 token），进入 pending；
// - 生成码的设备确认（approve）后才真正建号发 token；
// - 管理员生成的码（createdBy=admin）作为"创始码"直接放行（无确认人）；
// - /v1/device/join 按 IP 限速 + 连续失败锁定，防暴力遍历。

import (
	"crypto/rand"
	"encoding/json"
	"log"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ---------- 配对码 ----------

// codeCharset 去掉易混淆的 0/1/I/O，32 字符，8 位组合空间 2^40
const codeCharset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

func randomCode(n int) string {
	b := make([]byte, n)
	for i := range b {
		v, err := rand.Int(rand.Reader, big.NewInt(int64(len(codeCharset))))
		if err != nil {
			b[i] = codeCharset[i%len(codeCharset)]
			continue
		}
		b[i] = codeCharset[v.Int64()]
	}
	return string(b)
}

// ---------- 待确认申请 ----------

type PendingJoin struct {
	RequestID string    `json:"requestId"`
	Code      string    `json:"code"`
	Device    string    `json:"device"`   // 生成码的设备 ID
	Name      string    `json:"name"`     // 申请设备名
	CreatedAt time.Time `json:"createdAt"`
	Status    string    `json:"status"` // pending / approved / rejected
	// approve 后填充，供申请方轮询领取
	DeviceID   string `json:"deviceId,omitempty"`
	DeviceToken string `json:"deviceToken,omitempty"`
}

type PendingStore struct {
	mu      sync.Mutex
	entries map[string]*PendingJoin
}

func NewPendingStore() *PendingStore {
	return &PendingStore{entries: map[string]*PendingJoin{}}
}

func (ps *PendingStore) Create(code, device, name string, ttl time.Duration) *PendingJoin {
	id, _ := randomToken(16)
	p := &PendingJoin{
		RequestID: id,
		Code:      code,
		Device:    device,
		Name:      name,
		CreatedAt: time.Now(),
		Status:    "pending",
	}
	ps.mu.Lock()
	ps.expireLocked()
	ps.entries[p.RequestID] = p
	ps.mu.Unlock()
	return p
}

func (ps *PendingStore) Get(id string) *PendingJoin {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	p, ok := ps.entries[id]
	if !ok {
		return nil
	}
	if p.Status == "pending" && time.Since(p.CreatedAt) > 10*time.Minute {
		p.Status = "expired"
	}
	return p
}

// ListByDevice 返回某设备生成的码对应的待确认申请（仅 pending）
func (ps *PendingStore) ListByDevice(device string) []*PendingJoin {
	ps.mu.Lock()
	ps.expireLocked()
	defer ps.mu.Unlock()
	var out []*PendingJoin
	for _, p := range ps.entries {
		if p.Device == device && p.Status == "pending" {
			out = append(out, p)
		}
	}
	return out
}

func (ps *PendingStore) expireLocked() {
	now := time.Now()
	for id, p := range ps.entries {
		if p.Status == "pending" && now.Sub(p.CreatedAt) > 10*time.Minute {
			p.Status = "expired"
		}
		// 已终态的条目保留 30 分钟后清理
		if p.Status != "pending" && now.Sub(p.CreatedAt) > 40*time.Minute {
			delete(ps.entries, id)
		}
	}
}

// ---------- join 防爆破（按 IP） ----------

type ipState struct {
	windowStart time.Time
	windowCount int
	failCount   int
	lockedUntil time.Time
}

type JoinGuard struct {
	mu sync.Mutex
	ips map[string]*ipState
}

func NewJoinGuard() *JoinGuard {
	return &JoinGuard{ips: map[string]*ipState{}}
}

func (g *JoinGuard) key(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		// 反代场景取第一个跳；无反代时该头可伪造，但限速对象仍收敛
		host = strings.TrimSpace(strings.Split(fwd, ",")[0])
	}
	return host
}

// Allow 返回 (是否放行, 错误码)
// 规则：每 IP 每分钟最多 5 次；连续失败 10 次锁 15 分钟；成功清零失败计数。
func (g *JoinGuard) Allow(r *http.Request, success bool) (bool, string) {
	now := time.Now()
	k := g.key(r)
	g.mu.Lock()
	defer g.mu.Unlock()
	st, ok := g.ips[k]
	if !ok {
		st = &ipState{}
		g.ips[k] = st
	}
	if now.Before(st.lockedUntil) {
		return false, "locked"
	}
	if now.Sub(st.windowStart) > time.Minute {
		st.windowStart = now
		st.windowCount = 0
	}
	if !success {
		st.windowCount++
		st.failCount++
		if st.failCount >= 10 {
			st.lockedUntil = now.Add(15 * time.Minute)
			log.Printf("[pair] IP %s 连续失败 %d 次，锁定 15 分钟", k, st.failCount)
		}
		if st.windowCount > 5 {
			return false, "rate_limited"
		}
		return true, ""
	}
	st.failCount = 0
	st.windowCount++
	return true, ""
}

// ---------- 设备鉴权 ----------

func (s *Server) authDevice(r *http.Request) *Device {
	return s.devices.FindByToken(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
}

// ---------- REST：设备侧配对 ----------

// POST /v1/device/invite —— 已配对设备生成配对码
func (s *Server) handleDeviceInvite(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		respond(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	device := s.authDevice(r)
	if device == nil {
		respond(w, 401, map[string]string{"error": "设备 token 无效"})
		return
	}
	// 每设备同时最多 5 个有效码，防刷
	active := 0
	for _, inv := range s.invites.ListByCreator(device.ID) {
		if !inv.Used && time.Now().Before(inv.ExpiresAt) {
			active++
		}
	}
	if active >= 5 {
		respond(w, 429, map[string]string{"error": "有效配对码已达上限（5 个），请等其过期"})
		return
	}
	inv := s.invites.New(device.ID, s.cfg.InviteTTL)
	log.Printf("[pair] 设备 %s(%s) 生成配对码 %s", device.Name, device.ID, inv.Code)
	respond(w, 200, map[string]any{"code": inv.Code, "expiresAt": inv.ExpiresAt.UnixMilli()})
}

// POST /v1/device/join —— 新设备填码申请加入（两阶段）
func (s *Server) handleDeviceJoin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		InviteCode string `json:"inviteCode"`
		DeviceName string `json:"deviceName"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.joinGuard.Allow(r, false)
		respond(w, 403, map[string]string{"error": "请求格式错误"})
		return
	}
	inv := s.invites.Peek(req.InviteCode)
	ok := inv != nil && !inv.Used && time.Now().Before(inv.ExpiresAt)
	if allowed, code := s.joinGuard.Allow(r, ok); !allowed {
		if code == "locked" {
			respond(w, 429, map[string]string{"error": "失败次数过多，请 15 分钟后再试"})
		} else {
			respond(w, 429, map[string]string{"error": "请求过于频繁，每分钟最多 5 次"})
		}
		return
	}
	if inv == nil || inv.Used || !time.Now().Before(inv.ExpiresAt) {
		respond(w, 403, map[string]string{"error": "配对码无效或已使用"})
		return
	}
	// 消耗配对码（一次性）
	if !s.invites.Take(req.InviteCode) {
		respond(w, 403, map[string]string{"error": "配对码无效或已使用"})
		return
	}
	// 创始码（管理员生成）：无确认人，直接建号
	if inv.CreatedBy == "admin" {
		d, err := s.devices.Create(req.DeviceName, "admin")
		if err != nil {
			respond(w, 500, map[string]string{"error": err.Error()})
			return
		}
		log.Printf("[pair] 创始码加入：设备 %s(%s)", req.DeviceName, d.ID)
		respond(w, 200, map[string]string{"status": "approved", "deviceId": d.ID, "deviceToken": d.Token})
		return
	}
	// 设备生成的码：挂起等确认
	if len(req.DeviceName) > 64 {
		req.DeviceName = req.DeviceName[:64]
	}
	p := s.pending.Create(inv.Code, inv.CreatedBy, req.DeviceName, 10*time.Minute)
	log.Printf("[pair] 设备 %s 申请加入 %s 的房间（待确认 %s）", req.DeviceName, inv.CreatedBy, p.RequestID[:8])
	respond(w, 200, map[string]string{"status": "pending", "requestId": p.RequestID})
}

// GET /v1/device/join/status?requestId=xxx —— 申请方轮询
func (s *Server) handleJoinStatus(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("requestId")
	p := s.pending.Get(id)
	if p == nil {
		respond(w, 200, map[string]string{"status": "not_found"})
		return
	}
	switch p.Status {
	case "approved":
		respond(w, 200, map[string]string{"status": "approved", "deviceId": p.DeviceID, "deviceToken": p.DeviceToken})
	case "rejected":
		respond(w, 200, map[string]string{"status": "rejected"})
	case "expired":
		respond(w, 200, map[string]string{"status": "expired"})
	default:
		respond(w, 200, map[string]string{"status": "pending"})
	}
}

// GET /v1/device/pending —— 房主拉取待确认申请
func (s *Server) handleDevicePending(w http.ResponseWriter, r *http.Request) {
	device := s.authDevice(r)
	if device == nil {
		respond(w, 401, map[string]string{"error": "设备 token 无效"})
		return
	}
	list := s.pending.ListByDevice(device.ID)
	type item struct {
		RequestID  string `json:"requestId"`
		DeviceName string `json:"deviceName"`
		CreatedAt  int64  `json:"createdAt"`
	}
	out := make([]item, 0, len(list))
	for _, p := range list {
		out = append(out, item{RequestID: p.RequestID, DeviceName: p.Name, CreatedAt: p.CreatedAt.UnixMilli()})
	}
	respond(w, 200, map[string]any{"pending": out})
}

// POST /v1/device/pending/decide —— 房主允许/拒绝
func (s *Server) handlePendingDecide(w http.ResponseWriter, r *http.Request) {
	device := s.authDevice(r)
	if device == nil {
		respond(w, 401, map[string]string{"error": "设备 token 无效"})
		return
	}
	var req struct {
		RequestID string `json:"requestId"`
		Approve   bool   `json:"approve"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RequestID == "" {
		respond(w, 400, map[string]string{"error": "请求格式错误"})
		return
	}
	p := s.pending.Get(req.RequestID)
	if p == nil {
		respond(w, 404, map[string]string{"error": "申请不存在"})
		return
	}
	if p.Device != device.ID {
		respond(w, 403, map[string]string{"error": "只能处理自己生成的配对码的申请"})
		return
	}
	if p.Status != "pending" {
		respond(w, 409, map[string]string{"error": "申请已处理或已过期"})
		return
	}
	if !req.Approve {
		p.Status = "rejected"
		log.Printf("[pair] %s 拒绝了 %s 的加入申请", device.ID, p.Name)
		respond(w, 200, map[string]string{"ok": "rejected"})
		return
	}
	// 记录引入关系：房主可在此后列表/踢出该设备
	d, err := s.devices.Create(p.Name, device.ID)
	if err != nil {
		respond(w, 500, map[string]string{"error": err.Error()})
		return
	}
	p.Status = "approved"
	p.DeviceID = d.ID
	p.DeviceToken = d.Token
	log.Printf("[pair] %s 允许 %s 加入（新设备 %s，由 %s 引入）", device.ID, p.Name, d.ID, device.ID)
	respond(w, 200, map[string]string{"ok": "approved", "deviceId": d.ID})
}

// GET /v1/device/peers —— 列出经由本机配对码加入的设备（不含已吊销）
func (s *Server) handleDevicePeers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		respond(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	device := s.authDevice(r)
	if device == nil {
		respond(w, 401, map[string]string{"error": "设备 token 无效"})
		return
	}
	list := s.devices.ListByCreator(device.ID)
	type item struct {
		DeviceID  string `json:"deviceId"`
		Name      string `json:"name"`
		CreatedAt int64  `json:"createdAt"`
	}
	out := make([]item, 0, len(list))
	for _, d := range list {
		out = append(out, item{DeviceID: d.ID, Name: d.Name, CreatedAt: d.CreatedAt})
	}
	respond(w, 200, map[string]any{"peers": out})
}

// POST /v1/device/peers/revoke —— 房主移除（踢出）自己引入的设备
func (s *Server) handleDevicePeerRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		respond(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	device := s.authDevice(r)
	if device == nil {
		respond(w, 401, map[string]string{"error": "设备 token 无效"})
		return
	}
	var req struct {
		DeviceID string `json:"deviceId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.DeviceID == "" {
		respond(w, 400, map[string]string{"error": "请求格式错误"})
		return
	}
	target := s.devices.FindByID(req.DeviceID)
	if target == nil || target.Revoked {
		respond(w, 404, map[string]string{"error": "设备不存在或已移除"})
		return
	}
	if target.ID == device.ID {
		respond(w, 403, map[string]string{"error": "不能移除本机，请使用解除配对"})
		return
	}
	if target.CreatedBy != device.ID {
		respond(w, 403, map[string]string{"error": "只能移除经由本机配对码加入的设备"})
		return
	}
	if !s.devices.Revoke(target.ID) {
		respond(w, 500, map[string]string{"error": "移除失败"})
		return
	}
	// 踢下线：立即断开其 WS 连接（其 token 已吊销，无法重连）
	kicked := s.hub.KickDevice(target.ID)
	log.Printf("[pair] %s 移除设备 %s(%s)，踢下线 %d 个连接", device.ID, target.Name, target.ID, kicked)
	respond(w, 200, map[string]any{"ok": true, "kicked": kicked})
}
