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
 *
 * 安全模型：首次使用必须由用户在 GUI 设置密码（hash 为空时所有认证
 * 一律拒绝——fail-closed）。不再存在内置默认口令。
 */
class Auth(
    private val config: AppConfig,
    private val configManager: ConfigManager
) {
    companion object {
        const val BCRYPT_COST      = 10
        const val MIN_PASSWORD_LEN = 8
        private const val TAG = "Auth"
    }

    /**
     * Verify a plaintext password against the stored bcrypt hash.
     * 未设置密码（哈希为空）时直接拒绝——服务器不提供"免认证"开箱模式，
     * 用户必须在 GUI 首次设置密码。
     */
    fun verifyPassword(plainPassword: String): Boolean {
        if (config.auth.passwordHash.isEmpty()) {
            Logger.w(TAG, "No password set — all authentication rejected until user sets one")
            return false
        }
        return BCrypt.verifyer()
            .verify(plainPassword.toCharArray(), config.auth.passwordHash)
            .verified
    }

    /**
     * Called from ViewModel on Save: generate a new bcrypt hash and persist.
     * 内部强制长度校验（防绕过 UI 的调用路径），不满足直接拒绝。
     *
     * @param newPassword plaintext new password
     */
    fun setNewPassword(newPassword: String) {
        require(newPassword.length >= MIN_PASSWORD_LEN) {
            "Password must be at least $MIN_PASSWORD_LEN characters"
        }
        val newHash = BCrypt.withDefaults().hashToString(BCRYPT_COST, newPassword.toCharArray())
        config.auth.passwordHash = newHash
        config.auth.updatedAt    = Instant.now().toString()
        configManager.save(config)
        Logger.i(TAG, "Password changed")
    }

    /** 密码是否已设置（用于 GUI 提示"未设置密码"状态）。 */
    fun isPasswordSet(): Boolean = config.auth.passwordHash.isNotEmpty()
}
