package com.arohi.ai.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arohi.ai.MainActivity
import com.arohi.ai.R
import com.arohi.ai.actions.AppLauncher
import com.arohi.ai.actions.DeviceController
import com.arohi.ai.core.ApiHealth
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.ConvState
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.databinding.FragmentHomeBinding
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.services.ArohiForegroundService
import com.arohi.ai.util.Extensions
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6 — HOME স্ক্রিন ব্যবহারকারীর Screenshot অনুযায়ী হুবহু পুনর্নির্মিত:
 * অ্যানিমে অ্যাভাটার হিরো + স্পিচ বাবল, ওয়েভফর্ম বার, ৪টি বাস্তব স্ট্যাটাস পিল,
 * Start/Brain/Stop তিন বৃত্ত, Listening পিল, ৬টি ফাংশন বাটন, Shù Vrô ব্র্যান্ডিং।
 *
 * v5-এর সব কার্যকর উপাদান অক্ষত (নিচে স্ক্রলে): সম্পূর্ণ স্ট্যাটাস গ্রিড, quick
 * actions, recent activities, setup wizard, ব্রেইন ডিটেইল।
 *
 * v6 MIC FIX: START AROHI = ব্যবহারকারীর স্পষ্ট অনুমতি → তার আগে মাইক কখনো
 * জাগে না; STOP AROHI = মাইক সম্পূর্ণ বন্ধ। কোনো এলোমেলো অন/অফ নেই।
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val appCtx = requireContext().applicationContext

        // ---------- START AROHI (mic consent + REAL start verification) ----------
        binding.circleStart.setOnClickListener {
            val ctx = requireContext()
            if (!PermissionHelper.hasMicrophone(ctx)) {
                ArohiBus.toast("আগে মাইক্রোফোন অনুমতি দিন — তাহলে ২৪/৭ শোনা সম্ভব")
                PermissionHelper.request(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), 103)
                return@setOnClickListener
            }
            // v6 MIC FIX: মাইক শুধু এই স্পষ্ট সিদ্ধান্তেই জাগে — wake word চালু
            SettingsStore.get(ctx).wakeWordEnabled = true
            ArohiForegroundService.start(ctx)
            lifecycleScope.launch {
                delay(2500)
                val running = ArohiForegroundService.isRunning
                if (running) {
                    ArohiBus.toast("✓ ২৪/৭ মোড চালু — 'আরোহী' বললেই জেগে উঠবো; এর আগে মাইক নীরব থাকবে")
                    TtsManager.speak("চালু হয়ে গেলাম শুভ্র! এখন থেকে 'আরোহী' বললেই আমি শুনবো। আর মাইক একা একা জাগবে না।")
                    ApiHealth.refreshFlags(appCtx)
                } else {
                    ArohiBus.toast("✗ সার্ভিস চালু হয়নি — ব্যাটারি অপটিমাইজেশন চেক করুন (SYSTEM HEALTH)")
                    TtsManager.speak("সার্ভিস চালু করা গেল না শুভ্র। SYSTEM HEALTH স্ক্রিনে ব্যাটারি সেটিং ঠিক করে আবার চেষ্টা করো।")
                }
            }
        }

        // ---------- STOP AROHI (mic fully off — deterministic) ----------
        binding.circleStop.setOnClickListener {
            val ctx = requireContext()
            SettingsStore.get(ctx).wakeWordEnabled = false // v6: মাইক সম্পূর্ণ বন্ধ
            ArohiForegroundService.stop(ctx)
            ArohiBus.resetToIdle()
            ArohiBus.sessionOpen.value = false
            ArohiBus.toast("আরোহী বন্ধ — মাইক সম্পূর্ণ বন্ধ হয়ে গেছে")
        }

        // ---------- BRAIN circle (real API test) ----------
        binding.circleBrain.setOnClickListener { testBrain(manual = true) }
        binding.btnSetup.setOnClickListener { (activity as? MainActivity)?.showSetupWizard() }

        // ---------- 6 function buttons (all REAL navigation) ----------
        binding.gridAssistant.setOnClickListener { openDestination(R.id.nav_assistant) }
        binding.gridDevice.setOnClickListener { openDestination(R.id.nav_device) }
        binding.gridMemory.setOnClickListener { openDestination(R.id.nav_memory) }
        binding.gridAutomation.setOnClickListener { openDestination(R.id.nav_automation) }
        binding.gridVision.setOnClickListener { openDestination(R.id.nav_vision) }
        binding.gridMore.setOnClickListener {
            (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
        }

        binding.recentList.layoutManager = LinearLayoutManager(requireContext())
        buildStatusGrid()

        // ---------- live bindings ----------
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.serviceRunning.collect { running ->
                binding.serviceStatus.text = if (running)
                    "সার্ভিস: চালু (২৪/৭) — মাইক ওয়েক-স্ট্যান্ডবাইয়ে, 'আরোহী' বললেই সত্যিকারের শোনা শুরু"
                else "সার্ভিস: বন্ধ — মাইক সম্পূর্ণ নীরব"
                binding.serviceStatus.setTextColor(ContextCompat.getColor(requireContext(),
                    if (running) R.color.arohi_success else R.color.arohi_text_dim))
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.state.collect { st ->
                val txt = when (st) {
                    ConvState.IDLE -> if (ArohiBus.serviceRunning.value) "DORMANT — 'আরোহী' বললেই শুনবো" else "IDLE — শুনছি না"
                    ConvState.DORMANT -> "DORMANT — ওয়েক স্ট্যান্ডবাই ('আরোহী' বললেই জাগবো)"
                    ConvState.LISTENING -> "LISTENING — শুনছি…"
                    ConvState.THINKING -> "THINKING — ভাবছি…"
                    ConvState.EXECUTING -> "EXECUTING — কাজ করছি…"
                    ConvState.SPEAKING -> "SPEAKING — বলছি…"
                    ConvState.ERROR -> "ERROR — সমস্যা হয়েছে"
                }
                binding.stateText.text = txt
                // listening pill — honest per real state
                val (pillTxt, pillColor) = when (st) {
                    ConvState.LISTENING -> "● Listening... Speak Now..." to R.color.arohi_success
                    ConvState.SPEAKING -> "● বলছি… (কথা বলা থামাতে শুধু বলুন)" to R.color.arohi_cyan
                    ConvState.THINKING, ConvState.EXECUTING -> "● কাজ করছি…" to R.color.arohi_warn
                    ConvState.ERROR -> "● সমস্যা — আবার বলুন" to R.color.arohi_error
                    ConvState.DORMANT -> "● ওয়েক স্ট্যান্ডবাই — \"আরোহী\" বলুন" to R.color.arohi_cyan
                    else -> if (ArohiBus.serviceRunning.value)
                        "● ওয়েক স্ট্যান্ডবাই — \"আরোহী\" বলুন" to R.color.arohi_cyan
                    else "● শুনছি না — চালু করতে Start AROHI চাপুন" to R.color.arohi_text_dim
                }
                binding.listeningPill.text = pillTxt
                binding.listeningPill.setTextColor(ContextCompat.getColor(requireContext(), pillColor))
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.emotion.collect { e -> binding.emotionText.text = "আবেগ: ${e.labelBn}" }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.activeAgent.collect { agent ->
                binding.agentText.text = if (agent.isBlank()) "" else "🤖 $agent"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.adaptiveMode.collect { mode ->
                val m = try { com.arohi.ai.core.AdaptiveEngine.Mode.valueOf(mode) } catch (_: Exception) { null }
                binding.adaptiveText.text = m?.let { "⚡ ${it.labelBn}" } ?: ""
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.taskProgress.collect { tp ->
                if (tp != null) {
                    binding.taskProgress.visibility = View.VISIBLE
                    binding.taskProgress.text = "⚙ ${tp.first} — ${tp.second}%"
                } else binding.taskProgress.visibility = View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.offline.collect { off ->
                binding.offlineBanner.visibility = if (off) View.VISIBLE else View.GONE
                if (off) ApiHealth.refreshFlags(appCtx)
            }
        }
        // live refresh: status pills + grid + memory count (5s cycle)
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(5000)
                ApiHealth.refreshFlags(appCtx)
                refreshPills(appCtx)
            }
        }
        val mem = MemoryRepository.get(appCtx)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val count = try { mem.memoryCount() } catch (_: Exception) { 0 }
            val activities = try { mem.recentActivities(4) } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                binding.memoryCount.text = "$count স্মৃতি জমা আছে"
                val adapter = ActivityAdapter()
                adapter.submit(activities)
                binding.recentList.adapter = adapter
            }
        }

        buildQuickActions()
        refreshPills(appCtx)
        testBrain(manual = false)
    }

    /** helper for the 6 grid buttons — opens a drawer destination directly. */
    private fun openDestination(navId: Int) {
        (activity as? MainActivity)?.openDestination(navId)
    }

    // ---------- 4 real status pills ----------
    private fun refreshPills(appCtx: android.content.Context) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val online = com.arohi.ai.core.NetworkMonitor.isOnline
            val label = com.arohi.ai.core.NetworkMonitor.networkLabel
            val memCount = try { MemoryRepository.get(appCtx).memoryCount() } catch (_: Exception) { 0 }
            val battery = try { DeviceController.batterySnapshot(appCtx) } catch (_: Exception) { null }
            val secure = ApiHealth.isBatteryUnrestricted(appCtx) && PermissionHelper.hasMicrophone(appCtx)
            withContext(Dispatchers.Main) {
                binding.pillOnlineText.text = if (online) "অনলাইন\n$label" else "অফলাইন"
                binding.pillOnlineText.setTextColor(ContextCompat.getColor(ctx,
                    if (online) R.color.arohi_success else R.color.arohi_error))
                binding.pillMemoryText.text = "স্মৃতি\n$memCount টি"
                val bat = battery?.first
                binding.pillBatteryText.text = if (bat != null) "ব্যাটারি\n$bat%" else "ব্যাটারি\n?"
                binding.pillBatteryText.setTextColor(ContextCompat.getColor(ctx,
                    when { bat == null -> R.color.arohi_text_dim; bat >= 50 -> R.color.arohi_success
                        bat >= 20 -> R.color.arohi_warn; else -> R.color.arohi_error }))
                binding.pillSecureText.text = if (secure) "সুরক্ষিত\nসক্রিয়" else "সুরক্ষা\nচেক করুন"
                binding.pillSecureText.setTextColor(ContextCompat.getColor(ctx,
                    if (secure) R.color.arohi_success else R.color.arohi_warn))
            }
        }
    }

    /**
     * v4: the brain check is explicit + cached — visiting Home doesn't burn an
     * API call every time; the user tests manually, results are honest.
     */
    private fun testBrain(manual: Boolean) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (manual) {
                withContext(Dispatchers.Main) {
                    binding.brainStatus.text = "টেস্ট হচ্ছে…"
                    binding.brainStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.arohi_warn))
                }
            } else {
                ApiHealth.refreshFlags(ctx)
                val cached = ApiHealth.gemini.value
                withContext(Dispatchers.Main) { showBrain(cached) }
                if (cached.st == ApiHealth.St.NOT_CONFIGURED && !com.arohi.ai.brain.ArohiBrain.anyConfigured(ctx)) return@launch
            }
            val r = ApiHealth.testGemini(ctx)
            withContext(Dispatchers.Main) {
                showBrain(ApiHealth.gemini.value)
                if (manual) {
                    ArohiBus.toast(if (r.ok) "ব্রেইন কানেক্টেড ✓" else "ব্রেইন: ${r.message}")
                    if (!r.ok) TtsManager.speak("ব্রেইন কানেক্ট হতে পারছি না। ${r.message} ${r.suggestion}")
                }
            }
        }
    }

    private fun showBrain(item: ApiHealth.Item) {
        binding.brainStatus.text = item.detail
        binding.brainStatus.setTextColor(ContextCompat.getColor(requireContext(), when (item.st) {
            ApiHealth.St.ACTIVE -> R.color.arohi_success
            ApiHealth.St.NOT_CONFIGURED, ApiHealth.St.UNSUPPORTED -> R.color.arohi_text_dim
            ApiHealth.St.CONNECTING -> R.color.arohi_warn
            else -> R.color.arohi_error
        }))
        binding.brainDetail.text = when (item.st) {
            ApiHealth.St.ACTIVE -> com.arohi.ai.brain.ArohiBrain.statusLineBn(requireContext())
            ApiHealth.St.NOT_CONFIGURED -> "API CENTER-এ ফ্রি কী নিয়ে দাও (Google AI Studio / OpenAI বাটন আছে)"
            else -> "কারণ: ${item.detail} — API CENTER-এ সমাধান দেখো"
        }
    }

    // ---------- live colored status chips (preserved) ----------
    private fun buildStatusGrid() {
        val ctx = requireContext()
        val grid = binding.statusGrid
        grid.removeAllViews()

        val refs = ApiHealth.all()
        refs.forEach { flow ->
            val chip = TextView(ctx).apply {
                textSize = 12f
                setPadding(24, 12, 24, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 3, 0, 3) }
            }
            grid.addView(chip)
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { item ->
                    chip.text = "● ${item.label}: ${item.detail}"
                    chip.setTextColor(ContextCompat.getColor(ctx, when (item.st) {
                        ApiHealth.St.ACTIVE -> R.color.arohi_success
                        ApiHealth.St.NOT_CONFIGURED, ApiHealth.St.UNSUPPORTED -> R.color.arohi_text_dim
                        ApiHealth.St.CONNECTING -> R.color.arohi_warn
                        else -> R.color.arohi_error
                    }))
                }
            }
        }
        ApiHealth.refreshFlags(requireContext().applicationContext)
    }

    // ---------- quick actions (all REAL, preserved) ----------
    private fun buildQuickActions() {
        val ctx = requireContext()
        val actions = listOf(
            "টর্চ" to { TtsManager.speak(DeviceController.setFlashlight(ctx, true)) },
            "স্ক্রিনশট" to { startScreenshot() },
            "ক্যামেরা ভিশন" to { openDestination(R.id.nav_vision) },
            "নোটিফিকেশন" to { openDestination(R.id.nav_notifications) },
            "মেসেজ" to { openDestination(R.id.nav_messages) },
            "API CENTER" to { openDestination(R.id.nav_api_center) },
            "SYSTEM HEALTH" to { openDestination(R.id.nav_system_health) },
            "অটোমেশন" to { openDestination(R.id.nav_automation) },
            "ফাইল ও গ্যালারি" to { openDestination(R.id.nav_files) },
            "স্ট্যাটাস বলো" to { TtsManager.speak(DeviceController.fullStatus(ctx)) },
            "সিস্টেম হেলথ রিপোর্ট" to { TtsManager.speak(ApiHealth.summaryBn(ctx.applicationContext)) },
            "অ্যাপ খুলুন" to { showAppPicker() },
            "সেটিংস" to { openDestination(R.id.nav_settings) }
        )
        binding.quickRow.removeAllViews()
        actions.forEach { (label, action) ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = label
                isClickable = true
                setOnClickListener { action() }
            }
            binding.quickRow.addView(chip)
        }
    }

    private fun startScreenshot() {
        (activity as? MainActivity)?.let { m ->
            m.binding.bottomNav.selectedItemId = R.id.nav_device
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(600)
                ArohiBus.toast("TRIGGER_SCREENSHOT")
            }
        }
    }

    private fun showAppPicker() {
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val apps = AppLauncher.listApps(ctx)
            withContext(Dispatchers.Main) {
                val labels = apps.take(40).map { it.label }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("অ্যাপ খুলুন (${apps.size} টি ইনস্টল করা)")
                    .setItems(labels) { _, which ->
                        AppLauncher.open(ctx, apps[which].pkg)
                    }
                    .setNegativeButton("বন্ধ", null)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

/** tiny adapter for recent activities (preserved) */
private class ActivityAdapter : RecyclerView.Adapter<ActivityAdapter.AVH>() {
    private val items = mutableListOf<com.arohi.ai.memory.ActivityLog>()
    fun submit(list: List<com.arohi.ai.memory.ActivityLog>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
    class AVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.activity_text)
        val time: TextView = v.findViewById(R.id.activity_time)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AVH =
        AVH(LayoutInflater.from(parent.context).inflate(R.layout.item_activity, parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: AVH, position: Int) {
        holder.text.text = "${items[position].type}: ${items[position].detail}"
        holder.time.text = Extensions.dateTime(items[position].timestamp)
    }
}
