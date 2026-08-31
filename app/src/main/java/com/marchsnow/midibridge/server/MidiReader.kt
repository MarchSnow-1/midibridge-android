package com.marchsnow.midibridge.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import com.marchsnow.midibridge.data.MidiEvent
import com.marchsnow.midibridge.util.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * USB MIDI reader using Android's MidiManager directly.
 *
 * IMPORTANT — Android MIDI naming trap:
 *   MidiOutputPort = device → app  (use this to READ from a USB keyboard!)
 *   MidiInputPort  = app → device  (sending TO the device)
 *   getOutputPortCount() > 0       → this device has data we can read
 *
 * Corresponds to the combined Go midireader.go + Java MidiReceiver from the old version.
 *
 * MIDI events are published via SharedFlow. WsServer collects from midiFlow
 * and broadcasts to authenticated WebSocket clients.
 *
 * 设备生命周期（AND-M4/S5/M5）：
 *  - 注册 ACTION_USB_DEVICE_DETACHED 广播（minSdk 23 兼容方案，无需 API 33
 *    的 registerDeviceCallback）：当前打开的 USB MIDI 设备被拔出时立即
 *    closeCurrentDevice —— 释放失效句柄、发出 disconnectFlow、让 Service
 *    更新通知。否则端口残留到下一次用户手动选择设备为止。
 *  - openDevice 代际守卫：每次请求递增序号；异步回调到达时若已被更新的
 *    open/close 请求取代，直接关闭迟到设备，防止旧回调覆盖新状态
 *    （泄漏的 device 句柄 / 幽灵 isConnected）。
 */
class MidiReader(private val context: Context) {

    private val midiManager = context.getSystemService(MidiManager::class.java)

    /**
     * Standard MIDI byte-stream state machine (message splitting, Running
     * Status, realtime extraction, SysEx reassembly). See [MidiParser].
     */
    private val parser = MidiParser { warning -> Logger.w("MidiReader", warning) }

