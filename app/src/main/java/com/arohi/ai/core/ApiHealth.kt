package com.arohi.ai.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.arohi.ai.brain.GeminiClient
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4 — Central, honest health board. ONE source of truth for every surface
 * (HOME / API CENTER / SYSTEM HEALTH / SETTINGS), so the "কোনটা কানেক্টেড
 * কোনটা ডিসকানেক্টেড" confusion is structurally impossible.
 *
 * Status model (per the master spec):
 *  ACTIVE             — verified working right now (green)
 *  INACTIVE           — off, can be turned on (red)
 *  CONNECTING         — a real operation is in flight (amber)
 *  ERROR              — tried and failed, error detail shown (red)
 *  PERMISSION_REQUIRED— needs a system permission (red)
 *  NOT_CONFIGURED     — needs setup (key/host) (grey)
 *  UNSUPPORTED        — this device cannot do it (grey)
 */
object ApiHealth {

    enum class St {
        ACTIVE, INACTIVE, CONNECTING, ERROR, PERMISSION_REQUIRED, NOT_CONFIGURED, UNSUPPORTED
    }

    data class Item(val st: St, val label: String, val detail: String)

    // Back-compat aliases used by fragments
    val St.ok get() = this == St.ACTIVE
    val St.na get() = this == St.NOT_CONFIGURED || this == St.UNSUPPORTED

    // ----- live flags -----
    val internet = MutableStateFlow(Item(St.ACTIVE, "ইন্টারনেট", "অনলাইন"))
    val gemini = MutableStateFlow(Item(St.NOT_CONFIGURED, "জেমিনি ব্রেইন", "কী সেট নেই"))
    val openai = MutableStateFlow(Item(St.NOT_CONFIGURED, "OpenAI ব্রেইন", "কী সেট নেই"))
    val eleven = MutableStateFlow(Item(St.NOT_CONFIGURED, "ElevenLabs ভয়েস", "কী সেট নেই"))
    val voiceRecognition = MutableStateFlow(Item(St.INACTIVE, "ভয়েস রিকগনিশন (STT)", "যাচাই হচ্ছে"))
    val tts = MutableStateFlow(Item(St.INACTIVE, "টেক্সট-টু-স্পিচ (TTS)", "যাচাই হচ্ছে"))
    val wakeWord = MutableStateFlow(Item(St.INACTIVE, "Wake Word ইঞ্জিন", "বন্ধ"))
    val service = MutableStateFlow(Item(St.INACTIVE, "২৪/৭ ব্যাকগ্রাউন্ড সার্ভিস", "বন্ধ"))
    val mic = MutableStateFlow(Item(St.PERMISSION_REQUIRED, "মাইক্রোফোন", "অনুমতি নেই"))
    val notifListener = MutableStateFlow(Item(St.PERMISSION_REQUIRED, "নোটিফিকেশন লিসেনার", "অফ"))
    val accessibility = MutableStateFlow(Item(St.PERMISSION_REQUIRED, "অ্যাক্সেসিবিলিটি", "অফ"))
    val batteryOpt = MutableStateFlow(Item(St.PERMISSION_REQUIRED, "ব্যাটারি অপটিমাইজেশন", "সীমাবদ্ধ"))
    val messaging = MutableStateFlow(Item(St.INACTIVE, "মেসেজিং ইন্টিগ্রেশন", "অফ"))
    val memoryDb = MutableStateFlow(Item(St.INACTIVE, "মেমরি ডেটাবেস (Room)", "যাচাই হচ্ছে"))
    val automation = MutableStateFlow(Item(St.INACTIVE, "অটোমেশন ইঞ্জিন", "নিষ্ক্রিয়"))
    val vision = MutableStateFlow(Item(St.PERMISSION_REQUIRED, "ভিশন ইঞ্জিন", "ক্যামেরা অনুমতি নেই"))
    val deviceControl = MutableStateFlow(Item(St.ACTIVE, "ডিভাইস কন্ট্রোল", "সব API উপলব্ধ"))
    val contacts = MutableStateFlow(Item(St.PERMISSION_REQUIRED, "কন্টাক্ট", "অনুমতি নেই"))
    val phone = MutableStateFlow(Item(St.PERMISSION_REQUIRED, "ফোন কল", "অনুমতি নেই"))

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    @Volatile var geminiLastTested: String = ""
        private set
    @Volatile var openaiLastTested: String = ""
        private set

