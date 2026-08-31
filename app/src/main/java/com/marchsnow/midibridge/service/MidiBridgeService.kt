package com.marchsnow.midibridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.midi.MidiDeviceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.marchsnow.midibridge.R
import com.marchsnow.midibridge.data.AppConfig
import com.marchsnow.midibridge.data.ConfigManager
import com.marchsnow.midibridge.server.Auth
import com.marchsnow.midibridge.server.MidiReader
import com.marchsnow.midibridge.server.WsServer
import com.marchsnow.midibridge.ui.MainActivity
import com.marchsnow.midibridge.util.Logger
import kotlinx.coroutines.*

/**
 * Foreground service that owns the lifecycle of all server modules.
 *
 * Startup order: Config → Auth → MidiReader → WsServer
 * Shutdown order: MidiReader → WsServer  (matches Go version)
 *
 * WsServer is exposed publicly so ViewModel can call kickAllClients()
 * when the password changes via GUI Save.
 *
 * 生命周期契约（Android 12+ 前台服务规则）：
 *  - onStartCommand 的每条路径（含 null intent 的 START_STICKY 重建、
 *    ACTION_START 早退分支）都必须及时调用 startForeground，
 *    否则系统将抛出 ForegroundServiceDidNotStartInTimeException。
 *  - 模块在 onCreate 中初始化为可空引用，startBridge 中按需创建，
 *    对外提供 null-safe 访问器——避免 lateinit 在进程重建早期的
 *    UninitializedPropertyAccessException 崩溃。
 */
class MidiBridgeService : Service() {

    companion object {
        const val CHANNEL_ID      = "midibridge_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START    = "com.marchsnow.midibridge.START"
        const val ACTION_STOP     = "com.marchsnow.midibridge.STOP"
        private const val TAG     = "MidiBridgeService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MidiBridgeService = this@MidiBridgeService
    }

    private val binder = LocalBinder()
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 模块引用：onCreate 置 null，startBridge 创建，stopBridge 清理。
    // 对外一律通过 null-safe 访问器，杜绝 lateinit 崩溃（AND-V2/S4）
    var midiReader: MidiReader? = null
        private set

    var wsServer: WsServer? = null
        private set

    var configManager: ConfigManager? = null
        private set

    var config: AppConfig? = null
        private set

    private var auth: Auth? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    // ─── Service lifecycle ───

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBridge()
            ACTION_STOP  -> { stopBridge(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            // START_STICKY 重建（进程被杀后系统重启）携带 null intent：
            // 按恢复处理——重新启动桥接业务，绝不能让服务空转
            null         -> {
                Logger.i(TAG, "Service restarted by system (sticky) — resuming bridge")
                startBridge()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopBridge()
        VideoKeepAlive.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ─── Bridge start / stop / restart ───

    /** Initialize all modules and start the WebSocket server. */
    fun startBridge() {
        // 前台化前置：必须在任何可能耗时/抛异常的初始化之前完成，
        // 保证 startForegroundService() 的 5 秒契约在所有路径上都被满足
        //（含 ACTION_START 的"已在运行"早退分支——旋转屏幕会重复触发）
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        if (isRunning) {
            Logger.w(TAG, "Server already running")
            updateNotification("Running on port ${config?.ws?.port ?: 9001}")
            return
        }

        val cm = ConfigManager(applicationContext)
        val cfg = cm.load()
        val a  = Auth(cfg, cm)
        val mr = MidiReader(applicationContext)
        val ws = WsServer(cfg, a, mr.midiFlow)

        configManager = cm
        config        = cfg
        auth          = a
        midiReader    = mr
        wsServer      = ws

        ws.start()

        // Event bus: device connect → log + notification
        scope.launch {
            mr.connectFlow.collect { info ->
                val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown"
                Logger.i(TAG, "MIDI device connected: $name")
                updateNotification("MIDI: $name | Port ${cfg.ws.port}")
            }
        }

        // Event bus: device disconnect → log + notification
        scope.launch {
            mr.disconnectFlow.collect {
                Logger.w(TAG, "MIDI device disconnected")
                updateNotification("MIDI disconnected | Port ${cfg.ws.port}")
            }
        }

        isRunning = true
        updateNotification("Running on port ${cfg.ws.port}")
        Logger.i(TAG, "MIDIBridge started (WS port=${cfg.ws.port})")
    }

    /** Stop all services. Order: MidiReader → WsServer. */
    fun stopBridge() {
        if (!isRunning) return
        midiReader?.release()
        wsServer?.stop()
        midiReader = null
        wsServer   = null
        isRunning  = false
        Logger.i(TAG, "MIDIBridge stopped")
        // 服务保持前台但通知更新为已停止状态（不再显示"Running"误导用户）
        updateNotification("Stopped")
    }

    /** Restart the full stack — called by ViewModel after config save. */
    fun restartBridge() {
        stopBridge()
        startBridge()
    }

    // ─── Notification ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MIDIBridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "MIDI WebSocket server running in background" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MIDIBridge")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_midi_note)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
