package com.cliplite.sync.ui.history

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliplite.sync.AppContainer
import com.cliplite.sync.data.ClipEntity
import com.cliplite.sync.engine.SyncEngine
import com.cliplite.sync.ui.ButtonFull
import com.cliplite.sync.ui.ImageViewerDialog
import com.cliplite.sync.ui.StatusPill
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    val clips = container.db.clipDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var query by mutableStateOf("")

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(container) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(container: AppContainer, conn: SyncEngine.ConnState, snackbar: SnackbarHostState) {
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
    val clips by vm.clips.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var openId by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<ClipEntity?>(null) }
    var textDetail by remember { mutableStateOf<ClipEntity?>(null) }
    var more by remember { mutableStateOf<ClipEntity?>(null) }

    val filtered = remember(clips, vm.query) {
        if (vm.query.isBlank()) clips
        else clips.filter { it.textContent?.contains(vm.query, ignoreCase = true) == true }
    }

    fun copyText(clip: ClipEntity) {
        scope.launch {
            val ok = container.engine.copyToClipboard(clip.syncId)
            snackbar.showSnackbar(if (ok) "已复制到剪贴板" else "复制失败")
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶栏：标题 + 状态胶囊 + 搜索
        val wsError by container.engine.lastWsError.collectAsState()
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                val (pillText, pillColor) = when (conn) {
                    SyncEngine.ConnState.ONLINE -> "已连接 · 同步中" to Color(0xFF1E9E5A)
                    SyncEngine.ConnState.CONNECTING -> "连接中…" to Color(0xFFB45309)
                    SyncEngine.ConnState.WAITING ->
                        (if (wsError.isNullOrBlank()) "重连中 · 将自动恢复"
                         else "重连中 · $wsError") to Color(0xFFB45309)
                    SyncEngine.ConnState.OFFLINE -> "已断开 · 点按重连" to Color(0xFFB91C1C)
                }
                StatusPill(pillText, pillColor, modifier = Modifier.clickable {
                    if (conn != SyncEngine.ConnState.ONLINE) {
                        container.engine.reconnectNow()
                        scope.launch { snackbar.showSnackbar("正在重新连接…") }
                    }
                })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索剪贴板内容", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                shape = RoundedCornerShape(12.dp),
            )
        }
        Text(
            "今天 · ${clips.size} 条 · 上限 500 条",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp),
        ) {
            items(filtered, key = { it.syncId }) { clip ->
                val actions = buildActions(
                    clip,
                    onCopy = { copyText(clip) },
                    onView = { viewer = clip },
                    onDownload = {
                        val ok = downloadClip(context, clip)
                        scope.launch {
                            snackbar.showSnackbar(if (ok) "已保存到 Download/ClipLiteSync/" else "下载失败或内容不可用")
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.engine.deleteLocal(clip.syncId)
                            openId = null
                            snackbar.showSnackbar("已删除")
                        }
                    },
                )
                SwipeActionCard(
                    actions = actions,
                    isOpen = openId == clip.syncId,
                    onOpenChange = { o -> openId = if (o) clip.syncId else null },
                ) {
                    ClipContent(
                        clip = clip,
                        onPin = { scope.launch { container.engine.togglePin(clip.syncId) } },
                        onMore = { more = clip },
                        onImageClick = { viewer = clip },
                        onTextClick = { textDetail = clip },
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (vm.query.isBlank()) "还没有内容\n在电脑上复制点什么试试" else "没有匹配的内容",
                        fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    // 更多操作弹层
    more?.let { clip ->
        ModalBottomSheet(onDismissRequest = { more = null }) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 26.dp)) {
                Text(
                    (clip.textContent ?: "图片内容").take(40),
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                )
                SheetRow(Icons.Filled.ContentCopy, "复制到剪贴板") {
                    more = null
                    if (clip.clipType == 3) viewer = clip else copyText(clip)
                }
                SheetRow(Icons.Filled.Visibility, "重新发送") {
                    more = null
                    scope.launch {
                        val ok = container.engine.reSend(clip.syncId)
                        snackbar.showSnackbar(if (ok) "已加入发送队列" else "该内容暂不支持重发")
                    }
                }
                SheetRow(Icons.Filled.PushPin, if (clip.pinned) "取消置顶" else "置顶") {
                    more = null
                    scope.launch { container.engine.togglePin(clip.syncId) }
                }
                SheetRow(Icons.Filled.Delete, "删除（仅本机）", danger = true) {
                    more = null
                    scope.launch {
                        container.engine.deleteLocal(clip.syncId)
                        snackbar.showSnackbar("已删除")
                    }
                }
            }
        }
    }

    // 文本详情弹层（点击文本条目查看全文，而非直接复制）
    textDetail?.let { clip ->
        ModalBottomSheet(onDismissRequest = { textDetail = null }) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 26.dp)) {
                Text(
                    "文本内容",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                Text(
                    clip.textContent ?: "",
                    fontSize = 14.5.sp, lineHeight = 23.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 360.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
                ) {
                    Text(clip.originDevice, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fmtTime(clip.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ButtonFull(label = "复制到剪贴板", onClick = {
                    textDetail = null
                    copyText(clip)
                })
            }
        }
    }

    // 图片全屏预览
    viewer?.let { clip ->
        val imgFile = clip.imageFile?.let(::File)
        when {
            imgFile == null || !imgFile.exists() -> viewer = null
            // 旧版本同步的是 Windows DIB 原始数据，Android 无法解码：提示重建而不是白屏
            sniffImageFormat(imgFile) == null -> {
                viewer = null
                scope.launch {
                    snackbar.showSnackbar("该图片来自旧版本同步，格式不兼容；请重新从电脑端同步（或清空历史）")
                }
            }
            else -> ImageViewerDialog(
                file = imgFile,
                meta = clip.textContent,
                onDownload = {
                    val ok = saveImageToGallery(context, imgFile)
                    scope.launch { snackbar.showSnackbar(if (ok) "已保存到 图库/ClipLiteSync" else "保存失败") }
                },
                onDismiss = { viewer = null },
            )
        }
    }
}

/** 侧滑操作按钮（按内容类型生成，对齐设计方案 v1） */
private fun buildActions(
    clip: ClipEntity,
    onCopy: () -> Unit,
    onView: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
): List<SwipeAction> = buildList {
    if ((clip.clipType == 1 || clip.clipType == 5) && !clip.localOnly)
        add(SwipeAction("复制", Icons.Filled.ContentCopy, Color(0xFF1A73E8), Color.White, onCopy))
    if (clip.clipType == 3 || clip.clipType == 4)
        add(SwipeAction("查看图片", Icons.Filled.Visibility, Color(0xFF23282F), Color.White, onView))
    if ((clip.clipType == 3 || clip.clipType == 4 || clip.clipType == 2) && !clip.localOnly)
        add(SwipeAction("下载", Icons.Filled.Download, Color(0xFF1E9E5A), Color.White, onDownload))
    add(SwipeAction("删除", Icons.Filled.Delete, Color(0xFFB91C1C), Color.White, onDelete))
}

data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val background: Color,
    val contentColor: Color,
    val action: () -> Unit = {},
)