    /** Cheap, instant refresh of flag-based items (no network). */
    fun refreshFlags(ctx: Context) {
        val c = ctx.applicationContext
        val s = SettingsStore.get(c)
        val store = SecureStore.get(c)

        internet.value = if (NetworkMonitor.isOnline)
            Item(St.ACTIVE, "ইন্টারনেট", "অনলাইন (${NetworkMonitor.networkLabel})")
        else Item(St.INACTIVE, "ইন্টারনেট", "অফলাইন — স্বয়ংক্রিয় পুনঃসংযোগ চলছে")

        gemini.value = when {
            !store.geminiConfigured -> Item(St.NOT_CONFIGURED, "জেমিনি ব্রেইন", "কী সেট নেই")
            gemini.value.st == St.CONNECTING -> gemini.value
            else -> gemini.value // keep last real test result (OK/ERROR)
        }

        // v6 — OpenAI: flag-based honest refresh (last REAL test result kept)
        openai.value = when {
            !store.openaiConfigured -> Item(St.NOT_CONFIGURED, "OpenAI ব্রেইন", "কী সেট নেই")
            s.openaiVerified -> Item(St.ACTIVE, "OpenAI ব্রেইন", "কানেক্টেড ✓ ${s.openaiModel}")
            openai.value.st == St.CONNECTING -> openai.value
            openai.value.st == St.ERROR -> openai.value
            else -> Item(St.INACTIVE, "OpenAI ব্রেইন", "কী আছে, বাস্তব টেস্ট হয়নি")
        }

        eleven.value = if (store.elevenConfigured)
            Item(if (s.ttsEngine == "elevenlabs") St.ACTIVE else St.INACTIVE, "ElevenLabs ভয়েস",
                if (s.ttsEngine == "elevenlabs") "চালু" else "কী আছে, ইঞ্জিন অফ")
        else Item(St.NOT_CONFIGURED, "ElevenLabs ভয়েস", "কী সেট নেই (ডিভাইস TTS চলছে)")

        mic.value = if (PermissionHelper.hasMicrophone(c)) Item(St.ACTIVE, "মাইক্রোফোন", "অনুমতি আছে")
        else Item(St.PERMISSION_REQUIRED, "মাইক্রোফোন", "অনুমতি নেই — ভয়েস বন্ধ")

        notifListener.value = if (PermissionHelper.isNotificationListenerEnabled(c))
            Item(St.ACTIVE, "নোটিফিকেশন লিসেনার", "পড়া ও ঘোষণা চালু")
        else Item(St.PERMISSION_REQUIRED, "নোটিফিকেশন লিসেনার", "সেটিংসে চালু করুন")

        accessibility.value = if (PermissionHelper.isAccessibilityEnabled(c))
            Item(St.ACTIVE, "অ্যাক্সেসিবিলিটি", "টাচ ও রিপ্লাই চালু")
        else Item(St.PERMISSION_REQUIRED, "অ্যাক্সেসিবিলিটি", "সেটিংসে চালু করুন")

        batteryOpt.value = if (isBatteryUnrestricted(c)) Item(St.ACTIVE, "ব্যাটারি অপটিমাইজেশন", "আনরেস্ট্রিক্টেড")
        else Item(St.PERMISSION_REQUIRED, "ব্যাটারি অপটিমাইজেশন", "সীমাবদ্ধ — ঠিক করুন")

        service.value = if (ArohiBus.serviceRunning.value) Item(St.ACTIVE, "২৪/৭ ব্যাকগ্রাউন্ড সার্ভিস", "চালু")
        else Item(St.INACTIVE, "২৪/৭ ব্যাকগ্রাউন্ড সার্ভিস", "বন্ধ — START AROHI চাপুন")

        wakeWord.value = when {
            !s.wakeWordEnabled -> Item(St.INACTIVE, "Wake Word ইঞ্জিন", "সেটিংসে বন্ধ")
            ArohiBus.serviceRunning.value && PermissionHelper.hasMicrophone(c) ->
                Item(St.ACTIVE, "Wake Word ইঞ্জিন", "\"আরোহী\" শুনছে")
            ArohiBus.sessionOpen.value -> Item(St.ACTIVE, "Wake Word ইঞ্জিন", "কথোপকথনে ব্যস্ত")
            else -> Item(St.INACTIVE, "Wake Word ইঞ্জিন", "সার্ভিস বন্ধ")
        }

        voiceRecognition.value = when {
            !PermissionHelper.hasMicrophone(c) -> Item(St.PERMISSION_REQUIRED, "ভয়েস রিকগনিশন (STT)", "মাইক অনুমতি নেই")
            !com.arohi.ai.voice.SttManager.isAvailable(c) ->
                Item(St.UNSUPPORTED, "ভয়েস রিকগনিশন (STT)", "এই ডিভাইসে Google Speech সার্ভিস নেই")
            else -> Item(St.ACTIVE, "ভয়েস রিকগনিশন (STT)", s.sttLanguage)
        }

        tts.value = when {
            !PermissionHelper.hasNotifications(c) && Build.VERSION.SDK_INT >= 33 ->
                Item(St.PERMISSION_REQUIRED, "টেক্সট-টু-স্পিচ (TTS)", "নোটিফিকেশন অনুমতি নেই")
            TtsManager.isSpeaking || TtsManager.engineReady(c) ->
                Item(St.ACTIVE, "টেক্সট-টু-স্পিচ (TTS)",
                    if (s.ttsEngine == "elevenlabs" && store.elevenConfigured) "ElevenLabs ইঞ্জিন"
                    else "ডিভাইস TTS (bn)")
            else -> Item(St.INACTIVE, "টেক্সট-টু-স্পিচ (TTS)", "প্রস্তুত হচ্ছে…")
        }

        // messaging integration = real reply pipeline readiness
        messaging.value = when {
            !PermissionHelper.isNotificationListenerEnabled(c) ->
                Item(St.PERMISSION_REQUIRED, "মেসেজিং ইন্টিগ্রেশন", "নোটিফিকেশন লিসেনার অফ")
            !PermissionHelper.isAccessibilityEnabled(c) ->
                Item(St.PERMISSION_REQUIRED, "মেসেজিং ইন্টিগ্রেশন", "অ্যাক্সেসিবিলিটি অফ — রিপ্লাই পাঠানো যাবে না")
            s.autoReplyMode == SettingsStore.AUTO_REPLY_OFF ->
                Item(St.INACTIVE, "মেসেজিং ইন্টিগ্রেশন", "অটো-রিপ্লাই বন্ধ (সেটিংসে)")
            else -> Item(St.ACTIVE, "মেসেজিং ইন্টিগ্রেশন",
                if (s.autoReplyMode == SettingsStore.AUTO_REPLY_AUTO) "অটো-রিপ্লাই চালু" else "রিপ্লাইয়ে জিজ্ঞেস করে")
        }

        memoryDb.value = try {
            val count = com.arohi.ai.memory.MemoryDatabase.get(c).memoryDao().countSync()
            Item(St.ACTIVE, "মেমরি ডেটাবেস (Room)", "$count স্মৃতি জমা")
        } catch (_: Exception) {
            Item(St.ERROR, "মেমরি ডেটাবেস (Room)", "খোলা যাচ্ছে না")
        }

        automation.value = when {
            com.arohi.ai.automation.TaskEngine.get(c).isBusy() ->
                Item(St.ACTIVE, "অটোমেশন ইঞ্জিন", "টাস্ক চলছে")
            PermissionHelper.isAccessibilityEnabled(c) ->
                Item(St.ACTIVE, "অটোমেশন ইঞ্জিন", "প্রস্তুত (স্ক্রিন টাচ সহ)")
            else -> Item(St.PERMISSION_REQUIRED, "অটোমেশন ইঞ্জিন", "অ্যাক্সেসিবিলিটি ছাড়া স্ক্রিন অটোমেশন বন্ধ")
        }

        vision.value = if (PermissionHelper.hasCamera(c))
            Item(St.ACTIVE, "ভিশন ইঞ্জিন", "ক্যামেরা + OCR প্রস্তুত")
        else Item(St.PERMISSION_REQUIRED, "ভিশন ইঞ্জিন", "ক্যামেরা অনুমতি নেই")

        deviceControl.value = Item(St.ACTIVE, "ডিভাইস কন্ট্রোল",
            if (android.provider.Settings.System.canWrite(c)) "সব API উপলব্ধ" else "ব্রাইটনেসের জন্য WRITE SETTINGS দরকার")

        contacts.value = if (PermissionHelper.hasContacts(c)) Item(St.ACTIVE, "কন্টাক্ট", "অনুমতি আছে")
        else Item(St.PERMISSION_REQUIRED, "কন্টাক্ট", "অনুমতি নেই")
        phone.value = if (PermissionHelper.hasPhone(c)) Item(St.ACTIVE, "ফোন কল", "অনুমতি আছে")
        else Item(St.PERMISSION_REQUIRED, "ফোন কল", "অনুমতি নেই")
    }

