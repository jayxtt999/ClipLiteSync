package com.cliplite.sync.engine

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 应用内诊断日志：Logcat 同步输出 + 内存环形缓冲（供「诊断日志」页展示/复制）。
 * 便于在无 adb 的场景（ABox 模拟器/真机直装）现场排查连接问题。
 */
object AppLog {

    private const val CAPACITY = 800
    private const val TAG = "ClipSync"

    private val buf = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    private fun log(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(buf) {
            if (buf.size >= CAPACITY) buf.removeFirst()
            buf.addLast(line)
        }
        when (level) {
            "W" -> Log.w(TAG, "[$tag] $msg")
            "E" -> Log.e(TAG, "[$tag] $msg")
            else -> Log.i(TAG, "[$tag] $msg")
        }
    }

    /** 全部日志行（旧→新） */
    fun snapshot(): List<String> = synchronized(buf) { buf.toList() }

    fun clear() = synchronized(buf) { buf.clear() }
}
