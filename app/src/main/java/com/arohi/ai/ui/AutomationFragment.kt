package com.arohi.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arohi.ai.R
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.EmergencyStop
import com.arohi.ai.automation.RoutineScheduler
import com.arohi.ai.automation.TaskEngine
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.util.Extensions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 — AUTOMATION screen: real task engine control, routine management,
 * honest history log and the emergency cancel — everything actually wired.
 */
class AutomationFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val col = UiKit.column(ctx)
        val scroll = UiKit.scrollRoot(ctx).apply { addView(col) }

        // ---------- engine status ----------
        col.addView(UiKit.section(ctx, "অটোমেশন ইঞ্জিন (বাস্তব)"))
        val engineStatus = UiKit.statusLine(ctx)
        val progressStatus = UiKit.statusLine(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            com.arohi.ai.core.ArohiBus.taskProgress.collect { tp ->
                progressStatus.text = tp?.let { "⚙ ${it.first} — ${it.second}%" } ?: "এখন কোনো টাস্ক চলছে না"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                engineStatus.text = if (TaskEngine.get(appCtx).isBusy())
                    "● টাস্ক চলছে — বাতিল করতে নিচের বাটন"
                else "● ইঞ্জিন প্রস্তুত (স্ক্রিন টাচ: " +
                    if (com.arohi.ai.core.PermissionHelper.isAccessibilityEnabled(appCtx)) "চালু)" else "অ্যাক্সেসিবিলিটি দরকার)"
                delay(2000)
            }
        }
        col.addView(UiKit.card(ctx,
            engineStatus, progressStatus,
            UiKit.button(ctx, "নতুন প্ল্যান চালাও (মাল্টি-স্টেপ)") { planDialog() },
            UiKit.button(ctx, "⏹ চলমান টাস্ক বাতিল করো") {
                TaskEngine.get(appCtx).cancel()
                ArohiBus.toast("বাতিলের নির্দেশ দেওয়া হয়েছে — চলমান ধাপ শেষ হলে থামবে")
            }))

        // ---------- routines ----------
        col.addView(UiKit.section(ctx, "রুটিন (নির্দিষ্ট সময়ে অটো-চালু)"))
        col.addView(UiKit.card(ctx,
            TextView(ctx).apply {
                text = if (RoutineScheduler.exactAlarmsAllowed(appCtx))
                    "এক্স্যাক্ট অ্যালার্ম অনুমোদিত — রুটিন নির্ভুল সময়ে চলবে"
                else "এক্স্যাক্ট অ্যালার্ম ব্লকড — রুটিন ±১ মিনিট টাইমে চলবে"
                textSize = 13f; setTextColor(ctx.getColor(R.color.arohi_text_dim))
            },
            UiKit.button(ctx, "রুটিন তৈরি করুন") { routineDialog() },
            UiKit.button(ctx, "রুটিন তালিকা / মুছুন") { routineListDialog() }))

        // ---------- history ----------
        col.addView(UiKit.section(ctx, "অটোমেশন হিস্ট্রি (শুধু বাস্তব ঘটনা)"))
        val historyCard = UiKit.card(ctx)
        col.addView(historyCard)
        val historyTv = TextView(ctx).apply { textSize = 12f }
        historyCard.addView(historyTv)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = try { MemoryRepository.get(appCtx).recentActivities(15) } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                historyTv.text = list.joinToString("\n\n") {
                    "[${Extensions.dateTime(it.timestamp)}] ${it.type}: ${it.detail}"
                }.ifBlank { "এখনো কোনো অটোমেশন লগ হয়নি — প্ল্যান/রুটিন চালালে এখানে জমা হবে" }
            }
        }

        // ---------- emergency ----------
        col.addView(UiKit.section(ctx, "নিরাপত্তা"))
        col.addView(UiKit.card(ctx,
            UiKit.button(ctx, "🚨 EMERGENCY STOP (সব থামাও)") {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("EMERGENCY STOP?")
                    .setMessage("সব কাজ, কথা ও অটোমেশন থামবে; ব্যাকগ্রাউন্ড সার্ভিসও বন্ধ হবে।")
                    .setPositiveButton("হ্যাঁ") { _, _ -> EmergencyStop.execute(appCtx) }
                    .setNegativeButton("না", null).show()
            }))

        return scroll
    }

    private fun planDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val title = EditText(ctx).apply { hint = "প্ল্যানের নাম (যেমন: Morning routine)" }
        val steps = EditText(ctx).apply { hint = "প্রতি লাইনে একটি কাজ:\nyoutube khulo\nbattery bolo\nalarm 8:00" }
        layout.addView(title); layout.addView(steps)
        MaterialAlertDialogBuilder(ctx).setTitle("মাল্টি-স্টেপ প্ল্যান")
            .setView(layout)
            .setPositiveButton("চালাও") { _, _ ->
                val stepList = steps.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (stepList.isEmpty()) { ArohiBus.toast("অন্তত একটি ধাপ দিন"); return@setPositiveButton }
                TaskEngine.get(ctx.applicationContext).runPlan(title.text.toString().ifBlank { "টাস্ক" }, stepList)
                ArohiBus.toast("প্ল্যান শুরু হয়েছে — ${stepList.size} ধাপ")
            }
            .setNegativeButton("বাতিল", null).show()
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
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val list = try { MemoryRepository.get(ctx).routinesNow() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                if (list.isEmpty()) { ArohiBus.toast("কোনো রুটিন নেই"); return@withContext }
                val labels = list.map { "${it.name} — ${it.timeOfDay}" }.toTypedArray()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("রুটিন (${list.size})")
                    .setItems(labels) { _, which ->
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("রুটিন মুছবেন?")
                            .setMessage(labels[which])
                            .setPositiveButton("মুছে ফেলো") { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    MemoryRepository.get(ctx).deleteRoutine(list[which])
                                    withContext(Dispatchers.Main) { RoutineScheduler.rescheduleAll(ctx) }
                                }
                            }
                            .setNegativeButton("না", null).show()
                    }
                    .setNegativeButton("বন্ধ", null).show()
            }
        }
    }
}