/** 左滑呼出右侧操作按钮的卡片（对齐设计方案 v1 的交互） */
@Composable
fun SwipeActionCard(
    actions: List<SwipeAction>,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val actionWidth = 72.dp
    val totalPx = with(density) { (actionWidth * actions.size).toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isOpen, totalPx) {
        if (isOpen) offsetX.animateTo(-totalPx, tween(220))
        else offsetX.animateTo(0f, tween(220))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(intrinsicSize = androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        // 操作按钮层（右侧）
        Row(Modifier.matchParentSize()) {
            Spacer(Modifier.weight(1f))
            actions.forEach { a ->
                Column(
                    Modifier
                        .width(actionWidth)
                        .fillMaxSize()
                        .background(a.background)
                        .clickable {
                            a.action()
                            onOpenChange(false)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(a.icon, contentDescription = a.label, tint = a.contentColor, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.height(5.dp))
                    Text(a.label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = a.contentColor)
                }
            }
        }
        // 卡片本体
        Surface(
            onClick = { if (isOpen) onOpenChange(false) },
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(actions.size) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-totalPx, 0f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val open = offsetX.value < -totalPx / 2
                                if (open) offsetX.animateTo(-totalPx, tween(200))
                                else offsetX.animateTo(0f, tween(200))
                                onOpenChange(open)
                            }
                        },
                    )
                },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Box(Modifier.padding(start = 14.dp, top = 13.dp, end = 14.dp, bottom = 12.dp)) { content() }
        }
    }
}