    /** Set Gemini to a live CONNECTING state while a test runs. */
    fun markGeminiTesting() {
        gemini.value = Item(St.CONNECTING, "জেমিনি ব্রেইন", "টেস্ট হচ্ছে… (সরাসরি Google সার্ভারে)")
    }

    /** v4.1 AUTO-CONNECT: live stage display (key validation, model discovery…). */
    fun markGeminiStage(stage: String) {
        gemini.value = Item(St.CONNECTING, "জেমিনি ব্রেইন", stage)
    }

    // ================= v6 — OpenAI mirrors =================

    fun markOpenAITesting() {
        openai.value = Item(St.CONNECTING, "OpenAI ব্রেইন", "টেস্ট হচ্ছে… (সরাসরি OpenAI সার্ভারে)")
    }

    fun markOpenAIStage(stage: String) {
        openai.value = Item(St.CONNECTING, "OpenAI ব্রেইন", stage)
    }

    fun setOpenAIAutoResult(ctx: Context, r: com.arohi.ai.brain.OpenAIClient.AutoResult) {
        openaiLastTested = fmt.format(Date())
        openai.value = when {
            r.ok -> Item(St.ACTIVE, "OpenAI ব্রেইন", "কানেক্টেড ✓ ${r.model} ($openaiLastTested)")
            r.stage == "NETWORK" ->
                Item(St.ERROR, "OpenAI ব্রেইন", "কী সেভ হয়েছে; ইন্টারনেট ফিরলে অটো-কানেক্ট হবে")
            !SecureStore.get(ctx).openaiConfigured ->
                Item(St.NOT_CONFIGURED, "OpenAI ব্রেইন", "কী সেট নেই")
            else -> Item(St.ERROR, "OpenAI ব্রেইন", "${r.message} ($openaiLastTested)")
        }
    }

