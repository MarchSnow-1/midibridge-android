package com.marchsnow.midibridge.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.midi.MidiDeviceInfo
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import at.favre.lib.crypto.bcrypt.BCrypt
import com.marchsnow.midibridge.data.AppConfig
import com.marchsnow.midibridge.data.AuthConfig
import com.marchsnow.midibridge.data.ClientInfo
import com.marchsnow.midibridge.data.ConfigManager
import com.marchsnow.midibridge.data.KickReason
import com.marchsnow.midibridge.data.LoggingConfig
import com.marchsnow.midibridge.data.WsConfig
import com.marchsnow.midibridge.server.Auth
import com.marchsnow.midibridge.service.MidiBridgeService
import com.marchsnow.midibridge.service.VideoKeepAlive
import com.marchsnow.midibridge.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

// ─── UI state data classes ───

/** Runtime state, polled every 1.5s. */
data class UiState(
    val isRunning:        Boolean              = false,
    val wsPort:           Int                  = 9001,
    val clientCount:      Int                  = 0,
    val midiConnected:    Boolean              = false,
    val clients:          List<ClientInfo>     = emptyList(),
    val localIPs:         List<String>         = emptyList(),
    val logs:             String               = "",
    val availableDevices: List<MidiDeviceInfo> = emptyList(),
    val selectedDevice:   MidiDeviceInfo?      = null,
    val keepAliveOn:       Boolean              = false
)

/** Configuration edit state (user-driven, not yet saved). */
data class ConfigEditState(
    val wsPort:            String  = "9001",
    val allowedIPs:        String  = "",
    val password:          String  = "",
    val midiVerbose:       Boolean = true,
    val hasUnsavedChanges: Boolean = false
)

/** One-shot UI events (Toasts, validation errors). */
sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    data class ValidationError(val field: String, val message: String) : UiEvent()
}

// ─── ViewModel ───

class MidiBridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState    = MutableLiveData(UiState())
    val uiState: LiveData<UiState> = _uiState

    private val _configEdit = MutableLiveData(ConfigEditState())
    val configEdit: LiveData<ConfigEditState> = _configEdit

    private val _uiEvent    = MutableLiveData<UiEvent>()
    val uiEvent: LiveData<UiEvent> = _uiEvent

    private var service: MidiBridgeService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as MidiBridgeService.LocalBinder).getService()
            service = svc
            initEditStateFromService(svc)
            startPolling()
            refreshDeviceList()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    init {
        val intent = Intent(getApplication(), MidiBridgeService::class.java)
        getApplication<Application>().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unbindService(connection)
    }

    // ─── Config edit callbacks (called by Activity TextWatchers / listeners) ───

    fun onPortChanged(value: String) {
        val cur = _configEdit.value ?: return
        _configEdit.value = cur.copy(wsPort = value, hasUnsavedChanges = true)
    }

    fun onAllowedIPsChanged(value: String) {
        val cur = _configEdit.value ?: return
        _configEdit.value = cur.copy(allowedIPs = value, hasUnsavedChanges = true)
    }

    fun onPasswordChanged(value: String) {
        val cur = _configEdit.value ?: return
        _configEdit.value = cur.copy(password = value, hasUnsavedChanges = true)
    }

    fun onMidiVerboseChanged(enabled: Boolean) {
        val cur = _configEdit.value ?: return
        _configEdit.value = cur.copy(midiVerbose = enabled, hasUnsavedChanges = true)
    }

    // ─── Save & restart ───

    /**
     * Called when user clicks Save.
     *
     * Full flow:
     *   1. Validate port range and password length
     *   2. If password actually changed → kickAllClients(PASSWORD_CHANGED)
     *   3. Generate new bcrypt hash and persist config
     *   4. Restart the service stack
     *   5. Reset hasUnsavedChanges → Save button disabled
     */
    fun saveAndRestart() {
        val edit = _configEdit.value ?: return
        val svc  = service ?: return

        // 1. Validate port
        val port = edit.wsPort.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            _uiEvent.value = UiEvent.ValidationError("port", "Port must be between 1024 and 65535")
            return
        }

        // 2. Validate password length（仅在用户填写了新密码时才校验/修改）
        val newPassword = edit.password
        if (newPassword.isNotEmpty() && newPassword.length < Auth.MIN_PASSWORD_LEN) {
            _uiEvent.value = UiEvent.ValidationError(
                "password", "Password must be at least ${Auth.MIN_PASSWORD_LEN} characters"
            )
            return
        }

        viewModelScope.launch {
            val configManager = ConfigManager(getApplication())
            val currentConfig = configManager.load()

            // 3. 密码语义：留空 = 不修改密码；非空且与存储哈希不同 = 修改
            val storedHash = currentConfig.auth.passwordHash
            val passwordChanged = newPassword.isNotEmpty() && storedHash.isNotEmpty() &&
                    !runCatching {
                        BCrypt.verifyer()
                            .verify(newPassword.toCharArray(), storedHash)
                            .verified
                    }.getOrDefault(false)

            // 首次设置密码（尚无存储哈希）也视为修改
            val firstTimeSet = newPassword.isNotEmpty() && storedHash.isEmpty()

            if ((passwordChanged || firstTimeSet) && svc.isRunning) {
                svc.wsServer?.kickAllClients(KickReason.PASSWORD_CHANGED)
                Logger.i("ViewModel", "Password changed, all clients kicked")
            }

            // 4. Build new config and persist
            val newConfig = currentConfig.copy(
                ws = WsConfig(port = port, allowedIPs = edit.allowedIPs.trim()),
                auth = currentConfig.auth,
                logging = LoggingConfig(midiVerbose = edit.midiVerbose)
            )

            // 仅在用户填写了密码时才重新哈希并持久化；
            // 留空则保留存储的哈希——密码绝不会被静默重置
            if (newPassword.isNotEmpty()) {
                val tempAuth = Auth(newConfig, configManager)
                tempAuth.setNewPassword(newPassword)
            }

            // 5. Restart service (re-loads config from SharedPreferences)
            svc.restartBridge()

            // 6. Reset edit state（保存后清空密码框，防止下次保存意外复用）
            _configEdit.postValue(edit.copy(password = "", hasUnsavedChanges = false))
            _uiEvent.postValue(UiEvent.ShowToast("Settings saved, server restarted"))
            Logger.i("ViewModel", "Config saved and server restarted (port=$port)")
        }
    }

    // ─── Other UI actions ───

    fun startServer() {
        service?.startBridge()
    }

    fun stopServer() {
        service?.stopBridge()
    }

    fun selectDevice(info: MidiDeviceInfo) {
        service?.midiReader?.openDevice(info)
        _uiState.value = _uiState.value?.copy(selectedDevice = info)
    }

    fun refreshDeviceList() {
        val devices = service?.midiReader?.getAvailableDevices() ?: emptyList()
        _uiState.value = _uiState.value?.copy(availableDevices = devices)
    }

    // ─── Keep-alive ───

    fun toggleVideoKeepAlive(enabled: Boolean) {
        if (enabled) {
            VideoKeepAlive.start(getApplication())
        } else {
            VideoKeepAlive.stop()
        }
        _uiState.value = _uiState.value?.copy(keepAliveOn = enabled)
    }

    // ─── Init & polling ───

    /** Initialize edit form from stored config after service binds. */
    private fun initEditStateFromService(svc: MidiBridgeService) {
        val cfg = ConfigManager(getApplication()).load()
        _configEdit.value = ConfigEditState(
            wsPort            = cfg.ws.port.toString(),
            allowedIPs        = cfg.ws.allowedIPs,
            // 密码框留空：填入值才视为"修改密码"。旧实现预填默认密码，
            // 导致用户只改端口保存时把密码静默重置回默认值并踢光客户端
            password          = "",
            midiVerbose       = cfg.logging.midiVerbose,
            hasUnsavedChanges = false
        )
    }

    /** Poll runtime status every 1.5s (matches old Java Timer logic). */
    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                poll()
                delay(1500)
            }
        }
    }

    private fun poll() {
        val svc = service ?: return
        refreshDeviceList()
        _uiState.postValue(
            UiState(
                isRunning        = svc.isRunning,
                wsPort           = if (svc.isRunning) svc.config?.ws?.port ?: 9001 else 9001,
                clientCount      = if (svc.isRunning) svc.wsServer?.clientCount() ?: 0 else 0,
                midiConnected    = svc.midiReader?.isConnected ?: false,
                clients          = if (svc.isRunning) svc.wsServer?.getClients() ?: emptyList() else emptyList(),
                localIPs         = getLocalIPs(),
                logs             = Logger.getLogs(),
                availableDevices = _uiState.value?.availableDevices ?: emptyList(),
                selectedDevice   = _uiState.value?.selectedDevice,
                keepAliveOn      = VideoKeepAlive.enabled
            )
        )
    }

    /** Enumerate all non-loopback IPv4 / IPv6 addresses. */
    private fun getLocalIPs(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .toList()
        }.getOrDefault(emptyList())
    }
}
