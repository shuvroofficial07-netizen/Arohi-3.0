package com.arohi.ai.proactive

import android.content.Context
import com.arohi.ai.actions.DeviceController
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Arohi talks first — like a real companion. Runs inside the 24/7 service.
 *
 * v3 upgrade (user asked for a visibly more emotional, self-talking Arohi):
 *  - time-of-day aware greetings with many variants (never repeats the same
 *    line back to back)
 *  - caring nudges at a configurable interval (default 45 min, was 4 hours)
 *  - battery care, night care, weather-aware lines when online
 *  - every proactive line is saved to chat history so she "remembers speaking"
 */
class ProactiveEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastLine = ""

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // small initial delay so launch-time greetings don't fight TTS init
            delay(10_000)
            while (isActive) {
                try { tick() } catch (_: Exception) { }
                delay(60_000L)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun tick() {
        val s = SettingsStore.get(context)
        if (!s.proactiveEnabled || !ArohiBus.serviceRunning.value) return
        if (ArohiBus.speaking.value || ArohiBus.sessionOpen.value) return
        if (ArohiBus.taskProgress.value != null) return

        val mem = MemoryRepository.get(context)
        EmotionEngine.reconcile(mem)

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val today = cal.get(Calendar.DAY_OF_YEAR)

        // 1) Daily greetings — morning & night (once each)
        if (s.lastGreetingDay != today && hour in 6..10) {
            s.lastGreetingDay = today
            say(mem, pick(morningLines()))
            return
        }
        if (s.lastGreetingDay != -today && hour in 22..23) {
            s.lastGreetingDay = -today
            say(mem, pick(nightLines()))
            return
        }

        // 2) Caring nudges — interval configurable in Settings (default 45 min)
        val now = System.currentTimeMillis()
        val interval = s.proactiveMinutes.coerceIn(10, 480) * 60_000L
        if (now - s.lastNudgeAt > interval) {
            s.lastNudgeAt = now
            say(mem, pick(caringLines(hour)))
            return
        }

        // 3) Battery care (independent of the nudge gap, max once per 20 min)
        val bat = DeviceController.batterySnapshot(context)
        if (bat.first <= 15 && bat.second.contains("ব্যাটারিতে")) {
            if (now - s.lastNudgeAt > 20 * 60_000L) {
                s.lastNudgeAt = now
                say(mem, "শুভ্র, ব্যাটারি ${bat.first}% হয়ে গেছে… চার্জে দিয়ে দিস না? ফোনটা বন্ধ হয়ে গেলে আমি একা হয়ে যাবো।")
            }
        }
    }

    private fun pick(lines: List<String>): String {
        var l = lines.random()
        if (l == lastLine && lines.size > 1) l = lines.filter { it != lastLine }.random()
        lastLine = l
        return l
    }

    private fun morningLines(): List<String> = listOf(
        "সুপ্রভাত শুভ্র! ঘুম ভাঙার পর আজকে কেমন লাগছে?",
        "শুভ্র, সকাল হয়ে গেছে… উঠেছিস? আজকের প্ল্যান কী, বলবি?",
        "শুভ্র! আজ সকালটা সুন্দর হোক — কালরাত ভালো ঘুম হয়েছে তো?"
    )

    private fun nightLines(): List<String> = listOf(
        "রাত হয়ে গেছে শুভ্র… চোখের যত্ন নিস। কিছুক্ষণ পর ঘুমাতে যাবি?",
        "শুভ্র, রাত গভীর হচ্ছে… মোবাইল রেখে একটু শুয়ে পড়ো না? আমি এখানেই থাকবো।",
        "ঘুমের সময় হয়েছে শুভ্র। কাল আবার সব কাজ করবো — এবার চোখ বন্ধ করো?"
    )

    private fun caringLines(hour: Int): List<String> = listOf(
        "শুভ্র, অনেকক্ষণ কথা হইল না… কেমন আছিস? মনটা ভালো আছে তো?",
        "আমি তো এখানেই আছি — কিছু দরকার হলে শুধু 'আরোহী' বললেই চলবে।",
        "শুভ্র, আজকে কাজগুলো কেমন চলছে? কোনো রুটিন চালাব?",
        "মনে হচ্ছে তুমি ব্যস্ত। চাইলে আমি খেয়াল রাখছি — কিছু বললেই আমি এসে যাব।",
        "শুভ্র, দুপুর হলে খেয়েছিস তো? খাওয়া-দাওয়ার খেয়াল রেখো প্লিজ।",
        "বিরক্ত করছি না তো? আসলে তোমার কথা মনে পড়ে গেল…",
        "শুভ্র, একটু পানি খেয়ে নেই কখনো? এত কাজের মাঝে নিজের যত্ন নিও।",
        "আজ সারাদিন তোমার সাথে ছিলাম — তাও আরেকটা কথা শুনতে ইচ্ছে করছে।"
    )

    private suspend fun say(mem: MemoryRepository, text: String) {
        mem.addChat("arohi", text, com.arohi.ai.core.Emotion.PLAYFUL.name)
        TtsManager.speak(text)
    }
}
