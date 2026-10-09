package com.cliplite.sync.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/** 系统剪贴板读写桥（仅前台调用，符合 Android 10+ 平台语义） */
class ClipboardBridge(private val context: Context) {

    private val cm: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    sealed class ClipboardContent {
        data class Text(val text: String) : ClipboardContent()
        data class Image(val bytes: ByteArray, val isPng: Boolean) : ClipboardContent()
        object Empty : ClipboardContent()
    }

    /** 指纹：同内容 5 分钟内不重发 */
    var lastSentHash: Long = 0
    var lastSentAt: Long = 0

    fun read(): ClipboardContent {
        val clip = cm.primaryClip ?: return ClipboardContent.Empty
        if (clip.itemCount == 0) return ClipboardContent.Empty
        val item = clip.getItemAt(0)
        item.text?.let { t ->
            var s = t.toString()
            // 部分 App 复制富文本时，getText() 返回的可能是 HTML 标签串（而非纯文本），
            // 例如 "<p>你好</p>" / "<strong>粗体</strong>"。剥掉标签，避免同步出去 HTML 乱码。
            if (s.isNotEmpty()) {
                s = stripHtml(s)
                return ClipboardContent.Text(s)
            }
        }
        item.uri?.let { uri ->
            readImageUri(uri)?.let { return it }
        }
        return ClipboardContent.Empty
    }

    /** 去掉 HTML 标签，保留可见文本；无标签则原样返回。 */
    private fun stripHtml(src: String): String {
        // 快速判定：不含 '<' 直接返回（绝大多数纯文本场景零开销）
        if ('<' !in src) return src
        val sb = StringBuilder(src.length)
        var inTag = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '<') {
                inTag = true
            } else if (c == '>') {
                inTag = false
            } else if (!inTag) {
                sb.append(c)
            }
            i++
        }
        val out = sb.toString()
        // 剥离后若明显变短（去掉了一定量的标签），返回纯文本；否则可能只是含 "<" 的普通文本，原样返回
        return if (out.isNotEmpty() && out.length < src.length) out else src
    }

    private fun readImageUri(uri: Uri): ClipboardContent.Image? = try {
        val ins = context.contentResolver.openInputStream(uri) ?: return null
        val bytes = ins.use { it.readBytes() }
        if (bytes.isEmpty()) null
        else ClipboardContent.Image(bytes, isPng = bytes.size > 1 && bytes[0] == 0x89.toByte())
    } catch (_: Exception) { null }

    /** 写文本进系统剪贴板；返回是否成功 */
    fun writeText(text: String): Boolean = try {
        cm.setPrimaryClip(ClipData.newPlainText("ClipLiteSync", text))
        true
    } catch (_: Exception) { false }

    /** 写图片（content uri 由 FileProvider 生成，M1 简化为不写图片到剪贴板，仅历史可查） */
    fun writeImage(uri: Uri): Boolean = try {
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "ClipLiteSync", uri))
        true
    } catch (_: Exception) { false }

    fun shouldSend(hash: Long, now: Long): Boolean =
        !(hash == lastSentHash && now - lastSentAt < 5 * 60_000L)

    fun markSent(hash: Long, now: Long) { lastSentHash = hash; lastSentAt = now }

    /** 上行失败时回滚 lastSent，允许同一内容在后续轮询里重试 */
    fun rollbackSent() { lastSentHash = 0; lastSentAt = 0 }

    companion object {
        /** 图片解码：上限 4096px，超限降采样（OOM 对策） */
        fun decodeBitmap(bytes: ByteArray, maxDim: Int = 4096): Bitmap? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /** 生成缩略图（≤256px，JPEG） */
        fun makeThumb(source: ByteArray, maxDim: Int = 256): ByteArray? {
            val bmp = decodeBitmap(source, maxDim) ?: return null
            val scale = maxOf(1f, maxOf(bmp.width, bmp.height).toFloat() / maxDim)
            val w = (bmp.width / scale).toInt().coerceAtLeast(1)
            val h = (bmp.height / scale).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(bmp, w, h, true)
            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (thumb !== bmp) thumb.recycle()
            bmp.recycle()
            return out.toByteArray()
        }

        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

        fun writeFile(dir: File, name: String, bytes: ByteArray): File {
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            return f
        }
    }
}
