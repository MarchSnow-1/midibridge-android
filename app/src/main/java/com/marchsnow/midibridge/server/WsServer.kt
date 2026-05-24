package com.marchsnow.midibridge.server

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.marchsnow.midibridge.data.AppConfig
import com.marchsnow.midibridge.data.ClientInfo
import com.marchsnow.midibridge.data.KickReason
import com.marchsnow.midibridge.data.MidiEvent
import com.marchsnow.midibridge.util.Logger
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket server — the core of MIDIBridge.
 *
 * Strictly matches the Go wsserver.go protocol:
 *   - 5-second auth timeout
 *   - auth / ping message handling
 *   - MIDI broadcast to authenticated clients (deltaMs → seconds, Base64-encoded)
 *   - kicked messages with reason constants
 *
 * Deadlock fix (Go → Kotlin):
 *   ConcurrentHashMap snapshot + per-session coroutines naturally avoid
 *   the original "lock-inside-broadcast" deadlock. No global mutex needed.
 */
class WsServer(
    private val config: AppConfig,
    private val auth: Auth,
    private val midiFlow: SharedFlow<MidiEvent>
) {
    private val gson = Gson()

    private data class ClientSession(
        val session: DefaultWebSocketSession,
        val ip: String,
        @Volatile var authenticated: Boolean = false
    )

    private val clients = ConcurrentHashMap<String, ClientSession>()
    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val AUTH_TIMEOUT_MS = 5_000L
        private const val TAG = "WsServer"
    }

    // ─── Start / Stop ───

    /** Start the WebSocket server (non-blocking). */
    fun start() {
        server = embeddedServer(CIO, port = config.ws.port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = null       // We handle ping/pong at application level
                timeout    = java.time.Duration.ofSeconds(30)
            }
            routing {
                webSocket("/") {
                    handleConnection(this, call.request.local.remoteAddress)
                }
            }
        }.start(wait = false)

        scope.launch { broadcastMidi() }
        Logger.i(TAG, "WebSocket server started on port ${config.ws.port}")
    }

    /** Stop: kick all clients, close HTTP listener, cancel coroutines. */
    fun stop() {
        kickAllClients(KickReason.SERVER_SHUTDOWN)
        server?.stop(500, 1000)
        server = null
        scope.cancel()
        Logger.i(TAG, "WebSocket server stopped")
    }

    // ─── Kick / Client queries ───

    /** Kick every connected client with a reason code. */
    fun kickAllClients(reason: String) {
        val snapshot = clients.values.toList()
        snapshot.forEach { client ->
            scope.launch {
                runCatching {
                    client.session.send(Frame.Text(kickedJson(reason)))
                    client.session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                }
            }
        }
        clients.clear()
        Logger.i(TAG, "Kicked all clients ($reason)")
    }

    /** Return current client list (authenticated + pending). */
    fun getClients(): List<ClientInfo> {
        return clients.values.map { ClientInfo(it.ip, it.authenticated) }
    }

    fun clientCount(): Int = clients.size

    // ─── Connection handler ───

    private suspend fun handleConnection(session: DefaultWebSocketSession, clientIp: String) {
        val ip = clientIp

        // IP allowlist check (matches Go handleConnection)
        if (!IpFilter.isAllowed(ip, config.ws.allowedIPs)) {
            Logger.w(TAG, "Connection rejected (IP not allowed): $ip")
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "IP not allowed"))
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val client    = ClientSession(session, ip)
        clients[sessionId] = client
        Logger.i(TAG, "Client connected: $ip")

        // 5-second auth timeout (matches Go authTimer)
        val authTimeoutJob = scope.launch {
            delay(AUTH_TIMEOUT_MS)
            if (!client.authenticated) {
                Logger.w(TAG, "Auth timeout: $ip")
                runCatching {
                    session.send(Frame.Text(kickedJson(KickReason.AUTH_TIMEOUT)))
                    session.close(CloseReason(CloseReason.Codes.NORMAL, KickReason.AUTH_TIMEOUT))
                }
                clients.remove(sessionId)
            }
        }

        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                handleMessage(client, frame.readText())
            }
        } catch (e: Exception) {
            Logger.d(TAG, "Client disconnected: $ip — ${e.message}")
        } finally {
            authTimeoutJob.cancel()
            clients.remove(sessionId)
            Logger.i(TAG, "Client removed: $ip")
        }
    }

    // ─── Message dispatch ───

    private suspend fun handleMessage(client: ClientSession, text: String) {
        val json = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
        when (json.get("type")?.asString) {
            "auth" -> {
                val password = json.get("password")?.asString ?: ""
                if (auth.verifyPassword(password)) {
                    client.authenticated = true
                    client.session.send(Frame.Text("""{"type":"auth_ok"}"""))
                    Logger.i(TAG, "Auth OK: ${client.ip}")
                } else {
                    client.session.send(Frame.Text("""{"type":"auth_fail","reason":"Incorrect password"}"""))
                    Logger.w(TAG, "Auth failed: ${client.ip}")
                }
            }
            "ping" -> {
                client.session.send(Frame.Text("""{"type":"pong"}"""))
            }
        }
    }

    // ─── MIDI broadcast ───

    /**
     * Main broadcast loop: collect MIDI events from MidiReader, log verbose if enabled,
     * and broadcast to all authenticated clients.
     *
     * ConcurrentHashMap.values.filter().toList() creates a snapshot — no global
     * lock. Each session's send() runs in its own coroutine, so a dead connection
     * can't stall the broadcast (the original Go deadlock fix).
     */
    private suspend fun broadcastMidi() {
        midiFlow.collect { event ->
            // Verbose MIDI logging (matches Go midiVerbose check in event bus)
            if (config.logging.midiVerbose) {
                val verbose = Logger.formatMidiVerbose(event.data)
                if (verbose.isNotEmpty()) {
                    Logger.midi(verbose)
                }
            }

            val deltaSeconds = event.deltaMs / 1000.0
            val base64Data   = Base64.encodeToString(event.data, Base64.NO_WRAP)
            val msg = """{"type":"midi","data":{"t":$deltaSeconds,"m":"$base64Data"}}"""

            val authenticated = clients.values.filter { it.authenticated }.toList()
            authenticated.forEach { client ->
                scope.launch {
                    runCatching { client.session.send(Frame.Text(msg)) }
                }
            }
        }
    }

    // ─── Helpers ───

    private fun kickedJson(reason: String) =
        """{"type":"kicked","reason":"$reason"}"""
}
