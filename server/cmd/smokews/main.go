package main

// 一次性 WS 冒烟客户端：连接 /ws，发布一条事件，打印收到的所有帧。
// 用法: go run cmd/smokews/main.go <ws-url> <device-token>

import (
	"fmt"
	"os"
	"time"

	"github.com/gorilla/websocket"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Println("usage: smokews <ws-url> <device-token>")
		os.Exit(1)
	}
	ws, _, err := websocket.DefaultDialer.Dial(os.Args[1], nil)
	if err != nil {
		fmt.Println("dial error:", err)
		os.Exit(1)
	}
	defer ws.Close()
	ws.SetReadDeadline(time.Now().Add(5 * time.Second))

	if err := ws.WriteJSON(map[string]any{"v": 1, "type": "hello", "token": os.Args[2], "lastSeq": 0}); err != nil {
		fmt.Println("write hello error:", err)
		os.Exit(1)
	}
	if err := ws.WriteJSON(map[string]any{
		"v": 1, "type": "publish", "clientEventId": "smoke-1",
		"events": []map[string]any{{
			"kind": "clip.add", "syncId": "smoke-sync-1", "clipType": 1,
			"hash": 12345, "payloadB64": "c21va2UtdGVzdA==", "ts": 1728283021123,
		}},
	}); err != nil {
		fmt.Println("write publish error:", err)
		os.Exit(1)
	}

	for {
		_, b, err := ws.ReadMessage()
		if err != nil {
			return
		}
		fmt.Println("recv:", string(b))
	}
}
