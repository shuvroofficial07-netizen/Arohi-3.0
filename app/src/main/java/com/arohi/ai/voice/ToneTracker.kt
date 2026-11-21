package com.arohi.ai.voice

import com.arohi.ai.core.ArohiBus
import kotlin.math.abs

/**
 * v5 — শুভ্রের "গলার টোন" শনাক্তকারী (voice-tone emotion detector).
 *
 * ব্যবহারকারীর চাহিদা: আরোহী যেন শুভ্রের কথা বলার ধরন আর গলার টোন শুনে বুঝে
 * সে কী ধরনের কথা বলছে, আর সেই ভঙ্গিতেই উত্তর দেয়।
 *
 * দুটি বাস্তব সিগন্যাল (কোনো ভুয়া অনুমান নেই):
 *  1) PROSODY — STT চলাকালীন onRmsChanged থেকে আসা প্রকৃত voice-energy
 *     নমুনা: গড় শক্তি, শিখর শক্তি, শক্তির ওঠানামা (variance), বাক্যের দৈর্ঘ্য
 *     এবং কথার গতি (প্রতি সেকেন্ডে শব্দ)।
 *  2) TEXT — লেখার বিরামচিহ্ন (!!, ??), বড় হাতের অক্ষর, আবেগের শব্দ
 *     (EmotionEngine lexicon) এবং শব্দসংখ্যা।
 *
 * ফল: EXCITED | ANGRY | SAD | CALM | NORMAL — এটি ArohiBus.userTone-এ যায়
 * এবং SystemPromptBuilder সেই টোন মেলানোর নির্দেশ ব্রেইনে দেয়।
 */
object ToneTracker {

    data class Prosody(
        val avgEnergy: Float,     // 0..1 average voice energy
        val peakEnergy: Float,    // 0..1 loudest moment
        val variance: Float,      // energy swings (excitement marker)
        val samples: Int,
        val speakSeconds: Float   // rough speaking duration
    )

    private const val WINDOW_MS = 14_000L      // one utterance window
    private const val MAX_SAMPLES = 400

    private var buf = FloatArray(MAX_SAMPLES)
    private var count = 0
    private var windowStart = 0L

    /** Called from STT onRmsChanged — cheap ring-buffer sampling. */
    fun sample(energy: Float) {
        val now = System.currentTimeMillis()
        if (count == 0 || now - windowStart > WINDOW_MS) { windowStart = now; count = 0 }
        if (count < MAX_SAMPLES) buf[count++] = energy
    }

    fun reset() { count = 0 }

    /** Compute prosody for the just-finished utterance. */
    fun snapshot(): Prosody {
        if (count < 5) return Prosody(0f, 0f, 0f, count, 0f)
        var sum = 0f; var peak = 0f
        for (i in 0 until count) { sum += buf[i]; if (buf[i] > peak) peak = buf[i] }
        val avg = sum / count
        var v = 0f
        for (i in 0 until count) { val d = buf[i] - avg; v += d * d }
        val seconds = ((System.currentTimeMillis() - windowStart) / 1000f).coerceIn(0.5f, 14f)
        return Prosody(avg, peak, v / count, count, seconds)
    }

    /**
     * Final tone decision for an utterance: prosody + text fused.
     * Returns Bengali label put straight into ArohiBus.userTone.
     */
    fun classify(text: String, prosody: Prosody): String {
        val t = text.trim()
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wps = if (prosody.speakSeconds > 0.5f) words.size / prosody.speakSeconds else 0f
        val exclam = t.count { it == '!' } + t.count { it == '।' && false }
        val question = t.contains('?') || t.containsAny("কেন", "কীভাবে", "কী", "কি", "কোথায়", "why", "how", "what")
        val capsWords = words.count { w -> w.length > 2 && w.all { it.isUpperCase() && it.isLetter() } }

        // --- text-first hard signals ---
        if (capsWords >= 2 || exclam >= 2) return publish("উত্তেজিত")
        if (textHasSad(t)) return publish("বিষণ্ণ")
        if (textHasAngry(t)) return publish("রাগী")

        // --- prosody-based classification (real mic energy) ---
        val loud = prosody.avgEnergy >= 0.42f || prosody.peakEnergy >= 0.85f
        val quiet = prosody.avgEnergy in 0.02f..0.16f && prosody.peakEnergy < 0.5f
        val fast = wps >= 3.2f
        val slow = wps in 0.001f..1.3f && words.size >= 4
        val swingy = prosody.variance > 0.09f

        return publish(when {
            loud && fast -> "উত্তেজিত"                       // excited / hyped
            loud && swingy -> "রাগী"                          // shouting-ish, irritated
            quiet && slow -> "বিষণ্ণ"                         // low, tired, gloomy
            quiet && question -> "কৌতূহলী"                    // softly asking
            !loud && !quiet -> "শান্ত"                        // calm conversation
            else -> "স্বাভাবিক"
        })
    }

    /** Text-only fallback when no prosody was captured (typed input). */
    fun classifyText(text: String): String {
        val t = text.lowercase()
        return publish(when {
            textHasAngry(t) -> "রাগী"
            textHasSad(t) -> "বিষণ্ণ"
            t.endsWith("!!") || t.count { it == '!' } >= 2 -> "উত্তেজিত"
            t.containsAny("হাহা", "haha", "lol", "মজা") -> "খেলা-ধূলা"
            else -> "স্বাভাবিক"
        })
    }

    private fun publish(label: String): String {
        ArohiBus.userTone.value = label
        return label
    }

    private val sadWords = listOf("মন খারাপ", "কষ্ট", "কান্না", "দুঃখ", "একা", "হারিয়ে", "মিস", "মিসিং",
        "কেদ", "ব্যথা", "sad", "lonely", "miss you", "depressed", "ব্যথা লাগছে", "ভালো লাগছে না", "হতাশ")
    private val angryWords = listOf("রাগ", "বিরক্ত", "ঝামেলা", "না লাগবে", "ঘটিয়া", "বোকা", "মূর্খ",
        "শুটআপ", "stupid", "idiot", "angry", "hate", "শত্রু", "বাজে", "খারাপ লাগছে")

    private fun textHasSad(t: String) = sadWords.any { t.contains(it) }
    private fun textHasAngry(t: String) = angryWords.any { t.contains(it) }

    private fun String.containsAny(vararg keys: String): Boolean = keys.any { contains(it, true) }
}
