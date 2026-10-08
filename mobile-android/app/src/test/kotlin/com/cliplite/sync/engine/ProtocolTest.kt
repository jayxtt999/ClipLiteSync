package com.cliplite.sync.engine

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议帧编解码往返测试（不依赖 Android） */
class ProtocolTest {

    @Test
    fun `hello 帧编解码`() {
        val hello = Protocol.HelloFrame(token = "tok123", lastSeq = 42L)
        val text = Protocol.encode(hello)
        // hello 帧由客户端发出，服务端解析；这里只验证 JSON 结构
        assertTrue(text.contains("\"type\":\"hello\""))
        assertTrue(text.contains("\"token\":\"tok123\""))
        assertTrue(text.contains("\"lastSeq\":42"))
    }

    @Test
    fun `ping 帧编解码`() {
        val text = Protocol.encode(Protocol.PingFrame())
        assertTrue(text.contains("\"type\":\"ping\""))
    }

    @Test
    fun `hello ok 解析`() {
        val frame = Protocol.decode("""{"type":"hello.ok","seq":100,"replayed":3}""")
        assertTrue(frame is Protocol.ServerFrame.HelloOk)
        frame as Protocol.ServerFrame.HelloOk
        assertEquals(100L, frame.seq)
        assertEquals(3, frame.replayed)
    }

    @Test
    fun `event 帧解析 byDevice 与 seq`() {
        val frame = Protocol.decode(
            """{"type":"event","seq":7,"byDevice":"dev-A","event":{"kind":"clip.add","syncId":"s1","clipType":1,"hash":0,"ts":0}}"""
        )
        assertTrue(frame is Protocol.ServerFrame.Event)
        frame as Protocol.ServerFrame.Event
        assertEquals(7L, frame.seq)
        assertEquals("dev-A", frame.byDevice)
    }

    @Test
    fun `error 帧解析 code`() {
        val frame = Protocol.decode("""{"type":"error","code":"bad_token","detail":"x"}""")
        assertTrue(frame is Protocol.ServerFrame.Error)
        frame as Protocol.ServerFrame.Error
        assertEquals("bad_token", frame.code)
    }

    @Test
    fun `pong 解析`() {
        assertTrue(Protocol.decode("""{"type":"pong"}""") is Protocol.ServerFrame.Pong)
    }

    @Test
    fun `未知类型返回 Unknown`() {
        assertTrue(Protocol.decode("""{"type":"nope"}""") is Protocol.ServerFrame.Unknown)
    }

    @Test
    fun `clip add 事件体解码`() {
        val event = buildJsonObject {
            put("kind", "clip.add")
            put("syncId", "s-1")
            put("clipType", 1)
            put("hash", 123L)
            put("payloadB64", "aGVsbG8=")
            put("ts", 999L)
        }
        val clip = Protocol.decodeClipEvent(event)
        assertTrue(clip != null)
        clip!!
        assertEquals("s-1", clip.syncId)
        assertEquals(1, clip.clipType)
        assertEquals(123L, clip.hash)
    }

    @Test
    fun `非 clip add 事件体返回 null`() {
        val event = buildJsonObject {
            put("kind", "clip.delete")
            put("syncId", "s-1")
        }
        assertNull(Protocol.decodeClipEvent(event))
    }

    @Test
    fun `hash64 稳定且区分不同输入`() {
        val a = Protocol.hash64("hello".toByteArray())
        val b = Protocol.hash64("hello".toByteArray())
        val c = Protocol.hash64("world".toByteArray())
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