    // Buffer 256, DROP_LATEST = non-blocking send. Matches the Go
    // select-default pattern: when the buffer is full the NEW event is
    // dropped (send falls through to default) while already-queued events
    // keep their order — 丢新不丢旧 (AND-新N8).
    private val _midiFlow = MutableSharedFlow<MidiEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_LATEST
    )
    val midiFlow: SharedFlow<MidiEvent> = _midiFlow.asSharedFlow()

    private val _connectFlow = MutableSharedFlow<MidiDeviceInfo>(extraBufferCapacity = 8)
    val connectFlow: SharedFlow<MidiDeviceInfo> = _connectFlow.asSharedFlow()

    private val _disconnectFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val disconnectFlow: SharedFlow<Unit> = _disconnectFlow.asSharedFlow()

    @Volatile var isConnected: Boolean = false
        private set

    private var lastEventTimeNs: Long = 0L
    private var currentDevice: android.media.midi.MidiDevice? = null
    private var openPort: MidiOutputPort? = null

    /** Info of the device currently open (used for detach matching). */
    @Volatile private var openInfo: MidiDeviceInfo? = null

    /** Generation counter for openDevice callbacks (AND-M5). */
    private val openRequestSeq = AtomicInteger(0)

    // ─── USB detach detection (AND-M4/S5) ───

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = IntentCompat.getParcelableExtra(
                intent, UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java
            ) ?: return
            val info = openInfo ?: return
            // USB MIDI devices carry the originating UsbDevice in their
            // properties bundle — match by device name
            val openUsb = BundleCompat.getParcelable(
                info.properties, MidiDeviceInfo.PROPERTY_USB_DEVICE,
                android.hardware.usb.UsbDevice::class.java
            )
            if (openUsb != null && openUsb.deviceName == detached.deviceName) {
                Logger.i("MidiReader", "USB MIDI device detached: ${detached.deviceName}")
                closeCurrentDevice()
            }
        }
    }

    init {
        // ACTION_USB_DEVICE_DETACHED is a protected system broadcast, so plain
        // registerReceiver is valid on every API level (minSdk 23) — no
        // RECEIVER_EXPORTED flags or API 33 registerDeviceCallback needed.
        // (init must run AFTER detachReceiver's declaration)
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        context.registerReceiver(detachReceiver, filter)
    }

    // ─── Device enumeration ───

    /**
     * List all MIDI devices that have at least one output port (device → app).
     * Note: outputPortCount > 0 = device sends data TO the app.
     */
    @Suppress("DEPRECATION") // MidiManager.devices deprecated in API 33; still the minSdk-23-compatible API
    fun getAvailableDevices(): List<MidiDeviceInfo> {
        return midiManager.devices.filter { it.outputPortCount > 0 }
    }

    // ─── Device open / close ───

    /**
     * Open the specified MIDI device and start reading.
     * If another device is already open, close it first.
     *
     * The open result arrives asynchronously; a generation counter guards
     * against stale callbacks superseded by a newer open/close request (AND-M5).
     */
    fun openDevice(info: MidiDeviceInfo) {
        closeCurrentDevice()
        val request = openRequestSeq.incrementAndGet()
        midiManager.openDevice(info, { device ->
            if (request != openRequestSeq.get()) {
                // Superseded by a newer openDevice/closeCurrentDevice call —
                // close the late device instead of overwriting the new state
                Logger.i("MidiReader", "Ignoring stale openDevice callback (superseded request)")
                runCatching { device?.close() }
                return@openDevice
            }
            if (device == null) {
                Logger.w("MidiReader", "Failed to open device: ${info.properties}")
                return@openDevice
            }
            // openOutputPort — "output" from device = input to us
            val port = device.openOutputPort(0)
            if (port == null) {
                Logger.w("MidiReader", "Failed to open output port")
                device.close()
                return@openDevice
            }
            port.connect(createReceiver())
            currentDevice   = device
            openPort        = port
            openInfo        = info
            isConnected     = true
            lastEventTimeNs = System.nanoTime()
            _connectFlow.tryEmit(info)
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown"
            Logger.i("MidiReader", "Device opened: $name")
        }, Handler(Looper.getMainLooper()))
    }

    /** Close current device and publish a disconnect event. */
    fun closeCurrentDevice() {
        // Invalidate any in-flight openDevice callback before closing
        openRequestSeq.incrementAndGet()
        runCatching { openPort?.close() }
        runCatching { currentDevice?.close() }
        openPort      = null
        currentDevice = null
        openInfo      = null
        // Any partially assembled message from the old device is meaningless now
        parser.reset()
        if (isConnected) {
            isConnected = false
            _disconnectFlow.tryEmit(Unit)
        }
    }

    fun release() {
        runCatching { context.unregisterReceiver(detachReceiver) }
        closeCurrentDevice()
    }

    // ─── MIDI receiver (callback on MIDI driver thread) ───

    /**
     * Create the MidiReceiver that the MIDI driver calls back on its own thread.
     *
     * IMPORTANT: Android's MidiReceiver delivers a raw MIDI *byte stream*,
     * NOT USB-MIDI event packets. One callback may contain several
     * concatenated messages, and a single message (SysEx) may be split
     * across several callbacks. All splitting/reassembly is handled by
     * [MidiParser] — there is deliberately no CIN-header stripping here
     * (the old size==4 heuristic corrupted legitimate data).
     *
     * Each completed message is emitted via tryEmit (non-blocking, matches
     * the Go "select default" pattern). MidiParser allocates a fresh array
     * per message, so we never retain the driver's reusable buffer.
     */
    private fun createReceiver() = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            // Guard: zero/negative-length chunks carry no data
            if (count <= 0) return

            val nowNs   = System.nanoTime()
            var deltaMs = if (lastEventTimeNs == 0L) 0.0
                          else (nowNs - lastEventTimeNs) / 1_000_000.0
            lastEventTimeNs = nowNs

            // The first message of the chunk carries the inter-arrival delta;
            // subsequent messages in the same chunk share the timestamp.
            parser.feed(msg, offset, count) { data ->
                _midiFlow.tryEmit(MidiEvent(data, deltaMs))
                deltaMs = 0.0
            }
        }
    }
}
