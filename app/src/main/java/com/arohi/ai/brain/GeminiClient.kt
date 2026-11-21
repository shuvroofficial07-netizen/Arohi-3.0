package com.arohi.ai.brain

import android.content.Context
import com.arohi.ai.core.NetworkMonitor
import com.arohi.ai.core.SecureStore
import com.arohi.ai.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v4.1 — GEMINI AUTO-CONNECTION (user spec, top priority):
 *
 *   Paste the Google AI Studio key → Save → EVERYTHING else is automatic.
 *   The user NEVER selects or types a model name.
 *
 * Pipeline (saveAndAutoConnect):
 *   1. CLEAN      — strips paste noise (spaces, newlines, quotes, "Bearer", "key=")
 *   2. FORMAT     — AIza… shape check before any network call
 *   3. AUTH       — real ListModels call = key validation + model discovery in ONE step
 *   4. DISCOVER   — ranks every generateContent-capable model THIS key can use
 *                   and auto-picks the best (stable flash first, no user input)
 *   5. TEST       — a REAL generateContent round-trip; on 404/model-not-found it
 *                   walks the fallback chain automatically (up to 5 candidates)
 *   6. STATUS     — the UI only ever shows a real, verified result
 *
 * Request-time self-healing (chat): if a model ever disappears (Google retires
 * names), the request rotates through the discovered fallback chain and heals
 * the saved config — HTTP 404 / invalid-model errors stop being fatal.
 */
