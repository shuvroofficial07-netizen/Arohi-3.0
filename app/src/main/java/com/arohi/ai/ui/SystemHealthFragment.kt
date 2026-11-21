package com.arohi.ai.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arohi.ai.MainActivity
import com.arohi.ai.R
import com.arohi.ai.core.ApiHealth
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.EmergencyStop
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.AdaptiveEngine
import com.arohi.ai.core.NetworkMonitor
import com.arohi.ai.services.ArohiForegroundService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v4 — COMPLETE SYSTEM HEALTH DASHBOARD (master spec section 18).
 * Every service's REAL status with one of the 7 states, plus a direct
 * Fix/Setup action per item and the true device compatibility profile.
 */
class SystemHealthFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val col = UiKit.column(ctx)
        val scroll = UiKit.scrollRoot(ctx).apply { addView(col) }

        // ---------- device profile (real values) ----------
        col.addView(UiKit.section(ctx, "ডিভাইস প্রোফাইল"))
        col.addView(UiKit.card(ctx,
            TextView(ctx).apply {
                text = ApiHealth.deviceProfileBn(appCtx); textSize = 14f
                setTextColor(ctx.getColor(R.color.arohi_text))
            },
            UiKit.note(ctx, "সামঞ্জস্য: Android 9+ সাপোর্টেড — ম্যানুফ্যাকচারার-ভিত্তিক ব্যাটারি গাইড নিচে।"),
            adaptiveText(ctx)))

        // ---------- live status list with FIX buttons ----------
        col.addView(UiKit.section(ctx, "সব সিস্টেমের বাস্তব স্ট্যাটাস"))
        val rowsBox = UiKit.column(ctx).apply { setPadding(0, 0, 0, 0) }
        col.addView(UiKit.card(ctx, rowsBox))

        val fixButtons = mutableMapOf<String, android.widget.LinearLayout>()

        fun stColor(st: ApiHealth.St) = ctx.getColor(when (st) {
            ApiHealth.St.ACTIVE -> R.color.arohi_success
            ApiHealth.St.NOT_CONFIGURED, ApiHealth.St.UNSUPPORTED -> R.color.arohi_text_dim
            ApiHealth.St.CONNECTING -> R.color.arohi_warn
            else -> R.color.arohi_error
        })

        fun refreshAll() {
            ApiHealth.refreshFlags(appCtx)
            for (flow in ApiHealth.all()) {
                val item = flow.value
                val row = fixButtons[item.label]
                val tv = row?.getChildAt(0) as? TextView
                val btn = row?.getChildAt(1) as? com.google.android.material.button.MaterialButton
                tv?.text = "● ${item.label}: ${item.detail}"
                tv?.setTextColor(stColor(item.st))
                btn?.visibility = if (fixHint(item.label).isNotBlank()) View.VISIBLE else View.GONE
            }
        }

        for (flow in ApiHealth.all()) {
            val item = flow.value
            val tv = TextView(ctx).apply {
                textSize = 13f
                setPadding(0, 8, 0, 8)
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val fix = com.google.android.material.button.MaterialButton(ctx).apply {
                text = "FIX"; textSize = 11f; isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener { openFix(item.label) }
            }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
                addView(tv); addView(fix)
            }
            fixButtons[item.label] = row
            rowsBox.addView(row)
            // live update per item
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect {
                    tv.text = "● ${it.label}: ${it.detail}"
                    tv.setTextColor(stColor(it.st))
                    fix.visibility = if (fixHint(it.label).isNotBlank()) View.VISIBLE else View.GONE
                }
            }
        }
        refreshAll()

        // refresh loop
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) { delay(5000); ApiHealth.refreshFlags(appCtx) }
        }

        // ---------- manufacturer battery guide ----------
        col.addView(UiKit.section(ctx, "২৪/৭ ব্যাকগ্রাউন্ড গাইড (তোমার ব্র্যান্ড অনুযায়ী)"))
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, manufacturerGuideBn(appCtx), warn = true),
            UiKit.button(ctx, "ব্যাটারি অপটিমাইজেশন সেটিংস খুলুন") {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
            },
            UiKit.button(ctx, "অ্যাপ ইনফো (অটো-স্টার্ট/ব্যাকগ্রাউন্ড অনুমতি)") {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${appCtx.packageName}")))
                } catch (_: Exception) {}
            }))

        // ---------- emergency controls ----------
        col.addView(UiKit.section(ctx, "নিয়ন্ত্রণ"))
        col.addView(UiKit.card(ctx,
            UiKit.button(ctx, "সার্ভিস রিস্টার্ট") {
                ArohiForegroundService.stop(appCtx)
                ArohiForegroundService.start(appCtx)
                ArohiBus.toast("২৪/৭ সার্ভিস রিস্টার্ট হয়েছে")
            },
            UiKit.button(ctx, "🚨 EMERGENCY STOP (সব থামাও)") {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("EMERGENCY STOP?")
                    .setMessage("সব কাজ, কথা ও অটোমেশন থামবে।")
                    .setPositiveButton("হ্যাঁ") { _, _ -> EmergencyStop.execute(appCtx) }
                    .setNegativeButton("না", null).show()
            },
            UiKit.button(ctx, "SETUP WIZARD (সব অনুমতি একসাথে)") {
                (activity as? MainActivity)?.showSetupWizard()
            }))

        return scroll
    }

    private fun adaptiveText(ctx: android.content.Context): TextView {
        val tv = UiKit.statusLine(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            com.arohi.ai.core.ArohiBus.adaptiveMode.collect { mode ->
                val m = try { AdaptiveEngine.Mode.valueOf(mode) } catch (_: Exception) { AdaptiveEngine.current() }
                tv.text = "অ্যাডাপটিভ প্রসেসিং: ${m.labelBn} • নেটওয়ার্ক: ${NetworkMonitor.networkLabel}"
            }
        }
        return tv
    }

    /** Real fix hint per system item — opens the exact settings surface. */
    private fun fixHint(label: String): String = when {
        label.contains("মাইক্রোফোন") -> "মাইক্রোফোন"
        label.contains("নোটিফিকেশন লিসেনার") -> "nl"
        label.contains("অ্যাক্সেসিবিলিটি") -> "acc"
        label.contains("ব্যাটারি") -> "bat"
        label.contains("২৪/৭") || label.contains("সার্ভিস") -> "svc"
        label.contains("জেমিনি") || label.contains("ElevenLabs") -> "api"
        label.contains("কন্টাক্ট") -> "contacts"
        label.contains("ফোন কল") -> "phone"
        label.contains("ক্যামেরা") || label.contains("ভিশন") -> "camera"
        label.contains("টেক্সট-টু-স্পিচ") -> "tts"
        else -> ""
    }

    private fun openFix(label: String) {
        val ctx = requireContext()
        when (fixHint(label)) {
            "mic" -> PermissionHelper.request(requireActivity(), arrayOf(android.Manifest.permission.RECORD_AUDIO), 105)
            "nl" -> try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (_: Exception) {}
            "acc" -> try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            "bat" -> try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"))) } catch (_: Exception) {}
            "svc" -> try { ArohiForegroundService.start(ctx.applicationContext); ArohiBus.toast("সার্ভিস চালু হয়েছে") } catch (_: Exception) {}
            "api" -> (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.nav_settings
            "contacts" -> PermissionHelper.request(requireActivity(), arrayOf(android.Manifest.permission.READ_CONTACTS), 106)
            "phone" -> PermissionHelper.request(requireActivity(), arrayOf(android.Manifest.permission.CALL_PHONE), 107)
            "camera" -> PermissionHelper.request(requireActivity(), arrayOf(android.Manifest.permission.CAMERA), 108)
            "tts" -> try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
        }
    }

    private fun manufacturerGuideBn(ctx: android.content.Context): String {
        val m = android.os.Build.MANUFACTURER.lowercase()
        return when {
            m.contains("samsung") ->
                "Samsung: Settings → Battery → Background limits → 'Never sleeping apps'-এ Arohi AI যোগ করো। " +
                    "Device care → Battery → App power management → Arohi AI 'Unrestricted'।"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                "Xiaomi/Redmi/POCO: Settings → Apps → Arohi AI → Autostart চালু + Battery saver 'No restrictions'। " +
                    "Security app → Boost speed-এর লিস্টে Arohi AI লক করো।"
            m.contains("oppo") || m.contains("realme") ->
                "OPPO/Realme: Settings → Battery → App battery management → Arohi AI 'Allow background'। " +
                    "App info → Allow auto-startup চালু।"
            m.contains("vivo") ->
                "Vivo: i Manager → App manager → Autostart-এ Arohi AI চালু + Battery → High background power consumption অনুমোদন।"
            m.contains("oneplus") ->
                "OnePlus: Settings → Battery → Battery optimization → Arohi AI 'Don't optimize'। App info → Battery → Intelligent control বন্ধ।"
            m.contains("huawei") || m.contains("honor") ->
                "Huawei/Honor: Settings → Battery → App launch → Arohi AI: Manage manually (Auto-start, Secondary launch, Run in background — সব চালু)।"
            m.contains("asus") ->
                "ASUS: Mobile Manager → PowerMaster → Arohi AI-কে 'untracked' তালিকায় দাও।"
            m.contains("motorola") ->
                "Motorola: Settings → Battery → Battery optimization → Arohi AI 'Don't optimize'।"
            m.contains("google") ->
                "Pixel: Settings → Apps → Arohi AI → Battery → Unrestricted।"
            m.contains("nothing") ->
                "Nothing Phone: Settings → Apps → Arohi AI → Battery → Unrestricted + Autostart চালু।"
            else -> "সাধারণ নিয়ম: Settings → Apps → Arohi AI → Battery → 'Unrestricted/Don't optimize' দাও, " +
                "Autostart/Auto-launch চালু করো — তাহলে ২৪/৭ সার্ভিস বন্ধ হবে না।"
        }
    }
}
