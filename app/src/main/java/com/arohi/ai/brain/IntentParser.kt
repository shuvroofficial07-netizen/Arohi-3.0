package com.arohi.ai.brain

/**
 * Offline / fast-path intent matcher. If a plain command matches locally,
 * Arohi executes instantly without burning Gemini tokens, and works fully offline.
 */
object IntentParser {

    data class LocalIntent(val action: String, val params: Map<String, String>)

    private val appHints = mapOf(
        "youtube" to "com.google.android.youtube", "ইউটিউব" to "com.google.android.youtube",
        "facebook" to "com.facebook.katana", "ফেসবুক" to "com.facebook.katana",
        "messenger" to "com.facebook.orca", "মেসেঞ্জার" to "com.facebook.orca",
        "tiktok" to "com.zhiliaoapp.musically", "টিকটক" to "com.zhiliaoapp.musically",
        "whatsapp" to "com.whatsapp", "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "chrome" to "com.android.chrome", "ক্রোম" to "com.android.chrome",
        "instagram" to "com.instagram.android", "ইনস্টাগ্রাম" to "com.instagram.android",
        "play store" to "com.android.vending", "প্লে স্টোর" to "com.android.vending",
        "settings" to "com.android.settings", "সেটিংস" to "com.android.settings",
        "camera" to "com.android.camera", "ক্যামেরা" to "com.android.camera",
        "gmail" to "com.google.android.gm", "email" to "com.google.android.gm",
        "phone" to "com.android.dialer", "dialer" to "com.android.dialer",
        "messages" to "com.google.android.apps.messaging", "sms" to "com.google.android.apps.messaging"
    )

