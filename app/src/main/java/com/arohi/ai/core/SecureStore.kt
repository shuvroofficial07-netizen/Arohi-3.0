package com.arohi.ai.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Hardware-backed encrypted storage for secrets (API keys).
 * Keys never touch the codebase — this is the ONLY place they live.
 */
class SecureStore private constructor(context: Context) {

    private val sp: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "arohi_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // Fallback: never crash the app; still private (app-internal prefs).
        context.getSharedPreferences("arohi_secure_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        @Volatile private var inst: SecureStore? = null
        fun get(context: Context): SecureStore =
            inst ?: synchronized(this) { inst ?: SecureStore(context.applicationContext).also { inst = it } }
    }

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v).apply()

    // v6 — OpenAI provider (user spec section 5: Gemini + OpenAI দুটোই)
    var openaiKey: String
        get() = sp.getString("openai_key", "") ?: ""
        set(v) = sp.edit().putString("openai_key", v).apply()

    var elevenKey: String
        get() = sp.getString("eleven_key", "") ?: ""
        set(v) = sp.edit().putString("eleven_key", v).apply()

    var elevenVoiceId: String
        get() = sp.getString("eleven_voice_id", "") ?: ""
        set(v) = sp.edit().putString("eleven_voice_id", v).apply()

    var pcToken: String
        get() = sp.getString("pc_token", "") ?: ""
        set(v) = sp.edit().putString("pc_token", v).apply()

    val geminiConfigured: Boolean get() = geminiKey.isNotBlank()
    val openaiConfigured: Boolean get() = openaiKey.isNotBlank()
    /** v6: অন্তত একটি ব্রেইন প্রোভাইডার কনফিগার্ড কি না। */
    val anyBrainConfigured: Boolean get() = geminiConfigured || openaiConfigured
    val elevenConfigured: Boolean get() = elevenKey.isNotBlank() && elevenVoiceId.isNotBlank()
}
