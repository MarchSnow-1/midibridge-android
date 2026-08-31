package com.marchsnow.midibridge.server

import java.io.ByteArrayOutputStream

/**
 * Incremental parser for a raw MIDI 1.0 byte stream (wire format).
 *
 * Android's [android.media.midi.MidiReceiver.onSend] delivers a *byte stream*
 * (not USB-MIDI event packets): a single callback may contain several
 * concatenated messages, and a single message (notably SysEx) may be split
 * across several callbacks. This class reassembles the stream into complete
 * MIDI messages, implementing:
 *
 *  - Status-byte based message splitting (multiple messages per chunk)
 *  - Per-status data-length tracking for all channel-voice and system-common
 *    messages
 *  - Running Status (omitted status byte reuses the previous channel-voice
 *    status; cleared by any system-common message, unaffected by realtime)
 *  - Realtime messages (0xF8–0xFF) extracted wherever they appear — including
 *    in the middle of another message or inside a SysEx — as standalone
 *    one-byte messages
 *  - SysEx reassembly: 0xF0 starts accumulation, terminating 0xF7 ends it,
 *    across any number of feed() calls; bounded by [maxSysexBytes] with
 *    discard-until-EOX behaviour on overflow
 *
 * NOTE: there is deliberately NO USB-MIDI packet (CIN) handling here.
 * The old heuristic ("4 bytes not starting with 0xF0 → strip first byte")
 * was wrong: MidiReceiver delivers plain MIDI bytes, and the heuristic
 * corrupted legitimate 4-byte sequences (e.g. F0-less data or padded frames).
 *
 * Pure JVM code — no Android dependencies — so it is unit-testable.
 */
class MidiParser(
    private val maxSysexBytes: Int = DEFAULT_MAX_SYSEX_BYTES,
    private val warn: (String) -> Unit = {}
) {

    companion object {
        /** Default cap on a single SysEx message (defense against a runaway F0 stream). */
        const val DEFAULT_MAX_SYSEX_BYTES = 64 * 1024
    }

    /** Last channel-voice status byte, for Running Status. 0 = none. */
    private var runningStatus = 0

    /** Message currently being assembled (status + up to 2 data bytes). */
    private var msgStatus = 0
    private val msgData = ByteArray(2)
    private var msgDataLen = 0
    private var msgDataExpected = 0

    /** Non-null while accumulating a SysEx (holds the leading 0xF0). */
    private var sysex: ByteArrayOutputStream? = null

    /** True after an oversized SysEx: swallow everything until the terminating 0xF7. */
    private var discardingSysex = false

    /**
     * Feed a chunk of the MIDI byte stream. Every complete message is passed
     * to [emit] as a freshly allocated byte array (safe to hold onto).
     *
     * @param input  buffer holding the chunk
     * @param offset start index of the chunk within [input]
     * @param count  number of bytes in the chunk; values <= 0 are ignored
     */
    fun feed(input: ByteArray, offset: Int, count: Int, emit: (ByteArray) -> Unit) {
        if (count <= 0 || offset < 0) return
        val end = minOf(offset + count, input.size)
        for (i in offset until end) {
            val b = input[i].toInt() and 0xFF

            // Realtime messages are valid at ANY position — even interleaved
            // inside another message or a SysEx — and never affect any
            // in-progress parsing state.
            if (b >= 0xF8) {
                emit(byteArrayOf(b.toByte()))
                continue
            }

            if (discardingSysex) {
                when (b) {
                    0xF7 -> discardingSysex = false            // EOX: resume normal parsing
                    0xF0 -> { discardingSysex = false; startSysex() }
                    else  -> Unit                               // swallow garbage
                }
                continue
            }

            if (sysex != null) {
                if (b == 0xF7) {
                    // End of SysEx: 0xF7 is part of the message (terminator)
                    sysex!!.write(0xF7)
                    emit(sysex!!.toByteArray())
                    sysex = null
                } else if (b >= 0x80) {
                    // A status byte inside an unterminated SysEx is malformed.
                    // Abandon the partial SysEx and process the status byte.
                    warn("Status byte 0x%02X inside unterminated SysEx — discarding partial SysEx".format(b))
                    sysex = null
                    handleStatus(b, emit)
                } else {
                    sysex!!.write(b)
                    if (sysex!!.size() > maxSysexBytes) {
                        sysex = null
                        discardingSysex = true
                        warn("SysEx exceeds $maxSysexBytes bytes — discarding until EOX (0xF7)")
                    }
                }
                continue
            }

            if (b >= 0x80) handleStatus(b, emit) else handleData(b, emit)
        }
    }

    /** Drop all parsing state (e.g. when switching to a different device). */
    fun reset() {
        runningStatus = 0
        msgStatus = 0
        msgDataLen = 0
        msgDataExpected = 0
        sysex = null
        discardingSysex = false
    }

    // ─── Internals ───

    private fun startSysex() {
        sysex = ByteArrayOutputStream(64).also { it.write(0xF0) }
    }

    private fun handleStatus(b: Int, emit: (ByteArray) -> Unit) {
        if (b < 0xF0) {
            // Channel-voice message: establishes Running Status
            runningStatus = b
            msgStatus = b
            msgDataLen = 0
            msgDataExpected = dataBytesFor(b)
        } else {
            // System-common message: clears Running Status
            runningStatus = 0
            msgStatus = 0
            msgDataLen = 0
            when (b) {
                0xF0 -> startSysex()
                0xF1 -> { msgStatus = b; msgDataExpected = 1 }  // MTC quarter frame
                0xF2 -> { msgStatus = b; msgDataExpected = 2 }  // Song position pointer
                0xF3 -> { msgStatus = b; msgDataExpected = 1 }  // Song select
                0xF6 -> emit(byteArrayOf(0xF6.toByte()))        // Tune request (no data)
                else  -> emit(byteArrayOf(b.toByte()))          // F4/F5 undefined — forward as-is
            }
        }
    }

    private fun handleData(b: Int, emit: (ByteArray) -> Unit) {
        if (msgStatus != 0 && msgDataLen < msgDataExpected) {
            // Continue the message started by an explicit status byte
            msgData[msgDataLen++] = b.toByte()
            if (msgDataLen == msgDataExpected) emitAssembled(emit)
        } else if (runningStatus != 0) {
            // Running Status: data byte implies repetition of the previous
            // channel-voice status byte
            msgStatus = runningStatus
            msgDataExpected = dataBytesFor(runningStatus)
            msgDataLen = 0
            msgData[msgDataLen++] = b.toByte()
            if (msgDataLen == msgDataExpected) emitAssembled(emit)
        }
        // else: stray data byte with no context — drop it silently
    }

    private fun emitAssembled(emit: (ByteArray) -> Unit) {
        emit(
            when (msgDataExpected) {
                0    -> byteArrayOf(msgStatus.toByte())
                1    -> byteArrayOf(msgStatus.toByte(), msgData[0])
                else -> byteArrayOf(msgStatus.toByte(), msgData[0], msgData[1])
            }
        )
        msgStatus = 0
        msgDataLen = 0
    }

    private fun dataBytesFor(status: Int): Int = when (status and 0xF0) {
        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2  // note off/on, poly AT, CC, pitch bend
        0xC0, 0xD0 -> 1                    // program change, channel pressure
        else -> 0
    }
}