    /** Real OpenAI API test — caches the result with a timestamp. */
    suspend fun testOpenAI(ctx: Context): com.arohi.ai.brain.OpenAIClient.TestResult {
        markOpenAITesting()
        val r = com.arohi.ai.brain.OpenAIClient.testConnectionDetailed(ctx)
        openaiLastTested = fmt.format(Date())
        openai.value = when {
            !SecureStore.get(ctx).openaiConfigured -> Item(St.NOT_CONFIGURED, "OpenAI ব্রেইন", "কী সেট নেই")
            r.ok -> Item(St.ACTIVE, "OpenAI ব্রেইন", "কানেক্টেড (${openaiLastTested})")
            else -> Item(St.ERROR, "OpenAI ব্রেইন", "${r.message} ($openaiLastTested)")
        }
        return r
    }

    /** v4.1 AUTO-CONNECT: publish the REAL pipeline outcome to the health board. */
    fun setGeminiAutoResult(ctx: Context, r: GeminiClient.AutoResult) {
        geminiLastTested = fmt.format(Date())
        gemini.value = when {
            r.ok -> Item(St.ACTIVE, "জেমিনি ব্রেইন", "কানেক্টেড ✓ ${r.model} ($geminiLastTested)")
            r.stage == "NETWORK" ->
                Item(St.ERROR, "জেমিনি ব্রেইন", "কী সেভ হয়েছে; ইন্টারনেট ফিরলে অটো-কানেক্ট হবে")
            r.stage == "AUTH" || r.stage == "FORMAT" || r.stage == "CLEAN" ->
                Item(St.ERROR, "জেমিনি ব্রেইন", "${r.message} ($geminiLastTested)")
            !SecureStore.get(ctx).geminiConfigured ->
                Item(St.NOT_CONFIGURED, "জেমিনি ব্রেইন", "কী সেট নেই")
            else -> Item(St.ERROR, "জেমিনি ব্রেইন", "${r.message} ($geminiLastTested)")
        }
    }

