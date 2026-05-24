package com.marchsnow.midibridge.util

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Dual-output logger: Logcat + in-memory ring buffer (200 entries).
 * The ring buffer is polled by the UI every 1.5s.
 *
 * Corresponds to Go logger.go + logRing in main.go.
 */
object Logger {

    private const val RING_SIZE = 200

    private val ring = Array(RING_SIZE) { "" }
    private var index = 0
    private val lock  = Any()

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    /** MIDI verbose events (Note On/Off, CC, etc.) */
    fun midi(msg: String) = log("M", "MIDI", msg)

    private fun log(level: String, tag: String, msg: String) {
        when (level) {
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "D" -> Log.d(tag, msg)
            "E" -> Log.e(tag, msg)
            "M" -> Log.v(tag, msg)
        }
        val timestamp = LocalTime.now().format(timeFmt)
        val line = "[$level] $timestamp $tag: $msg"
        synchronized(lock) {
            ring[index % RING_SIZE] = line
            index++
        }
    }

    /**
     * Return the last 200 log lines in chronological order, joined by newlines.
     * Called by the UI polling loop every 1.5s.
     */
    fun getLogs(): String {
        synchronized(lock) {
            val count = minOf(index, RING_SIZE)
            if (count == 0) return ""
            val start = if (index >= RING_SIZE) index % RING_SIZE else 0
            return (0 until count)
                .map { ring[(start + it) % RING_SIZE] }
                .joinToString("\n")
        }
    }

    // ─── MIDI verbose formatter (matches Go midiVerbose) ───

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * Parse raw MIDI bytes into a human-readable string.
     * Handles Note On/Off, CC, Program Change, Pitch Bend.
     * System messages return empty string.
     *
     * Corresponds to Go midiVerbose().
     */
    fun formatMidiVerbose(data: ByteArray): String {
        if (data.size < 2) return ""
        val status  = data[0].toInt() and 0xFF
        val msgType = status and 0xF0
        val channel = (status and 0x0F) + 1

        return when (msgType) {
            0x80 -> { // Note Off
                if (data.size < 3) return ""
                val note = data[1].toInt() and 0xFF
                val vel  = data[2].toInt() and 0xFF
                "CH$channel Note Off: ${noteName(note)} vel=$vel"
            }
            0x90 -> { // Note On (vel=0 treated as Note Off)
                if (data.size < 3) return ""
                val note = data[1].toInt() and 0xFF
                val vel  = data[2].toInt() and 0xFF
                if (vel == 0) "CH$channel Note Off: ${noteName(note)} vel=0"
                else          "CH$channel Note On:  ${noteName(note)} vel=$vel"
            }
            0xB0 -> { // Control Change
                if (data.size < 3) return ""
                val cc  = data[1].toInt() and 0xFF
                // Skip All Notes Off (123) and All Sound Off (120) — noisy, per Go behavior
                if (cc == 123 || cc == 120) return ""
                val value = data[2].toInt() and 0xFF
                "CH$channel CC#$cc=$value"
            }
            0xC0 -> { // Program Change
                val prog = data[1].toInt() and 0xFF
                "CH$channel Program: $prog"
            }
            0xE0 -> { // Pitch Bend
                if (data.size < 3) return ""
                val lsb = data[1].toInt() and 0xFF
                val msb = data[2].toInt() and 0xFF
                val pb  = ((msb shl 7) or lsb) - 8192
                "CH$channel PitchBend=$pb"
            }
            else -> "" // System messages — not logged
        }
    }

    private fun noteName(midi: Int): String {
        val name = NOTE_NAMES[midi % 12]
        val octave = midi / 12 - 1
        return "$name$octave"
    }
}
