package com.arohi.ai.agents

import android.content.Context
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.core.SecureStore

/**
 * v4 — Multi-Agent AI System.
 *
 * A central orchestrator sits ABOVE the capability modules (device control,
 * memory, vision, messaging…) and exposes them as named agents. Each agent:
 *  - owns a specific responsibility
 *  - declares what it needs (permission/config) so the UI can show real readiness
 *  - routes a user request to the right agent (exact-match first, keyword next)
 *
 * The orchestrator publishes the active agent to ArohiBus so every screen can
 * show "কে কাজ করছে" — full transparency, no hidden behaviour.
 */
object AgentOrchestrator {

    enum class Duty { ASSIST, RESEARCH, MEMORY, DEVICE, VISION, AUTOMATION, NOTIFICATION, MESSAGING, SECURITY, LEARNING, FILES, DIAGNOSTICS }

    data class Agent(
        val name: String,
        val nameBn: String,
        val duty: Duty,
        val descriptionBn: String,
        val keywords: List<String>,
        val needsOnline: Boolean = false
    )

    val AGENTS = listOf(
        Agent("PersonalAssistant", "ব্যক্তিগত সহকারী", Duty.ASSIST,
            "সাধারণ কথোপকথন, প্রশ্নোত্তর, পরামর্শ", listOf(), needsOnline = true),
        Agent("Research", "রিসার্চ", Duty.RESEARCH,
            "সার্চ, আবহাওয়া, খবর, উইকিপিডিয়া, ম্যাপ",
            listOf("সার্চ", "search", "google", "weather", "আবহাওয়া", "news", "খবর",
                "wiki", "উইকি", "map", "ম্যাপ"), needsOnline = true),
        Agent("Memory", "স্মৃতি", Duty.MEMORY,
            "মনে রাখা, খোঁজা, আপডেট, ব্যাকআপ",
            listOf("মনে রাখ", "remember", "মনে আছে", "মনে রেখেছ", "memory", "স্মৃতি")),
        Agent("DeviceControl", "ডিভাইস কন্ট্রোল", Duty.DEVICE,
            "টর্চ, ভলিউম, ব্রাইটনেস, ব্যাটারি, অ্যালার্ম, অ্যাপ",
            listOf("টর্চ", "torch", "flashlight", "volume", "ভলিউম", "brightness", "ব্রাইটনেস",
                "battery", "ব্যাটারি", "alarm", "অ্যালার্ম", "timer", "টাইমার", "খুল", "open",
                "wifi", "ওয়াইফাই", "bluetooth", "ব্লুটুথ")),
        Agent("Vision", "ভিশন", Duty.VISION,
            "ক্যামেরা দেখা, ছবি বিশ্লেষণ, OCR, ডকুমেন্ট",
            listOf("দেখ", "camera", "ক্যামেরা", "ছবি", "image", "photo", "scan", "স্ক্যান",
                "screenshot", "স্ক্রিনশট"), needsOnline = true),
        Agent("Automation", "অটোমেশন", Duty.AUTOMATION,
            "মাল্টি-স্টেপ টাস্ক, রুটিন, স্ক্রিন টাচ",
            listOf("plan", "প্ল্যান", "রুটিন", "routine", "টাচ", "tap", "swipe", "সোয়াইপ",
                "ব্যাক", "back", "হোম", "automation")),
        Agent("Notification", "নোটিফিকেশন", Duty.NOTIFICATION,
            "নোটিফিকেশন পড়া, সামারি, ঘোষণা",
            listOf("notification", "নোটিফিকেশন", "সামারি", "summary", "কী খবর")),
        Agent("Messaging", "মেসেজিং", Duty.MESSAGING,
            "মেসেজ পাঠানো, রিপ্লাই, ড্রাফট",
            listOf("message", "মেসেজ", "রিপ্লাই", "reply", "send", "পাঠাও", "whatsapp",
                "হোয়াটসঅ্যাপ", "messenger", "telegram", "টেলিগ্রাম", "sms")),
        Agent("Security", "সিকিউরিটি", Duty.SECURITY,
            "অ্যাপ লক, অনুমতি, প্রাইভেসি",
            listOf("lock", "লক", "pin", "পিন", "নিরাপত্তা", "security", "emergency", "জরুরি")),
        Agent("Learning", "লার্নিং", Duty.LEARNING,
            "তোমার পছন্দ-অভ্যাস শেখা ও মানিয়ে চলা",
            listOf("পছন্দ", "আমি চাই", "ভবিষ্যতে", "সবসময়", "কখনো", "শেখো", "learn")),
        Agent("FileManager", "ফাইল ম্যানেজার", Duty.FILES,
            "ডাউনলোড, ফাইল খোঁজা, স্টোরেজ",
            listOf("file", "ফাইল", "download", "ডাউনলোড", "স্টোরেজ", "storage", "গান খুঁজ")),
        Agent("SystemDiagnostics", "সিস্টেম ডায়াগনস্টিকস", Duty.DIAGNOSTICS,
            "সিস্টেম হেলথ চেক, স্ট্যাটাস রিপোর্ট, সমস্যা নির্ণয়",
            listOf("health", "হেলথ", "স্ট্যাটাস", "status", "diagnose", "ডায়াগ", "সমস্যা",
                "রিপোর্ট", "report", "কেমন আছ", "চলছে কি না"), needsOnline = false)
    )

