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
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.automation.AutoReplyEngine
import com.arohi.ai.services.ArohiNotificationListener
import com.arohi.ai.util.Extensions
import com.arohi.ai.voice.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 — DIRECT MESSAGING HUB (master spec section 10, সর্বোচ্চ গুরুত্ব).
 * Real send pipeline visibility: which platforms have Inline Reply, which
 * need open-and-type, honest per-message send results, draft queue.
 */
class MessagesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val s = SettingsStore.get(appCtx)
        val col = UiKit.column(ctx)
        val scroll = UiKit.scrollRoot(ctx).apply { addView(col) }

        // ---------- pipeline status (REAL) ----------
        col.addView(UiKit.section(ctx, "মেসেজিং পাইপলাইন (বাস্তব)"))
        val pipeTv = UiKit.statusLine(ctx)
        val notifOk = PermissionHelper.isNotificationListenerEnabled(appCtx)
        val accOk = PermissionHelper.isAccessibilityEnabled(appCtx)
        pipeTv.text = buildString {
            append("● নোটিফিকেশন লিসেনার: ${if (notifOk) "চালু" else "বন্ধ"}\n")
            append("● অ্যাক্সেসিবিলিটি (রিপ্লাই পাঠানো): ${if (accOk) "চালু" else "বন্ধ"}\n")
            append("● Inline Reply চ্যানেল: যেসব নোটিফিকেশনে অ্যাপ এটি দেয়, আরোহী সরাসরি পাঠায়\n")
            append("● ব্যর্থ হলে: ড্রাফট চ্যাটে সেভ + সৎ রিপোর্ট (ভুয়া 'Sent' কখনো নেই)")
        }
        pipeTv.setTextColor(ctx.getColor(if (notifOk && accOk) R.color.arohi_success else R.color.arohi_warn))
        col.addView(UiKit.card(ctx,
            pipeTv,
            UiKit.button(ctx, "নোটিফিকেশন লিসেনার চালু") {
                try {
                    startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            },
            UiKit.button(ctx, "অ্যাক্সেসিবিলিটি চালু") {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            },
            UiKit.button(ctx, "রিপ্লাই মোড বদলাও (এখন: ${modeLabel(s.autoReplyMode)})") {
                val opts = arrayOf(
                    "অটো — নিজে পাঠাবে (সুপারিশকৃত)" to SettingsStore.AUTO_REPLY_AUTO,
                    "আগে জিজ্ঞেস করবে" to SettingsStore.AUTO_REPLY_ASK,
                    "বন্ধ" to SettingsStore.AUTO_REPLY_OFF)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("অটো-রিপ্লাই মোড")
                    .setItems(opts.map { it.first }.toTypedArray()) { _, which ->
                        s.autoReplyMode = opts[which].second
                        pipeTv.text = pipeTv.text.toString().replace(Regex("রিপ্লাই মোড: .*"),
                            "রিপ্লাই মোড: ${modeLabel(s.autoReplyMode)}")
                        ArohiBus.toast("মোড: ${modeLabel(s.autoReplyMode)}")
                    }
                    .setNegativeButton("বন্ধ", null).show()
            }))

        // ---------- quick actions ----------
        col.addView(UiKit.section(ctx, "দ্রুত মেসেজ অ্যাকশন"))
        col.addView(UiKit.card(ctx,
            UiKit.button(ctx, "সর্বশেষ মেসেজে রিপ্লাই পাঠাও…") { replyToLastDialog() },
            UiKit.button(ctx, "SMS ড্রাফট খুলুন (নিজে পাঠাবেন)") { smsDraftDialog() },
            UiKit.button(ctx, "WhatsApp ড্রাফট খুলুন") { waDraftDialog() },
            UiKit.button(ctx, "নোটিফিকেশন সামারি শুনুন") {
                TtsManager.speak(ArohiNotificationListener.summaryBn())
            }))

        // ---------- live message list ----------
        col.addView(UiKit.section(ctx, "আসা মেসেজ (মেসেজিং/সোশ্যাল অ্যাপ)"))
        val listBox = UiKit.column(ctx).apply { setPadding(0, 0, 0, 0) }
        col.addView(UiKit.card(ctx, listBox))

        fun refreshList() {
            listBox.removeAllViews()
            val items = synchronized(ArohiNotificationListener.recent) {
                ArohiNotificationListener.recent.toList()
                    .filter { it.pkg in ArohiNotificationListener.MESSAGING_PACKAGES ||
                              it.pkg in ArohiNotificationListener.SOCIAL_PACKAGES }
                    .asReversed()
            }
            if (items.isEmpty()) {
                listBox.addView(UiKit.note(ctx, "মেসেজ জমা হয়নি — WhatsApp/Messenger/Telegram/Instagram-এ মেসেজ এলে এখানে দেখা যাবে"))
                return
            }
            for (item in items.take(10)) {
                val card = UiKit.card(ctx,
                    TextView(ctx).apply {
                        text = "${item.appLabel} • ${Extensions.dateTime(item.timestamp)}\n" +
                            "${item.title}: ${item.text.take(100)}"
                        textSize = 13f
                        setTextColor(ctx.getColor(R.color.arohi_text))
                    },
                    TextView(ctx).apply {
                        text = if (item.replyActions.isNotEmpty()) "Inline Reply ✓ সরাসরি পাঠানো যাবে"
                            else "অ্যাপ খুলে টাইপ করার চেষ্টা হবে"
                        textSize = 11f
                        setTextColor(ctx.getColor(if (item.replyActions.isNotEmpty()) R.color.arohi_success else R.color.arohi_warn))
                    },
                    UiKit.button(ctx, "রিপ্লাই পাঠাও…") { replyDialog(item) })
                listBox.addView(card)
            }
        }
        refreshList()
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) { delay(6000); withContext(Dispatchers.Main) { refreshList() } }
        }

        return scroll
    }

    private fun modeLabel(m: String) = when (m) {
        SettingsStore.AUTO_REPLY_AUTO -> "অটো"
        SettingsStore.AUTO_REPLY_ASK -> "জিজ্ঞেস করে"
        else -> "বন্ধ"
    }

    private fun replyDialog(item: ArohiNotificationListener.Companion.NotifItem) {
        val ctx = requireContext()
        val input = EditText(ctx).apply { hint = "রিপ্লাই লিখুন — পাঠানোর আগে নিশ্চিত করবেন"; setPadding(48, 24, 48, 8) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("রিপ্লাই — ${item.appLabel} (${item.title})")
            .setView(input)
            .setPositiveButton("পাঠাও") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) { ArohiBus.toast("খালি রিপ্লাই পাঠানো হয় না"); return@setPositiveButton }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("নিশ্চিত পাঠাবেন?")
                    .setMessage("${item.appLabel} → ${item.title}:\n\"$text\"")
                    .setPositiveButton("হ্যাঁ, পাঠাও") { _, _ ->
                        lifecycleScope.launch {
                            val outcome = AutoReplyEngine.sendToItem(ctx.applicationContext, item, text)
                            ArohiBus.toast(if (outcome.success) "✓ পাঠানো হয়েছে (${outcome.how})" else "✗ ${outcome.detail}")
                        }
                    }
                    .setNegativeButton("না", null).show()
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun replyToLastDialog() {
        val ctx = requireContext()
        val item = ArohiNotificationListener.latestReplyable()
        if (item == null) {
            ArohiBus.toast("রিপ্লাই দেওয়ার মতো কোনো মেসেজ জমা নেই")
            return
        }
        replyDialog(item)
    }

    private fun smsDraftDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val who = EditText(ctx).apply { hint = "প্রাপক (নাম/নাম্বার)" }
        val what = EditText(ctx).apply { hint = "মেসেজ লিখুন" }
        layout.addView(who); layout.addView(what)
        MaterialAlertDialogBuilder(ctx).setTitle("SMS ড্রাফট (Send চাপলে আপনি পাঠাবেন)")
            .setView(layout)
            .setPositiveButton("ড্রাফট খুলুন") { _, _ ->
                TtsManager.speak(com.arohi.ai.actions.PeopleHelper.smsDraft(ctx, who.text.toString(), what.text.toString()))
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun waDraftDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val who = EditText(ctx).apply { hint = "প্রাপক (নাম/নাম্বার)" }
        val what = EditText(ctx).apply { hint = "মেসেজ লিখুন" }
        layout.addView(who); layout.addView(what)
        MaterialAlertDialogBuilder(ctx).setTitle("WhatsApp ড্রাফট (Send চাপলে আপনি পাঠাবেন)")
            .setView(layout)
            .setPositiveButton("ড্রাফট খুলুন") { _, _ ->
                TtsManager.speak(com.arohi.ai.actions.PeopleHelper.whatsappDraft(ctx, who.text.toString(), what.text.toString()))
            }
            .setNegativeButton("বাতিল", null).show()
    }
}
