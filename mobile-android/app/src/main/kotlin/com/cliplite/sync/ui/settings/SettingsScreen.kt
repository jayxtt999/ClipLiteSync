package com.cliplite.sync.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliplite.sync.AppContainer
import com.cliplite.sync.ClipSyncService
import com.cliplite.sync.data.Prefs
import com.cliplite.sync.ui.ConfirmDialog
import com.cliplite.sync.ui.wizard.Hint
import com.cliplite.sync.ui.wizard.SectionCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 设置页：同步开关 / 大小上限 / 历史上限 / 设备名 / 服务器地址 / 退出同步组 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    var settings by mutableStateOf(Prefs.Settings())
        private set
    var deviceName by mutableStateOf("")
        private set
    var serverAddr by mutableStateOf("")
        private set
    var confirmExit by mutableStateOf(false)
    var confirmClear by mutableStateOf(false)
    var showDiagLog by mutableStateOf(false)

    init {
        viewModelScope.launch {
            settings = container.prefs.settings.first()
            deviceName = container.prefs.deviceName.first()
            serverAddr = container.prefs.serverAddr.first()
        }
    }

    fun toggleUp(v: Boolean) {
        settings = settings.copy(upSync = v)
        viewModelScope.launch { container.prefs.setSetting(upSync = v) }
    }

    fun toggleDown(v: Boolean) {
        settings = settings.copy(downSync = v)
        viewModelScope.launch { container.prefs.setSetting(downSync = v) }
    }

    fun toggleAutoCopy(v: Boolean) {
        settings = settings.copy(autoCopy = v)
        viewModelScope.launch { container.prefs.setSetting(autoCopy = v) }
    }

    fun toggleWifiOnly(v: Boolean) {
        settings = settings.copy(wifiOnly = v)
        viewModelScope.launch { container.prefs.setSetting(wifiOnly = v) }
    }

    fun setSizeLimit(v: Int) {
        val c = v.coerceIn(1, 64)
        settings = settings.copy(sizeLimitMb = c)
        viewModelScope.launch { container.prefs.setSetting(sizeLimitMb = c) }
    }

    fun setHistoryCap(v: Int) {
        val c = v.coerceIn(50, 5000)
        settings = settings.copy(historyCap = c)
        viewModelScope.launch { container.prefs.setSetting(historyCap = c) }
    }

    fun exitGroup() {
        viewModelScope.launch {
            ClipSyncService.stop(container.appContext)
            container.engine.unpair()
        }
    }

    fun clearHistory() {
        viewModelScope.launch { container.engine.clearHistory() }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
        }
    }
}

@Composable
fun SettingsScreen(container: AppContainer, snackbar: SnackbarHostState) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val scope = rememberCoroutineScope()

    if (vm.confirmExit) {
        ConfirmDialog(
            title = "退出同步组",
            body = "清除本机 token 并断开同步。服务器端的设备记录将由创建者移除。",
            confirmText = "退出同步组",
            danger = true,
            onConfirm = {
                vm.confirmExit = false
                vm.exitGroup()
            },
            onDismiss = { vm.confirmExit = false },
        )
    }

    if (vm.confirmClear) {
        ConfirmDialog(
            title = "清除本地历史",
            body = "将删除本机全部剪贴板历史（含置顶），同步组与其他设备不受影响。",
            confirmText = "清除",
            danger = true,
            onConfirm = {
                vm.confirmClear = false
                vm.clearHistory()
                scope.launch { snackbar.showSnackbar("已清除本地历史") }
            },
            onDismiss = { vm.confirmClear = false },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        SectionCard {
            SettingRow("发送本机剪贴板", "复制的内容自动同步到组内其他设备",
                vm.settings.upSync, vm::toggleUp)
            Divider()
            SettingRow("接收其他设备", "其他设备复制的内容同步到本机",
                vm.settings.downSync, vm::toggleDown)
            Divider()
            SettingRow("自动写入剪贴板", "收到新内容自动写入本机剪贴板",
                vm.settings.autoCopy, vm::toggleAutoCopy)
            Divider()
            SettingRow("仅 Wi-Fi 同步", "移动数据下暂停同步以省流量",
                vm.settings.wifiOnly, vm::toggleWifiOnly)
        }
        Spacer(Modifier.height(14.dp))

        SectionCard {
            StepperRow("图片大小上限", "${vm.settings.sizeLimitMb} MB",
                onMinus = { vm.setSizeLimit(vm.settings.sizeLimitMb - 1) },
                onPlus = { vm.setSizeLimit(vm.settings.sizeLimitMb + 1) })
            Divider()
            StepperRow("历史记录上限", "${vm.settings.historyCap} 条",
                onMinus = { vm.setHistoryCap(vm.settings.historyCap - 100) },
                onPlus = { vm.setHistoryCap(vm.settings.historyCap + 100) })
        }
        Spacer(Modifier.height(14.dp))

        SectionCard {
            InfoRow("设备名称", vm.deviceName)
            Divider()
            InfoRow("服务器地址", vm.serverAddr)
        }
        Spacer(Modifier.height(14.dp))

        SectionCard {
            ActionRow("清除本地历史", danger = false) {
                vm.confirmClear = true
            }
            Divider()
            ActionRow("诊断日志", danger = false) {
                vm.showDiagLog = true
            }
            Divider()
            ActionRow("退出同步组", danger = true) {
                vm.confirmExit = true
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    if (vm.showDiagLog) {
        DiagLogScreen(container = container, onClose = { vm.showDiagLog = false })
    }
}

@Composable
private fun SettingRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun StepperRow(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        StepperButton("−", onMinus)
        Spacer(Modifier.width(10.dp))
        StepperButton("＋", onPlus)
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value.ifBlank { "未设置" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionRow(label: String, danger: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, fontSize = 14.5.sp,
            fontWeight = if (danger) FontWeight.SemiBold else FontWeight.Medium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}