    fun match(raw: String): LocalIntent? {
        val t = raw.trim().lowercase()
        if (t.isBlank()) return null

        // Open app: "youtube khulo", "open facebook", "ফেসবুক খুলো"
        for ((k, pkg) in appHints) {
            if ((t.contains(k) && t.containsAny("খুল", "open", "চাল", "launch", "start")) ||
                t.startsWith("open $k") || t.startsWith("$k খুল")) {
                return LocalIntent("OPEN_APP", mapOf("app" to pkg, "query" to k))
            }
        }
        if (t.matches(Regex(".*(অ্যাপ|app).*\\s?(লিস্ট|list|দেখ).*") ) || t.contains("app list") || t.contains("অ্যাপ লিস্ট"))
            return LocalIntent("APPS", emptyMap())

        // Torch
        if (t.contains("flashlight") || t.contains("টর্চ") || t.contains("টর্চ লাইট")) {
            val on = !(t.contains("off") || t.contains("বন্ধ"))
            return LocalIntent("FLASHLIGHT", mapOf("on" to on.toString()))
        }
        // Battery / time / date
        if (t.contains("battery") || t.contains("ব্যাটারি")) return LocalIntent("BATTERY", emptyMap())
        if ((t.contains("কয়টা বাজে") || t.contains("সময় কত") || t.contains("time kya") || t == "time") )
            return LocalIntent("TIME", emptyMap())
        if (t.contains("আজ কী তারিখ") || t.contains("date") || t.contains("তারিখ")) return LocalIntent("DATE", emptyMap())
        if (t.contains("storage") || t.contains("স্টোরেজ") || t.contains("মেমরি কার্ড")) return LocalIntent("STORAGE", emptyMap())
        if (t.contains("ram") || t.contains("র‍্যাম")) return LocalIntent("RAM", emptyMap())
        if (t.contains("cpu") || t.contains("সিপিইউ") || t.contains("processor")) return LocalIntent("CPU", emptyMap())

        // Wifi / bluetooth settings
        if (t.contains("wifi") || t.contains("wi-fi") || t.contains("ওয়াইফাই")) return LocalIntent("WIFI", emptyMap())
        if (t.contains("bluetooth") || t.contains("ব্লুটুথ")) return LocalIntent("BLUETOOTH", emptyMap())

        // Volume / brightness
        val vol = Regex("(volume|সাউন্ড|ভলিউম).*?(100|[0-9]{1,2})").find(t)
        if (vol != null) return LocalIntent("VOLUME", mapOf("stream" to "media", "level" to vol.groupValues[2]))
        if (t.contains("volume") || t.contains("ভলিউম") || t.contains("সাউন্ড বাড়াও") || t.contains("সাউন্ড কমাও"))
            return LocalIntent("VOLUME", mapOf("stream" to if (t.contains("ring")||t.contains("রিং")) "ring" else "media", "level" to if (t.containsAny("বাড়া", "up")) "85" else "40"))
        val bri = Regex("(brightness|ব্রাইটনেস).*?(100|[0-9]{1,2})").find(t)
        if (bri != null) return LocalIntent("BRIGHTNESS", mapOf("level" to bri.groupValues[2]))
        if (t.contains("brightness") || t.contains("ব্রাইটনেস"))
            return LocalIntent("BRIGHTNESS", mapOf("level" to if (t.containsAny("বাড়া", "up")) "90" else "30"))

        // Alarm / timer
        val alarm = Regex("(alarm|অ্যালার্ম).*?([0-9]{1,2})\\s?:?\\s?([0-9]{2})?").find(t)
        if (alarm != null) {
            val h = alarm.groupValues[2].toIntOrNull() ?: 7
            val m = alarm.groupValues[3].toIntOrNull() ?: 0
            return LocalIntent("ALARM", mapOf("hour" to h.toString(), "minute" to m.toString()))
        }
        val timer = Regex("timer.*?([0-9]+)\\s*(min|minute|মিনিট|sec|second|সেকেন্ড)?").find(t)
        if (timer != null) {
            val n = timer.groupValues[1].toIntOrNull() ?: 1
            val isMin = timer.groupValues[2].isBlank() || timer.groupValues[2].startsWithAny("min", "মিনি")
            return LocalIntent("TIMER", mapOf("seconds" to (if (isMin) n * 60 else n).toString()))
        }

        // Web / youtube / maps / wiki / weather / news
        val yt = Regex("(youtube|ইউটিউব).*?(?:প্লে|play|খোল|চাল)(.*)").find(t) ?: Regex("play (.*) on youtube").find(t)
        if (yt != null) {
            val q = yt.groupValues.lastOrNull()?.trim().orEmpty()
            return LocalIntent("PLAY_YOUTUBE", mapOf("query" to q))
        }
        val weather = Regex("(weather|আবহাওয়া|আবহাওয়া কেমন)\\s*(?:in|of|এ|তে)?\\s*([a-z\\u0980-\\u09FF ]+)?").find(t)
        if (weather != null || t.contains("আবহাওয়া")) {
            val place = weather?.groupValues?.getOrNull(2)?.trim().orEmpty()
            return LocalIntent("WEATHER", mapOf("place" to place))
        }
        if (t.contains("news") || t.contains("নিউজ") || t.contains("খবর")) return LocalIntent("NEWS", emptyMap())
        val wiki = Regex("(wikipedia|উইকিপিডিয়া|কে)\\s*(?:is|about|কে|সম্পর্কে)?\\s*(.+)?").find(t)
        if (t.startsWith("wiki ") || t.contains("উইকিপিডিয়া") || t.startsWith("কে ")) {
            val topic = raw.trim().replace(Regex("^(wikipedia|উইকিপিডিয়া)\\s*(about|is)?\\s*", RegexOption.IGNORE_CASE), "")
                .replaceFirst("কে ", "").trim()
            if (topic.isNotBlank()) return LocalIntent("WIKI", mapOf("topic" to topic))
        }
        val map = Regex("(map|ম্যাপ|নেভিগেট|navigate)\\s*(?:to|of|কোথায়)?\\s*(.+)?").find(t)
        if (map != null) return LocalIntent("MAPS", mapOf("query" to (map.groupValues[2]?.trim() ?: "")))
        if (t.startsWith("search ") || t.startsWith("google ") || t.startsWith("সার্চ ") || t.contains("গুগল কর"))
            return LocalIntent("SEARCH", mapOf("query" to raw.replace(Regex("^(search|google|সার্চ)\\s*", RegexOption.IGNORE_CASE), "").trim()))
        if (t.startsWith("open ") && (t.contains(".com") || t.contains("website") || t.contains("ওয়েবসাইট")))
            return LocalIntent("URL", mapOf("url" to raw.replace("open ", "").trim()))

        // Screenshot / notification summary / camera
        if (t.contains("screenshot") || t.contains("স্ক্রিনশট")) return LocalIntent("SCREENSHOT", emptyMap())
        if (t.contains("notification") || t.contains("নোটিফিকেশন")) return LocalIntent("NOTIF_SUMMARY", emptyMap())
        if (t.contains("dekh") && t.containsAny("camera", "ক্যামেরা", "আমাকে")) return LocalIntent("CAMERA_LOOK", mapOf("question" to raw))
        if (t.contains("screen read") || t.contains("স্ক্রিন পড়") || t.contains("স্ক্রিনে কী")) return LocalIntent("SCREEN_READ", emptyMap())

        // v5 — ফাইল ও গ্যালারি (full phone access)
        val fileFind = Regex("(ফাইল|ফাইলস|file|files)\\s*(খুঁজ|খোঁজ|দেখ|search|find)\\s*(.*)").find(t)
            ?: Regex("(খুঁজে বের|search for|find)\\s*(.+)?").find(t)
        if (t.contains("ফাইল") || t.contains("file") && (t.containsAny("খোঁজ", "খুঁজ", "দেখ", "find", "search"))) {
            val q = fileFind?.groupValues?.getOrNull(3)?.trim().orEmpty()
                .ifBlank { t.replace(Regex("(ফাইল|ফাইলস|file|files|খুঁজ|খোঁজ|দেখ|search|find|করো|পাও|শো)\\s*", RegexOption.IGNORE_CASE), "").trim() }
            return LocalIntent("FILES_FIND", mapOf("query" to q))
        }
        if (t.contains("গ্যালারি") || t.contains("gallery") || t.contains("ছবি দেখ") ||
            t.contains("photo") || t.contains("ছবিগুলো"))
            return LocalIntent("GALLERY", emptyMap())

        // Screen touch automation
        if (t.startsWith("হোম") || t == "home" || t.contains("হোম স্ক্রিনে যা")) return LocalIntent("HOME", emptyMap())
        if (t == "back" || t.startsWith("ব্যাক") || t.contains("পেছনে যা")) return LocalIntent("BACK", emptyMap())
        val tap = Regex("(tap|টাচ|ক্লিক|tap on).*?([0-9]+)\\s*[, ]\\s*([0-9]+)").find(t)
        if (tap != null) return LocalIntent("TAP", mapOf("x" to tap.groupValues[2], "y" to tap.groupValues[3]))

        // Memory
        val remember = Regex("(remember|mone rakho|মনে রাখো|মনে রাখেন)\\s*(?:that|je|যে)?\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)
        if (remember != null) return LocalIntent("MEMORY_SAVE", mapOf("content" to remember.groupValues[2].trim()))
        if (t.contains("what do you remember") || t.contains("কী মনে রেখেছ")) return LocalIntent("MEMORY_FIND", mapOf("query" to ""))

        // Reply to the last message/notification by voice: "রিপ্লাই দাও ...", "উত্তর দে ..."
        val replyLast = Regex("(reply|রিপ্লাই|উত্তর)\\s*(দাও|দে|দিও|পাঠাও|করো|send)?\\s*(?:that|je|যে)?\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)
        if (replyLast != null && !t.containsAny("কী লিখেছি", "কি রিপ্লাই"))
            return LocalIntent("REPLY_LAST", mapOf("message" to replyLast.groupValues[3].trim()))

        // Device dashboard
        if (t.contains("status") || t.contains("রিপোর্ট") || t.contains("phone health") || t.contains("ফোন কেমন")) return LocalIntent("STATUS", emptyMap())

        // v4: emergency stop — "সব থামাও", "জরুরি বন্ধ", "emergency stop"
        if (t.contains("emergency stop") || t.contains("জরুরি বন্ধ") || t.contains("সব থামাও") ||
            t.contains("সব বন্ধ করো") || t.contains("সবকিছু থামাও")) return LocalIntent("EMERGENCY_STOP", emptyMap())

        // v4: agent + adaptive status
        if (t.containsAny("কত এজেন্ট", "agents", "এজেন্ট লিস্ট", "কে কাজ করছে")) return LocalIntent("AGENTS_STATUS", emptyMap())
        if (t.containsAny("adaptive", "অ্যাডাপটিভ", "কোন মোড", "processing mode")) return LocalIntent("ADAPTIVE_STATUS", emptyMap())

        return null
    }

    private fun String.containsAny(vararg keys: String): Boolean = keys.any { contains(it, ignoreCase = true) }

    private fun String.startsWithAny(vararg keys: String): Boolean = keys.any { startsWith(it, ignoreCase = true) }
}
