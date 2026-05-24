package com.marchsnow.midibridge.server

import at.favre.lib.crypto.bcrypt.BCrypt
import com.marchsnow.midibridge.data.AppConfig
import com.marchsnow.midibridge.data.ConfigManager
import com.marchsnow.midibridge.util.Logger
import java.time.Instant

/**
 * Authentication module using bcrypt (cost=10).
 * Corresponds to the Go auth.go — verifyPassword() and changePassword().
 *
 * In the Kotlin version the password change entry-point is setNewPassword(),
 * called from ViewModel when the user clicks Save in the GUI.
 * No old-password verification is needed (GUI handles confirmation).
 */
class Auth(
    private val config: AppConfig,
    private val configManager: ConfigManager
) {
    companion object {
        const val BCRYPT_COST      = 10
        const val MIN_PASSWORD_LEN = 6
        private const val TAG = "Auth"
    }

    /**
     * Verify a plaintext password against the stored bcrypt hash.
     * If the hash is empty (first run), auto-seed with the default password hash.
     * Corresponds to Go verifyPassword().
     */
    fun verifyPassword(plainPassword: String): Boolean {
        ensureHashInitialized()
        if (config.auth.passwordHash.isEmpty()) return false
        return BCrypt.verifyer()
            .verify(plainPassword.toCharArray(), config.auth.passwordHash)
            .verified
    }

    /**
     * Called from ViewModel on Save: generate a new bcrypt hash and persist.
     * Caller (ViewModel) is responsible for length validation (>= MIN_PASSWORD_LEN)
     * and kicking clients before calling this.
     *
     * @param newPassword plaintext new password
     */
    fun setNewPassword(newPassword: String) {
        val newHash = BCrypt.withDefaults().hashToString(BCRYPT_COST, newPassword.toCharArray())
        config.auth.passwordHash = newHash
        config.auth.updatedAt    = Instant.now().toString()
        configManager.save(config)
        Logger.i(TAG, "Password changed")
    }

    /**
     * First-run: if no hash exists, generate one from the default password.
     * This ensures the server is protected out of the box.
     */
    private fun ensureHashInitialized() {
        if (config.auth.passwordHash.isNotEmpty()) return
        config.auth.passwordHash = BCrypt.withDefaults()
            .hashToString(BCRYPT_COST, ConfigManager.DEFAULT_PASSWORD.toCharArray())
        config.auth.updatedAt = Instant.now().toString()
        configManager.save(config)
        Logger.w(TAG, "Default password set — please change via Settings")
    }
}
