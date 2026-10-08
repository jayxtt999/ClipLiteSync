package com.cliplite.sync.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cliplite.sync.AppContainer
import com.cliplite.sync.engine.AppLog
import kotlinx.coroutines.delay

/** 诊断日志页（临时排查入口）：实时滚动查看内存日志，支持复制/清空/立即重连 */
@Composable
fun DiagLogScreen(container: AppContainer, onClose: () -> Unit) {
    var version by mutableIntStateOf(0)
    LaunchedEffect(Unit) {
        while (true) { delay(1000); version++ } // 每秒刷新
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("诊断日志", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "复现问题后点「复制全部」，把剪贴板内容发给我即可定位。日志同时输出到 Logcat（tag=ClipSync）。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                val lines = remember(version) { AppLog.snapshot() }
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                ) {
                    if (lines.isEmpty()) {
                        item {
                            Text("(暂无日志)",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp))
                        }
                    }
                    items(lines.size) { i ->
                        Text(
                            lines[i],
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                            color = when {
                                lines[i].contains(" E/") -> MaterialTheme.colorScheme.error
                                lines[i].contains(" W/") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val text = AppLog.snapshot().joinToString("\n")
                        container.engine.bridge.writeText(text)
                    }, modifier = Modifier.weight(1f)) { Text("复制全部", fontSize = 13.sp) }
                    OutlinedButton(onClick = { AppLog.clear(); version++ },
                        modifier = Modifier.weight(1f)) { Text("清空", fontSize = 13.sp) }
                    OutlinedButton(onClick = {
                        container.engine.reconnectNow()
                    }, modifier = Modifier.weight(1f)) { Text("立即重连", fontSize = 13.sp) }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("返回", fontSize = 14.sp) }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
