package com.marchsnow.midibridge.data

/** MIDI event: raw bytes + millisecond delta from previous message */
data class MidiEvent(
    val data: ByteArray,
    val deltaMs: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MidiEvent) return false
        return data.contentEquals(other.data) && deltaMs == other.deltaMs
    }

    override fun hashCode(): Int {
        return data.contentHashCode() * 31 + deltaMs.hashCode()
    }
}

/** WebSocket connected client info */
data class ClientInfo(
    val ip: String,
    val authenticated: Boolean
)

/** Kick reason constants (must match Go server protocol exactly) */
object KickReason {
    const val AUTH_TIMEOUT     = "auth_timeout"
    const val SERVER_SHUTDOWN  = "server_shutdown"
    const val PASSWORD_CHANGED = "password_changed"
}
