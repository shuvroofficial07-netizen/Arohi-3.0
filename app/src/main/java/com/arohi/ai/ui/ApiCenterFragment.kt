package com.arohi.ai.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arohi.ai.MainActivity
import com.arohi.ai.R
import com.arohi.ai.brain.ArohiBrain
import com.arohi.ai.brain.GeminiClient
import com.arohi.ai.brain.OpenAIClient
import com.arohi.ai.core.ApiHealth
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.core.SecureStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6 — API MANAGEMENT CENTER (ব্যবহারকারীর Screenshot অনুযায়ী পুনর্নির্মিত):
 *
 *   ┌ OpenAI ┐ ┌ Gemini ┐   ← ট্যাব টগল (স্ক্রিনশটের মতো)
 *   প্রোভাইডার কার্ড (লোগো + Enabled ✓ ব্যাজ)
 *   🔑 API Key (eye টগলসহ)
 *   [ Save API Key ]           ← গ্রেডিয়েন্ট বাটন — পেস্ট → সেভ → সম্পূর্ণ স্বয়ংক্রিয়
 *   ✓ Connection Successful!   ← বাস্তব ফলাফলের সবুজ/লাল ব্যানার
 *   — OR —
 *   [ ⚡ Test Connection ] [↻]
 *   AI Provider Settings: Default Provider ▾ / Auto Fallback / Smart Model Detection
 *   Note: অন্য প্রোভাইডারও ব্যবহার করা যায়
 *
 * পুরোনো সব ফাংশন অক্ষত: Gemini অটো-কানেক্ট (পেস্ট ডিবাউন্স), মডেল তালিকা,
 * ElevenLabs, ইন্টারনেট প্রোব, Smart Home, PC ব্রিজ।
 */
class ApiCenterFragment : Fragment() {

    private var selectedTab: String = ArohiBrain.OPENAI // screenshot shows OpenAI first

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val store = SecureStore.get(ctx)
        val s = SettingsStore.get(appCtx)
        val col = UiKit.column(ctx)
        val scroll = UiKit.scrollRoot(ctx).apply { addView(col) }

        fun paintStatus(tv: TextView, item: ApiHealth.Item) {
            tv.text = "● ${item.label}: ${item.detail}"
            tv.setTextColor(ctx.getColor(when (item.st) {
                ApiHealth.St.ACTIVE -> R.color.arohi_success
                ApiHealth.St.NOT_CONFIGURED, ApiHealth.St.UNSUPPORTED -> R.color.arohi_text_dim
                else -> R.color.arohi_error
            }))
        }

