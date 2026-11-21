package com.arohi.ai.core

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build

/**
 * v4 — Adaptive Intelligence.
 *
 * Chooses HOW Arohi processes work based on the REAL device state:
 *  LOCAL  — offline or light device: only local intents/memory, no AI call
 *  LIGHT  — weak device (low RAM) or low battery: short prompts, fewer tokens
 *  FULL   — healthy device + online: complete Gemini brain
 *  VISION — image tasks (routed to the vision-capable path)
 *
 * Every decision is published to ArohiBus.adaptiveMode so the UI always shows
 * the true processing mode — nothing is hidden from the user.
 */
object AdaptiveEngine {

    enum class Mode(val labelBn: String) {
        LOCAL("লোকাল (অফলাইন)"),
        LIGHT("লাইট (ডিভাইস-বান্ধব)"),
        FULL("ফুল (সম্পূর্ণ AI)"),
        VISION("ভিশন (ছবি বিশ্লেষণ)")
    }

    @Volatile private var ramTotalMb: Long = 0L
    @Volatile private var lowRamDevice: Boolean = false
    @Volatile private var cachedMode: Mode = Mode.FULL

    /** Called from ArohiApp once — real hardware probing. */
    fun init(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            ramTotalMb = mi.totalMem / (1024 * 1024)
            lowRamDevice = Build.VERSION.SDK_INT >= 19 && am.isLowRamDevice || ramTotalMb in 1..2999
        } catch (_: Exception) { }
    }

    fun ramClassBn(): String = when {
        ramTotalMb >= 8000 -> "হাই-এন্ড"
        ramTotalMb >= 4000 -> "মিড-রেঞ্জ"
        ramTotalMb > 0 -> "লো-রেঞ্জ"
        else -> "অজানা"
    }

    fun batteryPct(context: Context): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { 100 }

    /**
     * Recompute the current mode. [needsVision] forces the VISION lane.
     */
    fun decide(context: Context, needsVision: Boolean = false): Mode {
        val mode = when {
            needsVision -> Mode.VISION
            !NetworkMonitor.isOnline -> Mode.LOCAL
            lowRamDevice || batteryPct(context) <= 15 -> Mode.LIGHT
            else -> Mode.FULL
        }
        cachedMode = mode
        ArohiBus.adaptiveMode.value = mode.name
        return mode
    }

    fun current(): Mode = cachedMode

    /** Prompt-size hint for the brain: LIGHT devices get a leaner system prompt. */
    fun maxHistoryTurns(): Int = if (cachedMode == Mode.LIGHT) 6 else 10
}
