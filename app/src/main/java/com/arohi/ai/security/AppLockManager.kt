package com.arohi.ai.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.util.Extensions

/**
 * App lock: biometric unlock with hashed-PIN fallback.
 * PIN is stored as SHA-256(salt + pin) inside app-private settings only.
 */
object AppLockManager {

    private const val SESSION_MS = 2 * 60 * 1000L

    @Volatile private var lastUnlockedAt: Long = 0

    fun shouldLock(context: Context): Boolean {
        val s = SettingsStore.get(context)
        return s.appLockEnabled && (System.currentTimeMillis() - lastUnlockedAt > SESSION_MS)
    }

    fun markUnlocked() { lastUnlockedAt = System.currentTimeMillis() }

    fun setPin(context: Context, pin: String): Boolean {
        if (pin.length < 4) return false
        val s = SettingsStore.get(context)
        val salt = Extensions.randomSalt()
        s.pinSalt = salt
        s.pinHash = Extensions.sha256(salt + pin)
        return true
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val s = SettingsStore.get(context)
        if (s.pinHash.isBlank()) return true
        return Extensions.sha256(s.pinSalt + pin) == s.pinHash
    }

    fun hasPin(context: Context): Boolean = SettingsStore.get(context).pinHash.isNotBlank()

    fun biometricAvailable(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showLock(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val ctx = activity
        if (biometricAvailable(ctx)) {
            val executor = ContextCompat.getMainExecutor(ctx)
            val prompt = BiometricPrompt(activity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        markUnlocked(); onUnlocked()
                    }
                    override fun onAuthenticationError(code: Int, errString: CharSequence) {
                        showPinDialog(activity, onUnlocked)
                    }
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Arohi — অ্যাপ লক")
                .setSubtitle("শুভ্র, আপনাকে যাচাই করতে হবে")
                .setNegativeButtonText("PIN ব্যবহার করুন")
                .build()
            prompt.authenticate(info)
        } else {
            showPinDialog(activity, onUnlocked)
        }
    }

    private fun showPinDialog(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val ctx = activity
        val input = android.widget.EditText(ctx).apply {
            hint = "PIN দিন"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 16)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Arohi — PIN")
            .setView(input)
            .setPositiveButton("আনলক") { _, _ ->
                if (verifyPin(ctx, input.text.toString())) { markUnlocked(); onUnlocked() }
                else android.widget.Toast.makeText(ctx, "ভুল PIN", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }
}
