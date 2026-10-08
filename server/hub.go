package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// ---------- 事件与环形缓冲 ----------

type Event struct {
	Seq      int64           `json:"seq"`
	ByDevice string          `json:"byDevice"`
	Room     string          `json:"room,omitempty"` // 房间根设备 ID：只投递给同房间设备
	Event    json.RawMessage `json:"event"`
}

type RingBuffer struct {
	mu     sync.Mutex
	events []*Event
	bytes  int64
	cfg    Config
}

func NewRingBuffer(cfg Config) *RingBuffer { return &RingBuffer{cfg: cfg} }

func (rb *RingBuffer) Append(e *Event) {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	rb.events = append(rb.events, e)
	rb.bytes += int64(len(e.Event))
	for len(rb.events) > rb.cfg.BufferEvents || rb.bytes > rb.cfg.BufferBytes {
		old := rb.events[0]
		rb.bytes -= int64(len(old.Event))
		rb.events = rb.events[1:]
	}
}

// Replay 返回 seq > since 且属于指定房间（room 为空表示不区分房间）的全部缓冲事件
func (rb *RingBuffer) Replay(since int64, room string) []*Event {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	var out []*Event
	for _, e := range rb.events {
		if e.Seq > since && (room == "" || e.Room == "" || e.Room == room) {
			out = append(out, e)
		}
	}
	return out
}

func (rb *RingBuffer) LatestSeq() int64 {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	if len(rb.events) == 0 {
		return 0
	}
	return rb.events[len(rb.events)-1].Seq
}

// ---------- 连接 ----------

type conn struct {
	ws     *websocket.Conn
	device *Device
	send   chan []byte

	closeOnce sync.Once
}

func (c *conn) enqueue(b []byte) bool {
	defer func() { _ = recover() }() // 队列刚被关闭时安全失败
	select {
	case c.send <- b:
		return true
	default:
		return false
	}
}

func (c *conn) enqueueJSON(v any) bool {
	b, err := json.Marshal(v)
	if err != nil {
		return false
	}
	return c.enqueue(b)
}

func (c *conn) close() {
	c.closeOnce.Do(func() { close(c.send) })
}

func writeLoop(c *conn) {
	for b := range c.send {
		c.ws.SetWriteDeadline(time.Now().Add(30 * time.Second))
		if err := c.ws.WriteMessage(websocket.TextMessage, b); err != nil {
			return
		}
	}
}

// ---------- Hub ----------

type Hub struct {
	mu      sync.Mutex
	conns   map[*conn]bool
	seq     int64
	rb      *RingBuffer
	limiter map[*conn]*rateLimiter
	cfg     Config
}

func NewHub(cfg Config) *Hub {
	return &Hub{conns: map[*conn]bool{}, rb: NewRingBuffer(cfg), limiter: map[*conn]*rateLimiter{}, cfg: cfg}
}

func (h *Hub) add(c *conn) {
	h.mu.Lock()
	h.conns[c] = true
	h.limiter[c] = newRateLimiter()
	h.mu.Unlock()
}

func (h *Hub) drop(c *conn) {
	h.mu.Lock()
	_, present := h.conns[c]
	delete(h.conns, c)
	delete(h.limiter, c)
	h.mu.Unlock()
	if present {
		c.close()
	}
}

// KickDevice 断开指定设备的全部 WS 连接（吊销后调用），返回断开的连接数
func (h *Hub) KickDevice(deviceID string) int {
	h.mu.Lock()
	targets := make([]*conn, 0, len(h.conns))
	for c := range h.conns {
		if c.device != nil && c.device.ID == deviceID {
			targets = append(targets, c)
		}
	}
	h.mu.Unlock()
	for _, c := range targets {
		h.drop(c)
		c.ws.Close() // 立即断开底层连接，客户端感知后重连会被 bad_token 拒绝
	}
	return len(targets)
}

func (h *Hub) broadcast(e *Event, except *conn) {
	b, _ := json.Marshal(map[string]any{"v": 1, "type": "event", "seq": e.Seq, "byDevice": e.ByDevice, "event": e.Event})
	h.mu.Lock()
	targets := make([]*conn, 0, len(h.conns))
	for c := range h.conns {
		// 房间隔离：只投递给同房间设备（老数据 Room 为空时退化为全局广播）
		if c == except {
			continue
		}
		if e.Room != "" && c.device != nil && c.device.RoomID != "" && c.device.RoomID != e.Room {
			continue
		}
		targets = append(targets, c)
	}
	h.mu.Unlock()
	for _, c := range targets {
		if !c.enqueue(b) {
			log.Printf("[warn] 设备 %s 发送队列满，丢弃连接", c.device.ID)
			h.drop(c)
		}
	}
}

func (h *Hub) publish(device *Device, raw json.RawMessage) *Event {
	h.mu.Lock()
	h.seq++
	e := &Event{Seq: h.seq, ByDevice: device.ID, Room: device.RoomID, Event: raw}
	h.rb.Append(e)
	h.mu.Unlock()
	return e
}

func (h *Hub) LatestSeq() int64 { return h.rb.LatestSeq() }

// rateLimiter：极简令牌桶，20 条/秒，防失控客户端
type rateLimiter struct {
	mu sync.Mutex
	at time.Time
	n  int
}

func newRateLimiter() *rateLimiter { return &rateLimiter{at: time.Now()} }

