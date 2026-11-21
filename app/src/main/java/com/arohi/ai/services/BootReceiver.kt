package com.arohi.ai.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arohi.ai.core.SettingsStore

/** Re-launches Arohi after reboot — ONLY if the user's 24/7 mode was active.
 *  v6 MIC CONTRACT: START AROHI চাপলেই wakeWordEnabled=true হয়; STOP চাপলে
 *  false। তাই রিবুটে সার্ভিস ফিরবে কেবল তখনই যখন ব্যবহারকারী আসলে ২৪/৭ মোড
 *  চালু রেখেছিলেন — বন্ধ করা অবস্থায় রিবুটেও মাইক নীরব থাকবে। */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val s = SettingsStore.get(context)
            if (s.startOnBoot && s.wakeWordEnabled) {
                try { ArohiForegroundService.start(context) } catch (_: Exception) {}
            }
        }
    }
}
