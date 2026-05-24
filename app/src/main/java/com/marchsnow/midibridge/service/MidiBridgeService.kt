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

    lateinit var midiReader: MidiReader
        private set

    lateinit var wsServer: WsServer
        private set

    lateinit var configManager: ConfigManager
        private set

    lateinit var config: AppConfig
        private set

    private lateinit var auth: Auth

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
            ACTION_STOP  -> stopSelf()
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
        if (isRunning) {
            Logger.w(TAG, "Server already running")
            return
        }

        configManager = ConfigManager(applicationContext)
        config        = configManager.load()
        auth          = Auth(config, configManager)
        midiReader    = MidiReader(applicationContext)
        wsServer      = WsServer(config, auth, midiReader.midiFlow)

        wsServer.start()

        // Event bus: device connect → log + notification
        scope.launch {
            midiReader.connectFlow.collect { info ->
                val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown"
                Logger.i(TAG, "MIDI device connected: $name")
                updateNotification("MIDI: $name | Port ${config.ws.port}")
            }
        }

        // Event bus: device disconnect → log + notification
        scope.launch {
            midiReader.disconnectFlow.collect {
                Logger.w(TAG, "MIDI device disconnected")
                updateNotification("MIDI disconnected | Port ${config.ws.port}")
            }
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("Running on port ${config.ws.port}"))
        Logger.i(TAG, "MIDIBridge started (WS port=${config.ws.port})")
    }

    /** Stop all services. Order: MidiReader → WsServer. */
    fun stopBridge() {
        if (!isRunning) return
        midiReader.release()
        wsServer.stop()
        isRunning = false
        Logger.i(TAG, "MIDIBridge stopped")
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
