package com.cliplite.sync.ui.wizard

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliplite.sync.AppContainer
import com.cliplite.sync.ClipSyncService
import com.cliplite.sync.engine.AppLog
import com.cliplite.sync.engine.RestApi
import com.cliplite.sync.ui.ButtonFull
import com.cliplite.sync.ui.PairCodeSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WizardViewModel(private val container: AppContainer) : ViewModel() {

    enum class Probe { IDLE, TESTING, OK, FAIL }

    var addr by mutableStateOf("ws://192.168.1.10:8765")
    var probe by mutableStateOf(Probe.IDLE)
    var code by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var inviteSheet by mutableStateOf<RestApi.InviteResp?>(null)
        private set
    var joining by mutableStateOf(false)
        private set
    private var pollJob: Job? = null

    fun dismissInvite() { inviteSheet = null }

    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun onAddrChange(v: String) { addr = v; probe = Probe.IDLE }

    fun test() {
        probe = Probe.TESTING
        viewModelScope.launch {
            probe = if (RestApi(addr).healthz()) Probe.OK else Probe.FAIL
        }
    }

    fun onCodeChange(v: String) {
        code = v.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(8)
    }

    /** 路径 B：创建同步组（开放注册）→ 立即生成配对码给电脑填 */
    fun createGroup() {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val api = RestApi(addr)
                val machineId = container.prefs.currentMachineId()
                AppLog.i("wizard", "createGroup addr=$addr machineId=$machineId")
                val reg = api.register(deviceName, machineId)
                AppLog.i("wizard", "register ok deviceId=${reg.deviceId}")
                container.prefs.savePairing(addr, reg.deviceId, reg.deviceToken, deviceName)
                val inv = api.invite(reg.deviceToken)
                inviteSheet = inv
                container.engine.start()
                ClipSyncService.start(container.appContext)
            } catch (e: Exception) {
                AppLog.e("wizard", "createGroup 失败: ${e.javaClass.simpleName}: ${e.message}")
                error = e.message ?: "注册失败，请检查服务器地址"
            } finally { busy = false }
        }
    }

    /** 路径 A：填码加入（创始码立即通过；设备码 pending 轮询） */
    fun join(onResult: (approved: Boolean, msg: String) -> Unit) {
        if (busy || code.length < 8) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val api = RestApi(addr)
                val machineId = container.prefs.currentMachineId()
                AppLog.i("wizard", "join addr=$addr code=$code machineId=$machineId")
                val resp = api.join(code, deviceName, machineId)
                AppLog.i("wizard", "join resp status=${resp.status} requestId=${resp.requestId}")
                when (resp.status) {
                    "approved" -> {
                        container.prefs.savePairing(
                            addr, resp.deviceId!!, resp.deviceToken!!, deviceName)
                        container.engine.start()
                        ClipSyncService.start(container.appContext)
                        onResult(true, "已加入同步组，开始同步")
                    }
                    "pending" -> {
                        joining = true
                        pollStatus(api, resp.requestId!!, onResult)
                    }
                    else -> error = "申请未通过：${resp.status}"
                }
            } catch (e: Exception) {
                AppLog.e("wizard", "join 失败: ${e.javaClass.simpleName}: ${e.message}")
                error = e.message ?: "加入失败"
            } finally { busy = false }
        }
    }

    private fun pollStatus(api: RestApi, requestId: String,
                           onResult: (Boolean, String) -> Unit) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                try {
                    val st = api.joinStatus(requestId)
                    when (st.status) {
                        "approved" -> {
                            AppLog.i("wizard", "join approved deviceId=${st.deviceId}")
                            container.prefs.savePairing(addr, st.deviceId!!, st.deviceToken!!, deviceName)
                            container.engine.start()
                            ClipSyncService.start(container.appContext)
                            joining = false
                            onResult(true, "对方已确认，开始同步")
                            return@launch
                        }
                        "rejected" -> { joining = false; error = "对方拒绝了本次申请"; return@launch }
                        "expired" -> { joining = false; error = "申请已过期，请重新填码"; return@launch }
                        "not_found" -> { joining = false; error = "申请不存在"; return@launch }
                    }
                } catch (_: Exception) { /* 网络抖动继续轮询 */ }
            }
        }
    }

    override fun onCleared() { pollJob?.cancel() }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WizardViewModel(container) as T
        }
    }
}

