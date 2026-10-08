package com.cliplite.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.cliplite.sync.ui.AppRoot
import com.cliplite.sync.ui.theme.ClipSyncTheme
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
        container.engine.onBackground()
    }
}
