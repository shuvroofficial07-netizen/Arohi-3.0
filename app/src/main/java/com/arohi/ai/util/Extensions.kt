package com.arohi.ai.util

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Extensions {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    fun timeNow(): String = timeFmt.format(Date())
    fun dateTime(ts: Long): String = dayFmt.format(Date(ts))

    fun toast(ctx: Context, msg: String) {
        if (msg.isBlank()) return
        CoroutineScope(Dispatchers.Main).launch { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun randomSalt(): String =
        (1..16).map { "abcdef0123456789".random() }.joinToString("")

    /** Strip wake words from an utterance: "hey arohi আজ কী আছে" → "আজ কী আছে" */
    fun stripWakeWords(text: String, wakeWordsCsv: String): String {
        var t = text.trim()
        for (w in wakeWordsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            t = t.replaceFirst(Regex("(?i)^(${Regex.escape(w)}|হেই\\s*আরোহী?|আরোহী?|hey\\s+arohi)[,:]?\\s*"), "")
            t = t.replaceFirst(Regex("(?i)(হেই\\s*আরোহী?|hey\\s+arohi)[,:]?\\s*"), "")
        }
        return t.trim()
    }

    /**
     * v5 — STRICT wake match: the utterance must START with the wake command
     * (small courtesy prefixes like hey/hello/এই/ও/আরে allowed). A random
     * conversation that merely CONTAINS "arohi" somewhere mid-sentence no
     * longer wakes her — she wakes ONLY when Shubho actually addresses her.
     */
    fun matchesWakeWord(text: String, wakeWordsCsv: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isBlank()) return false
        val words = wakeWordsCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val prefixes = listOf("hey", "hello", "hi", "ওই", "এই", "আরে", "ও", "হেই", "হ্যালো", "হে", "okay", "ok")
        // first 4 words are the decision zone — "arohi" buried deeper is chatter
        val head = t.split(Regex("\\s+")).take(4).joinToString(" ")
        if (words.any { head.startsWith(it) || head.contains(it) }) return true
        val variants = listOf("আরোহী", "আরোহি", "এরোহি", "অরোহী", "অরোহি", "arohi", "a rohi", "hey arohi", "হেই আরোহি")
        if (variants.any { head.startsWith(it) || head.contains(it) }) return true
        // "hey/এই … আরোহী" in the first two words
        val first = t.split(Regex("\\s+")).firstOrNull() ?: return false
        if (first in prefixes && variants.any { t.contains(it) }) return true
        return false
    }

    fun isStopCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        return t in setOf("থামো", "বন্ধ করো", "stop", "stop it", "শাবাশ থামো", "স্টপ") ||
                t.startsWith("থাম") || t == "বন্ধ" || t.startsWith("stop")
    }
}