    fun isBatteryUnrestricted(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) { false }

    /** Real Gemini API test — caches the result with a timestamp. */
    suspend fun testGemini(ctx: Context): GeminiClient.TestResult {
        markGeminiTesting()
        val r = GeminiClient.testConnectionDetailed(ctx)
        geminiLastTested = fmt.format(Date())
        gemini.value = when {
            !SecureStore.get(ctx).geminiConfigured -> Item(St.NOT_CONFIGURED, "জেমিনি ব্রেইন", "কী সেট নেই")
            r.ok -> Item(St.ACTIVE, "জেমিনি ব্রেইন", "কানেক্টেড ($geminiLastTested)")
            else -> Item(St.ERROR, "জেমিনি ব্রেইন", "${r.message} ($geminiLastTested)")
        }
        return r
    }

    /** All items in display order (System Health dashboard + Home grid). */
    fun all(): List<MutableStateFlow<Item>> = listOf(
        internet, gemini, openai, eleven, voiceRecognition, tts, wakeWord, service, mic,
        notifListener, accessibility, messaging, memoryDb, automation, vision,
        deviceControl, batteryOpt, contacts, phone
    )

    /** One-line summary Arohi can speak. */
    fun summaryBn(ctx: Context): String {
        refreshFlags(ctx)
        val bad = all().filter { it.value.st != St.ACTIVE }
        return if (bad.isEmpty()) "সব সিস্টেম সবুজ — সবকিছু চমৎকার চলছে শুভ্র!"
        else "সমস্যা আছে: " + bad.joinToString("; ") { "${it.value.label} (${it.value.detail})" }
    }

    /** Device profile for the SYSTEM HEALTH header — real values only. */
    fun deviceProfileBn(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val ramMb = am?.let { ami ->
            val mi = android.app.ActivityManager.MemoryInfo()
            ami.getMemoryInfo(mi)
            mi.totalMem / (1024 * 1024)
        } ?: 0L
        val ramClass = when {
            ramMb >= 8000 -> "হাই-এন্ড"
            ramMb >= 4000 -> "মিড-রেঞ্জ"
            ramMb > 0 -> "লো-রেঞ্জ (অপ্টিমাইজড মোড)"
            else -> "অজানা"
        }
        return "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}" +
            " • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " • RAM ${(ramMb / 1024)}GB [$ramClass]"
    }
}
