package com.arohi.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arohi.ai.R
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.services.ArohiNotificationListener
import com.arohi.ai.util.Extensions
import com.arohi.ai.voice.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 — UNIVERSAL NOTIFICATION INTELLIGENCE screen.
 * Live list of REAL captured notifications, honest listener status,
 * per-category announce switches and privacy mode.
 */
class NotificationsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val s = SettingsStore.get(appCtx)
        val col = UiKit.column(ctx)
        val scroll = UiKit.scrollRoot(ctx).apply { addView(col) }

        // ---------- real status ----------
        col.addView(UiKit.section(ctx, "নোটিফিকেশন লিসেনার (বাস্তব স্ট্যাটাস)"))
        val statusTv = UiKit.statusLine(ctx)
        val listenerOn = PermissionHelper.isNotificationListenerEnabled(appCtx)
        statusTv.text = if (listenerOn) "● চালু — সব অ্যাপের নোটিফিকেশন পড়া হচ্ছে"
            else "● বন্ধ — সেটিংসে অনুমতি দিতে হবে (কোনো ভুয়া 'পড়া হচ্ছে' দেখানো হয় না)"
        statusTv.setTextColor(ctx.getColor(if (listenerOn) R.color.arohi_success else R.color.arohi_error))
        col.addView(UiKit.card(ctx,
            statusTv,
            UiKit.button(ctx, "নোটিফিকেশন লিসেনার চালু করতে যাও") {
                try {
                    startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { ArohiBus.toast("সেটিংস খোলা যায়নি") }
            },
            UiKit.switch(ctx, "নোটিফিকেশন ভয়েসে ঘোষণা", s.announceNotifications) { on ->
                s.announceNotifications = on
            },
            UiKit.switch(ctx, "বিষয়বস্তুও পড়ে শোনাও (Privacy: অফ = শুধু কে, কোথা থেকে)", s.announceContent) { on ->
                s.announceContent = on
            },
            UiKit.button(ctx, "ঘোষণা টেস্ট করো") {
                TtsManager.speak("টেস্ট ঘোষণা: শুভ্র, এটি আরোহীর নোটিফিকেশন সিস্টেম — কোন অ্যাপ থেকে, কে পাঠিয়েছে আর কী লেখা আছে সব বলে জানাবো।")
            }))

        // ---------- live notification list ----------
        col.addView(UiKit.section(ctx, "সাম্প্রতিক নোটিফিকেশন (যেগুলো আরোহী পেয়েছে)"))
        val listBox = UiKit.column(ctx).apply { setPadding(0, 0, 0, 0) }
        col.addView(UiKit.card(ctx, listBox))

        fun refreshList() {
            listBox.removeAllViews()
            val items = synchronized(ArohiNotificationListener.recent) {
                ArohiNotificationListener.recent.toList().asReversed()
            }
            if (items.isEmpty()) {
                listBox.addView(UiKit.note(ctx,
                    if (listenerOn) "নোটিফিকেশন জমা হয়নি — কিছু আসলে এখানে দেখা যাবে"
                    else "লিসেনার বন্ধ — অনুমতি দিলে সত্যিকারের নোটিফিকেশন এখানে আসবে"))
                return
            }
            for (it in items.take(12)) {
                listBox.addView(UiKit.card(ctx,
                    TextView(ctx).apply {
                        text = "${it.appLabel} • ${Extensions.dateTime(it.timestamp)}\n" +
                            "${it.title.ifBlank { "(শিরোনাম নেই)" }}\n${it.text.take(120)}"
                        textSize = 13f
                        setTextColor(ctx.getColor(R.color.arohi_text))
                    },
                    TextView(ctx).apply {
                        text = "${it.category} • রিপ্লাই চ্যানেল: " +
                            (if (it.replyActions.isNotEmpty()) "Inline Reply ✓" else "অ্যাপ খুলে")
                        textSize = 11f
                        setTextColor(ctx.getColor(if (it.replyActions.isNotEmpty()) R.color.arohi_success else R.color.arohi_text_dim))
                    }))
            }
        }
        refreshList()
        // periodic refresh — the list lives in the listener service
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) { delay(6000); withContext(Dispatchers.Main) { refreshList() } }
        }

        // ---------- category info ----------
        col.addView(UiKit.section(ctx, "সমর্থিত প্ল্যাটফর্ম (বাস্তব সাপোর্ট ম্যাপ)"))
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, "WhatsApp / WhatsApp Business / Messenger / Telegram / Instagram / Facebook / Facebook Lite / " +
                "SMS-Messages / Discord / Viber / Snapchat / LinkedIn — নোটিফিকেশন পড়া + Inline Reply চ্যানেল যেখানে অ্যাপ দেয়, সেখানে রিপ্লাই পাঠানো। " +
                "যে অ্যাপ Inline Reply দেয় না, সেখানে আরোহী কথোপকথন খুলে টাইপ করে — ব্যর্থ হলে সৎ রিপোর্ট।")))

        return scroll
    }
}
