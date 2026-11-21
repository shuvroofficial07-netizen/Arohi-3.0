package com.arohi.ai.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.services.RoutineReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Schedules user routines with real exact alarms (with honest fallback to
 * inexact windows when Android blocks SCHEDULE_EXACT_ALARM).
 */
object RoutineScheduler {

    fun rescheduleAll(context: Context) {
        val pending = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pending.launch {
            try {
                val repo = MemoryRepository.get(context)
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                repo.routinesNow().forEach { r ->
                    val (h, m) = r.timeOfDay.split(":").map { it.trim().toIntOrNull() ?: 0 }
                    val cal = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, h)
                        set(java.util.Calendar.MINUTE, m)
                        set(java.util.Calendar.SECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                    val pi = PendingIntent.getBroadcast(context, r.id.toInt(),
                        Intent(context, RoutineReceiver::class.java)
                            .putExtra(RoutineReceiver.EXTRA_ROUTINE_ID, r.id)
                            .putExtra(RoutineReceiver.EXTRA_ROUTINE_NAME, r.name),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                    val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
                    if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                    else am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 60_000L, pi)
                }
            } catch (_: Exception) { }
        }
    }

    fun exactAlarmsAllowed(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
    }

    fun requestExactPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
    }
}