@Composable
fun WizardScreen(container: AppContainer, snackbarHostState: SnackbarHostState) {
    val vm: WizardViewModel = viewModel(factory = WizardViewModel.factory(container))
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    vm.inviteSheet?.let { inv ->
        PairCodeSheet(
            code = inv.code,
            expiresAt = inv.expiresAt,
            onCopy = {
                container.engine.bridge.writeText(inv.code)
                scope.launch { snackbarHostState.showSnackbar("配对码已复制") }
            },
            onDismiss = { vm.dismissInvite() },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoMark()
        Spacer(Modifier.height(14.dp))
        Text("欢迎使用 ClipLiteSync", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "与你的电脑共用同一个剪贴板\n复制内容在设备间自动同步",
            fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, lineHeight = 20.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(22.dp))

        // ① 服务器地址
        SectionCard {
            Text("① 服务器地址", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.addr,
                    onValueChange = vm::onAddrChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp),
                )
                OutlinedButton(
                    onClick = vm::test,
                    enabled = vm.probe != WizardViewModel.Probe.TESTING,
                ) {
                    Text(
                        when (vm.probe) {
                            WizardViewModel.Probe.IDLE -> "测试连接"
                            WizardViewModel.Probe.TESTING -> "测试中…"
                            WizardViewModel.Probe.OK -> "✓ 正常"
                            WizardViewModel.Probe.FAIL -> "✗ 重试"
                        },
                        fontSize = 13.sp,
                        color = when (vm.probe) {
                            WizardViewModel.Probe.OK -> Color(0xFF1E9E5A)
                            WizardViewModel.Probe.FAIL -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Hint("局域网可用 ws://，公网建议 wss://（TLS）")
        }
        Spacer(Modifier.height(14.dp))

        // ② 创建同步组（推荐）
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("② 创建同步组", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Tag("推荐 · 无需电脑操作")
            }
            Spacer(Modifier.height(6.dp))
            Hint("在本机新建一个同步组，自动生成配对码，在电脑上填码即可加入。")
            Spacer(Modifier.height(12.dp))
            ButtonFull(label = if (vm.busy) "创建中…" else "创建同步组并生成配对码",
                enabled = !vm.busy, onClick = vm::createGroup)
        }
        Spacer(Modifier.height(14.dp))

        // ② 加入同步组
        SectionCard {
            Text("② 加入同步组", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Hint("在对方设备的「邀请设备加入」里点「生成配对码」，把 8 位码填到这里。")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = vm.code,
                onValueChange = vm::onCodeChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !vm.joining,
                placeholder = { Text("８位配对码", textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    letterSpacing = 6.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.joining) "等待对方确认…" else "${vm.code.length}/8",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Button(
                    onClick = {
                        vm.join { ok, msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    },
                    enabled = vm.code.length == 8 && !vm.busy && !vm.joining,
                    shape = RoundedCornerShape(10.dp),
                ) { Text(if (vm.joining) "等待确认" else "申请加入") }
            }
            Spacer(Modifier.height(8.dp))
            Hint("提交后需对方在设备上确认，通过后自动开始同步。")
        }

        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun LogoMark(size: Int = 62) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(size.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xFF2F7BEF), Color(0xFF1557B0))),
                RoundedCornerShape((size * 0.28).dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.ContentPaste, contentDescription = null,
            tint = Color.White, modifier = Modifier.size((size * 0.5).dp))
    }
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
}

@Composable
fun Tag(text: String) {
    Text(
        text,
        fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

