package com.arohi.ai.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arohi.ai.automation.TaskEngine
import com.arohi.ai.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Fires scheduled routines at their set time (exact alarms). */
class RoutineReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ROUTINE_ID = "routine_id"
        const val EXTRA_ROUTINE_NAME = "routine_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ROUTINE_ID, -1)
        val name = intent.getStringExtra(EXTRA_ROUTINE_NAME) ?: "রুটিন"
        if (id == -1L) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = MemoryRepository.get(context)
                val routine = repo.routinesNow().firstOrNull { it.id == id } ?: return@launch pending.finish()
                val steps = repo.parseSteps(routine.stepsJson)
                if (steps.isNotEmpty()) TaskEngine.get(context).runRoutine(steps, routine.name)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
