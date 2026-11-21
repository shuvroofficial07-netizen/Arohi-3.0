package com.arohi.ai.core

import android.content.Context
import android.content.SharedPreferences

/**
 * All non-sensitive Arohi settings. API keys live in [SecureStore] instead.
 */
class SettingsStore private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("arohi_settings", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var inst: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            inst ?: synchronized(this) { inst ?: SettingsStore(context.applicationContext).also { inst = it } }

        const val AUTO_REPLY_OFF = "off"
        const val AUTO_REPLY_ASK = "ask"
        const val AUTO_REPLY_AUTO = "auto"
    }

    private fun put(key: String, value: Any?) {
        sp.edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is Long -> putLong(key, value)
            }
        }.apply()
    }

    // ----- Identity / personality -----
    var userName: String get() = sp.getString("user_name", "শুভ্র (Shubho)") ?: "শুভ্র (Shubho)"
        set(v) = put("user_name", v)
    var warmth: Int get() = sp.getInt("warmth", 70)            // 0 professional .. 100 romantic
        set(v) = put("warmth", v)
    var proactiveEnabled: Boolean get() = sp.getBoolean("proactive_enabled", true)
        set(v) = put("proactive_enabled", v)
    /** minutes between self-initiated nudges (v3: user asked for a talkative Arohi) */
    var proactiveMinutes: Int get() = sp.getInt("proactive_minutes", 45)
        set(v) = put("proactive_minutes", v)
    var personalityNotes: String get() = sp.getString("personality_notes", "") ?: ""
        set(v) = put("personality_notes", v)

    // ----- Voice -----
    // v6 MIC FIX (user bug: "মাইক অটোমেটিক অন হয়ে যায়, আবার অটোমেটিক বন্ধ হয়"):
    // ডিফল্ট FALSE — মাইক কখনো নিজে নিজে চালু হবে না। চালু হবে কেবল ব্যবহারকারীর
    // স্পষ্ট সিদ্ধান্তে: START AROHI চাপলে বা Settings-এ Wake Word টগল করলে।
    // বন্ধ হবে কেবল STOP AROHI / টগল অফ করলে — কখনোই এলোমেলো নয়।
    var wakeWordEnabled: Boolean get() = sp.getBoolean("wake_word_enabled", false)
        set(v) = put("wake_word_enabled", v)
    var wakeWords: String get() = sp.getString("wake_words", "arohi, আরোহী, আরোহি, হেই আরোহী, hey arohi")
            ?: "arohi, আরোহী, আরোহি, হেই আরোহী, hey arohi"
        set(v) = put("wake_words", v)
    var startOnBoot: Boolean get() = sp.getBoolean("start_on_boot", true)
        set(v) = put("start_on_boot", v)
    var sttLanguage: String get() = sp.getString("stt_language", "bn-BD") ?: "bn-BD"
        set(v) = put("stt_language", v)
    var voiceMode: String get() = sp.getString("voice_mode", "voice") ?: "voice"   // voice | text
        set(v) = put("voice_mode", v)
    var ttsEngine: String get() = sp.getString("tts_engine", "device") ?: "device" // elevenlabs | device
        set(v) = put("tts_engine", v)
    var ttsSpeed: Float get() = sp.getFloat("tts_speed", 1.0f)
        set(v) = put("tts_speed", v)
    var ttsPitch: Float get() = sp.getFloat("tts_pitch", 1.15f)
        set(v) = put("tts_pitch", v)
    var bargeIn: Boolean get() = sp.getBoolean("barge_in", true)
        set(v) = put("barge_in", v)
    /** v5: একটানা গল্পের সেশন কত মিনিট নিস্তব্ধতায় বন্ধ হবে (লং চ্যাট সাপোর্ট)। */
    var sessionTimeoutMinutes: Int get() = sp.getInt("session_timeout_minutes", 5)
        set(v) = put("session_timeout_minutes", v)
    /** v5: নোটিফিকেশন থেকে টেক্সট চ্যাট (অ্যাপের ভেতরে না ঢুকেই গল্প)। */
    var notifChatEnabled: Boolean get() = sp.getBoolean("notif_chat_enabled", true)
        set(v) = put("notif_chat_enabled", v)
    var announceNotifications: Boolean get() = sp.getBoolean("announce_notifications", true)
        set(v) = put("announce_notifications", v)
    /** v4 privacy mode: when ON, notification CONTENT is spoken; when OFF only
     *  source+title are announced (nothing sensitive read aloud). */
    var announceContent: Boolean get() = sp.getBoolean("announce_content", true)
        set(v) = put("announce_content", v)
    var announceInDnd: Boolean get() = sp.getBoolean("announce_in_dnd", false)
        set(v) = put("announce_in_dnd", v)
    /** v3: AUTO is the default — Arohi replies to messages by herself. */
    var autoReplyMode: String get() = sp.getString("auto_reply_mode", AUTO_REPLY_AUTO) ?: AUTO_REPLY_AUTO
        set(v) = put("auto_reply_mode", v)

    // ----- AI -----
    var geminiModel: String get() = sp.getString("gemini_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(v) = put("gemini_model", v)

    // v6 — MULTI-PROVIDER (user spec: OpenAI + Gemini, Auto Fallback, Smart Model Detection)
    /** "gemini" বা "openai" — কোন ব্রেইন আগে ব্যবহার হবে। */
    var defaultProvider: String get() = sp.getString("default_provider", "gemini") ?: "gemini"
        set(v) = put("default_provider", v)
    /** ডিফল্ট প্রোভাইডার ব্যর্থ হলে (কোটা/অথ/নেট) অন্য কনফিগার্ড প্রোভাইডারে অটো-সুইচ। */
    var autoFallback: Boolean get() = sp.getBoolean("auto_fallback", true)
        set(v) = put("auto_fallback", v)
    /**
     * v7 — Provider Enable/Disable (spec §7/§9). A key can stay saved while the
     * provider is switched OFF: ArohiBrain never routes to a disabled provider,
     * and enabling it again needs no re-entry of the key.
     */
    var geminiEnabled: Boolean get() = sp.getBoolean("gemini_enabled", true)
        set(v) = put("gemini_enabled", v)
    var openaiEnabled: Boolean get() = sp.getBoolean("openai_enabled", true)
        set(v) = put("openai_enabled", v)
    /** স্মার্ট মডেল ডিটেকশন: কী থেকে সবচেয়ে ভালো মডেল নিজেই বাছে (v4.1 থেকে চালু আছে)। */
    var smartModelDetection: Boolean get() = sp.getBoolean("smart_model_detection", true)
        set(v) = put("smart_model_detection", v)
    var openaiModel: String get() = sp.getString("openai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = put("openai_model", v)
    /** v6: এই OpenAI কী-র জন্য যাচাইকৃত মডেল চেইন (কমা-সেপারেটেড)। */
    var openaiModelsCache: String get() = sp.getString("openai_models_cache", "") ?: ""
        set(v) = put("openai_models_cache", v)
    /** v6: true শুধু তখনই, যখন বাস্তব chat-completion টেস্ট সফল হয়েছে। */
    var openaiVerified: Boolean get() = sp.getBoolean("openai_verified", false)
        set(v) = put("openai_verified", v)
    var openaiVerifiedAt: String get() = sp.getString("openai_verified_at", "") ?: ""
        set(v) = put("openai_verified_at", v)

    /** v4.1 AUTO-CONNECT: comma list of usable models discovered for THIS key —
     *  the request-time fallback chain survives app restarts. */
    var geminiModelsCache: String get() = sp.getString("gemini_models_cache", "") ?: ""
        set(v) = put("gemini_models_cache", v)
    /** v4.1 AUTO-CONNECT: true only after a REAL generateContent test succeeded. */
    var geminiVerified: Boolean get() = sp.getBoolean("gemini_verified", false)
        set(v) = put("gemini_verified", v)
    /** v4.1 AUTO-CONNECT: timestamp of the last successful verification. */
    var geminiVerifiedAt: String get() = sp.getString("gemini_verified_at", "") ?: ""
        set(v) = put("gemini_verified_at", v)

    // ----- Memory -----
    var memoryEnabled: Boolean get() = sp.getBoolean("memory_enabled", true)
        set(v) = put("memory_enabled", v)

    // ----- Smart Home -----
    var mqttHost: String get() = sp.getString("mqtt_host", "") ?: ""
        set(v) = put("mqtt_host", v)
    var mqttPort: Int get() = sp.getInt("mqtt_port", 1883)
        set(v) = put("mqtt_port", v)
    var mqttUsername: String get() = sp.getString("mqtt_username", "") ?: ""
        set(v) = put("mqtt_username", v)
    var mqttPassword: String get() = sp.getString("mqtt_password", "") ?: ""
        set(v) = put("mqtt_password", v)
    var mqttBaseTopic: String get() = sp.getString("mqtt_base", "arohi/home") ?: "arohi/home"
        set(v) = put("mqtt_base", v)

    // ----- PC bridge -----
    var pcHost: String get() = sp.getString("pc_host", "") ?: ""
        set(v) = put("pc_host", v)
    var pcPort: Int get() = sp.getInt("pc_port", 9527)
        set(v) = put("pc_port", v)

    // ----- Security -----
    var appLockEnabled: Boolean get() = sp.getBoolean("app_lock_enabled", false)
        set(v) = put("app_lock_enabled", v)
    var pinHash: String get() = sp.getString("pin_hash", "") ?: ""
        set(v) = put("pin_hash", v)
    var pinSalt: String get() = sp.getString("pin_salt", "") ?: ""
        set(v) = put("pin_salt", v)

    // ----- Runtime helpers -----
    var elevenVoiceId: String
        get() = try { SecureStore.get(spKeyContext()).elevenVoiceId } catch (_: Exception) { "" }
        set(v) { try { SecureStore.get(spKeyContext()).elevenVoiceId = v } catch (_: Exception) {} }
    var lastGreetingDay: Int get() = sp.getInt("last_greeting_day", -1)
        set(v) = put("last_greeting_day", v)
    var lastNudgeAt: Long get() = sp.getLong("last_nudge_at", 0L)
        set(v) = put("last_nudge_at", v)

    private var ctxRef: Context? = null
    private fun spKeyContext(): Context = ctxRef ?: throw IllegalStateException("SettingsStore.init(context) not called")

    fun init(context: Context) { ctxRef = context.applicationContext }
}