/** 卡片内容（对齐设计方案 v1：文本两行截断 / 图片缩略图 / 文件清单+仅本机标记） */
@Composable
private fun ClipContent(
    clip: ClipEntity,
    onPin: () -> Unit,
    onMore: () -> Unit,
    onImageClick: () -> Unit,
    onTextClick: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                when (clip.clipType) {
                    3, 4 -> {
                        val thumb = clip.thumbFile?.let(::File) ?: clip.imageFile?.let(::File)
                        if (thumb != null && thumb.exists()) {
                            // 旧版本同步的是 Windows DIB 原始数据，Android 解码失败会显示成无声灰块；
                            // 按文件头嗅探，识别不出就给明确的占位提示
                            val decodable = remember(thumb.path, thumb.lastModified()) {
                                sniffImageFormat(thumb) != null
                            }
                            if (decodable) {
                                coil.compose.AsyncImage(
                                    model = thumb,
                                    contentDescription = "图片",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { onImageClick() },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { onImageClick() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "旧版本同步的图片 · 格式不兼容\n请在电脑端更新后重新同步",
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(clip.textContent ?: "图片",
                            fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    2 -> {
                        Icon(Icons.Filled.FilePresent, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(clip.textContent ?: "", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (clip.localOnly) {
                            Spacer(Modifier.height(6.dp))
                            Badge("仅本机 · 不可粘贴", Color(0xFFB45309), Color(0xFFFBF1E2))
                        }
                    }
                    else -> {
                        Text(
                            clip.textContent ?: "",
                            fontSize = 14.5.sp, lineHeight = 22.sp,
                            color = if (clip.localOnly) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = !clip.localOnly) { onTextClick() },
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.PushPin, contentDescription = if (clip.pinned) "取消置顶" else "置顶",
                tint = if (clip.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp).clickable { onPin() },
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(clip.originDevice, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtTime(clip.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clickable { onMore() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("更多", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("⌄", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 14.5.sp, fontWeight = if (danger) FontWeight.SemiBold else FontWeight.Medium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Badge(text: String, color: Color, bg: Color) {
    Text(
        text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun fmtTime(ts: Long): String {
    val cal = Calendar.getInstance()
    val now = Calendar.getInstance()
    cal.timeInMillis = ts
    return when {
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

/** 按文件头识别图片格式（PNG/JPEG），识别不出返回 null（如旧版同步的 DIB 坏数据） */
fun sniffImageFormat(file: File): Pair<String, String>? {
    // 注意：不能用 FileInputStream.readNBytes()——那是 Java 9+ 的 API，老 Android 上会
    // NoSuchMethodError 直接崩（历史列表一进就挂）。用 read(byte[]) 手工读，兼容到 Android 8。
    val head = try {
        val buf = ByteArray(8)
        var n = 0
        file.inputStream().use { ins ->
            while (n < 8) {
                val r = ins.read(buf, n, 8 - n)
                if (r < 0) break
                n += r
            }
        }
        if (n == 8) buf else buf.copyOf(n)
    } catch (_: Exception) { return null }
    return when {
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> "png" to "image/png"
        head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg" to "image/jpeg"
        else -> null
    }
}

/** 图片保存到系统相册（按真实格式定扩展名/MIME；坏数据直接失败并提示） */
fun saveImageToGallery(context: Context, file: File): Boolean {
    return try {
        val (ext, mime) = sniffImageFormat(file) ?: return false
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ClipLiteSync_${System.currentTimeMillis()}.$ext")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClipLiteSync")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(file.readBytes()) } ?: return false
            true
        } else {
            // Android 8/9：写公共图片目录（权限宽松期）
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES)
            val out = File(dir, "ClipLiteSync")
            out.mkdirs()
            File(out, "ClipLiteSync_${System.currentTimeMillis()}.$ext").writeBytes(file.readBytes())
            true
        }
    } catch (_: Exception) { false }
}

/** 下载（保存）条目内容到 Download/ClipLiteSync/，按类型分发 */
fun downloadClip(context: Context, clip: ClipEntity): Boolean {
    return try {
        val target = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "ClipLiteSync")
        target.mkdirs()
        when (clip.clipType) {
            // 文本：写 txt
            1, 5 -> {
                val name = "clip_${System.currentTimeMillis()}.txt"
                File(target, name).writeText(clip.textContent ?: "")
                true
            }
            // 图片：存相册
            3, 4 -> clip.imageFile?.let(::File)?.takeIf { it.exists() }?.let { saveImageToGallery(context, it) } ?: false
            // 文件：复制 bundle 解压目录内容
            2 -> {
                val src = File(context.filesDir, "clips/${clip.syncId}")
                if (!src.isDirectory) return false
                val outDir = File(target, clip.syncId.take(8))
                outDir.mkdirs()
                src.listFiles()?.forEach { f -> if (f.isFile) f.copyTo(File(outDir, f.name), overwrite = true) }
                true
            }
            else -> false
        }
    } catch (_: Exception) { false }
}
