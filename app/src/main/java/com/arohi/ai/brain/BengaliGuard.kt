package com.arohi.ai.brain

/**
 * v6 — BENGALI-ONLY GUARD (user bug: "সে বারবার ইংরেজিতে কথা বলছে"):
 *
 * System prompt ইতিমধ্যে বাংলা-কেবল নির্দেশ দেয়, কিন্তু মডেল মাঝে মাঝে
 * ইংরেজিতে উত্তর দিয়ে বসে (বিশেষত JSON-বহির্ভূত raw উত্তরে)। এই গার্ড
 * প্রতিটি উত্তরের লিপি যাচাই করে — ইংরেজি-প্রধান উত্তর হলে একবার
 * "সম্পূর্ণ বাংলায় আবার লেখো" নির্দেশ দিয়ে পুনঃরিকোয়েস্ট হয়।
 *
 * প্রযুক্তিগত শব্দ (YouTube, Wi-Fi, GPT, API…) ইংরেজি গণনায় পড়ে না —
 * সেগুলো বাংলা বাক্যের ভেতরে বৈধ।
 */
object BengaliGuard {

    private val LATIN_WORD = Regex("[A-Za-z]{2,}")

    // বাংলা বাক্যে চলে এমন প্রযুক্তিগত/সংক্ষিপ্ত শব্দ — এগুলো "ইংরেজি উত্তর" নয়
    private val TECHNICAL = setOf(
        "youtube", "wifi", "wi", "fi", "google", "gemini", "openai", "gpt", "api", "ai",
        "app", "apk", "sms", "sim", "cpu", "ram", "rom", "led", "hd", "usb", "vpn", "gps",
        "arohi", "ok", "okay", "hi", "hello", "no", "yes", "id", "qr", "ocr", "png", "jpg",
        "jpeg", "pdf", "url", "http", "https", "web", "link", "applock", "pin", "otp",
        "whatsapp", "facebook", "instagram", "messenger", "telegram", "snapchat", "twitter",
        "chrome", "gmail", "email", "paytm", "bkash", "nagad", "rocket", "phonepe",
        "android", "ios", "bluetooth", "hotspot", "miui", "oneui", "hyperos", "coloros"
    )

    private val BENGALI_START = '\u0980'
    private val BENGALI_END = '\u09FF'

    fun isBengaliChar(c: Char): Boolean = c in BENGALI_START..BENGALI_END

    /**
     * true হলে উত্তরটি ইংরেজি-প্রধান — বাংলাকরণ দরকার।
     * নিয়ম: বাংলা অক্ষর ≥৫ হলে বাংলা ধরে নেওয়া হয়; নাহলে ≥৩টি
     * অপ্রযুক্তিগত ইংরেজি শব্দ থাকলে সেটি ইংরেজি উত্তর।
     */
    fun isMostlyEnglish(text: String): Boolean {
        if (text.isBlank()) return false
        val bengaliLetters = text.count { isBengaliChar(it) }
        if (bengaliLetters >= 5) return false
        val words = LATIN_WORD.findAll(text)
            .map { it.value.lowercase() }
            .filter { it !in TECHNICAL && it.length >= 3 }
            .toList()
        return words.size >= 3
    }

    /** পুনঃরিকোয়েস্টের জন্য অতিরিক্ত নির্দেশ — মডেলকে জোর করে বাংলায় আনে। */
    const val FORCE_BENGALI_SUFFIX =
        "⚠️ গুরুত্বপূর্ণ: তোমার আগের উত্তরটি ইংরেজি হয়ে গিয়েছিল — এটা অনুমোদিত নয়। " +
        "একই উত্তর সম্পূর্ণ বাংলা ভাষায়, বাংলা লিপিতে আবার লেখো। প্রযুক্তিগত শব্দ " +
        "(YouTube, Wi-Fi ইত্যাদি) ছাড়া একটিও ইংরেজি শব্দ থাকবে না। ফরম্যাট আগের মতোই রাখো।"
}
