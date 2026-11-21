package com.arohi.ai.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.arohi.ai.R
import com.arohi.ai.core.ArohiBus

/**
 * v4 — DEVELOPER AND SUPPORT CENTER (master spec section 23).
 * The developer info is EXACTLY as specified — no other name is used:
 *   Created by "Shù Vrô"
 *   Telegram: @Shuvrojr07
 *   Facebook: https://www.facebook.com/shuvromridha77
 * Both buttons open the REAL destination (Telegram app first, browser fallback).
 */
class SupportCenterFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val col = UiKit.column(ctx)
        val scroll = UiKit.scrollRoot(ctx).apply { addView(col) }

        col.addView(UiKit.section(ctx, "ডেভেলপার ও সাপোর্ট"))
        col.addView(UiKit.card(ctx,
            android.widget.TextView(ctx).apply {
                text = "Created by \"Shù Vrô\""
                textSize = 22f
                setTextColor(ctx.getColor(R.color.arohi_cyan))
                setPadding(0, 8, 0, 8)
            },
            android.widget.TextView(ctx).apply {
                text = "AROHI AI 4.0 — Universal JARVIS + Personal AI Companion\n" +
                    "মালিক ও ব্যবহারকারী: শুভ্র (Shubho)"
                textSize = 13f
                setTextColor(ctx.getColor(R.color.arohi_text_dim))
                setPadding(0, 0, 0, 8)
            },
            UiKit.button(ctx, "✈ Telegram Support: @Shuvrojr07") { openTelegram() },
            UiKit.button(ctx, "📘 Facebook Support: shuvromridha77") {
                open("https://www.facebook.com/shuvromridha77",
                    "fb://facewebmodal/f?href=https://www.facebook.com/shuvromridha77")
            }))

        col.addView(UiKit.section(ctx, "সমস্যা রিপোর্ট করতে"))
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, "রিপোর্ট করার সময় বলুন: ডিভাইসের নাম-মডেল, Android ভার্সন, কোন ফিচারে সমস্যা, " +
                "আর SYSTEM HEALTH স্ক্রিনের স্ট্যাটাস — তাহলে দ্রুত সমাধান পাওয়া যাবে।"),
            UiKit.button(ctx, "SYSTEM HEALTH স্ক্রিন খুলুন") {
                val act = activity as? com.arohi.ai.MainActivity
                if (act != null) {
                    act.binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
                }
            },
            UiKit.button(ctx, "সাপোর্ট মেসেজ টেক্সট কপি করো") {
                val info = "AROHI AI 4.0 সাপোর্ট রিপোর্ট\n${com.arohi.ai.core.ApiHealth.deviceProfileBn(ctx)}\n" +
                    "স্ট্যাটাস: ${com.arohi.ai.core.ApiHealth.summaryBn(ctx)}"
                try {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("arohi_support", info))
                    ArohiBus.toast("রিপোর্ট কপি হয়েছে — Telegram/Facebook-এ পেস্ট করো")
                } catch (_: Exception) { ArohiBus.toast("কপি করা যায়নি") }
            }))

        col.addView(UiKit.section(ctx, "আরোহীর সততার ঘোষণা"))
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, "আরোহী কখনো ভুয়া সফল স্ট্যাটাস দেখায় না: মেসেজ সত্যিই না পাঠালে 'Sent' বলে না, " +
                "PC/স্মার্ট হোম কনফিগ ছাড়া 'connected' বলে না, ক্যামেরা চালু না থাকলে 'Camera Active' দেখায় না। " +
                "প্রতিটি স্ট্যাটাস সিস্টেম থেকে আসল মান এনে দেখানো হয়।")))

        return scroll
    }

    private fun openTelegram() {
        val ctx = requireContext()
        // official Telegram app route first, browser fallback
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("tg://resolve?domain=Shuvrojr07")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            open("https://t.me/Shuvrojr07")
        }
    }

    private fun open(url: String, scheme: String? = null) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            if (scheme != null) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { ArohiBus.toast("খোলা যায়নি") }
            } else ArohiBus.toast("ব্রাউজার খোলা যায়নি")
        }
    }
}
