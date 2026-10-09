package com.cliplite.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.cliplite.sync.ui.AppRoot
import com.cliplite.sync.ui.theme.ClipSyncTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as ClipSyncApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipSyncTheme {
                AppRoot(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { container.engine.onForeground() }
    }

    override fun onPause() {
        super.onPause()
        // 有后台前台服务（已配对）时，退到后台不停止引擎——否则前台服务的
        // 剪贴板轮询会因引擎 stop 而失效。仅当未配对（无 token）时才停引擎。
        // 引擎的连接状态由前台服务接管。
        lifecycleScope.launch {
            if (container.prefs.deviceToken.first() == null) {
                container.engine.onBackground()
            }
        }
    }
}