        // ============ HEADER (screenshot: "Connect Your AI Provider") ============
        col.addView(TextView(ctx).apply {
            text = "Connect Your AI Provider"
            textSize = 15f
            setTextColor(ctx.getColor(R.color.arohi_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 10)
        })

        // ============ TAB TOGGLE (OpenAI | Gemini) ============
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ctx.getDrawable(R.drawable.bg_tab_container)
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 4, 0, 8) }
        }
        val tabOpenAI = UiKit.tabChip(ctx, "⬡ OpenAI", true, {})
        val tabGemini = UiKit.tabChip(ctx, "✦ Gemini", false, {})
        tabRow.addView(tabOpenAI); tabRow.addView(tabGemini)
        col.addView(tabRow)

        // both provider panels are built; visibility toggles with the tab
        val openaiPanel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val geminiPanel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(openaiPanel)
        col.addView(geminiPanel)

        fun showTab(which: String) {
            selectedTab = which
            val openaiSel = which == ArohiBrain.OPENAI
            openaiPanel.visibility = if (openaiSel) View.VISIBLE else View.GONE
            geminiPanel.visibility = if (openaiSel) View.GONE else View.VISIBLE
            tabOpenAI.background = ctx.getDrawable(if (openaiSel) R.drawable.bg_tab_on else R.drawable.bg_tab_off)
            tabOpenAI.setTextColor(if (openaiSel) Color.WHITE else ctx.getColor(R.color.arohi_text_dim))
            tabGemini.background = ctx.getDrawable(if (openaiSel) R.drawable.bg_tab_off else R.drawable.bg_tab_on)
            tabGemini.setTextColor(if (!openaiSel) Color.WHITE else ctx.getColor(R.color.arohi_text_dim))
        }
        tabOpenAI.setOnClickListener { showTab(ArohiBrain.OPENAI) }
        tabGemini.setOnClickListener { showTab(ArohiBrain.GEMINI) }

        // =================================================================
        // ============ OPENAI PANEL (screenshot-matched card) =============
        // =================================================================
        val oaStatus = UiKit.statusLine(ctx)
        val oaModelInfo = UiKit.note(ctx, "")
        val oaKey = UiKit.field(ctx, "🔑 API Key  (sk-…)", store.openaiKey, password = true)
        val oaBannerBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun paintOpenAIBanner() {
            oaBannerBox.removeAllViews()
            val st = ApiHealth.openai.value
            when (st.st) {
                ApiHealth.St.ACTIVE -> oaBannerBox.addView(UiKit.banner(ctx, true,
                    "Connection Successful!", "OpenAI API is ready to use."))
                ApiHealth.St.CONNECTING -> { }
                ApiHealth.St.NOT_CONFIGURED -> { /* no banner — কী নেই */ }
                else -> oaBannerBox.addView(UiKit.banner(ctx, false,
                    "Connection Failed", st.detail.take(120)))
            }
        }

        fun paintModelInfo() {
            val st = SettingsStore.get(appCtx)
            oaModelInfo.text = when {
                st.openaiVerified ->
                    "অটো-নির্বাচিত মডেল: ${st.openaiModel}\nযাচাইকৃত: ${st.openaiVerifiedAt} • ফলব্যাক চেইন: ${st.openaiModelsCache.split(",").filter { it.isNotBlank() }.size}টি মডেল"
                st.openaiModelsCache.isNotBlank() ->
                    "মডেল: ${st.openaiModel} (এখনো বাস্তব টেস্টে যাচাই হয়নি)"
                else -> ""
            }
        }
        paintModelInfo()
        paintOpenAIBanner()

        val oaErr = UiKit.note(ctx, "")

        suspend fun runOpenAIConnect(keyRaw: String) {
            ApiHealth.markOpenAIStage("অটো-কানেক্ট চলছে…")
            withContext(Dispatchers.Main) { paintStatus(oaStatus, ApiHealth.openai.value) }
            val r = OpenAIClient.saveAndAutoConnect(appCtx, keyRaw) { stageLine ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    oaStatus.text = "● $stageLine"
                    oaStatus.setTextColor(ctx.getColor(R.color.arohi_warn))
                }
            }
            ApiHealth.setOpenAIAutoResult(appCtx, r)
            withContext(Dispatchers.Main) {
                paintStatus(oaStatus, ApiHealth.openai.value)
                paintModelInfo()
                paintOpenAIBanner()
                if (r.ok) {
                    oaErr.text = ""
                    ArohiBus.toast("OpenAI কানেক্টেড ✓ মডেল: ${r.model}")
                } else {
                    oaErr.text = "কারণ: ${r.message}\nসমাধান: ${r.suggestion}"
                    oaErr.setTextColor(ctx.getColor(R.color.arohi_error))
                    ArohiBus.toast("OpenAI: ${r.message}")
                }
            }
        }

        // TRUE paste-and-connect (1.2s debounce) — OpenAI-ও একই সুবিধা
        val oaHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var oaAutoKey = ""
        val oaRunnable = Runnable {
            val typed = OpenAIClient.cleanKey(oaKey.editText?.text.toString())
            if (OpenAIClient.keyLooksValid(typed) && typed != SecureStore.get(appCtx).openaiKey && typed != oaAutoKey) {
                oaAutoKey = typed
                viewLifecycleOwner.lifecycleScope.launch { runOpenAIConnect(typed) }
            }
        }
        oaKey.editText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                oaHandler.removeCallbacks(oaRunnable)
                oaHandler.postDelayed(oaRunnable, 1200)
            }
        })

        openaiPanel.addView(UiKit.card(ctx,
            // header row: title + subtitle + Enabled badge
            headerRow(ctx, "OpenAI API", "Powerful AI. Limitless Possibilities.", appCtx, forOpenAI = true),
            UiKit.note(ctx, "platform.openai.com/api-keys থেকে কী কপি করে নিচে পেস্ট করলেই হবে — আমি নিজেই কী যাচাই, Endpoint ও Compatible মডেল শনাক্ত করে কানেকশন সম্পন্ন করবো। মডেল লিখতে হবে না।"),
            oaStatus,
            oaKey,
            UiKit.gradientButton(ctx, "💾 Save API Key", R.drawable.bg_btn_save) {
                viewLifecycleOwner.lifecycleScope.launch {
                    runOpenAIConnect(oaKey.editText?.text.toString())
                }
            },
            oaBannerBox,
            orDivider(ctx),
            rowOf(ctx,
                UiKit.gradientButton(ctx, "⚡ Test Connection", R.drawable.bg_btn_test) {
                    ApiHealth.markOpenAITesting()
                    oaStatus.text = "● OpenAI ব্রেইন: টেস্ট হচ্ছে… (সরাসরি OpenAI সার্ভারে)"
                    oaStatus.setTextColor(ctx.getColor(R.color.arohi_warn))
                    viewLifecycleOwner.lifecycleScope.launch {
                        val r = ApiHealth.testOpenAI(appCtx)
                        paintStatus(oaStatus, ApiHealth.openai.value)
                        paintModelInfo()
                        paintOpenAIBanner()
                        oaErr.text = if (r.ok) "" else "কারণ: ${r.message}\nসমাধান: ${r.suggestion}"
                        oaErr.setTextColor(ctx.getColor(if (r.ok) R.color.arohi_success else R.color.arohi_error))
                        ArohiBus.toast(if (r.ok) "OpenAI কানেক্টেড ✓" else "OpenAI: ${r.message}")
                    }
                },
                refreshButton(ctx) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val saved = SecureStore.get(appCtx).openaiKey
                        if (saved.isBlank()) {
                            oaErr.text = "আগে কী পেস্ট করে 'Save API Key' চাপুন"
                            oaErr.setTextColor(ctx.getColor(R.color.arohi_warn))
                        } else runOpenAIConnect(saved)
                    }
                }),
            oaModelInfo,
            UiKit.button(ctx, "🔗 Get API Key (Official OpenAI Platform)") {
                open("https://platform.openai.com/api-keys")
            },
            UiKit.button(ctx, "📋 এই কী-তে উপলব্ধ মডেল তালিকা") {
                oaStatus.text = "● মডেল তালিকা আনা হচ্ছে… (OpenAI GET /models)"
                oaStatus.setTextColor(ctx.getColor(R.color.arohi_warn))
                viewLifecycleOwner.lifecycleScope.launch {
                    val key = SecureStore.get(appCtx).openaiKey
                    if (key.isBlank()) {
                        oaErr.text = "আগে কী দিন — তারপর তালিকা দেখা সম্ভব"
                        oaErr.setTextColor(ctx.getColor(R.color.arohi_warn))
                        return@launch
                    }
                    val r = OpenAIClient.listUsableModels(appCtx)
                    withContext(Dispatchers.Main) {
                        r.fold(onSuccess = { models ->
                            if (models.isNotEmpty()) {
                                SettingsStore.get(appCtx).openaiModelsCache = models.joinToString(",")
                            }
                            oaStatus.text = "● ${models.size}টি ব্যবহারযোগ্য মডেল — বর্তমান: ${SettingsStore.get(appCtx).openaiModel}"
                            oaStatus.setTextColor(ctx.getColor(R.color.arohi_success))
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("এই কী দিয়ে চলবে (${models.size}টি মডেল)\nআরোহী নিজেই সেরাটি ব্যবহার করে")
                                .setMessage(models.take(30).joinToString("\n") { "• $it" })
                                .setPositiveButton("ঠিক আছে", null)
                                .show()
                        }, onFailure = { e ->
                            oaStatus.text = "● মডেল খোঁজা যায়নি"
                            oaStatus.setTextColor(ctx.getColor(R.color.arohi_error))
                            oaErr.text = OpenAIClient.humanError(e)
                        })
                    }
                }
            },
            oaErr))

        // =================================================================
        // ============ GEMINI PANEL (screenshot-matched, v4.1 ফাংশন অক্ষত) ==
        // =================================================================
        val gemStatus = UiKit.statusLine(ctx)
        val gemModelInfo = UiKit.note(ctx, "")
        val gemKey = UiKit.field(ctx, "🔑 API Key  (AIza…)", store.geminiKey, password = true)
        val gemBannerBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val gemErr = UiKit.note(ctx, "")

        fun paintGemBanner() {
            gemBannerBox.removeAllViews()
            val st = ApiHealth.gemini.value
            when (st.st) {
                ApiHealth.St.ACTIVE -> gemBannerBox.addView(UiKit.banner(ctx, true,
                    "Connection Successful!", "Gemini API is ready to use."))
                ApiHealth.St.CONNECTING -> { }
                ApiHealth.St.NOT_CONFIGURED -> { }
                else -> gemBannerBox.addView(UiKit.banner(ctx, false,
                    "Connection Failed", st.detail.take(120)))
            }
        }

        fun paintGemModelInfo() {
            val st = SettingsStore.get(appCtx)
            gemModelInfo.text = when {
                st.geminiVerified ->
                    "অটো-নির্বাচিত মডেল: ${st.geminiModel}\nযাচাইকৃত: ${st.geminiVerifiedAt} • ফলব্যাক চেইন: ${st.geminiModelsCache.split(",").filter { it.isNotBlank() }.size}টি মডেল"
                st.geminiModelsCache.isNotBlank() ->
                    "মডেল: ${st.geminiModel} (এখনো বাস্তব টেস্টে যাচাই হয়নি)"
                else -> ""
            }
        }
        paintGemModelInfo()

        suspend fun runAutoConnect(keyRaw: String) {
            ApiHealth.markGeminiStage("অটো-কানেক্ট চলছে…")
            withContext(Dispatchers.Main) { paintStatus(gemStatus, ApiHealth.gemini.value) }
            val r = GeminiClient.saveAndAutoConnect(appCtx, keyRaw) { stageLine ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    gemStatus.text = "● $stageLine"
                    gemStatus.setTextColor(ctx.getColor(R.color.arohi_warn))
                }
            }
            ApiHealth.setGeminiAutoResult(appCtx, r)
            withContext(Dispatchers.Main) {
                paintStatus(gemStatus, ApiHealth.gemini.value)
                paintGemModelInfo()
                paintGemBanner()
                if (r.ok) {
                    gemErr.text = ""
                    ArohiBus.toast("জেমিনি কানেক্টেড ✓ মডেল: ${r.model}")
                } else {
                    gemErr.text = "কারণ: ${r.message}\nসমাধান: ${r.suggestion}"
                    gemErr.setTextColor(ctx.getColor(R.color.arohi_error))
                    ArohiBus.toast("জেমিনি: ${r.message}")
                }
            }
        }

        val autoHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var autoKey = ""
        val autoRunnable = Runnable {
            val typed = GeminiClient.cleanKey(gemKey.editText?.text.toString())
            if (GeminiClient.keyLooksValid(typed) && typed != SecureStore.get(appCtx).geminiKey && typed != autoKey) {
                autoKey = typed
                viewLifecycleOwner.lifecycleScope.launch { runAutoConnect(typed) }
            }
        }
        gemKey.editText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                autoHandler.removeCallbacks(autoRunnable)
                autoHandler.postDelayed(autoRunnable, 1200)
            }
        })

        geminiPanel.addView(UiKit.card(ctx,
            headerRow(ctx, "Gemini API", "Google AI Studio. Free tier available.", appCtx, forOpenAI = false),
            UiKit.note(ctx, "Google AI Studio থেকে কী কপি করে শুধু নিচে পেস্ট করলেই হবে — আমি নিজেই কী যাচাই, সঠিক Endpoint ও Compatible মডেল শনাক্ত করে কানেকশন সম্পন্ন করবো। মডেল লিখতে বা বাছতে হবে না।"),
            gemStatus,
            gemKey,
            UiKit.gradientButton(ctx, "💾 Save API Key", R.drawable.bg_btn_save) {
                viewLifecycleOwner.lifecycleScope.launch {
                    runAutoConnect(gemKey.editText?.text.toString())
                }
            },
            gemBannerBox,
            orDivider(ctx),
            rowOf(ctx,
                UiKit.gradientButton(ctx, "⚡ Test Connection", R.drawable.bg_btn_test) {
                    ApiHealth.markGeminiTesting()
                    gemStatus.text = "● জেমিনি ব্রেইন: টেস্ট হচ্ছে… (সরাসরি Google সার্ভারে)"
                    gemStatus.setTextColor(ctx.getColor(R.color.arohi_warn))
                    viewLifecycleOwner.lifecycleScope.launch {
                        val r = ApiHealth.testGemini(appCtx)
                        paintStatus(gemStatus, ApiHealth.gemini.value)
                        paintGemModelInfo()
                        paintGemBanner()
                        gemErr.text = if (r.ok) "" else "কারণ: ${r.message}\nসমাধান: ${r.suggestion}"
                        gemErr.setTextColor(ctx.getColor(if (r.ok) R.color.arohi_success else R.color.arohi_error))
                        ArohiBus.toast(if (r.ok) "জেমিনি কানেক্টেড ✓" else "জেমিনি: ${r.message}")
                    }
                },
                refreshButton(ctx) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val saved = SecureStore.get(appCtx).geminiKey
                        if (saved.isBlank()) {
                            gemErr.text = "আগে কী পেস্ট করে 'Save API Key' চাপুন"
                            gemErr.setTextColor(ctx.getColor(R.color.arohi_warn))
                        } else runAutoConnect(saved)
                    }
                }),
            gemModelInfo,
            UiKit.button(ctx, "🔗 Get API Key (Official Google AI Studio)") {
                open("https://aistudio.google.com/apikey")
            },
            UiKit.button(ctx, "📋 এই কী-তে উপলব্ধ মডেল তালিকা") {
                gemStatus.text = "● মডেল তালিকা আনা হচ্ছে… (Google ListModels)"
                gemStatus.setTextColor(ctx.getColor(R.color.arohi_warn))
                viewLifecycleOwner.lifecycleScope.launch {
                    val key = SecureStore.get(appCtx).geminiKey
                    if (key.isBlank()) {
                        gemErr.text = "আগে কী দিন — তারপর তালিকা দেখা সম্ভব"
                        gemErr.setTextColor(ctx.getColor(R.color.arohi_warn))
                        return@launch
                    }
                    val r = GeminiClient.listUsableModels(appCtx)
                    withContext(Dispatchers.Main) {
                        r.fold(onSuccess = { models ->
                            if (models.isNotEmpty()) {
                                SettingsStore.get(appCtx).geminiModelsCache = models.joinToString(",")
                            }
                            gemStatus.text = "● ${models.size}টি ব্যবহারযোগ্য মডেল — বর্তমান: ${SettingsStore.get(appCtx).geminiModel}"
                            gemStatus.setTextColor(ctx.getColor(R.color.arohi_success))
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("এই কী দিয়ে চলবে (${models.size}টি মডেল)\nআরোহী নিজেই সেরাটি ব্যবহার করে")
                                .setMessage(models.take(30).joinToString("\n") { "• $it" })
                                .setPositiveButton("ঠিক আছে", null)
                                .show()
                        }, onFailure = { e ->
                            gemStatus.text = "● মডেল খোঁজা যায়নি"
                            gemStatus.setTextColor(ctx.getColor(R.color.arohi_error))
                            gemErr.text = GeminiClient.humanError(e)
                        })
                    }
                }
            },
            gemErr))

        // ============ AI PROVIDER SETTINGS (screenshot card) ============
        col.addView(UiKit.section(ctx, "⚙ AI Provider Settings"))

        val providerBtn = Button(ctx).apply {
            text = when (s.defaultProvider) {
                ArohiBrain.OPENAI -> "⬡ OpenAI ▾"
                else -> "✦ Gemini ▾"
            }
            isAllCaps = false; textSize = 13f
            setTextColor(ctx.getColor(R.color.arohi_text))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val providerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 10, 8, 10)
            addView(TextView(ctx).apply {
                text = "Default Provider:"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.arohi_text))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(providerBtn)
        }

        providerBtn.setOnClickListener {
            val options = arrayOf("⬡ OpenAI", "✦ Gemini")
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Default Provider — কোন ব্রেইন আগে ব্যবহার হবে?")
                .setItems(options) { _, which ->
                    val pick = if (which == 0) ArohiBrain.OPENAI else ArohiBrain.GEMINI
                    SettingsStore.get(appCtx).defaultProvider = pick
                    providerBtn.text = options[which]
                    ArohiBus.toast("ডিফল্ট প্রোভাইডার: ${ArohiBrain.providerLabelBn(pick)}")
                }
                .setNegativeButton("বাতিল", null)
                .show()
        }

        col.addView(UiKit.card(ctx,
            providerRow,
            UiKit.switch(ctx, "Auto Fallback — একটি ব্যর্থ হলে অন্য প্রোভাইডারে স্বয়ংক্রিয় সুইচ",
                s.autoFallback) { on ->
                SettingsStore.get(appCtx).autoFallback = on
                ArohiBus.toast(if (on) "অটো-ফলব্যাক চালু — একটি ব্যর্থ হলে অন্যটি উত্তর দেবে"
                    else "অটো-ফলব্যাক বন্ধ — শুধু ডিফল্ট প্রোভাইডার ব্যবহার হবে")
            },
            UiKit.switch(ctx, "Smart Model Detection — সেরা মডেল অটো-সিলেক্ট",
                s.smartModelDetection) { on ->
                SettingsStore.get(appCtx).smartModelDetection = on
                ArohiBus.toast(if (on) "স্মার্ট মডেল ডিটেকশন চালু — কী থেকে সেরা মডেল নিজেই বাছবো"
                    else "স্মার্ট মডেল ডিটেকশন বন্ধ — সেভ করা মডেল ব্যবহার হবে")
            },
            // v7 — Provider Enable/Disable (spec §7/§9). Key stays saved; the
            // router simply never picks a disabled provider.
            UiKit.switch(ctx, "OpenAI প্রোভাইডার চালু", s.openaiEnabled) { on ->
                SettingsStore.get(appCtx).openaiEnabled = on
                ArohiBus.toast(
                    if (!on) "OpenAI বন্ধ — আরোহী এখন Gemini ব্যবহার করবে (কী সেভ থাকল)"
                    else if (!store.openaiConfigured) "OpenAI চালু হয়েছে — উপরে কী দিয়ে Save করুন"
                    else "OpenAI চালু — আবার ব্রেইন হিসেবে ব্যবহার হবে")
            },
            UiKit.switch(ctx, "Gemini প্রোভাইডার চালু", s.geminiEnabled) { on ->
                SettingsStore.get(appCtx).geminiEnabled = on
                ArohiBus.toast(
                    if (!on) "Gemini বন্ধ — আরোহী এখন OpenAI ব্যবহার করবে (কী সেভ থাকল)"
                    else if (!store.geminiConfigured) "Gemini চালু হয়েছে — উপরে কী দিয়ে Save করুন"
                    else "Gemini চালু — আবার ব্রেইন হিসেবে ব্যবহার হবে")
            },
            UiKit.note(ctx, ArohiBrain.statusLineBn(appCtx))))

        // ============ NOTE CARD (screenshot: "You can also use Gemini API") ============
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ctx.getDrawable(R.drawable.bg_note_card)
            setPadding(28, 20, 28, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 8, 0, 8) }
            addView(TextView(ctx).apply {
                text = "✦"
                textSize = 22f
                setTextColor(ctx.getColor(R.color.arohi_cyan))
                setPadding(0, 0, 22, 0)
            })
            addView(TextView(ctx).apply {
                text = "Note:\nদুটি প্রোভাইডারই ব্যবহার করা যায় — একটির কোটা/কী ব্যর্থ হলে Auto Fallback চালু থাকলে অন্যটি স্বয়ংক্রিয়ভাবে উত্তর দেয়।"
                textSize = 12f
                setTextColor(ctx.getColor(R.color.arohi_text_dim))
            })
        })

        // ============ ELEVENLABS (Voice) — preserved ============
        col.addView(UiKit.section(ctx, "ElevenLabs — ন্যাচারাল ফিমেল ভয়েস (ঐচ্ছিক)"))
        val elStatus = UiKit.statusLine(ctx)
        val elKey = UiKit.field(ctx, "ElevenLabs API Key", store.elevenKey, password = true)
        val elVoice = UiKit.field(ctx, "Voice ID", store.elevenVoiceId)
        val elSwitch = UiKit.switch(ctx, "ElevenLabs ভয়েস ব্যবহার (অফ = ডিভাইস TTS)", s.ttsEngine == "elevenlabs") { on ->
            s.ttsEngine = if (on) "elevenlabs" else "device"
            ApiHealth.refreshFlags(appCtx)
            paintStatus(elStatus, ApiHealth.eleven.value)
        }
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, "এই কী না দিলেও আরোহী ডিভাইসের TTS দিয়ে বাংলায় কথা বলবে।"),
            UiKit.button(ctx, "🔗 Get API Key (Official ElevenLabs)") {
                open("https://elevenlabs.io/app/settings/api-keys")
            },
            UiKit.button(ctx, "🔗 Voice ID বেছে নিতে (Voices Library)") {
                open("https://elevenlabs.io/app/voices")
            },
            elStatus, elKey, elVoice, elSwitch,
            UiKit.button(ctx, "সেভ") {
                store.elevenKey = elKey.editText?.text.toString().trim()
                store.elevenVoiceId = elVoice.editText?.text.toString().trim()
                ApiHealth.refreshFlags(appCtx)
                ArohiBus.toast("ElevenLabs কী সেভ হয়েছে")
            },
            UiKit.button(ctx, "ভয়েস টেস্ট (আসল কথা শুনবে)") {
                com.arohi.ai.voice.TtsManager.speak("হ্যালো শুভ্র! আমি আরোহী — ভয়েস ইঞ্জিন কাজ করছি।")
            }))

        // ============ INTERNET — preserved ============
        col.addView(UiKit.section(ctx, "ইন্টারনেট সংযোগ"))
        val netStatus = UiKit.statusLine(ctx)
        ApiHealth.refreshFlags(appCtx)
        paintStatus(netStatus, ApiHealth.internet.value)
        paintStatus(oaStatus, ApiHealth.openai.value)
        paintStatus(gemStatus, ApiHealth.gemini.value)
        paintOpenAIBanner(); paintGemBanner()
        showTab(SettingsStore.get(appCtx).defaultProvider.takeIf { it == ArohiBrain.OPENAI || it == ArohiBrain.GEMINI }
            ?: ArohiBrain.OPENAI)
        col.addView(UiKit.card(ctx,
            netStatus,
            UiKit.button(ctx, "এখনই প্রোব চালাও") {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    com.arohi.ai.core.NetworkMonitor.refresh()
                    val ok = com.arohi.ai.core.NetworkMonitor.httpProbe()
                    withContext(Dispatchers.Main) {
                        netStatus.text = if (ok) "● ইন্টারনেট: কাজ করছে (${com.arohi.ai.core.NetworkMonitor.networkLabel})"
                        else "● ইন্টারনেট: প্রোব ব্যর্থ — সত্যিই অফলাইন"
                        netStatus.setTextColor(ctx.getColor(if (ok) R.color.arohi_success else R.color.arohi_error))
                    }
                }
            }))

        // ============ SMART HOME (MQTT) — preserved ============
        col.addView(UiKit.section(ctx, "স্মার্ট হোম (MQTT)"))
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, "Broker (Home Assistant/ESP32) কনফিগ করতে SETTINGS → স্মার্ট হোম সেকশন। কনফিগ ছাড়া এটি জেনুইনভাবে 'ডিসকানেক্টেড' দেখাবে — ভুয়া সফল স্ট্যাটাস নেই।"),
            UiKit.button(ctx, "SETTINGS-এ কনফিগ করতে যাও") {
                (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.nav_settings
            }))

        // ============ PC BRIDGE — preserved ============
        col.addView(UiKit.section(ctx, "PC রিমোট (ঐচ্ছিক)"))
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, "PC-তে Arohi PC Companion চালু থাকলে টেস্ট কাজ করবে; না থাকলে সৎভাবে ব্যর্থ দেখাবে (README-তে প্রোটোকল)।"),
            UiKit.button(ctx, "PC কানেকশন টেস্ট") {
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = com.arohi.ai.pc.PcBridgeClient.sendCommand(appCtx, "status")
                    ArohiBus.toast(r)
                }
            }))

        return scroll
    }

    // ---------- v6 helpers (screenshot-matched sub-components) ----------

    /** provider card header: title + subtitle + Enabled ✓ badge (real state). */
    private fun headerRow(ctx: android.content.Context, title: String, subtitle: String,
                          appCtx: android.content.Context, forOpenAI: Boolean): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
        }
        // logo circle
        row.addView(TextView(ctx).apply {
            text = if (forOpenAI) "⬡" else "✦"
            textSize = 26f
            setTextColor(ctx.getColor(if (forOpenAI) R.color.arohi_success else R.color.arohi_cyan))
            setPadding(0, 0, 24, 0)
        })
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            text = title; textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.getColor(R.color.arohi_text))
        })
        box.addView(TextView(ctx).apply {
            text = subtitle; textSize = 12f
            setTextColor(ctx.getColor(R.color.arohi_text_dim))
        })
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        box.layoutParams = lp
        row.addView(box)
        // real Enabled badge from ApiHealth
        val item = if (forOpenAI) ApiHealth.openai.value else ApiHealth.gemini.value
        val enabled = item.st == ApiHealth.St.ACTIVE
        row.addView(TextView(ctx).apply {
            text = if (enabled) "Enabled ✓" else
                (if (item.st == ApiHealth.St.CONNECTING) "Connecting…" else "Disabled")
            textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ctx.getColor(when {
                enabled -> R.color.arohi_success
                item.st == ApiHealth.St.CONNECTING -> R.color.arohi_warn
                else -> R.color.arohi_text_dim
            }))
            background = ctx.getDrawable(R.drawable.bg_pill_status)
            setPadding(20, 10, 20, 10)
        })
        return row
    }

    /** "— OR —" divider (screenshot). */
    private fun orDivider(ctx: android.content.Context): View =
        TextView(ctx).apply {
            text = "— OR —"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.arohi_text_dim))
            setPadding(0, 6, 0, 6)
        }

    /** horizontal row of a main button + round refresh button (screenshot). */
    private fun rowOf(ctx: android.content.Context, main: View, refresh: View): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 4, 0, 4) }
            main.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(main)
            addView(refresh)
        }
    }

    /** round ↻ refresh button (screenshot, next to Test Connection). */
    private fun refreshButton(ctx: android.content.Context, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            text = "↻"
            textSize = 18f
            setTextColor(ctx.getColor(R.color.arohi_cyan))
            background = ctx.getDrawable(R.drawable.bg_grid_btn)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(96, 96).apply { setMargins(10, 0, 0, 0) }
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { ArohiBus.toast("ব্রাউজার খোলা যায়নি") }
    }
}
