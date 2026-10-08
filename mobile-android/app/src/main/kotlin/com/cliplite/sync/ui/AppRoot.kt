package com.cliplite.sync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cliplite.sync.AppContainer
import com.cliplite.sync.engine.SyncEngine
import com.cliplite.sync.ui.device.DeviceScreen
import com.cliplite.sync.ui.history.HistoryScreen
import com.cliplite.sync.ui.settings.SettingsScreen
import com.cliplite.sync.ui.wizard.WizardScreen
import kotlinx.coroutines.launch

/** 根：未配对 → 向导；已配对 → 三页签主界面 */
@Composable
fun AppRoot(container: AppContainer) {
    val token by container.prefs.deviceToken.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }

    // 引擎事件 → snackbar；吊销/失效 → 清 token 回向导
    LaunchedEffect(Unit) {
        container.engine.events.collect { e ->
            when (e) {
                is SyncEngine.EngineEvent.Toast -> snackbarHostState.showSnackbar(e.msg)
                is SyncEngine.EngineEvent.Unpaired -> {
                    container.engine.stop()
                    container.prefs.clearPairing()
                    snackbarHostState.showSnackbar(e.reason)
                }
            }
        }
    }

    if (token == null) {
        WizardScreen(container, snackbarHostState)
    } else {
        MainScaffold(container, snackbarHostState)
    }
}

private enum class Tab(val label: String) {
    HISTORY("历史"), DEVICE("设备"), SETTINGS("设置"),
}

@Composable
private fun MainScaffold(container: AppContainer, snackbarHostState: SnackbarHostState) {
    val tabs = remember {
        listOf(
            Tab.HISTORY to Icons.Outlined.History,
            Tab.DEVICE to Icons.Outlined.Devices,
            Tab.SETTINGS to Icons.Outlined.Settings,
        )
    }
    val conn by container.engine.connState.collectAsState()

    var current by remember { mutableStateOf(Tab.HISTORY) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { (tab, icon) ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = { Icon(icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (current) {
                Tab.HISTORY -> HistoryScreen(container, conn, snackbarHostState)
                Tab.DEVICE -> DeviceScreen(container, snackbarHostState)
                Tab.SETTINGS -> SettingsScreen(container, snackbarHostState)
            }
        }
    }
}
