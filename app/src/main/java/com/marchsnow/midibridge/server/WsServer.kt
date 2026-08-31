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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket server — the core of MIDIBridge.
 *
 * Strictly matches the Go wsserver.go protocol:
 *   - 5-second auth timeout
 *   - auth / ping message handling
 *   - MIDI broadcast to authenticated clients (deltaMs → seconds, Base64-encoded)
 *   - kicked messages with reason constants
 *
 * 安全加固（相对初始实现）：
 *   - 认证失败立即断开 + 按 IP 失败计数封禁（防在线爆破与 CPU DoS）
 *   - Origin 校验：拒绝浏览器跨域连接（CSWSH），放行原生客户端
 *   - maxFrameSize 与并发连接数上限（防资源耗尽）
 *   - per-client 有界有序发送通道（广播保序 + 慢消费者不拖垮全局）
 *   - 逐个移除客户端（避免 clients.clear() 的孤儿竞态）
 *   - type 字段安全解析（畸形 JSON 不再导致连接静默断开）
 */
class WsServer(
    private val config: AppConfig,
    private val auth: Auth,
    private val midiFlow: SharedFlow<MidiEvent>
) {
    private val gson = Gson()

    private data class ClientSession(
        val sessionId: String,
        val session: DefaultWebSocketServerSession,
        val ip: String,
        @Volatile var authenticated: Boolean = false,
        @Volatile var banned: Boolean = false,
        /** 该客户端的有序发送通道：广播方非阻塞投递，单写协程消费保证顺序 */
        val sendChannel: Channel<String> = Channel(CHANNEL_BUFFER_SIZE)
    )

    private val clients = ConcurrentHashMap<String, ClientSession>()
    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 认证失败计数（按 IP），超过阈值临时封禁 */
    private val authFailures = ConcurrentHashMap<String, FailureEntry>()

    private data class FailureEntry(
        var count: Int,
        var windowStart: Long,
        var blockedUntil: Long
    )

    companion object {
        private const val AUTH_TIMEOUT_MS   = 5_000L
        private const val TAG               = "WsServer"

        /** 单帧最大字节数（协议仅有小 JSON 消息，防超大帧内存耗尽） */
        private const val MAX_FRAME_SIZE    = 64L * 1024

        /** 最大并发连接数（含未认证，防连接洪水） */
        private const val MAX_CONNECTIONS   = 32

        /** per-client 发送通道容量；慢消费者满时丢弃该客户端的帧 */
        private const val CHANNEL_BUFFER_SIZE = 256

        /** 认证失败封禁参数 */
        private const val AUTH_FAIL_WINDOW_MS  = 60_000L
        private const val AUTH_FAIL_MAX        = 5
        private const val AUTH_BAN_DURATION_MS = 300_000L
    }

    // ─── Start / Stop ───

    /** Start the WebSocket server (non-blocking). */
    fun start() {
        server = embeddedServer(CIO, port = config.ws.port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = null       // We handle ping/pong at application level
                timeout    = java.time.Duration.ofSeconds(30)
                maxFrameSize = MAX_FRAME_SIZE
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
        // 先完成踢出通知（使用独立的短生命周期协程等待发送），
        // 再取消 scope——旧实现 kick 协程挂在即将 cancel 的 scope 上，
        // 踢出消息可能永远发不出去
        val kickJob = CoroutineScope(Dispatchers.IO + SupervisorJob())
        kickAllClients(KickReason.SERVER_SHUTDOWN, kickJob)
        runBlocking { withTimeoutOrNull(1500) { kickJob.coroutineContext[Job]?.children?.forEach { it.join() } } }
        server?.stop(500, 1000)
        server = null
        scope.cancel()
        Logger.i(TAG, "WebSocket server stopped")
    }

    // ─── Kick / Client queries ───

    /** Kick every connected client with a reason code.
     *  使用传入的 scope（stop 场景下用独立 scope，避免被 cancel 后发不出消息）。 */
    fun kickAllClients(reason: String, kickScope: CoroutineScope = scope) {
        val snapshot = clients.values.toList()
        snapshot.forEach { client ->
            // 逐个移除（按 UUID 键，避免 clients.clear() 的孤儿竞态）
            clients.remove(client.sessionId)
            kickScope.launch {
                runCatching {
                    client.session.send(Frame.Text(kickedJson(reason)))
                    client.session.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                }
            }
        }
        Logger.i(TAG, "Kicked all clients ($reason)")
    }

    /** Return current client list (authenticated + pending). */
    fun getClients(): List<ClientInfo> {
        return clients.values.map { ClientInfo(it.ip, it.authenticated) }
    }

    fun clientCount(): Int = clients.size

    // ─── Connection handler ───

    private suspend fun handleConnection(session: DefaultWebSocketServerSession, clientIp: String) {
        val ip = clientIp

        // 连接数上限（防资源耗尽——未认证连接同样占用协程与内存）
        if (clients.size >= MAX_CONNECTIONS) {
            Logger.w(TAG, "Connection rejected (max connections): $ip")
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many connections"))
            return
        }

        // 认证失败封禁检查（被封禁的 IP 直接拒绝）
        if (isAuthBanned(ip)) {
            Logger.w(TAG, "Connection rejected (auth ban): $ip")
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Temporarily banned"))
            return
        }

        // IP allowlist check (matches Go handleConnection)
        if (!IpFilter.isAllowed(ip, config.ws.allowedIPs)) {
            Logger.w(TAG, "Connection rejected (IP not allowed): $ip")
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "IP not allowed"))
            return
        }

        // Origin 校验：原生客户端（Go CLI / 安卓等）不发送 Origin 头，放行；
        // 浏览器连接仅在 Host 同源时放行——阻断跨站 WebSocket 劫持（CSWSH）。
        // 注意：Host 头格式为 "hostname[:port]"，须拆出主机名部分再比较
        //（URI.host 不含端口，直接与含端口的 Host 头比较会恒不等）
        val origin = session.call.request.headers["Origin"]
        if (origin != null) {
            val hostHeader = session.call.request.headers["Host"]
            val originUri = runCatching { java.net.URI(origin) }.getOrNull()
            val originHost = originUri?.host
            // 拆出 Host 头的主机名（去掉端口部分）
            val hostName = hostHeader?.substringBefore(':')
            if (originHost == null || hostName == null || originHost != hostName) {
                Logger.w(TAG, "Rejected cross-origin WebSocket: origin=$origin host=$hostHeader")
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Cross-origin not allowed"))
                return
            }
        }

        val sessionId = UUID.randomUUID().toString()
        val client    = ClientSession(sessionId, session, ip)
        clients[sessionId] = client
        Logger.i(TAG, "Client connected: $ip")

        // per-client 发送协程：消费有序通道，保证该客户端收到的消息按序
        val writerJob = scope.launch {
            for (msg in client.sendChannel) {
                runCatching { client.session.send(Frame.Text(msg)) }
                    .onFailure {
                        // 发送失败：连接已死，关闭通道让写协程退出
                        client.sendChannel.close()
                    }
            }
        }

        // 5-second auth timeout (matches Go authTimer)
        val authTimeoutJob = scope.launch {
            delay(AUTH_TIMEOUT_MS)
            if (!client.authenticated) {
                Logger.w(TAG, "Auth timeout: $ip")
                runCatching {
                    client.sendChannel.trySend(kickedJson(KickReason.AUTH_TIMEOUT))
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
            writerJob.cancel()
            client.sendChannel.close()
            clients.remove(sessionId)
            Logger.i(TAG, "Client removed: $ip")
        }
    }

    // ─── Message dispatch ───

    private suspend fun handleMessage(client: ClientSession, text: String) {
        // type 字段安全解析：畸形 JSON（如 "type":{}）不再抛异常导致连接断开
        val json = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
        val typeValue = json.get("type")
        val type = if (typeValue?.isJsonPrimitive == true) typeValue.asString else return

        when (type) {
            "auth" -> {
                val passwordValue = json.get("password")
                val password = if (passwordValue?.isJsonPrimitive == true) passwordValue.asString else ""
                if (client.authenticated) {
                    // 已认证的重复 auth：幂等处理
                    client.sendChannel.trySend("""{"type":"auth_ok"}""")
                    return
                }
                if (auth.verifyPassword(password)) {
                    client.authenticated = true
                    clearAuthFailures(client.ip)
                    client.sendChannel.trySend("""{"type":"auth_ok"}""")
                    Logger.i(TAG, "Auth OK: ${client.ip}")
                } else {
                    Logger.w(TAG, "Auth failed: ${client.ip}")
                    val banned = recordAuthFailure(client.ip)
                    val reason = if (banned) "Too many failed attempts, temporarily banned" else "Incorrect password"
                    client.sendChannel.trySend("""{"type":"auth_fail","reason":"$reason"}""")
                    // 认证失败立即断开（旧实现保持连接打开——可无限次重试）
                    client.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
                }
            }
            "ping" -> {
                client.sendChannel.trySend("""{"type":"pong"}""")
            }
        }
    }

    // ─── MIDI broadcast ───

    /**
     * Main broadcast loop: collect MIDI events from MidiReader, log verbose if enabled,
     * and broadcast to all authenticated clients.
     *
     * 每个客户端有独立的有界发送通道：投递非阻塞（trySend），
     * 慢消费者不会拖垮广播循环；通道满时丢弃该帧并计数。
     */
    private suspend fun broadcastMidi() {
        var droppedFrames = 0L
        midiFlow.collect { event ->
            if (config.logging.midiVerbose) {
                val verbose = Logger.formatMidiVerbose(event.data)
                if (verbose.isNotEmpty()) {
                    Logger.midi(verbose)
                }
            }

            val deltaSeconds = event.deltaMs / 1000.0
            val base64Data   = Base64.encodeToString(event.data, Base64.NO_WRAP)
            val msg = """{"type":"midi","data":{"t":$deltaSeconds,"m":"$base64Data"}}"""

            val authenticated = clients.values.filter { it.authenticated && !it.banned }.toList()
            authenticated.forEach { client ->
                val result = client.sendChannel.trySend(msg)
                if (result.isFailure) {
                    droppedFrames++
                    if (droppedFrames % 100 == 1L) {
                        Logger.w(TAG, "Slow client queue full — $droppedFrames frame(s) dropped so far")
                    }
                }
            }
        }
    }

    // ─── Auth failure tracking ───

    /** 记录一次认证失败，超过阈值则临时封禁该 IP。返回是否触发封禁。 */
    private fun recordAuthFailure(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val entry = authFailures.compute(ip) { _, existing ->
            if (existing == null || now - existing.windowStart > AUTH_FAIL_WINDOW_MS) {
                FailureEntry(1, now, 0)
            } else {
                existing.count++
                existing
            }
        } ?: return false

        if (entry.count > AUTH_FAIL_MAX && entry.blockedUntil < now) {
            entry.blockedUntil = now + AUTH_BAN_DURATION_MS
            Logger.w(TAG, "Too many auth failures, banning $ip for ${AUTH_BAN_DURATION_MS / 1000}s")
            return true
        }
        return false
    }

    /** 判断该 IP 是否处于封禁期。 */
    private fun isAuthBanned(ip: String): Boolean {
        val entry = authFailures[ip] ?: return false
        return entry.blockedUntil > System.currentTimeMillis()
    }

    /** 认证成功后清除该 IP 的失败记录。 */
    private fun clearAuthFailures(ip: String) {
        authFailures.remove(ip)
    }

    // ─── Helpers ───

    private fun kickedJson(reason: String) =
        """{"type":"kicked","reason":"$reason"}"""
}