func (rl *rateLimiter) allow() bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	now := time.Now()
	if now.Sub(rl.at) > time.Second {
		rl.at = now
		rl.n = 0
	}
	rl.n++
	return rl.n <= 20
}

// ---------- WS 处理 ----------

var upgrader = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}

type frame struct {
	V             int               `json:"v"`
	Type          string            `json:"type"`
	Token         string            `json:"token,omitempty"`
	LastSeq       int64             `json:"lastSeq,omitempty"`
	ClientEventID string            `json:"clientEventId,omitempty"`
	Events        []json.RawMessage `json:"events,omitempty"`
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer ws.Close()
	ws.SetReadLimit(s.cfg.MaxMsgBytes)

	// 握手：此阶段无并发写，可直接写
	ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	var hello frame
	if err := ws.ReadJSON(&hello); err != nil || hello.Type != "hello" {
		writeDirect(ws, map[string]any{"v": 1, "type": "error", "code": "bad_json", "detail": "首帧必须是 hello"})
		return
	}
	device := s.devices.FindByToken(hello.Token)
	if device == nil {
		writeDirect(ws, map[string]any{"v": 1, "type": "error", "code": "bad_token", "detail": "配对已失效，请重新配对"})
		return
	}
	ws.SetReadDeadline(time.Time{}) // 清除握手超时，此后按每条消息 120s

	c := &conn{ws: ws, device: device, send: make(chan []byte, 64)}
	s.hub.add(c)
	defer s.hub.drop(c)
	go writeLoop(c)

	// 重连补发：先 hello.ok（告知缺口条数），再按序回放 seq > lastSeq（仅同房间）
	// （含自己发布的，客户端按 byDevice==self 跳过）
	replay := s.hub.rb.Replay(hello.LastSeq, device.RoomID)
	c.enqueueJSON(map[string]any{"v": 1, "type": "hello.ok", "seq": s.hub.LatestSeq(), "replayed": len(replay)})
	for _, e := range replay {
		b, _ := json.Marshal(map[string]any{"v": 1, "type": "event", "seq": e.Seq, "byDevice": e.ByDevice, "event": e.Event})
		c.enqueue(b)
	}
	log.Printf("[sync] 设备 %s(%s) 已连接，补发 %d 条", device.Name, device.ID, len(replay))

	for {
		ws.SetReadDeadline(time.Now().Add(120 * time.Second))
		var f frame
		if err := ws.ReadJSON(&f); err != nil {
			break
		}
		switch f.Type {
		case "ping":
			c.enqueueJSON(map[string]any{"v": 1, "type": "pong"})
		case "publish":
			if !s.hub.limiter[c].allow() {
				c.enqueueJSON(map[string]any{"v": 1, "type": "error", "code": "rate_limited", "detail": "发送过快"})
				continue
			}
			for _, ev := range f.Events {
				if int64(len(ev)) > s.cfg.MaxMsgBytes {
					c.enqueueJSON(map[string]any{"v": 1, "type": "error", "code": "too_big", "detail": "事件超过上限"})
					continue
				}
				e := s.hub.publish(device, ev)
				c.enqueueJSON(map[string]any{"v": 1, "type": "publish.ok", "clientEventId": f.ClientEventID, "seq": e.Seq})
				s.hub.broadcast(e, c)
			}
		default:
			c.enqueueJSON(map[string]any{"v": 1, "type": "error", "code": "bad_json", "detail": "未知 type: " + f.Type})
		}
	}
	log.Printf("[sync] 设备 %s(%s) 断开", device.Name, device.ID)
}

func writeDirect(ws *websocket.Conn, v any) {
	ws.SetWriteDeadline(time.Now().Add(30 * time.Second))
	ws.WriteJSON(v)
}

// ---------- REST 处理 ----------

func respond(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func (s *Server) handleAdminLogin(w http.ResponseWriter, r *http.Request) {
	if s.cfg.AdminPassword == "" {
		respond(w, 403, map[string]string{"error": "服务端未设置 CLIPSYNC_ADMIN_PASSWORD，管理接口不可用"})
		return
	}
	var req struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Password != s.cfg.AdminPassword {
		respond(w, 401, map[string]string{"error": "密码错误"})
		return
	}
	respond(w, 200, map[string]string{"adminToken": s.admin.New()})
}

func (s *Server) authAdmin(r *http.Request) bool {
	return s.admin.Check(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
}

func (s *Server) handleAdminInvite(w http.ResponseWriter, r *http.Request) {
	if !s.authAdmin(r) {
		respond(w, 401, map[string]string{"error": "未授权"})
		return
	}
	inv, err := s.invites.New("admin")
	if err != nil {
		respond(w, 500, map[string]string{"error": err.Error()})
		return
	}
	log.Printf("[pair] 管理员生成创始码 %s", inv.Code)
	respond(w, 200, map[string]string{"inviteCode": inv.Code})
}

func (s *Server) handleAdminDevices(w http.ResponseWriter, r *http.Request) {
	if !s.authAdmin(r) {
		respond(w, 401, map[string]string{"error": "未授权"})
		return
	}
	respond(w, 200, map[string]any{"devices": s.devices.List()})
}

func (s *Server) handleAdminRevoke(w http.ResponseWriter, r *http.Request) {
	if !s.authAdmin(r) {
		respond(w, 401, map[string]string{"error": "未授权"})
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/v1/admin/devices/")
	respond(w, 200, map[string]bool{"ok": s.devices.Revoke(id)})
}