    /** Picks the agent that should own [text]; default = PersonalAssistant. */
    fun route(text: String): Agent {
        val t = text.lowercase().trim()
        // Non-default agents match only when their keywords hit; the assistant is the fallback.
        var best: Agent? = null
        var bestScore = 0
        for (a in AGENTS) {
            if (a.duty == Duty.ASSIST) continue
            val score = a.keywords.count { t.contains(it) }
            if (score > bestScore) { bestScore = score; best = a }
        }
        return best ?: AGENTS.first()
    }

    /** Publishes the routing decision to the bus (UI transparency). */
    fun publish(text: String) {
        val agent = route(text)
        ArohiBus.activeAgent.value = "${agent.nameBn} — ${agent.descriptionBn}"
    }

    fun clearActive() { ArohiBus.activeAgent.value = "" }

    /**
     * Real readiness of each agent: config + permissions it needs right now.
     * The UI shows this — nothing is claimed working without its prerequisites.
     */
    fun readinessBn(context: Context, agent: Agent): Pair<Boolean, String> {
        val c = context.applicationContext
        val s = SettingsStore.get(c)
        return when (agent.duty) {
            Duty.ASSIST -> if (!com.arohi.ai.brain.ArohiBrain.anyConfigured(c))
                false to "ব্রেইন কী দরকার (API CENTER-এ Gemini/OpenAI)"
            else if (!com.arohi.ai.core.NetworkMonitor.isOnline) false to "ইন্টারনেট দরকার"
            else true to "প্রস্তুত"
            Duty.RESEARCH -> if (!com.arohi.ai.core.NetworkMonitor.isOnline) false to "ইন্টারনেট দরকার"
                else true to "প্রস্তুত"
            Duty.MEMORY -> if (!s.memoryEnabled) false to "মেমরি বন্ধ (MEMORY ট্যাব)"
                else true to "প্রস্তুত"
            Duty.DEVICE -> true to "প্রস্তুত"
            Duty.VISION -> if (!PermissionHelper.hasCamera(c)) false to "ক্যামেরা অনুমতি দরকার"
                else if (!com.arohi.ai.core.NetworkMonitor.isOnline) false to "ইন্টারনেট দরকার (বিশ্লেষণে)"
                else true to "প্রস্তুত"
            Duty.AUTOMATION -> if (!PermissionHelper.isAccessibilityEnabled(c))
                false to "অ্যাক্সেসিবিলিটি দরকার (স্ক্রিন টাচের জন্য)"
                else true to "প্রস্তুত"
            Duty.NOTIFICATION -> if (!PermissionHelper.isNotificationListenerEnabled(c))
                false to "নোটিফিকেশন লিসেনার দরকার"
                else true to "প্রস্তুত"
            Duty.MESSAGING -> when {
                !PermissionHelper.isNotificationListenerEnabled(c) -> false to "নোটিফিকেশন লিসেনার দরকার"
                !PermissionHelper.isAccessibilityEnabled(c) -> false to "অ্যাক্সেসিবিলিটি দরকার (রিপ্লাই পাঠাতে)"
                else -> true to "প্রস্তুত"
            }
            Duty.SECURITY -> true to "প্রস্তুত"
            Duty.LEARNING -> if (!s.memoryEnabled) false to "মেমরি বন্ধ"
                else true to "প্রস্তুত"
            Duty.FILES -> true to "প্রস্তুত"
            Duty.DIAGNOSTICS -> true to "প্রস্তুত (অফলাইনেও চলে)"
        }
    }
}
