package com.arohi.ai.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arohi.ai.MainActivity
import com.arohi.ai.R
import com.arohi.ai.actions.DeviceController
import com.arohi.ai.automation.RoutineScheduler
import com.arohi.ai.brain.GeminiClient
import com.arohi.ai.core.ApiHealth
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.core.SecureStore
import com.arohi.ai.databinding.FragmentSettingsBinding
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.smarthome.MqttManager
import com.arohi.ai.smarthome.SmartDevice
import com.arohi.ai.smarthome.SmartHomeStore
import com.arohi.ai.security.AppLockManager
import com.arohi.ai.services.ArohiForegroundService
import com.arohi.ai.util.Extensions
import com.arohi.ai.voice.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val c = binding.settingsContainer
        val s = SettingsStore.get(ctx)
        val store = SecureStore.get(ctx)
        c.removeAllViews()

        // ---------- LIVE STATUS DASHBOARD ----------
        c.addView(section("স্ট্যাটাস ড্যাশবোর্ড — সবুজ চালু, লাল বন্ধ, ধূসর সেট নেই"))
        val statusBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        c.addView(glass(card(statusBox)))
        ApiHealth.all().forEach { flow ->
            val tv = TextView(ctx).apply { textSize = 13f; setPadding(0, 6, 0, 6) }
            statusBox.addView(tv)
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { item ->
                    tv.text = "● ${item.label}: ${item.detail}"
                    tv.setTextColor(ContextCompat.getColor(ctx, when (item.st) {
                        ApiHealth.St.ACTIVE -> R.color.arohi_success
                        ApiHealth.St.NOT_CONFIGURED, ApiHealth.St.UNSUPPORTED -> R.color.arohi_text_dim
                        ApiHealth.St.CONNECTING -> R.color.arohi_warn
                        else -> R.color.arohi_error
                    }))
                }
            }
        }
        ApiHealth.refreshFlags(ctx.applicationContext)

        // ---------- API KEYS ----------
        c.addView(section("API কী (সবচেয়ে জরুরি)"))
        val geminiField = field("Gemini API Key (Google AI Studio)", store.geminiKey, true)
        val elevenField = field("ElevenLabs API Key (ঐচ্ছিক — ন্যাচারাল ভয়েস)", store.elevenKey, true)
        val voiceField = field("ElevenLabs Voice ID", store.elevenVoiceId, false)
        val geminiTestResult = TextView(ctx).apply { textSize = 13f; setPadding(0, 4, 0, 0) }
        c.addView(glass(card(linear(
            // direct links to where keys come from — no more hunting
            TextView(ctx).apply {
                text = "কী কোথায় পাবো? নিচের বাটনে চাপলে সরাসরি ওই সাইটে যাবে:"
                textSize = 13f; setTextColor(ctx.getColor(R.color.arohi_text_dim))
            },
            row(button("Google AI Studio (ফ্রি কী)") {
                openUrl("https://aistudio.google.com/app/apikey")
            }, button("ElevenLabs কী") {
                openUrl("https://elevenlabs.io/app/settings/api-keys")
            }),
            button("Voice ID খুঁজবো (ElevenLabs Voices)") {
                openUrl("https://elevenlabs.io/app/voices")
            },
            geminiField, elevenField, voiceField,
            // v4.1: NO manual model input — model is auto-selected by the auto-connect
            // pipeline (Google ListModels). This screen only shows what was chosen.
            TextView(ctx).apply {
                text = "মডেল অটো-নির্বাচিত: ${s.geminiModel}${if (s.geminiVerified) " (যাচাইকৃত ✓ ${s.geminiVerifiedAt})" else " — 'সেভ ও অটো-কানেক্ট' চাপলে নিজেই যাচাই হবে"}"
                textSize = 13f; setTextColor(ctx.getColor(R.color.arohi_cyan))
            },
            row(button("সেভ কী + অটো-কানেক্ট") {
                store.geminiKey = geminiField.editText?.text.toString().trim()
                store.elevenKey = elevenField.editText?.text.toString().trim()
                store.elevenVoiceId = voiceField.editText?.text.toString().trim()
                ApiHealth.refreshFlags(ctx.applicationContext)
                ArohiBus.toast("কী সেভ হয়েছে — জেমিনি অটো-কানেক্ট চলছে…")
                lifecycleScope.launch {
                    geminiTestResult.text = "অটো-কানেক্ট: কী যাচাই + মডেল খোঁজা + টেস্ট চলছে…"
                    geminiTestResult.setTextColor(ContextCompat.getColor(ctx, R.color.arohi_warn))
                    val r = com.arohi.ai.brain.GeminiClient.saveAndAutoConnect(ctx.applicationContext,
                        store.geminiKey)
                    geminiTestResult.text = if (r.ok) "● জেমিনি কানেক্টেড ✓ — ${r.message}"
                        else "● জেমিনি — ${r.message} | সমাধান: ${r.suggestion}"
                    geminiTestResult.setTextColor(ContextCompat.getColor(ctx,
                        if (r.ok) R.color.arohi_success else R.color.arohi_error))
                    ArohiBus.toast(if (r.ok) "জেমিনি কানেক্টেড ✓ মডেল: ${r.model}" else "জেমিনি: ${r.message}")
                }
            },
            button("শুধু টেস্ট") {
                lifecycleScope.launch {
                    geminiTestResult.text = "টেস্ট হচ্ছে… (সরাসরি Google সার্ভারে যাচ্ছি)"
                    geminiTestResult.setTextColor(ContextCompat.getColor(ctx, R.color.arohi_warn))
                    val r = ApiHealth.testGemini(ctx.applicationContext)
                    geminiTestResult.text = if (r.ok) "● জেমিনি কানেক্টেড ✓ — ${r.message}" else "● জেমিনি ব্যর্থ — ${r.message}"
                    geminiTestResult.setTextColor(ContextCompat.getColor(ctx,
                        if (r.ok) R.color.arohi_success else R.color.arohi_error))
                    ArohiBus.toast(if (r.ok) "জেমিনি কানেকশন সফল!" else "জেমিনি: ${r.message}")
                }
            },
            geminiTestResult)))))

        // ---------- VOICE ----------
        c.addView(section("ভয়েস সেটিংস"))
        val engineSwitch = MaterialSwitch(ctx).apply {
            text = "ElevenLabs ভয়েস ব্যবহার (অফ হলে ডিভাইস TTS)"
            isChecked = s.ttsEngine == "elevenlabs"
        }
        engineSwitch.setOnCheckedChangeListener { _, on ->
            s.ttsEngine = if (on) "elevenlabs" else "device"
            ApiHealth.refreshFlags(ctx.applicationContext)
        }
        val wakeSwitch = MaterialSwitch(ctx).apply {
            // v6 MIC FIX: স্পষ্ট ব্যাখ্যা — মাইক এখন কেবল ব্যবহারকারীর টগলেই জাগে
            text = "Wake Word: \"আরোহী\" — এটি চালু করলেই মাইক জাগবে (অফ থাকলে মাইক সম্পূর্ণ নীরব)"
            isChecked = s.wakeWordEnabled
        }
        wakeSwitch.setOnCheckedChangeListener { _, on ->
            s.wakeWordEnabled = on
            // v4: applies LIVE — the service watchdog creates/stops the engine within 30s
            ArohiBus.toast(if (on) "Wake word চালু — মাইক এখন 'আরোহী' শুনবে; শুনতে পাওয়ার আগে ওয়েক ওয়ার্ড বলা লাগবে"
                else "Wake word বন্ধ — মাইক সম্পূর্ণ বন্ধ, অ্যাপ আর শুনবে না")
        }
        val bargeSwitch = MaterialSwitch(ctx).apply { text = "Barge-in: কথা বলার সময় 'থামো' বললে থামবে"; isChecked = s.bargeIn }
        bargeSwitch.setOnCheckedChangeListener { _, on -> s.bargeIn = on }
        val notifChatSwitch = MaterialSwitch(ctx).apply {
            text = "নোটিফিকেশন চ্যাট: অ্যাপের বাইরে থেকে লিখে আরোহীর সাথে গল্প"
            isChecked = s.notifChatEnabled
        }
        notifChatSwitch.setOnCheckedChangeListener { _, on -> s.notifChatEnabled = on }
        val sessionTimeoutField = field("গল্পের সেশন টাইমআউট (মিনিট, ১–৬০ — একটানা লং চ্যাট)", s.sessionTimeoutMinutes.toString(), false)
        val announceSwitch = MaterialSwitch(ctx).apply { text = "নোটিফিকেশন ভয়েসে ঘোষণা (বাংলায় কী এসেছে বলবে)"; isChecked = s.announceNotifications }
        announceSwitch.setOnCheckedChangeListener { _, on -> s.announceNotifications = on }
        val autoReplyGroup = radioRow(ctx, listOf(
            "auto" to "অটো-রিপ্লাই: নিজে পাঠাবে (সুপারিশকৃত)",
            "ask" to "অটো-রিপ্লাই: আগে জিজ্ঞেস করবে",
            "off" to "অটো-রিপ্লাই: বন্ধ"), s.autoReplyMode) { mode ->
            s.autoReplyMode = mode
            ArohiBus.toast(if (mode == "auto") "এখন থেকে আরোহী মেসেজে নিজে থেকেই রিপ্লাই পাঠাবে" else "অটো-রিপ্লাই মোড পরিবর্তন হয়েছে")
        }
        val speed = slider("ভয়েস স্পিড", s.ttsSpeed, 0.5f, 2.5f) { v -> s.ttsSpeed = v }
        val pitch = slider("ভয়েস পিচ", s.ttsPitch, 0.5f, 2.5f) { v -> s.ttsPitch = v }
        val langField = field("STT ভাষা (bn-BD / bn-IN / en-US)", s.sttLanguage, false)
        val wakeWordsField = field("Wake words (কমা দিয়ে)", s.wakeWords, false)
        c.addView(glass(card(linear(
            engineSwitch, wakeSwitch, bargeSwitch, notifChatSwitch, announceSwitch, autoReplyGroup,
            speed, pitch, langField, wakeWordsField, sessionTimeoutField,
            button("সেভ ভয়েস সেটিংস") {
                s.sttLanguage = langField.editText?.text.toString().trim().ifBlank { "bn-BD" }
                s.wakeWords = wakeWordsField.editText?.text.toString().trim().ifBlank { "arohi, আরোহী, আরোহি, হেই আরোহী, hey arohi" }
                s.sessionTimeoutMinutes = sessionTimeoutField.editText?.text.toString().trim().toIntOrNull()
                    ?.coerceIn(1, 60) ?: 5
                ArohiBus.toast("ভয়েস সেটিংস সেভ হয়েছে")
            }))))

        // ---------- PERSONALITY ----------
        c.addView(section("পার্সোনালিটি (আবেগ ও যত্ন)"))
        val warmth = slider("উষ্ণতা (০ পেশাদার → ১০০ আদুরে)", s.warmth.toFloat(), 0f, 100f) { v -> s.warmth = v.toInt() }
        val proactiveSwitch = MaterialSwitch(ctx).apply { text = "প্রোঅ্যাকটিভ মোড — নিজে থেকেই কথা শুরু করবে"; isChecked = s.proactiveEnabled }
        proactiveSwitch.setOnCheckedChangeListener { _, on -> s.proactiveEnabled = on }
        val proactiveMinutes = field("কত মিনিট পর পর নিজে থেকে কথা বলবে (১০–৪৮০)", s.proactiveMinutes.toString(), false)
        val notes = field("ব্যক্তিগত নির্দেশনা (আরোহী সবসময় মনে রাখবে)", s.personalityNotes, false)
        val nameField = field("আপনার নাম", s.userName, false)
        c.addView(glass(card(linear(
            TextView(ctx).apply {
                text = "আরোহী চিরদিন আপনাকে নাম ধরে ডাকবে — এটি তার স্মৃতিতে স্থায়ীভাবে লেখা আছে।"
                textSize = 13f; setTextColor(ctx.getColor(R.color.arohi_text_dim))
            },
            warmth, proactiveSwitch, proactiveMinutes, notes, nameField,
            button("সেভ পার্সোনালিটি") {
                s.personalityNotes = notes.editText?.text.toString().trim()
                s.userName = nameField.editText?.text.toString().trim().ifBlank { "শুভ্র (Shubho)" }
                s.proactiveMinutes = proactiveMinutes.editText?.text.toString().trim().toIntOrNull()
                    ?.coerceIn(10, 480) ?: 45
                lifecycleScope.launch(Dispatchers.IO) {
                    MemoryRepository.get(ctx).addMemory("আমার নাম ${s.userName}", "fact", 5)
                }
                ArohiBus.toast("পার্সোনালিটি সেভ হয়েছে")
            }))))

        // ---------- SMART HOME ----------
        c.addView(section("স্মার্ট হোম (MQTT — Arduino/ESP32/HA)"))
        val host = field("MQTT Broker Host", s.mqttHost, false)
        val port = field("MQTT Port", s.mqttPort.toString(), false)
        val user = field("Username (ঐচ্ছিক)", s.mqttUsername, false)
        val pass = field("Password (ঐচ্ছিক)", s.mqttPassword, true)
        val base = field("Base Topic", s.mqttBaseTopic, false)
        val connState = TextView(ctx).apply { textSize = 13f; setTextColor(ctx.getColor(R.color.arohi_text_dim)) }
        c.addView(glass(card(linear(
            host, port, user, pass, base, connState,
            row(button("সেভ ও কানেক্ট") {
                s.mqttHost = host.editText?.text.toString().trim()
                s.mqttPort = port.editText?.text.toString().toIntOrNull() ?: 1883
                s.mqttUsername = user.editText?.text.toString().trim()
                s.mqttPassword = pass.editText?.text.toString()
                s.mqttBaseTopic = base.editText?.text.toString().trim().ifBlank { "arohi/home" }
                MqttManager.connect(ctx)
            },
            button("ডিসকানেক্ট") { MqttManager.disconnect(ctx) },
            button("ডিভাইস ম্যানেজার") { deviceManagerDialog() })))))
        lifecycleScope.launch {
            MqttManager.state.collect { st ->
                connState.text = when (st) {
                    MqttManager.State.CONNECTED -> "স্ট্যাটাস: কানেক্টেড (বাস্তব)"
                    MqttManager.State.CONNECTING -> "স্ট্যাটাস: কানেক্ট হচ্ছে…"
                    MqttManager.State.ERROR -> "স্ট্যাটাস: ত্রুটি — ${MqttManager.lastError.value}"
                    else -> "স্ট্যাটাস: ডিসকানেক্টেড"
                }
            }
        }

        // ---------- PC BRIDGE ----------
        c.addView(section("PC রিমোট কন্ট্রোল"))
        val pcHost = field("PC Host/IP", s.pcHost, false)
        val pcPort = field("PC Port", s.pcPort.toString(), false)
        val pcToken = field("Shared Token", SecureStore.get(ctx).pcToken, true)
        c.addView(glass(card(linear(
            TextView(ctx).apply {
                text = "সততার সাথে বলছি: PC কম্প্যানিয়ন অ্যাপ (README-তে প্রোটোকল দেওয়া) চালু না থাকলে এই ফিচার কাজ করবে না।"
                textSize = 12f; setTextColor(ctx.getColor(R.color.arohi_warn))
            },
            pcHost, pcPort, pcToken,
            row(button("সেভ") {
                s.pcHost = pcHost.editText?.text.toString().trim()
                s.pcPort = pcPort.editText?.text.toString().toIntOrNull() ?: 9527
                SecureStore.get(ctx).pcToken = pcToken.editText?.text.toString().trim()
                ArohiBus.toast("PC ব্রিজ কনফিগ সেভ হয়েছে")
            },
            button("কানেকশন টেস্ট") {
                lifecycleScope.launch {
                    val r = com.arohi.ai.pc.PcBridgeClient.sendCommand(ctx, "status")
                    ArohiBus.toast(r)
                }
            })))))

        // ---------- AUTOMATION / ROUTINES ----------
        c.addView(section("রুটিন ও অটোমেশন"))
        c.addView(glass(card(linear(
            TextView(ctx).apply {
                text = if (RoutineScheduler.exactAlarmsAllowed(ctx))
                    "এক্স্যাক্ট অ্যালার্ম অনুমোদিত — রুটিন নির্ভুল সময়ে চলবে"
                else "এক্স্যাক্ট অ্যালার্ম ব্লকড — রুটিন ±১ মিনিট টাইমে চলবে"
                textSize = 13f; setTextColor(ctx.getColor(R.color.arohi_text_dim))
            },
            button("রুটিন তৈরি করুন") { routineDialog() },
            button("রুটিন তালিকা") { routineListDialog() }))))

        // ---------- SECURITY ----------
        c.addView(section("নিরাপত্তা ও প্রাইভেসি"))
        val lockSwitch = MaterialSwitch(ctx).apply { text = "অ্যাপ লক (ফিঙ্গারপ্রিন্ট/চেহারা)"; isChecked = s.appLockEnabled }
        lockSwitch.setOnCheckedChangeListener { _, on ->
            if (on) {
                if (AppLockManager.biometricAvailable(ctx)) {
                    s.appLockEnabled = true; ArohiBus.toast("অ্যাপ লক চালু")
                } else {
                    lockSwitch.isChecked = false
                    ArohiBus.toast("এই ডিভাইসে বায়োমেট্রিক নেই — প্রথমে PIN সেট করুন")
                    pinDialog()
                }
            } else s.appLockEnabled = false
        }
        c.addView(glass(card(linear(
            lockSwitch,
            row(button("PIN সেট") { pinDialog() },
                button("পারমিশন হাব") { permissionDialog() }),
            button("অ্যাক্টিভিটি লগ") { activityLogDialog() }))))

        // ---------- SERVICE & BATTERY ----------
        c.addView(section("ব্যাকগ্রাউন্ড সার্ভিস"))
        val bootSwitch = MaterialSwitch(ctx).apply { text = "রিবুটের পর অটো-স্টার্ট"; isChecked = s.startOnBoot }
        bootSwitch.setOnCheckedChangeListener { _, on -> s.startOnBoot = on }
        c.addView(glass(card(linear(
            bootSwitch,
            TextView(ctx).apply {
                text = "২৪/৭ চালু রাখতে: ব্যাটারি অপটিমাইজেশন থেকে Arohi AI-কে 'Unrestricted' দিন। " +
                    "Samsung: Settings → Battery → Background limits → Never sleeping apps।"
                textSize = 12f; setTextColor(ctx.getColor(R.color.arohi_warn))
            },
            button("ব্যাটারি অপটিমাইজেশন সেটিংস") {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { ArohiBus.toast("সেটিংস খোলা যায়নি") }
            },
            button("সার্ভিস রিস্টার্ট") {
                ArohiForegroundService.stop(ctx)
                ArohiForegroundService.start(ctx)
                ArohiBus.toast("সার্ভিস রিস্টার্ট হয়েছে — wake word আবার চালু")
            }))))

        // ---------- PERMISSIONS quick grant ----------
        c.addView(section("অনুমতি দ্রুত চালু"))
        c.addView(glass(card(linear(
            button("মাইক্রোফোন") { requestPerm(Manifest.permission.RECORD_AUDIO) },
            button("ক্যামেরা") { requestPerm(Manifest.permission.CAMERA) }))))
        c.addView(glass(card(linear(
            button("নোটিফিকেশন লিসেনার") {
                try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { ArohiBus.toast("সেটিংস খোলা যায়নি") }
            },
            button("অ্যাক্সেসিবিলিটি") {
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { ArohiBus.toast("সেটিংস খোলা যায়নি") }
            }))))
        c.addView(glass(card(linear(
            button("কন্টাক্ট") { requestPerm(Manifest.permission.READ_CONTACTS) },
            button("ফোন কল") { requestPerm(Manifest.permission.CALL_PHONE) }))))
        c.addView(glass(card(linear(
            button("সব ফাইল অ্যাক্সেস") {
                if (!com.arohi.ai.files.PhoneFiles.openAllFilesSettings(ctx))
                    ArohiBus.toast("সেটিংস খোলা যায়নি")
            },
            button("গ্যালারি/মিডিয়া") {
                if (Build.VERSION.SDK_INT >= 33)
                    requestPermArray(arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
                else requestPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }))))

        // ---------- EMERGENCY + DEVICE PROFILE (v4) ----------
        c.addView(section("জরুরি নিয়ন্ত্রণ ও ডিভাইস প্রোফাইল"))
        c.addView(glass(card(linear(
            TextView(ctx).apply {
                text = com.arohi.ai.core.ApiHealth.deviceProfileBn(ctx.applicationContext)
                textSize = 13f
            },
            TextView(ctx).apply {
                text = "অ্যাডাপটিভ মোড: ${com.arohi.ai.core.AdaptiveEngine.ramClassBn()} ডিভাইস প্রোফাইল — ব্যাটারি/RAM অনুযায়ী প্রসেসিং বেছে নেওয়া হয়"
                textSize = 12f; setTextColor(ctx.getColor(R.color.arohi_text_dim))
            },
            button("SYSTEM HEALTH স্ক্রিন (পূর্ণ ড্যাশবোর্ড)") {
                (activity as? MainActivity)?.openDestination(R.id.nav_system_health)
            },
            button("API CENTER স্ক্রিন") {
                (activity as? MainActivity)?.openDestination(R.id.nav_api_center)
            },
            button("\uD83D\uDEA8 EMERGENCY STOP (সব থামাও)") {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("EMERGENCY STOP?")
                    .setMessage("সব কাজ, কথা ও অটোমেশন থামবে।")
                    .setPositiveButton("হ্যাঁ") { _, _ -> com.arohi.ai.core.EmergencyStop.execute(ctx.applicationContext) }
                    .setNegativeButton("না", null).show()
            }))))

        // ---------- SUPPORT CENTER (v4 — developer info, exact) ----------
        c.addView(section("সাপোর্ট — ডেভেলপারের সাথে যোগাযোগ"))
        c.addView(glass(card(linear(
            TextView(ctx).apply {
                text = "Created by \"Shù Vrô\""
                textSize = 18f; setTextColor(ctx.getColor(R.color.arohi_cyan))
            },
            button("Telegram Support: @Shuvrojr07") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("tg://resolve?domain=Shuvrojr07")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    openUrl("https://t.me/Shuvrojr07")
                }
            },
            button("Facebook Support: shuvromridha77") {
                openUrl("https://www.facebook.com/shuvromridha77")
            },
            button("সম্পূর্ণ SUPPORT স্ক্রিন") {
                (activity as? MainActivity)?.openDestination(R.id.nav_support)
            }))))

        // ---------- ABOUT ----------
        c.addView(section("সম্পর্কে — সৎ সীমাবদ্ধতা"))
        c.addView(glass(card(TextView(ctx).apply {
            text = "AROHI v5.0 — Universal JARVIS + Personal AI Companion — শুভ্রের ব্যক্তিগত AI।\n\n" +
                "যা সত্যিই কাজ করে: ২৪/৭ ওয়েক ওয়ার্ড (শুধু 'আরোহী' বললেই জাগে — মাইক অটো-শোনা নয়), " +
                "ভয়েস কথোপকথন (সবসময় বাংলায়), গলার টোন বুঝে ভঙ্গি মেলানো, জেমিনি ব্রেইন (মডেল অটো-হিলিং), " +
                "স্মৃতি, নোটিফিকেশন ঘোষণা ও ইনলাইন-রিপ্লাই অটো-রিপ্লাই, নোটিফিকেশন থেকে লিখে চ্যাট, " +
                "ফোনের সব ফাইল ও গ্যালারি অ্যাক্সেস, অ্যাপ/ডিভাইস কন্ট্রোল, স্ক্রিন টাচ অটোমেশন, " +
                "কল/মেসেজ ড্রাফট, ক্যামেরা ভিশন, OCR, স্ক্রিনশট, অটোমেশন, রুটিন, স্মার্ট হোম MQTT।\n\n" +
                "সীমাবদ্ধতা (প্ল্যাটফর্ম নিয়ম):\n" +
                "• তৃতীয় পক্ষের মেসেজ প্র্যাপে 'সাইলেন্ট সেন্ড' কেউ করতে পারে না — রিপ্লাই Accessibility-এর মাধ্যমে হয়, ব্যর্থ হলে সৎভাবে বলবে।\n" +
                "• স্ক্রিনশটে প্রতিবার Android-এর অনুমতি ডায়ালগ আসবে (নিয়ম)।\n" +
                "• নিজে থেকে অ্যাপ ইনস্টল করা Play পলিসিতে নিষিদ্ধ — প্লে স্টোরে খুলে দেবে।\n" +
                "• PC কম্প্যানিয়ন অ্যাপ ছাড়া PC ফিচার চলবে না।\n" +
                "• ২৪/৭ চালু রাখতে ব্যাটারি অপটিমাইজেশন থেকে অ্যাপকে Unrestricted করুন।"
            textSize = 13f
        })))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { ArohiBus.toast("ব্রাউজার খোলা যায়নি") }
    }

    // ---------- helpers ----------
    private fun requestPerm(perm: String) {
        val act = activity ?: return
        PermissionHelper.request(act, arrayOf(perm), 104)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            ApiHealth.refreshFlags(requireContext().applicationContext)
            ArohiBus.toast("অনুমতি স্ট্যাটাস: ${PermissionHelper.has(requireContext(), perm)}")
        }
    }

    /** v5: multiple permissions at once (gallery media). */
    private fun requestPermArray(perms: Array<String>) {
        val act = activity ?: return
        PermissionHelper.request(act, perms, 105)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            ApiHealth.refreshFlags(requireContext().applicationContext)
            ArohiBus.toast("মিডিয়া অনুমতি: ${com.arohi.ai.files.PhoneFiles.hasMediaPermission(requireContext())}")
        }
    }

    private fun pinDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "৪+ ডিজিট PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(ctx).setTitle("PIN সেট করুন")
            .setView(input)
            .setPositiveButton("সেভ") { _, _ ->
                if (AppLockManager.setPin(ctx, input.text.toString()))
                    ArohiBus.toast("PIN সেভ হয়েছে (হ্যাশ করা)")
                else ArohiBus.toast("PIN কমপক্ষে ৪ ডিজিট হতে হবে")
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun permissionDialog() {
        val ctx = requireContext()
        ApiHealth.refreshFlags(ctx.applicationContext)
        val lines = PermissionHelper.statusLines(ctx).joinToString("\n") { (name, on) ->
            "${if (on) "চালু " else "বন্ধ "} $name"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("প্রাইভেসি ড্যাশবোর্ড (বাস্তব স্ট্যাটাস)")
            .setMessage(lines)
            .setPositiveButton("সেটিংস খুলুন") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) {}
            }
            .setNegativeButton("বন্ধ", null).show()
    }

    private fun activityLogDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = try { MemoryRepository.get(requireContext()).recentActivities(20) } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                val logText = list.joinToString("\n\n") { "[${Extensions.dateTime(it.timestamp)}] ${it.type}: ${it.detail}" }
                    .ifBlank { "এখনো কোনো অ্যাক্টিভিটি লগ হয়নি" }
                val tv = TextView(requireContext()).apply { text = logText; setPadding(48, 32, 48, 32); textSize = 13f }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("অটোমেশন অ্যাক্টিভিটি লগ")
                    .setView(android.widget.ScrollView(requireContext()).apply { addView(tv) })
                    .setPositiveButton("বন্ধ", null).show()
            }
        }
    }

    private fun routineDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val name = EditText(ctx).apply { hint = "রুটিনের নাম (যেমন: Morning)" }
        val time = EditText(ctx).apply { hint = "সময় HH:mm (যেমন 07:30)" }
        val steps = EditText(ctx).apply { hint = "প্রতি লাইনে এক কাজ:\nyoutube khulo\nbattery bolo\nalarm 8:00" }
        layout.addView(name); layout.addView(time); layout.addView(steps)
        MaterialAlertDialogBuilder(ctx).setTitle("নতুন রুটিন")
            .setView(layout)
            .setPositiveButton("সেভ") { _, _ ->
                val t = time.text.toString().trim()
                if (!t.matches(Regex("[0-2]?[0-9]:[0-5][0-9]"))) { ArohiBus.toast("সময় ফরম্যাট HH:mm দিন"); return@setPositiveButton }
                val stepList = steps.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (stepList.isEmpty()) { ArohiBus.toast("অন্তত একটি ধাপ দিন"); return@setPositiveButton }
                lifecycleScope.launch(Dispatchers.IO) {
                    MemoryRepository.get(ctx).addRoutine(name.text.toString().ifBlank { "রুটিন" }, t, stepList)
                    withContext(Dispatchers.Main) {
                        RoutineScheduler.rescheduleAll(ctx)
                        ArohiBus.toast("রুটিন সেভ ও শিডিউল হয়েছে")
                    }
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun routineListDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = try { MemoryRepository.get(requireContext()).routinesNow() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                if (list.isEmpty()) { ArohiBus.toast("কোনো রুটিন নেই"); return@withContext }
                val labels = list.map { "${it.name} — ${it.timeOfDay}" }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("রুটিন (${list.size})")
                    .setItems(labels) { _, which ->
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("রুটিন মুছবেন?")
                            .setMessage(labels[which])
                            .setPositiveButton("মুছে ফেলো") { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    MemoryRepository.get(requireContext()).deleteRoutine(list[which])
                                    withContext(Dispatchers.Main) { RoutineScheduler.rescheduleAll(requireContext()) }
                                }
                            }
                            .setNegativeButton("না", null).show()
                    }
                    .setNegativeButton("বন্ধ", null).show()
            }
        }
    }

    private fun deviceManagerDialog() {
        val ctx = requireContext()
        val store = SmartHomeStore.get(ctx)
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val name = EditText(ctx).apply { hint = "ডিভাইস নাম (যেমন Lamp)" }
        val topic = EditText(ctx).apply { hint = "MQTT Topic (যেমন arohi/home/lamp/set)" }
        val type = EditText(ctx).apply { hint = "type: light | fan | plug | rgb | sensor" }
        layout.addView(name); layout.addView(topic); layout.addView(type)

        val listText = TextView(ctx).apply {
            text = store.devices().joinToString("\n") { "• ${it.name} [${it.type}] → ${it.topic}" }
                .ifBlank { "কোনো ডিভাইস যোগ করা হয়নি (বাস্তব MQTT ছাড়া কিছু চলবে না)" }
            textSize = 13f
        }
        layout.addView(listText)
        MaterialAlertDialogBuilder(ctx).setTitle("স্মার্ট ডিভাইস ম্যানেজার")
            .setView(layout)
            .setPositiveButton("যোগ করো") { _, _ ->
                val d = SmartDevice(
                    id = System.currentTimeMillis().toString(),
                    name = name.text.toString().trim().ifBlank { "device" },
                    topic = topic.text.toString().trim().ifBlank { "arohi/home/device/set" },
                    type = type.text.toString().trim().ifBlank { "light" })
                store.add(d)
                ArohiBus.toast("ডিভাইস সেভ হয়েছে: ${d.name}")
            }
            .setNeutralButton("সব মুছুন") { _, _ -> store.devices().forEach { store.remove(it.id) }; ArohiBus.toast("সব ডিভাইস মুছে ফেলা হয়েছে") }
            .setNegativeButton("বন্ধ", null).show()
    }

    // ---------- view builders ----------
    private fun section(title: String): TextView =
        TextView(requireContext()).apply {
            text = title; textSize = 16f
            setTextColor(requireContext().getColor(R.color.arohi_cyan))
            setPadding(8, 28, 8, 8)
        }

    private fun glass(v: View): View = v

    private fun card(v: View): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_glass)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 8, 0, 8) }
            addView(v)
        }
    }

    private fun linear(vararg views: View): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it) }
        }

    private fun row(vararg buttons: View): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach {
                it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(4, 4, 4, 4) }
                addView(it)
            }
        }

    private fun button(label: String, onClick: () -> Unit): com.google.android.material.button.MaterialButton =
        com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }

    private fun field(hint: String, value: String, password: Boolean): com.google.android.material.textfield.TextInputLayout {
        val ctx = requireContext()
        return com.google.android.material.textfield.TextInputLayout(ctx).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 6, 0, 6) }
            val et = EditText(ctx).apply {
                setText(value)
                if (password) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            addView(et)
        }
    }

    private fun slider(title: String, initial: Float, from: Float, to: Float, onValue: (Float) -> Unit): View {
        val ctx = requireContext()
        val fmt = { v: Float -> String.format("%.2f", v) }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(ctx).apply { text = "$title: ${fmt(initial)}"; textSize = 14f }
        val sliderView = Slider(ctx).apply {
            valueFrom = from; valueTo = to; value = initial.coerceIn(from, to); stepSize = 0.05f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    label.text = "$title: ${fmt(value)}"
                    onValue(value)
                }
            }
        }
        box.addView(label); box.addView(sliderView)
        return box
    }

    private fun radioRow(ctx: android.content.Context, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val rg = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.VERTICAL }
        options.forEach { (value, label) ->
            val rb = android.widget.RadioButton(ctx).apply {
                text = label; id = View.generateViewId()
                isChecked = value == selected
                setOnClickListener { onPick(value) }
            }
            rg.addView(rb)
        }
        return rg
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
