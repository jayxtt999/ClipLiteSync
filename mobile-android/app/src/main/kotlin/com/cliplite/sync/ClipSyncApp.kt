package com.cliplite.sync

import android.app.Application
import com.cliplite.sync.engine.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ClipSyncApp : Application() {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 已配对（有 token）则拉起后台前台服务：进程重建（系统杀后重启）时也能恢复后台同步
        scope.launch {
            if (container.prefs.deviceToken.first() != null) {
                try {
                    ClipSyncService.start(this@ClipSyncApp)
                    AppLog.i("app", "已配对，启动后台同步服务")
                } catch (e: Exception) {
                    // 后台启动前台服务受限（如系统限制）时静默失败，不 crash
                    AppLog.w("app", "启动后台服务失败: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }
}
