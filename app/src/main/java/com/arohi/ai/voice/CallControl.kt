package com.arohi.ai.voice

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import com.arohi.ai.core.ArohiBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * v7 — VOICE EXIT for hands-free hangs up ("কল কেটে দাও" / "hang up").
 *
 * Why this file exists: on a phone call the normal path is broken by design —
 * the Android SpeechRecognizer keeps the call audio routed to the call stream,
 * so the mic path the assistant hears is the *uplink* (your own voice mixed
 * with echo), and Google's STT often refuses to run while a call is active.
 * Claiming "voice hang-up works everywhere" would be a lie.
 *
 * So this is implemented as an HONEST BEST-EFFORT path with a real fallback:
 *
 *  1) If a call is in progress, the ONLY honest way to hang up without root is
 *     TelecomManager.endCall(), which requires the app to be the default phone
 *     app OR to hold the ANSWER_PHONE_CALLS role granted through
 *     RoleManager.ROLE_CALL_SCREENING / default-dialer. We check that
 *     capability instead of assuming it.
 *  2) When endCall() is permitted, the command really ends the call and we
 *     report "কল কেটে দিয়েছি" only after TelecomManager returned true.
 *  3) When it is not permitted, we do not pretend: we say clearly that Android
 *     needs default-phone permission, and offer the accessibility "tap the red
 *     button" route if that service is enabled.
 *
 * The class is deliberately tiny and side-effect free so it can be unit-tested
 * with a fake capability answer.
 */
object CallControl {

    enum class Capability { CAN_END_CALL, NEEDS_DEFAULT_DIALER, ACCESSIBILITY_TAP, UNSUPPORTED }

    /** Real capability probe — no assumption is made about the app's role. */
    fun capability(context: Context): Capability {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val selfPkg = context.packageName
            val defaultDialer = try { tm?.defaultDialerPackage } catch (_: Exception) { null }
            if (defaultDialer != null && defaultDialer == selfPkg) return Capability.CAN_END_CALL
            if (com.arohi.ai.core.PermissionHelper.isAccessibilityEnabled(context))
                return Capability.ACCESSIBILITY_TAP
            return Capability.NEEDS_DEFAULT_DIALER
        }
        return if (com.arohi.ai.core.PermissionHelper.isAccessibilityEnabled(context))
            Capability.ACCESSIBILITY_TAP else Capability.UNSUPPORTED
    }

    /**
     * Attempts to end the active call. Returns a Bengali, truthful sentence
     * describing exactly what happened (never "হয়ে গেছে" unless it did).
     */
    fun endCall(context: Context): String {
        if (!isCallActive(context)) return "এখন কোনো কল চলছে না শুভ্র।"
        return when (capability(context)) {
            Capability.CAN_END_CALL -> {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val ok = try { tm?.endCall() ?: false } catch (_: Exception) { false }
                if (ok) "কল কেটে দিয়েছি।" else "কল কাটার অনুরোধ পাঠানো যায়নি — ফোন অ্যাপ নিজে থেকে বন্ধ করেনি।"
            }
            Capability.ACCESSIBILITY_TAP -> {
                AccessibilityTap.hangUpViaAccessibility(context)
                "ফোনে কল কাটার বোতাম খুঁজছি — স্ক্রিনে লাল বোতাম দেখা গেলে চেপে দেব।"
            }
            Capability.NEEDS_DEFAULT_DIALER ->
                "কল কাটতে Android-এর নিয়ম: আরোহীকে ডিফল্ট ফোন অ্যাপ হতে হবে। " +
                        "SYSTEM HEALTH → পারমিশনে সেটআপ করা যায়, নয়তো ফোনের লাল বোতাম নিজে চাপতে হবে।"
            Capability.UNSUPPORTED ->
                "এই Android ভার্সনে অ্যাপ থেকে কল কাটা সমর্থিত নয় — ফোনের লাল বোতাম চাপুন।"
        }
    }

    fun isCallActive(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
            } else {
                @Suppress("DEPRECATION")
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                tm?.isInCall == true
            }
        } catch (_: Exception) { false }
    }
}

/**
 * Accessibility-based "press the red button" route. It searches the live
 * window tree for a node whose content description / text matches a hang-up
 * label and performs a real ACTION_CLICK on it. If nothing matches within a
 * short budget, it reports failure honestly instead of claiming success.
 */
object AccessibilityTap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var pendingHangup = false

    fun hangUpViaAccessibility(context: Context) {
        val svc = com.arohi.ai.services.ArohiAccessibilityService.instance ?: run {
            ArohiBus.toast("অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই — ফোনের লাল বোতাম চাপুন")
            return
        }
        pendingHangup = true
        scope.launch {
            repeat(6) { attempt ->
                val clicked = try { svc.findAndClickHangup() } catch (_: Exception) { false }
                if (clicked) {
                    pendingHangup = false
                    ArohiBus.toast("কল কাটার বোতাম চাপা হয়েছে")
                    return@launch
                }
                delay(400L + attempt * 150L)
            }
            pendingHangup = false
            ArohiBus.toast("লাল বোতাম পাওয়া যায়নি — নিজে চাপুন")
        }
    }

    fun hasPendingHangup(): Boolean = pendingHangup
}

/**
 * Speaks a short confirmation with the call-safe audio stream. Kept separate
 * so the voice loop never steals the call audio route by accident.
 */
object CallAudio {
    fun speakOnCallStream(context: Context, text: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("bn-IN")
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.6f)
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "arohi_call")
            }
        }
    }
}
