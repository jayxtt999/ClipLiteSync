package com.cliplite.sync.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "clipsync_prefs")

/** 配置与配对凭据（token 明文存 DataStore，M4 再升级加密存储） */
class Prefs(private val context: Context) {

    private object K {
        val serverAddr = stringPreferencesKey("server_addr")
        val deviceToken = stringPreferencesKey("device_token")
        val deviceId = stringPreferencesKey("device_id")
        val deviceName = stringPreferencesKey("device_name")
        val machineId = stringPreferencesKey("machine_id")
        val lastSeq = longPreferencesKey("last_seq")
        val upSync = booleanPreferencesKey("up_sync")
        val downSync = booleanPreferencesKey("down_sync")
        val autoCopy = booleanPreferencesKey("auto_copy")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val sizeLimitMb = intPreferencesKey("size_limit_mb")
        val historyCap = intPreferencesKey("history_cap")
    }

    // ---------- Flow ----------
    val serverAddr: Flow<String> = context.dataStore.data.map { it[K.serverAddr] ?: "" }
    val deviceToken: Flow<String?> = context.dataStore.data.map { it[K.deviceToken] }
    val deviceId: Flow<String?> = context.dataStore.data.map { it[K.deviceId] }
    val deviceName: Flow<String> = context.dataStore.data.map { it[K.deviceName] ?: "" }
    val lastSeq: Flow<Long> = context.dataStore.data.map { it[K.lastSeq] ?: 0L }

    data class Settings(
        val upSync: Boolean = true,
        val downSync: Boolean = true,
        val autoCopy: Boolean = true,
        val wifiOnly: Boolean = true,
        val sizeLimitMb: Int = 8,
        val historyCap: Int = 500,
    )

    val settings: Flow<Settings> = context.dataStore.data.map {
        Settings(
            upSync = it[K.upSync] ?: true,
            downSync = it[K.downSync] ?: true,
            autoCopy = it[K.autoCopy] ?: true,
            wifiOnly = it[K.wifiOnly] ?: true,
            sizeLimitMb = it[K.sizeLimitMb] ?: 8,
            historyCap = it[K.historyCap] ?: 500,
        )
    }

    // ---------- 挂起写 ----------
    suspend fun setServerAddr(v: String) = context.dataStore.edit { it[K.serverAddr] = v }
    suspend fun setLastSeq(v: Long) = context.dataStore.edit { it[K.lastSeq] = v }
    suspend fun setSetting(upSync: Boolean? = null, downSync: Boolean? = null, autoCopy: Boolean? = null,
                           wifiOnly: Boolean? = null, sizeLimitMb: Int? = null, historyCap: Int? = null) =
        context.dataStore.edit {
        upSync?.let { v -> it[K.upSync] = v }
        downSync?.let { v -> it[K.downSync] = v }
        autoCopy?.let { v -> it[K.autoCopy] = v }
        wifiOnly?.let { v -> it[K.wifiOnly] = v }
        sizeLimitMb?.let { v -> it[K.sizeLimitMb] = v }
        historyCap?.let { v -> it[K.historyCap] = v }
    }

    suspend fun savePairing(serverAddr: String, deviceId: String, deviceToken: String, deviceName: String) {
        context.dataStore.edit {
            it[K.serverAddr] = serverAddr
            it[K.deviceId] = deviceId
            it[K.deviceToken] = deviceToken
            it[K.deviceName] = deviceName
            it[K.lastSeq] = 0L
        }
    }

    /** 退出同步组：清 token（保留服务器地址方便下次） */
    suspend fun clearPairing() {
        context.dataStore.edit {
            it.remove(K.deviceToken)
            it.remove(K.deviceId)
            it[K.lastSeq] = 0L
        }
    }

    suspend fun currentToken(): String? = deviceToken.first()
    suspend fun currentAddr(): String = serverAddr.first()
    suspend fun currentDeviceId(): String? = deviceId.first()

    /**
     * 硬件机器码：首次生成随机 UUID 持久化，此后永久复用（卸载重装前不变）。
     * 注册/加入时上报服务端用于设备去重——同机重新配对复用原设备身份，
     * 避免设备列表出现重复终端（与 PC 端 MachineGuid 语义对齐）。
     */
    suspend fun currentMachineId(): String {
        var v: String? = null
        context.dataStore.edit { d ->
            val existing = d[K.machineId]
            if (existing.isNullOrBlank()) {
                val fresh = java.util.UUID.randomUUID().toString()
                d[K.machineId] = fresh
                v = fresh
            } else {
                v = existing
            }
        }
        return v!!
    }
}
