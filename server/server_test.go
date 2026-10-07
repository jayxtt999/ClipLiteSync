package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func newTestServer(t *testing.T) (*Server, *httptest.Server) {
	t.Helper()
	cfg := loadConfig()
	cfg.DataDir = t.TempDir()
	cfg.AdminPassword = "test123"
	devices, err := NewDeviceStore(cfg.DataDir + "/devices.json")
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{cfg: cfg, devices: devices, invites: NewInviteStore(), pending: NewPendingStore(), joinGuard: NewJoinGuard(), admin: &AdminTokens{tokens: map[string]bool{}}, hub: NewHub(cfg)}
	ts := httptest.NewServer(httpHandler(s))
	t.Cleanup(ts.Close)
	return s, ts
}

func httpHandler(s *Server) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200); w.Write([]byte("ok")) })
	mux.HandleFunc("/v1/admin/login", s.handleAdminLogin)
	mux.HandleFunc("/v1/admin/invite", s.handleAdminInvite)
	mux.HandleFunc("/v1/admin/devices", s.handleAdminDevices)
	mux.HandleFunc("/v1/admin/devices/", s.handleAdminRevoke)
	mux.HandleFunc("/v1/device/join", s.handleDeviceJoin)
	mux.HandleFunc("/v1/device/pending", s.handleDevicePending)
	mux.HandleFunc("/v1/device/pending/decide", s.handlePendingDecide)
	mux.HandleFunc("/v1/device/peers", s.handleDevicePeers)
	mux.HandleFunc("/v1/device/peers/revoke", s.handleDevicePeerRevoke)
	mux.HandleFunc("/ws", s.handleWS)
	return mux
}

func wsURL(httpURL string) string {
	return "ws" + strings.TrimPrefix(httpURL, "http") + "/ws"
}

func joinDevice(t *testing.T, s *Server, name string) *Device {
	t.Helper()
	d, err := s.devices.Create(name, "admin", "")
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func dial(t *testing.T, ts *httptest.Server, token string, lastSeq int64) (*websocket.Conn, chan []byte) {
	t.Helper()
	ws, _, err := websocket.DefaultDialer.Dial(wsURL(ts.URL), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	ws.WriteJSON(map[string]any{"v": 1, "type": "hello", "token": token, "lastSeq": lastSeq})
	ch := make(chan []byte, 64)
	go func() {
		defer close(ch)
		for {
			_, b, err := ws.ReadMessage()
			if err != nil {
				return
			}
			ch <- b
		}
	}()
	return ws, ch
}

func waitFrame(t *testing.T, ch chan []byte, timeout time.Duration, typ string) map[string]any {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case b, ok := <-ch:
			if !ok {
				t.Fatalf("连接关闭，等待 %s", typ)
			}
			var f map[string]any
			json.Unmarshal(b, &f)
			if f["type"] == typ {
				return f
			}
		case <-deadline:
			t.Fatalf("超时等待帧 %s", typ)
		}
	}
}

func drain(ch chan []byte) {
	for {
		select {
		case _, ok := <-ch:
			if !ok {
				return
			}
		default:
			return
		}
	}
}

