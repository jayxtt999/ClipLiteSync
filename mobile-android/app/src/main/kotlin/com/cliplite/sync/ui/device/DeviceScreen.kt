package com.cliplite.sync.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import com.cliplite.sync.data.Prefs
import com.cliplite.sync.engine.RestApi
import com.cliplite.sync.ui.ButtonFull
import com.cliplite.sync.ui.ConfirmDialog
import com.cliplite.sync.ui.PairCodeSheet
import com.cliplite.sync.ui.wizard.Hint
import com.cliplite.sync.ui.wizard.SectionCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 设备页状态：同步组全体成员 + 待审批 + 邀请码 */
class DeviceViewModel(private val container: AppContainer) : ViewModel() {
    var members by mutableStateOf<List<RestApi.RoomItem>>(emptyList())
    var pending by mutableStateOf<List<RestApi.PendingItem>>(emptyList())
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var inviteSheet by mutableStateOf<RestApi.InviteResp?>(null)
        private set
    var selfName by mutableStateOf("")
        private set
    var selfId by mutableStateOf("")

    fun dismissInvite() { inviteSheet = null }

    fun refresh() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val token = container.prefs.currentToken() ?: return@launch
                val api = RestApi(container.prefs.currentAddr())
                members = api.room(token).room
                pending = api.pending(token).pending
                selfName = container.prefs.deviceName.first()
                selfId = container.prefs.currentDeviceId() ?: ""
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally { loading = false }
        }
    }

    fun generateInvite() {
        viewModelScope.launch {
            try {
                val token = container.prefs.currentToken() ?: return@launch
                val api = RestApi(container.prefs.currentAddr())
                inviteSheet = api.invite(token)
            } catch (e: Exception) {
                error = e.message ?: "生成配对码失败"
            }
        }
    }

    fun decide(requestId: String, approve: Boolean) {
        viewModelScope.launch {
            try {
                val token = container.prefs.currentToken() ?: return@launch
                val api = RestApi(container.prefs.currentAddr())
                if (api.decide(token, requestId, approve)) refresh()
            } catch (e: Exception) {
                error = e.message ?: "操作失败"
            }
        }
    }

    fun revoke(deviceId: String) {
        viewModelScope.launch {
            try {
                val token = container.prefs.currentToken() ?: return@launch
                val api = RestApi(container.prefs.currentAddr())
                if (api.revokePeer(token, deviceId)) refresh()
            } catch (e: Exception) {
                error = e.message ?: "移除失败"
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DeviceViewModel(container) as T
        }
    }
}

@Composable
fun DeviceScreen(container: AppContainer, snackbar: SnackbarHostState) {
    val vm: DeviceViewModel = viewModel(factory = DeviceViewModel.factory(container))
    val scope = rememberCoroutineScope()
    var revokeTarget by remember { mutableStateOf<RestApi.RoomItem?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    vm.inviteSheet?.let { inv ->
        PairCodeSheet(
            code = inv.code,
            expiresAt = inv.expiresAt,
            onCopy = {
                container.engine.bridge.writeText(inv.code)
                scope.launch { snackbar.showSnackbar("配对码已复制") }
            },
            onDismiss = { vm.dismissInvite() },
        )
    }

    revokeTarget?.let { member ->
        ConfirmDialog(
            title = "移除设备",
            body = "将把「${member.name}」从你的同步组移除，该设备需重新配对才能再同步。",
            confirmText = "移除",
            danger = true,
            onConfirm = {
                revokeTarget = null
                vm.revoke(member.deviceId)
                scope.launch { snackbar.showSnackbar("已移除") }
            },
            onDismiss = { revokeTarget = null },
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
            Text("设备", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "同步组内共 ${vm.members.size} 台设备",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 邀请设备（生成配对码）
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("邀请设备加入", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("生成长期配对码，对方填码即可加入",
                                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ButtonFull(label = "生成配对码", onClick = vm::generateInvite)
                }
            }

            // 待审批申请
            if (vm.pending.isNotEmpty()) {
                item {
                    Text("待确认的加入申请", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 6.dp))
                }
                items(vm.pending, key = { it.requestId }) { req ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Person, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("「${req.deviceName}」请求加入", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("通过后自动开始同步", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.Check, "同意",
                                tint = Color(0xFF1E9E5A),
                                modifier = Modifier.size(26.dp).clickable {
                                    vm.decide(req.requestId, true)
                                    scope.launch { snackbar.showSnackbar("已同意") }
                                })
                            Spacer(Modifier.size(18.dp))
                            Icon(Icons.Filled.Close, "拒绝",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(26.dp).clickable {
                                    vm.decide(req.requestId, false)
                                    scope.launch { snackbar.showSnackbar("已拒绝") }
                                })
                        }
                    }
                }
            }

            // 同步组全体设备（含本机）
            item {
                Text("同步组设备", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp))
            }
            items(vm.members, key = { it.deviceId }) { member ->
                val isSelf = member.self
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(member.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                                if (isSelf) {
                                    Spacer(Modifier.size(8.dp))
                                    Text("本机", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                            Text(
                                when {
                                    isSelf -> "本设备"
                                    member.mine -> "经由本机配对码加入"
                                    else -> "已配对"
                                },
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 仅"经由本机配对码加入"的设备可移除（服务端同权限校验）
                        if (member.mine && !isSelf) {
                            Text("移除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { revokeTarget = member })
                        }
                    }
                }
            }

            vm.error?.let {
                item {
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
            }

            if (vm.loading) {
                item {
                    Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
