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
 * v6 — OPENAI PROVIDER (user spec section 5: Gemini + OpenAI দুটোই থাকবে):
 *
 * GeminiClient-এর সঙ্গে হুবহু একই AUTO-CONNECT স্থাপত্য — Paste → Save →
 * যাচাই → মডেল শনাক্ত → বাস্তব টেস্ট → Connected। ব্যবহারকারী মডেল নাম
 * টাইপ বা সিলেক্ট করবে না।
 *
 * Pipeline (saveAndAutoConnect):
 *   1. CLEAN   — paste noise (space/newline/quote/"Bearer") মুছে ফেলা
 *   2. FORMAT  — ন্যূনতম দৈর্ঘ্য যাচাই (sk-… বা যেকোনো দৈর্ঘ্যের কী; প্রকৃত
 *                বৈধতা OpenAI সার্ভারই বলবে)
 *   3. AUTH    — GET https://api.openai.com/v1/models (Bearer) — কী যাচাই +
 *                মডেল ডিসকভারি এক ধাপে
 *   4. DISCOVER— চ্যাট-সক্ষম মডেলগুলো র‍্যাঙ্ক করে সেরাটি অটো-বাছা
 *   5. TEST    — বাস্তব chat/completions রাউন্ড-ট্রিপ; 404/model বন্ধ থাকলে
 *                ফলব্যাক চেইনে স্বয়ংক্রিয় ঘুরে যাওয়া (৫টি পর্যন্ত)
 *   6. STATUS  — শুধু বাস্তব, যাচাইকৃত ফলাফলই UI-তে দেখানো হয়
 *
 * Request-time self-healing (chat): মডেল বন্ধ হয়ে গেলে প্রতিটি রিকোয়েস্ট
 * ফলব্যাক চেইনে ঘুরে যায় — HTTP 404 আর কখনো fatal নয়।
 */
object OpenAIClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Single endpoint truth — official OpenAI API base. */
    private const val BASE = "https://api.openai.com/v1"

    /** AUTO-SELECT ranking — দ্রুত + সাশ্রয়ী মডেল আগে, শক্তিশালীগুলো পরে। */
    private val PREFERRED_MODELS = listOf(
        "gpt-4o-mini", "gpt-4.1-mini", "gpt-4o", "gpt-4.1",
        "gpt-4.1-nano", "o4-mini", "gpt-4-turbo", "gpt-3.5-turbo"
    )

    /** এগুলো চ্যাট মডেল নয় — কখনো বাছা হবে না। */
    private val EXCLUDED = listOf(
        "embedding", "dall-e", "whisper", "tts", "moderation", "babbage",
        "davinci", "curie", "-ada", "audio", "realtime", "transcribe",
        "search", "image", "codex", "chatgpt-4o-latest" // snapshot churn এড়াতে
    )

    data class Turn(val role: String, val text: String) // role: user | assistant

    // ================================================================ AUTO-CONNECT

    data class AutoResult(
        val ok: Boolean,
        val stage: String,        // CLEAN | FORMAT | NETWORK | AUTH | DISCOVER | TEST
        val message: String,      // বাংলা হেডলাইন
        val detail: String,       // টেকনিক্যাল ডিটেইল (HTTP কোড ইত্যাদি)
        val suggestion: String,   // বাংলা সমাধান
        val model: String,        // যে মডেল যাচাই হয়ে সেভ হলো ("" ব্যর্থ হলে)
        val modelCount: Int,
        val models: List<String>
    )

    /** Paste noise পরিষ্কার — GeminiClient.cleanKey এর সঙ্গে অভিন্ন নিয়ম। */
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
     * sk-… কী সাধারণত ৪০+ অক্ষরের হয় (sk-proj- আরও লম্বা) — কিন্তু দৈর্ঘ্য
     * ভিন্ন হতে পারে। ক্লায়েন্ট-সাইডে শুধু স্পষ্ট ভাঙা paste আটকানো হয়;
     * প্রকৃত বৈধতা OpenAI সার্ভার বলবে। (৪০ অক্ষরের কী "ভুল" নয়।)
     */
    fun keyLooksValid(key: String): Boolean =
        key.length >= 20 && key.none { it.isWhitespace() }

    /** Paste → Save → সম্পূর্ণ স্বয়ংক্রিয় কানেকশন — এক ট্যাপে। */
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
                "input is empty", "platform.openai.com/api-keys থেকে কী কপি করে নিচে পেস্ট করুন", "", 0, emptyList())

        // ---- 2. FORMAT ----
        stage("২/৫ — কী ফরম্যাট যাচাই…")
        if (!keyLooksValid(key))
            return@withContext AutoResult(false, "FORMAT",
                if (key.length < 20) "কী খুব ছোট (${key.length} অক্ষর) — সম্পূর্ণ কী কপি হয়নি"
                else "কী-র ভেতরে স্পেস আছে — সম্পূর্ণ কী একবারে কপি হয়নি",
                "got ${key.length} chars, starts '${key.take(4)}'",
                "platform.openai.com/api-keys → Copy বাটনে চাপ দিয়ে সম্পূর্ণ কী পেস্ট করুন (sk- দিয়ে শুরু); স্পেস/লাইনব্রেক আমি নিজেই পরিষ্কার করে নিই",
                "", 0, emptyList())

        // Save NOW — নেটওয়ার্ক ব্যর্থ হলেও আবার টাইপ করতে হবে না।
        SecureStore.get(appCtx).openaiKey = key

        // ---- 3. NETWORK ----
        if (!NetworkMonitor.isOnline) {
            registerReconnectAutoRetry(appCtx)
            return@withContext AutoResult(false, "NETWORK",
                "ইন্টারনেট অফলাইন — কী সেভ হয়েছে, কিন্তু যাচাই হয়নি",
                "no connectivity (${NetworkMonitor.networkLabel})",
                "ইন্টারনেট চালু করুন — ফিরলেই আমি নিজে নিজে কানেকশন সম্পন্ন করবো",
                "", 0, emptyList())
        }

        // ---- 4. AUTH + DISCOVER (GET /models কী যাচাই করে সত্যিই) ----
        stage("৩/৫ — OpenAI সার্ভারে কী যাচাই + মডেল খোঁজা…")
        val listed = listModelsRaw(key)
        if (!listed.ok)
            return@withContext AutoResult(false, "AUTH", authFailMessage(listed.code, listed.body),
                "HTTP ${listed.code}: ${listed.body.take(200)}",
                authFailSuggestion(listed.code), "", 0, emptyList())

        val usable = rankModels(listed.models)
        if (usable.isEmpty())
            return@withContext AutoResult(false, "DISCOVER",
                "এই কী দিয়ে কোনো চ্যাট মডেল পাওয়া যায়নি",
                "GET /models ok but 0 chat-capable models",
                "platform.openai.com/api-keys থেকে নতুন কী নিন (বিলিং চালু আছে কি না দেখুন)", "", 0, emptyList())

        val s = SettingsStore.get(appCtx)
        s.openaiModelsCache = usable.joinToString(",")
        s.openaiModel = usable.first()

        // ---- 5. বাস্তব TEST + স্বয়ংক্রিয় ফলব্যাক চেইন ----
        stage("৪/৫ — সেরা মডেল নির্বাচন: ${s.openaiModel} — বাস্তব কানেকশন টেস্ট…")
        val tried = mutableListOf<String>()
        var lastErr: Throwable? = null
        for ((idx, model) in usable.take(5).withIndex()) {
            if (idx > 0) stage("৪/৫ — ${tried.lastOrNull() ?: "মডেল"} কাজ করেনি — ফলব্যাক মডেল: $model…")
            tried.add(model)
            val r = rawChat(key, model, "You are a ping checker. Reply with exactly: pong",
                emptyList(), "ping", 64, null)
            if (r.isSuccess) {
                s.openaiModel = model
                s.openaiVerified = true
                s.openaiVerifiedAt = com.arohi.ai.util.Extensions.timeNow()
                val cnt = usable.size
                return@withContext AutoResult(true, "TEST",
                    "কানেকশন সফল ✓ — মডেল অটো-নির্বাচিত: $model ($cnt টি মডেল পাওয়া গেছে)",
                    "verified with real chat/completions; candidates ${tried.joinToString("→")}",
                    if (idx == 0) "সবকিছু প্রস্তুত — এখনই আরোহীর সাথে কথা বলুন!"
                    else "মূল মডেল বন্ধ ছিল, ফলব্যাক $model নিজে থেকে বসানো হয়েছে",
                    model, cnt, usable)
            }
            lastErr = r.exceptionOrNull()
            val raw = lastErr?.message ?: ""
            // v7: মডেল/প্যারামিটার-সংক্রান্ত ব্যর্থতা হলে ফলব্যাক চেইনে এগোও;
            // auth/quota/network হলে থামো (অকারণে কোটা খরচ নয়)।
            if (!isModelProblem(raw)) break
        }
        s.openaiVerified = false
        AutoResult(false, "TEST",
            humanError(lastErr ?: IllegalStateException("unknown")),
            "tried: ${tried.joinToString("→")} — ${lastErr?.message?.take(200) ?: ""}",
            fixHint(lastErr ?: IllegalStateException("unknown")), "", usable.size, usable)
    }

    private fun registerReconnectAutoRetry(ctx: Context) {
        NetworkMonitor.addReconnectListener("openai_autoconnect") {
            CoroutineScope(Dispatchers.IO).launch {
                val key = SecureStore.get(ctx).openaiKey
                if (key.isNotBlank() && !SettingsStore.get(ctx).openaiVerified)
                    saveAndAutoConnect(ctx, key)
                NetworkMonitor.removeReconnectListener("openai_autoconnect")
            }
        }
    }

    // ================================================================ chat

    /**
     * Generate a reply. [imageBase64] optional — gpt-4o/4.1 ভিশনে image_url
     * (data URI) হিসেবে যায়; ভিশন-অক্ষম মডেল হলে সার্ভার স্পষ্ট এরর দেবে।
     */
    suspend fun chat(
        context: Context,
        system: String,
        history: List<Turn>,
        userText: String,
        imageBase64: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val s = SettingsStore.get(context)
        val key = SecureStore.get(context).openaiKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("no_key"))
        if (!NetworkMonitor.isOnline) return@withContext Result.failure(IllegalStateException("offline"))

        val chain = buildModelChain(s)
        var lastErr: Throwable? = null
        for (model in chain) {
            val r = rawChat(key, model, system, history, userText, 2048, imageBase64)
            r.fold(onSuccess = {
                if (model != s.openaiModel) { s.openaiModel = model; s.openaiVerified = true } // self-heal
                return@withContext Result.success(it)
            }, onFailure = { e ->
                lastErr = e
                val raw = e.message ?: ""
                // v7: auth/quota/network → আসল কারণ দেখাও; মডেল/প্যারামিটার
                // সমস্যা হলে চেইনের পরের মডেলে যাও।
                if (!isModelProblem(raw)) return@withContext Result.failure(e)
            })
        }
        Result.failure(lastErr ?: IllegalStateException("unknown"))
    }

    /**
     * Reasoning-model family detector (v7). These models reject `max_tokens`
     * and any temperature other than the default; they use
     * `max_completion_tokens` instead. Keeping the check in one place means the
     * fallback chain never sends an incompatible request shape.
     */
    fun isReasoningModel(model: String): Boolean {
        val m = model.lowercase().trim()
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
                m.startsWith("gpt-5") || m.contains("-reasoning")
    }

    /** Parameter/model mismatch errors mean "try the next model", not "give up". */
    private fun isModelProblem(raw: String): Boolean =
        raw.contains("404") || raw.contains("not found", true) ||
                raw.contains("does not exist", true) || raw.contains("empty response") ||
                raw.contains("unsupported_parameter") || raw.contains("unsupported_value") ||
                raw.contains("Unknown parameter", true) || raw.contains("max_tokens", true) ||
                (raw.contains("400") && raw.contains("model", true))

    private fun buildModelChain(s: SettingsStore): List<String> {
        val out = LinkedHashSet<String>()
        out.add(s.openaiModel)
        s.openaiModelsCache.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        // v6: Smart Model Detection বন্ধ থাকলে শুধু যাচাইকৃত মডেলগুলোই ফলব্যাকে যায়
        if (s.smartModelDetection) PREFERRED_MODELS.forEach { out.add(it) }
        return out.toList().take(6)
    }

    /** একটি বাস্তব chat/completions কল। */
    private suspend fun rawChat(
        key: String,
        model: String,
        system: String,
        history: List<Turn>,
        userText: String,
        maxTokens: Int,
        imageBase64: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            val reasoning = isReasoningModel(model)
            // v7: o-series models (o1-mini especially) reject a "system" role.
            // The persona is folded into the user turn instead, so Arohi's
            // character survives on every model in the fallback chain.
            var effectiveUserText = userText
            if (reasoning) {
                if (system.isNotBlank()) effectiveUserText = "$system\n\n$userText"
            } else {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (t in history) {
                messages.put(JSONObject().put("role", if (t.role == "user") "user" else "assistant")
                    .put("content", t.text))
            }
            val userContent: Any = if (imageBase64.isNullOrBlank()) {
                effectiveUserText
            } else {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", effectiveUserText))
                    .put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")))
            }
            messages.put(JSONObject().put("role", "user").put("content", userContent))

            // v7 FIX — reasoning models (o1/o3/o4/gpt-5 family) reject `max_tokens`
            // and require temperature=1. Sending the classic parameters made every
            // reasoning fallback die with HTTP 400, so the chain looked "broken"
            // whenever the primary model was unavailable. Parameter shape is now
            // chosen per model family — OpenAI-compatible format either way.
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
            if (isReasoningModel(model)) {
                body.put("max_completion_tokens", maxTokens)   // reasoning models
            } else {
                body.put("temperature", 0.75).put("max_tokens", maxTokens)
            }

            val req = Request.Builder().url("$BASE/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${text.take(300)}")
                val parsed = parseContent(text)
                if (parsed.isNullOrBlank()) throw Exception("empty response")
                Result.success(parsed)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    // ================================================================ discovery

    private class ListOut(val ok: Boolean, val code: Int, val models: List<String>, val body: String)

    /** GET /v1/models — এই এক কলেই AUTH যাচাই + মডেল তালিকা। */
    private suspend fun listModelsRaw(key: String): ListOut = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/models")
                .header("Authorization", "Bearer $key")
                .get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext ListOut(false, resp.code, emptyList(), text)
                val root = try { JSONObject(text) } catch (_: Exception) {
                    return@withContext ListOut(false, resp.code, emptyList(), text)
                }
                val arr = root.optJSONArray("data")
                    ?: return@withContext ListOut(false, resp.code, emptyList(), text)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id", "")
                    if (id.isNotBlank()) out.add(id)
                }
                ListOut(true, 200, out, text)
            }
        } catch (e: Exception) {
            ListOut(false, -1, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    /** চ্যাট-সক্ষম মডেল র‍্যাঙ্ক: পছন্দের ক্রম আগে, বাকিগুলো বর্ণানুক্রমে। */
    private fun rankModels(raw: List<String>): List<String> {
        val usable = raw.filter { id ->
            val lower = id.lowercase()
            (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") ||
                    lower.startsWith("o4")) && EXCLUDED.none { lower.contains(it) }
        }
        val out = LinkedHashSet<String>()
        PREFERRED_MODELS.filter { it in usable }.forEach { out.add(it) }
        usable.filter { it !in out }.sorted().forEach { out.add(it) }
        return out.toList()
    }

    /** API Center-এর মডেল তালিকা ডায়ালগের জন্য। */
    suspend fun listUsableModels(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = SecureStore.get(context).openaiKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException("no_key"))
        val listed = listModelsRaw(key)
        if (!listed.ok) Result.failure(Exception("HTTP ${listed.code}: ${listed.body.take(150)}"))
        else Result.success(rankModels(listed.models))
    }

    // ================================================================ tests

    data class TestResult(val ok: Boolean, val message: String, val suggestion: String = "")

    /** গভীর টেস্ট: FORMAT → network → বাস্তব API রাউন্ড-ট্রিপ (ফলব্যাক চেইনসহ)। */
    suspend fun testConnectionDetailed(context: Context): TestResult = withContext(Dispatchers.IO) {
        val key = SecureStore.get(context).openaiKey
        if (key.isBlank())
            return@withContext TestResult(false, "OpenAI কী সেট করা হয়নি",
                "API CENTER খুলুন → 'Get API Key' চাপুন → কী পেস্ট করে 'Save API Key' চাপুন")
        if (!keyLooksValid(key))
            return@withContext TestResult(false,
                if (key.length < 20) "সেভ করা কী অসম্পূর্ণ (${key.length} অক্ষর)"
                else "সেভ করা কী-র ভেতরে স্পেস আছে",
                "সম্পূর্ণ কী আবার পেস্ট করে 'Save API Key' চাপুন")
        if (!NetworkMonitor.isOnline)
            return@withContext TestResult(false, "ইন্টারনেট অফলাইন (${NetworkMonitor.networkLabel})",
                "Wi-Fi/মোবাইল ডেটা চালু করুন — ফিরলে আমি নিজেই আবার চেষ্টা করবো")

        val s = SettingsStore.get(context)
        val r = chat(context, "You are a ping checker. Reply with exactly: pong", emptyList(), "ping")
        r.fold(
            onSuccess = {
                s.openaiVerified = true
                s.openaiVerifiedAt = com.arohi.ai.util.Extensions.timeNow()
                TestResult(true, "সংযোগ সফল — মডেল: ${s.openaiModel} (অটো-নির্বাচিত)")
            },
            onFailure = { TestResult(false, humanError(it), fixHint(it)) }
        )
    }

    /** OpenAI ব্যর্থতার বাংলা কারণ। */
    fun humanError(t: Throwable): String {
        val raw = t.message ?: ""
        return when {
            raw == "offline" -> "ইন্টারনেট অফলাইন"
            raw == "no_key" -> "OpenAI API কী সেট করা হয়নি"
            raw.contains("401") || raw.contains("Incorrect API key") -> "কী অবৈধ — OpenAI এটি চিনছে না (HTTP 401)"
            raw.contains("429") && raw.contains("quota", true) -> "কোটা/বিলিং শেষ (HTTP 429) — প্ল্যাটফর্মে বিলিং চেক করুন"
            raw.contains("429") -> "রেট লিমিট শেষ (HTTP 429) — এক মিনিট অপেক্ষা করুন"
            raw.contains("insufficient_quota") -> "কোটা শেষ — billing চালু করুন বা নতুন কী নিন"
            raw.contains("404") -> "মডেল নাম খুঁজে পাওয়া যায়নি (HTTP 404) — ফলব্যাক চেইনও ব্যর্থ"
            raw.contains("400") && raw.contains("image", true) -> "এই মডেল ছবি বুঝতে পারে না — ভিশনের জন্য জেমিনি বা gpt-4o ব্যবহার করুন"
            raw.contains("HTTP 5") -> "OpenAI সার্ভারের সমস্যা (${raw.substringAfter("HTTP ").take(3)})"
            raw.contains("timeout") || raw.contains("Timeout") -> "সংযোগের সময় শেষ — নেটওয়ার্ক ধীর"
            raw.contains("Unable to resolve") -> "DNS সমাধান হচ্ছে না — ইন্টারনেট চেক করুন"
            raw.contains("Failed to connect") || raw.contains("Connection reset") ->
                "সার্ভারে পৌঁছানো যাচ্ছে না"
            else -> "সংযোগ ব্যর্থ: ${raw.take(100)}"
        }
    }

    private fun fixHint(t: Throwable): String = when {
        (t.message ?: "").contains("401") || (t.message ?: "").contains("Incorrect API key") ->
            "platform.openai.com/api-keys থেকে নতুন কী নিয়ে 'Save API Key' আবার চাপুন"
        (t.message ?: "").contains("429") || (t.message ?: "").contains("quota") ->
            "কোটা রিসেট/বিলিং চালু হলে আবার চেষ্টা করুন — বিকল্প হিসেবে জেমিনি প্রোভাইডারও ব্যবহার করতে পারেন"
        else -> "নেটওয়ার্ক ঠিক হলে আমি নিজেই আবার চেষ্টা করবো"
    }

    private fun authFailMessage(code: Int, body: String): String = when {
        code == 401 -> "কী অবৈধ — OpenAI এটি চিনছে না (HTTP 401)"
        code == 403 -> "এই কী-তে অনুমতি নেই (HTTP 403)"
        code == 429 -> "কোটা/রেট লিমিট শেষ (HTTP 429)"
        code == -1 -> "OpenAI সার্ভারে পৌঁছানো যায়নি (নেটওয়ার্ক/DNS/TLS)"
        else -> "OpenAI সার্ভারের সমস্যা (HTTP $code)"
    }

    private fun authFailSuggestion(code: Int): String = when {
        code == 401 || code == 403 -> "platform.openai.com/api-keys থেকে নতুন কী নিন; কী সম্ভবত অসম্পূর্ণ কপি হলে সম্পূর্ণ কী আবার পেস্ট করুন"
        code == 429 -> "এক মিনিট অপেক্ষা করে আবার 'Save API Key' চাপুন; বিলিং চেক করুন"
        code == -1 -> "ইন্টারনেট চেক করুন — ফিরলে আমি নিজেই আবার চেষ্টা করবো"
        else -> "কিছুক্ষণ পর আবার চেষ্টা করুন"
    }

    private fun parseContent(raw: String): String? = try {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) null
        else {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            val content = msg?.optString("content", "") ?: ""
            content.trim().ifEmpty { null }
        }
    } catch (_: Exception) { null }
}