func httpPost(t *testing.T, url string, body []byte, bearer string) (int, []byte) {
	t.Helper()
	req, err := http.NewRequest("POST", url, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, b
}

func TestAdminLoginAndInviteFlow(t *testing.T) {
	s, ts := newTestServer(t)

	code, body := httpPost(t, ts.URL+"/v1/admin/login", []byte(`{"password":"test123"}`), "")
	if code != 200 {
		t.Fatalf("登录应成功: %d %s", code, body)
	}
	var login map[string]string
	json.Unmarshal(body, &login)

	code, body = httpPost(t, ts.URL+"/v1/admin/invite", []byte("{}"), login["adminToken"])
	if code != 200 {
		t.Fatalf("生成邀请码应成功: %d", code)
	}
	var inv map[string]string
	json.Unmarshal(body, &inv)
	if len(inv["inviteCode"]) != 8 {
		t.Fatalf("配对码应为 8 位: %q", inv["inviteCode"])
	}

	// 创始码（管理员生成）：直接放行，返回 token
	joinBody, _ := json.Marshal(map[string]string{"inviteCode": inv["inviteCode"], "deviceName": "pc-A"})
	code, body = httpPost(t, ts.URL+"/v1/device/join", joinBody, "")
	if code != 200 {
		t.Fatalf("设备加入应成功: %d", code)
	}
	var dev map[string]string
	json.Unmarshal(body, &dev)
	if dev["deviceToken"] == "" {
		t.Fatal("缺少 deviceToken")
	}

	code, _ = httpPost(t, ts.URL+"/v1/device/join", joinBody, "")
	if code != 403 {
		t.Fatalf("邀请码应一次性，得到 %d", code)
	}

	// 未授权访问管理接口
	if code, _ := httpPost(t, ts.URL+"/v1/admin/invite", []byte("{}"), "wrong"); code != 401 {
		t.Fatal("错误 admin token 应 401")
	}
	_ = s
}

func TestPublishBroadcastAndReplay(t *testing.T) {
	s, ts := newTestServer(t)
	devA := joinDevice(t, s, "pc-A")
	devB := joinDevice(t, s, "pc-B")

	wsA, chA := dial(t, ts, devA.Token, 0)
	defer wsA.Close()
	waitFrame(t, chA, 2*time.Second, "hello.ok")

	wsB, chB := dial(t, ts, devB.Token, 0)
	defer wsB.Close()
	waitFrame(t, chB, 2*time.Second, "hello.ok")

	// A 发布事件 → publish.ok seq=1；B 收到广播
	wsA.WriteJSON(map[string]any{
		"v": 1, "type": "publish", "clientEventId": "e1",
		"events": []map[string]any{{
			"kind": "clip.add", "syncId": "s1", "clipType": 1,
			"hash": 42, "payloadB64": "aGVsbG8=", "ts": 1728283021123,
		}},
	})
	ok := waitFrame(t, chA, 2*time.Second, "publish.ok")
	if ok["seq"].(float64) != 1 {
		t.Fatalf("A 发布 seq 应为 1，得到 %v", ok["seq"])
	}
	ev := waitFrame(t, chB, 2*time.Second, "event")
	if ev["seq"].(float64) != 1 || ev["byDevice"] != devA.ID {
		t.Fatalf("B 收到的事件不正确: %v", ev)
	}

	// B 离线，A 再发两条（seq=2、3）
	wsB.Close()
	drain(chB)
	wsA.WriteJSON(map[string]any{
		"v": 1, "type": "publish", "clientEventId": "e2",
		"events": []map[string]any{{"kind": "clip.delete", "syncId": "s0"}},
	})
	waitFrame(t, chA, 2*time.Second, "publish.ok")
	wsA.WriteJSON(map[string]any{
		"v": 1, "type": "publish", "clientEventId": "e3",
		"events": []map[string]any{{"kind": "clip.add", "syncId": "s2", "clipType": 1, "hash": 43, "payloadB64": "d29ybGQ="}},
	})
	waitFrame(t, chA, 2*time.Second, "publish.ok")

	// B 带 lastSeq=1 重连 → 按序补发 2、3
	wsB2, chB2 := dial(t, ts, devB.Token, 1)
	defer wsB2.Close()
	h := waitFrame(t, chB2, 2*time.Second, "hello.ok")
	if h["replayed"].(float64) != 2 {
		t.Fatalf("应补发 2 条，得到 %v", h["replayed"])
	}
	e1 := waitFrame(t, chB2, 2*time.Second, "event")
	e2 := waitFrame(t, chB2, 2*time.Second, "event")
	if e1["seq"].(float64) != 2 || e2["seq"].(float64) != 3 {
		t.Fatalf("补发顺序错误: %v %v", e1["seq"], e2["seq"])
	}

	// 发布者不应收到自己的广播（A 在 seq=1 后只应收到 publish.ok，没有 event）
	select {
	case b := <-chA:
		var f map[string]any
		json.Unmarshal(b, &f)
		if f["type"] == "event" {
			t.Fatalf("发布者不应收到自己的事件: %v", f)
		}
	default:
	}
}

func TestRevokedTokenRejected(t *testing.T) {
	s, ts := newTestServer(t)
	dev := joinDevice(t, s, "pc-X")
	s.devices.Revoke(dev.ID)
	ws, ch := dial(t, ts, dev.Token, 0)
	defer ws.Close()
	f := waitFrame(t, ch, 2*time.Second, "error")
	if f["code"] != "bad_token" {
		t.Fatalf("吊销 token 应被拒绝: %v", f)
	}
}

// 设备侧"房间成员"接口：列表只含自己引入的设备，且只能移除自己引入的设备。
func TestDevicePeersListAndRevoke(t *testing.T) {
	s, ts := newTestServer(t)
	owner := joinDevice(t, s, "pc-owner")
	peer := joinDevice(t, s, "pc-peer")
	peer.CreatedBy = owner.ID
	if !s.devices.SetCreatedBy(peer.ID, owner.ID) {
		t.Fatal("SetCreatedBy 失败")
	}
	stranger := joinDevice(t, s, "pc-stranger")

	authed := func(method, path, token, body string) (int, map[string]any) {
		req, _ := http.NewRequest(method, ts.URL+path, strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var out map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return resp.StatusCode, out
	}

	// owner 列表：只有 pc-peer
	code, out := authed("GET", "/v1/device/peers", owner.Token, "")
	if code != 200 {
		t.Fatalf("peers 应 200，得到 %d", code)
	}
	peers := out["peers"].([]any)
	if len(peers) != 1 || peers[0].(map[string]any)["deviceId"] != peer.ID {
		t.Fatalf("owner 应只看到自己引入的设备: %v", out)
	}
	// stranger 的列表为空
	code, out = authed("GET", "/v1/device/peers", stranger.Token, "")
	if code != 200 || len(out["peers"].([]any)) != 0 {
		t.Fatalf("stranger 应无 peers: %d %v", code, out)
	}

	// 被踢设备先建立 WS 连接
	ws, ch := dial(t, ts, peer.Token, 0)
	defer ws.Close()

	// 不能移除本机、不能移除非自己引入的设备
	code, _ = authed("POST", "/v1/device/peers/revoke", owner.Token,
		`{"deviceId":"`+owner.ID+`"}`)
	if code != 403 {
		t.Fatalf("移除本机应 403，得到 %d", code)
	}
	code, _ = authed("POST", "/v1/device/peers/revoke", owner.Token,
		`{"deviceId":"`+stranger.ID+`"}`)
	if code != 403 {
		t.Fatalf("移除非引入设备应 403，得到 %d", code)
	}

	// 正常移除：200，且其 WS 被断开
	code, out = authed("POST", "/v1/device/peers/revoke", owner.Token,
		`{"deviceId":"`+peer.ID+`"}`)
	if code != 200 || out["ok"] != true {
		t.Fatalf("移除引入设备应 200: %d %v", code, out)
	}
	if kicked, ok := out["kicked"].(float64); !ok || kicked < 1 {
		t.Fatalf("应有至少 1 个连接被踢下线: %v", out)
	}
	// 被踢设备：要么收到错误帧，要么连接被直接断开（均为踢下线表现）
	select {
	case b, ok := <-ch:
		if ok {
			var f map[string]any
			_ = json.Unmarshal(b, &f)
			if f["type"] == "error" && f["code"] != "bad_token" {
				t.Fatalf("被踢设备应收到 bad_token 或直接断开: %v", f)
			}
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("被踢设备的连接未在 2 秒内断开")
	}

	// 移除后列表为空、重复移除 404
	code, out = authed("GET", "/v1/device/peers", owner.Token, "")
	if code != 200 || len(out["peers"].([]any)) != 0 {
		t.Fatalf("移除后 peers 应为空: %v", out)
	}
	code, _ = authed("POST", "/v1/device/peers/revoke", owner.Token,
		`{"deviceId":"`+peer.ID+`"}`)
	if code != 404 {
		t.Fatalf("重复移除应 404，得到 %d", code)
	}
}

func TestRingBufferEviction(t *testing.T) {
	cfg := loadConfig()
	cfg.BufferEvents = 3
	rb := NewRingBuffer(cfg)
	for i := int64(1); i <= 5; i++ {
		raw, _ := json.Marshal(map[string]int{"i": int(i)})
		rb.Append(&Event{Seq: i, ByDevice: "d", Event: raw})
	}
	if rb.LatestSeq() != 5 {
		t.Fatalf("LatestSeq 应为 5")
	}
	if got := len(rb.Replay(0, "")); got != 3 {
		t.Fatalf("缓冲应只保留 3 条，得到 %d", got)
	}
	if got := rb.Replay(2, ""); len(got) != 3 || got[0].Seq != 3 {
		t.Fatalf("Replay(2) 应返回 3、4、5")
	}
	if got := rb.Replay(5, ""); len(got) != 0 {
		t.Fatalf("无缺口时 Replay 应为空")
	}
}

// TestRoomIsolation 开放注册各自成新房间；同房间互相收到事件，跨房间收不到。
func TestRoomIsolation(t *testing.T) {
	s, ts := newTestServer(t)
	// register 路由开放时：两次注册是两个独立房间
	devA, err := s.devices.Create("pc-A", "register", "")
	if err != nil {
		t.Fatal(err)
	}
	devB, err := s.devices.Create("pc-B", "register", "")
	if err != nil {
		t.Fatal(err)
	}
	// A2 与 A 同房间（模拟 A 生成配对码、对方加入后归入 A 的房间）
	devA2, err := s.devices.Create("pc-A2", "invite", devA.RoomID)
	if err != nil {
		t.Fatal(err)
	}
	if devA.RoomID == devB.RoomID {
		t.Fatalf("两次注册应产生不同房间: %s", devA.RoomID)
	}

	wsA, chA := dial(t, ts, devA.Token, 0)
	defer wsA.Close()
	waitFrame(t, chA, 2*time.Second, "hello.ok")
	wsA2, chA2 := dial(t, ts, devA2.Token, 0)
	defer wsA2.Close()
	waitFrame(t, chA2, 2*time.Second, "hello.ok")
	wsB, chB := dial(t, ts, devB.Token, 0)
	defer wsB.Close()
	waitFrame(t, chB, 2*time.Second, "hello.ok")

	// A 发布 → 同房间的 A2 收到，跨房间的 B 收不到
	wsA.WriteJSON(map[string]any{
		"v": 1, "type": "publish", "clientEventId": "e1",
		"events": []map[string]any{{
			"kind": "clip.add", "syncId": "s1", "clipType": 1,
			"hash": 42, "payloadB64": "aGVsbG8=", "ts": 1728283021123,
		}},
	})
	waitFrame(t, chA, 2*time.Second, "publish.ok")
	ev := waitFrame(t, chA2, 2*time.Second, "event")
	if ev["byDevice"] != devA.ID {
		t.Fatalf("A2 应收到 A 的事件，得到 %v", ev)
	}
	select {
	case b := <-chB:
		t.Fatalf("跨房间设备不应收到事件，却收到: %s", b)
	case <-time.After(800 * time.Millisecond):
		// 符合预期：隔离生效
	}
}