object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Single endpoint truth (v1beta is the supported GA surface for these models). */
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** AUTO-SELECT ranking — best first. Stable flash models lead: fast, free-tier friendly. */
    private val PREFERRED_MODELS = listOf(
        "gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.0-flash-lite",
        "gemini-2.5-flash-lite", "gemini-1.5-flash", "gemini-1.5-flash-8b",
        "gemini-1.5-flash-002", "gemini-1.5-pro", "gemini-2.5-pro", "gemini-2.0-pro"
    )

    /** Never pick these even if the API lists them (not chat models). */
    private val EXCLUDED = listOf("embedding", "aqa", "imagen", "veo", "tts", "live",
        "native-audio", "image-generation", "gemma")

    data class Turn(val role: String, val text: String) // role: user | model

    // ================================================================ AUTO-CONNECT

    /** Full result of the auto-connect pipeline — every field is REAL. */
    data class AutoResult(
        val ok: Boolean,
        val stage: String,        // CLEAN | FORMAT | NETWORK | AUTH | DISCOVER | TEST
        val message: String,      // Bengali headline
        val detail: String,       // technical detail (HTTP codes etc.)
        val suggestion: String,   // Bengali concrete fix
        val model: String,        // the model chosen & verified ("" when failed)
        val modelCount: Int,      // how many usable models this key supports
        val models: List<String>  // the discovered usable models
    )

    /**
     * Strips the classic copy-paste noise so a "clean" paste always succeeds:
     * leading/trailing spaces, newlines, quotes, "Bearer ", "key=", "API key:".
     */
    fun cleanKey(raw: String): String {
        var k = raw.trim()
            .replace("\"", "").replace("'", "")
            .replace("\n", "").replace("\r", "").replace("\t", "")
        k = k.replace(Regex("(?i)^\\s*bearer\\s+"), "")
        k = k.replace(Regex("(?i)^\\s*key\\s*[=:]\\s*"), "")
        k = k.replace(Regex("(?i)^\\s*api[-_ ]?key\\s*[=:]\\s*"), "")
        return k.trim()
    }

    /**
     * v5 — UNIVERSAL KEY ACCEPTANCE (the "40-character key" bug):
     * Google AI Studio keys are typically AIza + 35 chars (39 total), but the
     * length varies (some accounts get 40+ chars). NO client-side prefix or
     * exact-length rejection any more — only obviously-broken pastes (too short
     * or still containing spaces) are stopped locally. EVERYTHING else — real
     * validity, real length — is decided by Google's server via ListModels.
     */
    fun keyLooksValid(key: String): Boolean =
        key.length >= 20 && key.none { it.isWhitespace() }

    /**
     * THE one-tap pipeline. Paste key → call this → connected or an exact
     * Bengali reason + fix. The key is saved even when the network is down so
     * the user never retypes it; when internet returns we auto-retry once.
     */
    suspend fun saveAndAutoConnect(
        context: Context,
        pastedKey: String,
        onStage: (suspend (String) -> Unit)? = null
    ): AutoResult = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        suspend fun stage(s: String) { onStage?.invoke(s) }

        // ---- 1. CLEAN ----
        stage("১/৫ — কী পরিষ্কার করা হচ্ছে…")
        val key = cleanKey(pastedKey)
        if (key.isBlank())
            return@withContext AutoResult(false, "CLEAN", "কী খালি — কিছু পেস্ট করা হয়নি",
                "input is empty", "Google AI Studio থেকে কী কপি করে নিচে পেস্ট করুন", "", 0, emptyList())

        // ---- 2. FORMAT ----
        // v5: কোনো দৈর্ঘ্য/উপসর্গ দাবি নেই — ৩৯, ৪০ বা যত অক্ষরেরই হোক, সম্পূর্ণ কী
        // পেস্ট করলেই এগোবে; প্রকৃত বৈধতা Google সার্ভারই বলবে (ListModels)।
        stage("২/৫ — কী ফরম্যাট যাচাই…")
        if (!keyLooksValid(key))
            return@withContext AutoResult(false, "FORMAT",
                if (key.length < 20) "কী খুব ছোট (${key.length} অক্ষর) — সম্পূর্ণ কী কপি হয়নি"
                else "কী-র ভেতরে স্পেস আছে — সম্পূর্ণ কী একবারে কপি হয়নি",
                "got ${key.length} chars, starts '${key.take(4)}'",
                "aistudio.google.com/apikey → কী-র পাশের Copy বাটনে চাপ দিয়ে সম্পূর্ণ কী পেস্ট করুন (৩৯-৪০ অক্ষর, AIza দিয়ে শুরু); স্পেস/লাইনব্রেক আমি নিজেই পরিষ্কার করে নিই",
                "", 0, emptyList())

        // Save NOW — network failures must never force a retype.
        SecureStore.get(appCtx).geminiKey = key

        // ---- 3. NETWORK ----
        if (!NetworkMonitor.isOnline) {
            registerReconnectAutoRetry(appCtx)
            return@withContext AutoResult(false, "NETWORK",
                "ইন্টারনেট অফলাইন — কী সেভ হয়েছে, কিন্তু যাচাই হয়নি",
                "no connectivity (${NetworkMonitor.networkLabel})",
                "ইন্টারনেট চালু করুন — ফিরলেই আমি নিজে নিজে কানেকশন সম্পন্ন করবো",
                "", 0, emptyList())
        }

        // ---- 4. AUTH + DISCOVER (ListModels validates the key for real) ----
        stage("৩/৫ — Google সার্ভারে কী যাচাই + মডেল খোঁজা…")
        val listed = listModelsRaw(appCtx, key)
        if (!listed.ok)
            return@withContext AutoResult(false, "AUTH", authFailMessage(listed.code, listed.body),
                "HTTP ${listed.code}: ${listed.body.take(200)}",
                authFailSuggestion(listed.code), "", 0, emptyList())

        val usable = rankModels(listed.models)
        if (usable.isEmpty())
            return@withContext AutoResult(false, "DISCOVER",
                "এই কী দিয়ে কোনো চ্যাট মডেল পাওয়া যায়নি",
                "ListModels ok but 0 generateContent gemini models",
                "নতুন কী নিন — aistudio.google.com/apikey", "", 0, emptyList())

        // Save the whole fallback chain + the best model.
        val s = SettingsStore.get(appCtx)
        s.geminiModelsCache = usable.joinToString(",")
        s.geminiModel = usable.first()

        // ---- 5. REAL TEST with automatic fallback chain ----
        stage("৪/৫ — সেরা মডেল নির্বাচন: ${s.geminiModel} — বাস্তব কানেকশন টেস্ট…")
        val tried = mutableListOf<String>()
        var lastErr: Throwable? = null
        for ((idx, model) in usable.take(5).withIndex()) {
            if (idx > 0) stage("৪/৫ — ${tried.lastOrNull() ?: "মডেল"} কাজ করেনি — ফলব্যাক মডেল: $model…")
            tried.add(model)
            val r = rawChat(appCtx, key, model,
                "You are a ping checker. Reply with exactly: pong", emptyList(), "ping", 2048)
            if (r.isSuccess) {
                s.geminiModel = model          // persist the verified model
                s.geminiVerified = true
                s.geminiVerifiedAt = com.arohi.ai.util.Extensions.timeNow()
                val cnt = usable.size
                return@withContext AutoResult(true, "TEST",
                    "কানেকশন সফল ✓ — মডেল অটো-নির্বাচিত: $model ($cnt টি মডেল পাওয়া গেছে)",
                    "verified with real generateContent; candidates ${tried.joinToString("→")}",
                    if (idx == 0) "সবকিছু প্রস্তুত — এখনই আরোহীর সাথে কথা বলুন!"
                    else "মূল মডেল বন্ধ ছিল, ফলব্যাক $model নিজে থেকে বসানো হয়েছে",
                    model, cnt, usable)
            }
            val e = r.exceptionOrNull()
            lastErr = e
            // 404 / not-found / invalid-model / empty-output → try the NEXT candidate;
            // auth/quota/network errors are NOT model problems → stop, real reason shown
            val raw = e?.message ?: ""
            if (!(raw.contains("404") || raw.contains("not found", true) ||
                        raw.contains("not supported", true) || raw.contains("400") ||
                        raw.contains("empty response"))) break
        }
        s.geminiVerified = false
        AutoResult(false, "TEST",
            humanError(lastErr ?: IllegalStateException("unknown")),
            "tried: ${tried.joinToString("→")} — ${lastErr?.message?.take(200) ?: ""}",
            fixHint(lastErr ?: IllegalStateException("unknown")), "", usable.size, usable)
    }

    /** One-shot auto-retry when internet returns after an offline save. */
    private fun registerReconnectAutoRetry(ctx: Context) {
        NetworkMonitor.addReconnectListener("gemini_autoconnect") {
            CoroutineScope(Dispatchers.IO).launch {
                val key = SecureStore.get(ctx).geminiKey
                if (key.isNotBlank() && !SettingsStore.get(ctx).geminiVerified)
                    saveAndAutoConnect(ctx, key)
                NetworkMonitor.removeReconnectListener("gemini_autoconnect")
            }
        }
    }

    // ================================================================ chat

    /** Generate a reply. [imageBase64] optional (Gemini Vision). Returns raw text. */
    suspend fun chat(
        context: Context,
        system: String,
        history: List<Turn>,
        userText: String,
        imageBase64: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val s = SettingsStore.get(context)
        val key = SecureStore.get(context).geminiKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("no_key"))
        // v5: কী-র দৈর্ঘ্য/ফরম্যাট নিয়ে ক্লায়েন্ট-সাইড রিজেকশন নেই — Google সার্ভারই
        // প্রকৃত বৈধতা বলবে; ভুল হলে AUTH ধাপের বাংলা ব্যাখ্যা দেখাবে।
        if (!NetworkMonitor.isOnline) return@withContext Result.failure(IllegalStateException("offline"))

        // fallback chain: saved model first, then the discovered cache, then defaults
        val chain = buildModelChain(s)
        var lastErr: Throwable? = null
        for ((idx, model) in chain.withIndex()) {
            try {
                val r = rawChat(context, key, model, system, history, userText, 2048, imageBase64)
                r.fold(onSuccess = {
                    if (model != s.geminiModel) {
                        // self-heal: this model works, remember it
                        s.geminiModel = model
                        s.geminiVerified = true
                    }
                    return@withContext Result.success(it)
                }, onFailure = { e ->
                    lastErr = e
                    val raw = e.message ?: ""
                    val modelProblem = raw.contains("404") || raw.contains("not found", true) ||
                            raw.contains("not supported", true) || raw.contains("empty response")
                    if (!modelProblem) return@withContext Result.failure(e)  // auth/quota/network → real reason
                    // model gone → next candidate (never fatal)
                })
            } catch (e: Exception) { lastErr = e }
        }
        Result.failure(lastErr ?: IllegalStateException("unknown"))
    }

    private fun buildModelChain(s: SettingsStore): List<String> {
        val out = LinkedHashSet<String>()
        out.add(s.geminiModel)
        s.geminiModelsCache.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        // v6: Smart Model Detection বন্ধ থাকলে প্রেফার্ড-মডেল অনুমান চালু হয় না —
        // শুধু এই কী-র জন্য আসলে পাওয়া যাচাইকৃত মডেলগুলোই ফলব্যাকে যায়।
        if (s.smartModelDetection) PREFERRED_MODELS.forEach { out.add(it) }
        return out.toList().take(6)
    }

    /** One raw generateContent call. [maxTokens] default 2048 (long JSON replies safe). */
    private suspend fun rawChat(
        context: Context,
        key: String,
        model: String,
        system: String,
        history: List<Turn>,
        userText: String,
        maxTokens: Int,
        imageBase64: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // v7 SECURITY FIX — the key travels in the x-goog-api-key header
            // (officially supported) instead of a ?key= query param, so it can
            // never leak into URLs, proxy logs or exception messages.
            val url = "$BASE/models/$model:generateContent"
            val body = buildBody(system, history, userText, imageBase64, maxTokens)
            val req = Request.Builder().url(url)
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${text.take(300)}")
                val parsed = parseCandidates(text)
                if (parsed.isNullOrBlank()) throw Exception("empty response")
                Result.success(parsed)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun buildBody(
        system: String,
        history: List<Turn>,
        userText: String,
        imageBase64: String?,
        maxTokens: Int
    ): JSONObject {
        val contents = JSONArray()
        for (t in history) {
            contents.put(JSONObject()
                .put("role", if (t.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        val userParts = JSONArray()
        if (!imageBase64.isNullOrBlank()) {
            userParts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", imageBase64)))
        }
        userParts.put(JSONObject().put("text", userText))
        contents.put(JSONObject().put("role", "user").put("parts", userParts))

        return JSONObject()
            .put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("temperature", 0.75)
                .put("maxOutputTokens", maxTokens)
                .put("topP", 0.95))
    }

    // ================================================================ discovery

    private class ListOut(val ok: Boolean, val code: Int, val models: List<String>, val body: String)

    /** Real ListModels call — the single step that validates AUTH and discovers models. */
    private suspend fun listModelsRaw(context: Context, key: String): ListOut = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE/models?pageSize=100")
                .header("x-goog-api-key", key)   // v7: key never in the URL
                .get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext ListOut(false, resp.code, emptyList(), text)
                val root = try { JSONObject(text) } catch (_: Exception) { return@withContext ListOut(false, resp.code, emptyList(), text) }
                val arr = root.optJSONArray("models") ?: return@withContext ListOut(false, resp.code, emptyList(), text)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name", "").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var canGenerate = false
                    for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") canGenerate = true
                    if (canGenerate && name.startsWith("gemini")) out.add(name)
                }
                ListOut(true, 200, out, text)
            }
        } catch (e: Exception) {
            ListOut(false, -1, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    /** Rank usable models: preferred order first, then the rest alphabetically. */
    private fun rankModels(raw: List<String>): List<String> {
        val usable = raw.filter { name ->
            name.contains("gemini") && EXCLUDED.none { name.contains(it) }
        }
        val out = LinkedHashSet<String>()
        PREFERRED_MODELS.filter { it in usable }.forEach { out.add(it) }
        usable.filter { it !in out }.sorted().forEach { out.add(it) }
        return out.toList()
    }

    /** Lists usable models (API Center info dialog). */
    suspend fun listUsableModels(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = SecureStore.get(context).geminiKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("no_key"))
        val listed = listModelsRaw(context, key)
        if (!listed.ok) Result.failure(Exception("HTTP ${listed.code}: ${listed.body.take(150)}"))
        else Result.success(rankModels(listed.models))
    }

    /** Legacy helper kept for compatibility — now powered by the ranked discovery. */
    suspend fun discoverAndFixModel(context: Context, key: String = SecureStore.get(context).geminiKey): Boolean =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext false
            val listed = listModelsRaw(context, key)
            if (!listed.ok) return@withContext false
            val usable = rankModels(listed.models)
            if (usable.isEmpty()) return@withContext false
            val s = SettingsStore.get(context)
            s.geminiModelsCache = usable.joinToString(",")
            val changed = s.geminiModel !in usable
            if (changed) s.geminiModel = usable.first()
            true
        }

    // ================================================================ tests

    /** Quick real connectivity test. */
    suspend fun testConnection(context: Context): Boolean = withContext(Dispatchers.IO) {
        val r = chat(context, "You are a ping checker. Reply with exactly: pong", emptyList(), "ping")
        r.isSuccess
    }

    data class TestResult(val ok: Boolean, val message: String, val suggestion: String = "")

    /**
     * Deep test: FORMAT → network → real API round-trip with the fallback chain.
     * The UI always gets the exact reason + a concrete fix.
     */
    suspend fun testConnectionDetailed(context: Context): TestResult = withContext(Dispatchers.IO) {
        val key = SecureStore.get(context).geminiKey
        if (key.isBlank())
            return@withContext TestResult(false, "কী সেট করা হয়নি",
                "API CENTER খুলুন → 'Get API Key' চাপুন → কী পেস্ট করে 'সেভ ও অটো-কানেক্ট' চাপুন")
        if (!keyLooksValid(key))
            return@withContext TestResult(false,
                if (key.length < 20) "সেভ করা কী অসম্পূর্ণ (${key.length} অক্ষর)"
                else "সেভ করা কী-র ভেতরে স্পেস আছে",
                "সম্পূর্ণ কী আবার পেস্ট করে 'সেভ ও অটো-কানেক্ট' চাপুন")
        if (!NetworkMonitor.isOnline)
            return@withContext TestResult(false, "ইন্টারনেট অফলাইন (${NetworkMonitor.networkLabel})",
                "Wi-Fi/মোবাইল ডেটা চালু করুন — ফিরলে আমি নিজেই আবার চেষ্টা করবো")

        val s = SettingsStore.get(context)
        val r = chat(context, "You are a ping checker. Reply with exactly: pong", emptyList(), "ping")
        r.fold(
            onSuccess = {
                s.geminiVerified = true
                s.geminiVerifiedAt = com.arohi.ai.util.Extensions.timeNow()
                TestResult(true, "সংযোগ সফল — মডেল: ${s.geminiModel} (অটো-নির্বাচিত)")
            },
            onFailure = {
                TestResult(false, humanError(it), fixHint(it))
            }
        )
    }

    /** Bengali message for an auth/discovery failure with a real HTTP code. */
    private fun authFailMessage(code: Int, body: String): String = when {
        code == 400 && body.contains("API_KEY_INVALID", true) -> "কী অবৈধ — Google এটি চিনছে না (HTTP 400)"
        code == 400 -> "রিকোয়েস্ট অবৈধ (HTTP 400)"
        code == 401 || code == 403 -> "কী অবৈধ বা এই কী-তে অনুমতি নেই (HTTP $code)"
        code == 429 -> "কোটা/রেট লিমিট শেষ (HTTP 429)"
        code == -1 -> "Google সার্ভারে পৌঁছানো যায়নি (নেটওয়ার্ক/DNS/TLS)"
        else -> "Google সার্ভারের সমস্যা (HTTP $code)"
    }

    private fun authFailSuggestion(code: Int): String = when {
        code == 400 -> "কী সম্ভবত অসম্পূর্ণ কপি হয়েছে — সম্পূর্ণ কী আবার পেস্ট করে 'সেভ ও অটো-কানেক্ট' চাপুন; সমস্যা থাকলে aistudio.google.com/apikey থেকে নতুন কী নিন"
        code == 401 || code == 403 -> "aistudio.google.com/apikey থেকে নতুন কী নিন (Generate করার সময় কোনো restriction চালু করা থাকলে 'None' রাখুন)"
        code == 429 -> "এক মিনিট অপেক্ষা করে আবার 'সেভ ও অটো-কানেক্ট' চাপুন (ফ্রি কোটা রিসেট)"
        code == -1 -> "ইন্টারনেট চেক করুন — ফিরলে আমি নিজেই আবার চেষ্টা করবো"
        else -> "কিছুক্ষণ পর আবার চেষ্টা করুন"
    }

    /** Human-readable Bengali reason for a Gemini failure. */
    fun humanError(t: Throwable): String {
        val raw = t.message ?: ""
        return when {
            raw == "offline" -> "ইন্টারনেট অফলাইন"
            raw == "no_key" -> "জেমিনি API কী সেট করা হয়নি"
            raw == "bad_key_format" -> "কী ফরম্যাট ভুল — সম্পূর্ণ কী আবার পেস্ট করুন"
            raw.contains("API key not valid") || raw.contains("API_KEY_INVALID") ->
                "কী অবৈধ (Google এটি চিনছে না)"
            raw.contains("HTTP 400") -> "রিকোয়েস্ট অবৈধ (HTTP 400)"
            raw.contains("HTTP 401") || raw.contains("HTTP 403") ->
                "কী অবৈধ বা অনুমতি নেই (${raw.substringAfter("HTTP ").take(3)})"
            raw.contains("HTTP 429") -> "কোটা/রেট লিমিট শেষ (HTTP 429)"
            raw.contains("HTTP 404") -> "মডেল নাম খুঁজে পাওয়া যায়নি (HTTP 404) — ফলব্যাক চেইনও ব্যর্থ"
            raw.contains("HTTP 5") -> "Google সার্ভারের সমস্যা (${raw.substringAfter("HTTP ").take(3)})"
            raw.contains("timeout") || raw.contains("Timeout") -> "সংযোগের সময় শেষ — নেটওয়ার্ক ধীর"
            raw.contains("Unable to resolve") -> "DNS সমাধান হচ্ছে না — ইন্টারনেট চেক করুন"
            raw.contains("Failed to connect") || raw.contains("Connection reset") ->
                "সার্ভারে পৌঁছানো যাচ্ছে না"
            else -> "সংযোগ ব্যর্থ: ${raw.take(100)}"
        }
    }

    private fun fixHint(t: Throwable): String {
        val raw = t.message ?: ""
        return when {
            raw.contains("API_KEY_INVALID") || raw.contains("API key not valid") || raw.contains("HTTP 401") ||
                raw.contains("HTTP 403") -> "aistudio.google.com/apikey থেকে নতুন কী নিয়ে 'সেভ ও অটো-কানেক্ট' আবার চাপুন"
            raw.contains("HTTP 429") -> "ফ্রি কোটা রিসেট হওয়া পর্যন্ত অপেক্ষা করুন (সাধারণত ১ মিনিট)"
            raw.contains("HTTP 404") || raw.contains("not found") ->
                "'আবার অটো-কানেক্ট' চাপুন — আমি এই কী-র জন্য কার্যকর মডেল নিজেই খুঁজে বসিয়ে দেবো"
            else -> "নেটওয়ার্ক ঠিক হলে আমি নিজেই আবার চেষ্টা করবো"
        }
    }

    // ================================================================ utils

    private fun parseCandidates(raw: String): String? {
        val result: String? = try {
            val root = JSONObject(raw)
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                null
            } else {
                val parts = cands.getJSONObject(0).getJSONObject("content").optJSONArray("parts")
                if (parts == null) null else {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                    sb.toString().trim().ifEmpty { null }
                }
            }
        } catch (_: Exception) { null }
        return result
    }

    /** Extract the first {...} JSON object from a possibly noisy model reply. */
    fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }
}
