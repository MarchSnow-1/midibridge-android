package com.marchsnow.midibridge.server

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.marchsnow.midibridge.data.MidiEvent
import com.marchsnow.midibridge.util.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 */
class MidiReader(private val context: Context) {

    private val midiManager = context.getSystemService(MidiManager::class.java)

    // Buffer 256, DROP_OLDEST = non-blocking send (matches Go select-default pattern)
    private val _midiFlow = MutableSharedFlow<MidiEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val midiFlow: SharedFlow<MidiEvent> = _midiFlow.asSharedFlow()

    private val _connectFlow = MutableSharedFlow<MidiDeviceInfo>(extraBufferCapacity = 8)
    val connectFlow: SharedFlow<MidiDeviceInfo> = _connectFlow.asSharedFlow()

    private val _disconnectFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val disconnectFlow: SharedFlow<Unit> = _disconnectFlow.asSharedFlow()

    @Volatile var isConnected: Boolean = false
        private set

    private var lastEventTimeNs: Long = 0L
    private var openDevice: android.media.midi.MidiDevice? = null
    private var openPort: MidiOutputPort? = null

    // ─── Device enumeration ───

    /**
     * List all MIDI devices that have at least one output port (device → app).
     * Note: outputPortCount > 0 = device sends data TO the app.
     */
    fun getAvailableDevices(): List<MidiDeviceInfo> {
        return midiManager.devices.filter { it.outputPortCount > 0 }
    }

    // ─── Device open / close ───

    /**
     * Open the specified MIDI device and start reading.
     * If another device is already open, close it first.
     */
    fun openDevice(info: MidiDeviceInfo) {
        closeCurrentDevice()
        midiManager.openDevice(info, { device ->
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
            openDevice      = device
            openPort        = port
            isConnected     = true
            lastEventTimeNs = System.nanoTime()
            _connectFlow.tryEmit(info)
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown"
            Logger.i("MidiReader", "Device opened: $name")
        }, Handler(Looper.getMainLooper()))
    }

    /** Close current device and publish a disconnect event. */
    fun closeCurrentDevice() {
        runCatching { openPort?.close() }
        runCatching { openDevice?.close() }
        openPort   = null
        openDevice = null
        if (isConnected) {
            isConnected = false
            _disconnectFlow.tryEmit(Unit)
        }
    }

    fun release() {
        closeCurrentDevice()
    }

    // ─── MIDI receiver (callback on MIDI driver thread) ───

    /**
     * Create the MidiReceiver that the MIDI driver calls back on its own thread.
     *
     * Critical: we must IMMEDIATELY copy the data array — the driver may reuse
     * the buffer after the callback returns. This matches the Go version's
     * "copy(msg, data)" inside the SetListener callback.
     *
     * We use tryEmit (non-blocking) to match the Go "select default" pattern.
     */
    private fun createReceiver() = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val nowNs   = System.nanoTime()
            val deltaMs = if (lastEventTimeNs == 0L) 0.0
                          else (nowNs - lastEventTimeNs) / 1_000_000.0
            lastEventTimeNs = nowNs

            // Copy and strip USB-MIDI CIN header if present.
            // Some Android USB-MIDI drivers deliver 4-byte USB-MIDI packets
            // (Cable Number + CIN + 3 MIDI bytes). Valid MIDI messages are
            // 1-3 bytes, or SysEx (starts with 0xF0). If we get 4 bytes that
            // don't start with SysEx, strip the CIN header byte.
            val rawData = msg.copyOfRange(offset, offset + count)
            val data = if (rawData.size == 4 && rawData[0] != 0xF0.toByte()) {
                rawData.copyOfRange(1, 4)
            } else {
                rawData
            }

            _midiFlow.tryEmit(MidiEvent(data, deltaMs))
        }
    }
}
