package com.marchsnow.midibridge.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Top-level configuration matching the JSON structure of the Go server's config.json.
 * AdminConfig is removed — all settings are managed through the GUI.
 */
data class AppConfig(
    @SerializedName("ws")      val ws: WsConfig          = WsConfig(),
    @SerializedName("auth")    val auth: AuthConfig       = AuthConfig(),
    @SerializedName("midi")    val midi: MidiConfig       = MidiConfig(),
    @SerializedName("logging") val logging: LoggingConfig = LoggingConfig()
)

data class WsConfig(
    @SerializedName("port")       val port: Int       = 9001,
    @SerializedName("allowedIPs") val allowedIPs: String = ""
)

/**
 * AuthConfig uses var for passwordHash and updatedAt so Auth.setNewPassword()
 * can mutate them in-place before persisting.
 */
data class AuthConfig(
    @SerializedName("passwordHash") var passwordHash: String = "",
    @SerializedName("updatedAt")    var updatedAt: String    = ""
)

data class MidiConfig(
    @SerializedName("deviceName")          val deviceName: String  = "",
    @SerializedName("autoReconnect")       val autoReconnect: Boolean = true,
    @SerializedName("reconnectIntervalMs") val reconnectIntervalMs: Int = 3000
)

data class LoggingConfig(
    @SerializedName("midiVerbose") val midiVerbose: Boolean = true
)

/**
 * Configuration persistence using SharedPreferences + Gson.
 * Corresponds to the Go server's data/config.json file storage.
 * Thread-safe: SharedPreferences.edit().apply() is async-committed.
 */
class ConfigManager(context: Context) {

    private val prefs = context.getSharedPreferences("midibridge_config", Context.MODE_PRIVATE)
    private val gson  = Gson()

    companion object {
        private const val KEY_CONFIG   = "config_json"
        const val DEFAULT_PASSWORD     = "midiBridge123"
    }

    /** Load config; return defaults if nothing is saved yet (password hash empty, Auth will seed it). */
    fun load(): AppConfig {
        val json = prefs.getString(KEY_CONFIG, null) ?: return AppConfig()
        return runCatching { gson.fromJson(json, AppConfig::class.java) }.getOrDefault(AppConfig())
    }

    /** Persist config to SharedPreferences. */
    fun save(config: AppConfig) {
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
    }
}
