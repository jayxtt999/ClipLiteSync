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
	mux.HandleFunc("/ws", s.handleWS)
	return mux
}

func wsURL(httpURL string) string {
	return "ws" + strings.TrimPrefix(httpURL, "http") + "/ws"
}

func joinDevice(t *testing.T, s *Server, name string) *Device {
	t.Helper()
	d, err := s.devices.Create(name)
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
	if got := len(rb.Replay(0)); got != 3 {
		t.Fatalf("缓冲应只保留 3 条，得到 %d", got)
	}
	if got := rb.Replay(2); len(got) != 3 || got[0].Seq != 3 {
		t.Fatalf("Replay(2) 应返回 3、4、5")
	}
	if got := rb.Replay(5); len(got) != 0 {
		t.Fatalf("无缺口时 Replay 应为空")
	}
}
