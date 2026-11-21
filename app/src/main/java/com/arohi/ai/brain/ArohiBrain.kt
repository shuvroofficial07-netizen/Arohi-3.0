package com.arohi.ai.brain

import android.content.Context
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.NetworkMonitor
import com.arohi.ai.core.SecureStore
import com.arohi.ai.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v6 — AROHI BRAIN: একক প্রবেশদ্বার, দুই প্রোভাইডার (user spec section 5):
 *
 * সব chat রিকোয়েস্ট (কথোপকথন, অটো-রিপ্লাই, ভিশন, টাস্ক) এই রাউটার দিয়েই
 * যায়। নিয়ম:
 *   • defaultProvider আগে চেষ্টা হয় (Gemini বা OpenAI — ব্যবহারকারীর বাছা)
 *   • ব্যর্থ হলে এবং Auto Fallback চালু থাকলে অন্য কনফিগার্ড প্রোভাইডারে
 *     স্বয়ংক্রিয় সুইচ (কোটা শেষ / কী ব্যর্থ / নেটওয়ার্ক সমস্যা — সব ক্ষেত্রে)
 *   • কোনটা উত্তর দিলো তা ArohiBus.activeProvider-এ প্রকাশ হয় — UI-তে সৎ
 *     স্ট্যাটাস; কখনো ভুয়া "connected" দেখায় না
 *   • দুটোই ব্যর্থ হলে বাস্তব বাংলা কারণ ফেরত যায়
 */
object ArohiBrain {

    const val GEMINI = "gemini"
    const val OPENAI = "openai"

    /** সর্বশেষ কোন প্রোভাইডার সফলভাবে উত্তর দিয়েছে (UI সততার জন্য)। */
    @Volatile var lastSuccessfulProvider: String = ""
        private set

    /**
     * Providers that can actually answer right now = key present AND enabled.
     * A provider switched off in API CENTER keeps its key but is never routed
     * to (v7: spec §7/§9 Provider Enable/Disable).
     */
    fun configuredProviders(ctx: Context): List<String> {
        val store = SecureStore.get(ctx)
        val s = SettingsStore.get(ctx)
        val out = mutableListOf<String>()
        if (store.geminiConfigured && s.geminiEnabled) out.add(GEMINI)
        if (store.openaiConfigured && s.openaiEnabled) out.add(OPENAI)
        return out
    }

    fun anyConfigured(ctx: Context): Boolean = configuredProviders(ctx).isNotEmpty()

    fun providerLabelBn(p: String): String = when (p) {
        GEMINI -> "Google Gemini"
        OPENAI -> "OpenAI GPT"
        else -> p
    }

    /**
     * মূল প্রবেশদ্বার। [history] = (role, text) জোড়ার তালিকা — role "user"/"model"।
     * [imageBase64] ভিশনের জন্য (জেপেগ বেস৬৪)। ফেরত: (উত্তর, কোন প্রোভাইডার)।
     */
    suspend fun chat(
        context: Context,
        system: String,
        history: List<Pair<String, String>>,
        userText: String,
        imageBase64: String? = null
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val s = SettingsStore.get(context)
        val store = SecureStore.get(context)
        val configured = configuredProviders(context)

        if (configured.isEmpty())
            return@withContext Result.failure(IllegalStateException("no_key"))
        if (!NetworkMonitor.isOnline)
            return@withContext Result.failure(IllegalStateException("offline"))

        // চেষ্টা-ক্রম: ডিফল্ট প্রোভাইডার আগে, fallback চালু থাকলে অন্যটি পরে
        val order = mutableListOf(s.defaultProvider.takeIf { it in configured } ?: configured.first())
        if (s.autoFallback) configured.filter { it !in order }.forEach { order.add(it) }

        var lastErr: Throwable? = null
        var lastProvider: String = ""
        for (provider in order) {
            lastProvider = provider
            val turns: List<Any> = history.map { (r, t) ->
                if (provider == OPENAI) OpenAIClient.Turn(
                    if (r == "model") "assistant" else r, t) else GeminiClient.Turn(r, t)
            }
            val r = try {
                if (provider == OPENAI) {
                    OpenAIClient.chat(context, system,
                        turns.filterIsInstance<OpenAIClient.Turn>(), userText, imageBase64)
                } else {
                    GeminiClient.chat(context, system,
                        turns.filterIsInstance<GeminiClient.Turn>(), userText, imageBase64)
                }
            } catch (e: Exception) { Result.failure<String>(e) }

            r.fold(onSuccess = { text ->
                lastSuccessfulProvider = provider
                ArohiBus.activeProvider.value = providerLabelBn(provider)
                return@withContext Result.success(provider to text)
            }, onFailure = { e ->
                lastErr = e
                // এই প্রোভাইডারের নিজস্ব ফলব্যাক-চেইনও ব্যর্থ — পরের প্রোভাইডার
            })
        }
        Result.failure(lastErr ?: IllegalStateException("all providers failed"))
    }

    /** কোন প্রোভাইডার ব্যবহার হবে সেটার জন্য পরীক্ষা — API Center-এর টেস্ট বাটন। */
    suspend fun testProvider(context: Context, provider: String): Boolean = withContext(Dispatchers.IO) {
        when (provider) {
            OPENAI -> OpenAIClient.testConnectionDetailed(context).ok
            else -> GeminiClient.testConnectionDetailed(context).ok
        }
    }

    /** সক্রিয় প্রোভাইডারের এক-লাইন সৎ স্ট্যাটাস (হোম/হেলথ ড্যাশবোর্ড)। */
    fun statusLineBn(ctx: Context): String {
        val configured = configuredProviders(ctx)
        if (configured.isEmpty()) return "কোনো ব্রেইন কী সেট নেই — API CENTER-এ দিন"
        val s = SettingsStore.get(ctx)
        val gemOk = s.geminiVerified
        val openaiOk = s.openaiVerified
        val parts = mutableListOf<String>()
        if (SecureStore.get(ctx).geminiConfigured)
            parts.add("Gemini ${if (gemOk) "✓ ${s.geminiModel}" else "(অযাচাইকৃত)"}")
        if (SecureStore.get(ctx).openaiConfigured)
            parts.add("OpenAI ${if (openaiOk) "✓ ${s.openaiModel}" else "(অযাচাইকৃত)"}")
        val default = providerLabelBn(s.defaultProvider)
        return "ডিফল্ট: $default • ${parts.joinToString(" • ")}" +
            (if (lastSuccessfulProvider.isNotBlank() && s.autoFallback)
                " • শেষ উত্তর: ${providerLabelBn(lastSuccessfulProvider)}" else "")
    }
}
