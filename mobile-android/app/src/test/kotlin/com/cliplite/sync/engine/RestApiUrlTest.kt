package com.cliplite.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 地址规范化测试：REST ↔ WS 转换（不依赖 Android/网络） */
class RestApiUrlTest {

    @Test
    fun `ws 地址转 http`() {
        assertEquals("http://192.168.1.10:8765", RestApi.normalizeHttp("ws://192.168.1.10:8765"))
        assertEquals("https://example.com", RestApi.normalizeHttp("wss://example.com"))
    }

    @Test
    fun `裸地址补 http`() {
        assertEquals("http://192.168.1.10:8765", RestApi.normalizeHttp("192.168.1.10:8765"))
    }

    @Test
    fun `尾部斜杠去除`() {
        assertEquals("http://example.com", RestApi.normalizeHttp("http://example.com/"))
    }

    @Test
    fun `http 地址转 ws 且默认补 ws 路径`() {
        // 桌面端踩过的坑：地址无路径时默认 /ws
        assertEquals("ws://192.168.1.10:8765/ws", RestApi.wsUrl("http://192.168.1.10:8765"))
        assertEquals("ws://192.168.1.10:8765/ws", RestApi.wsUrl("192.168.1.10:8765"))
    }

    @Test
    fun `已有路径被去掉再补 ws`() {
        assertEquals("ws://example.com/ws", RestApi.wsUrl("http://example.com/some/path"))
    }

    @Test
    fun `https 转 wss`() {
        assertEquals("wss://example.com/ws", RestApi.wsUrl("https://example.com"))
    }
}
