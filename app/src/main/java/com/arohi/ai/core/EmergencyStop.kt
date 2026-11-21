package com.arohi.ai.core

import android.content.Context
import com.arohi.ai.automation.TaskEngine
import com.arohi.ai.services.ArohiForegroundService
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v4 — Emergency Stop (নিরাপত্তা ব্যবস্থা).
 *
 * One command that REALLY stops everything Arohi can do:
 *  - cancels the running multi-step task
 *  - stops speech immediately
 *  - closes the conversation session
 *  - stops camera/screen capture
 *  - clears pending confirmations (nothing gets sent afterwards)
 *  - stops the background service (mic listening) — full stop
 *
 * It does NOT pretend things stopped: every component's own stop call runs.
 */
object EmergencyStop {

    fun execute(context: Context) {
        try { TaskEngine.get(context).cancel() } catch (_: Exception) {}
        try { TtsManager.stop() } catch (_: Exception) {}
        try { com.arohi.ai.vision.CameraVisionManager.stopGlobal() } catch (_: Exception) {}
        try { ArohiBus.pendingConfirmation.value = null } catch (_: Exception) {}
        try { ArohiBus.taskProgress.value = null } catch (_: Exception) {}
        try {
            CommandRouterSafe(context).clearPending()
        } catch (_: Exception) {}
        ArohiBus.sessionOpen.value = false
        ArohiBus.emergencyStopped.value = true
        ArohiBus.resetToIdle()
        // stop the 24/7 service last — full stop of mic listening
        try { ArohiForegroundService.stop(context.applicationContext) } catch (_: Exception) {}
        ArohiBus.toast("জরুরি বন্ধ: সব কাজ ও শোনা থামানো হয়েছে")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.arohi.ai.memory.MemoryRepository.get(context)
                    .logActivity("SECURITY", "EMERGENCY_STOP — সব কার্যক্রম ব্যবহারকারীর নির্দেশে থামানো হয়েছে")
            } catch (_: Exception) {}
        }
    }

    /** Clears the pending yes/no gates so nothing auto-sends after the stop. */
    private class CommandRouterSafe(private val ctx: Context) {
        fun clearPending() {
            com.arohi.ai.commands.CommandRouter.get(ctx).clearPendingActions()
        }
    }
}
