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

    /**
     * v7 — STRICT wake match, now actually strict (unit tested).
     *
     * v6 documented "must START with the wake command" but implemented
     * `head.contains(word)` across the first four words. So an ordinary sentence
     * such as "আমি ভাবছিলাম আরোহী নামটা কোথা থেকে এলো" woke her — precisely the
     * false-wake behaviour the spec forbids. A unit test now pins this down.
     *
     * Rule: after an optional courtesy prefix (hey/hello/এই/আরে/…), the very
     * next token(s) must BE a wake variant. Everything else is conversation.
     */
    fun matchesWakeWord(text: String, wakeWordsCsv: String): Boolean =
        wakeMatchIndex(text, wakeWordsCsv) >= 0

    /** Character index where the wake phrase begins, or -1. */
    private fun wakeMatchIndex(text: String, wakeWordsCsv: String): Int {
        val t = normaliseSpeech(text)
        if (t.isEmpty()) return -1
        if (wakeVariants(wakeWordsCsv).any { t == it || t.startsWith("$it ") }) return 0
        for (p in COURTESY) {
            if (!t.startsWith("$p ")) continue
            val rest = t.removePrefix("$p ").trimStart()
            if (wakeVariants(wakeWordsCsv).any { rest == it || rest.startsWith("$it ") }) {
                return p.length + 1
            }
        }
        return -1
    }

    /**
     * Strip the wake phrase so the command reaches the brain clean:
     * "হেই আরোহি, আজ কী আছে" → "আজ কী আছে". Uses the same matcher, so accept
     * and strip can never disagree.
     */
    fun stripWakeWords(text: String, wakeWordsCsv: String): String {
        if (wakeMatchIndex(text, wakeWordsCsv) < 0) return text.trim()
        var out = normaliseSpeech(text)
        for (p in COURTESY) {
            if (!out.startsWith("$p ")) continue
            val rest = out.removePrefix("$p ").trimStart()
            if (wakeVariants(wakeWordsCsv).any { rest == it || rest.startsWith("$it ") }) {
                out = rest
                break
            }
        }
        for (v in wakeVariants(wakeWordsCsv)) {   // longest first
            if (out == v) return ""
            if (out.startsWith("$v ")) return out.removePrefix(v).trimStart().trim()
        }
        return out.trim()
    }

    /** Courtesy prefixes a person may put in front of the wake word. */
    private val COURTESY = listOf(
        "hey", "hello", "hi", "okay", "ok", "oi",
        "এই", "ওই", "হেই", "হ্যালো", "আরে", "হে", "ও"
    )

    /** Spelled variants seen in real Bengali/English speech recognition output. */
    private fun wakeVariants(wakeWordsCsv: String): List<String> =
        (listOf(
            "আরোহী", "আরোহি", "এরোহি", "অরোহী", "অরোহি", "আরহি", "আরুহি",
            "arohi", "a rohi", "aro hi"
        ) + wakeWordsCsv.split(","))
            .map { normaliseSpeech(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }

    private fun normaliseSpeech(s: String): String = s.lowercase()
        .replace(Regex("[,।!?…:;\"'’“”]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun isStopCommand(text: String): Boolean {
        val t = text.lowercase().trim()
            .replace(Regex("[!।,.?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty()) return false
        val exact = setOf("থামো", "থাম", "বন্ধ করো", "বন্ধ", "শাবাশ থামো", "স্টপ",
            "stop", "stop it", "stop now", "shut up", "thamo")
        if (t in exact) return true
        val words = t.split(" ")
        val lead = words.first()
        val stopLeads = setOf("থামো", "থাম", "স্টপ", "stop", "শান্ত", "চুপ")
        if (lead in stopLeads && words.size <= 4) {
            // "stop talking", "থামো একটু" → stop.  "stop the music later" → not.
            return true
        }
        return false
    }
}
