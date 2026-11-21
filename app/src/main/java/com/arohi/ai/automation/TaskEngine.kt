package com.arohi.ai.automation

import android.content.Context
import com.arohi.ai.brain.IntentParser
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.Emotion
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.services.ArohiAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-step task engine: breaks goals into steps, shows live progress,
 * retries safely, logs everything, and can be cancelled mid-run.
 */
class TaskEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null
    private lateinit var mem: MemoryRepository

    companion object {
        @Volatile private var inst: TaskEngine? = null
        fun init(context: Context) { get(context) }
        fun get(context: Context): TaskEngine =
            inst ?: synchronized(this) { inst ?: TaskEngine(context.applicationContext).also { inst = it } }
    }

    fun cancel() { cancelled.set(true) }

    fun isBusy() = job?.isActive == true

    fun runPlan(title: String, steps: List<String>) { execute(title, steps, isRoutine = false) }

    fun runRoutine(steps: List<String>, name: String) { execute(name, steps, isRoutine = true) }

    private fun execute(title: String, steps: List<String>, isRoutine: Boolean) {
        if (job?.isActive == true) {
            ArohiBus.toast("আগের টাস্ক এখনো চলছে — আগে সেটা শেষ করি")
            return
        }
        cancelled.set(false)
        mem = MemoryRepository.get(context)
        job = scope.launch {
            ArohiBus.taskProgress.value = title to 0
            val startLine = if (isRoutine) "$title রুটিন চালু করছি, ${steps.size} ধাপ।" else
                "$title শুরু করছি — মোট ${steps.size} ধাপ।"
            mem.addChat("arohi", startLine)
            TtsProxy.speak(startLine)
            mem.logActivity("AUTOMATION", "$title — শুরু (${steps.size} ধাপ)")

            var done = 0
            for ((index, step) in steps.withIndex()) {
                if (cancelled.get()) {
                    mem.addChat("arohi", "টাস্ক বাতিল করা হয়েছে শুভ্র।")
                    TtsProxy.speak("টাস্ক বাতিল করা হয়েছে।")
                    ArohiBus.taskProgress.value = null
                    return@launch
                }
                ArohiBus.taskProgress.value = title to ((index * 100) / steps.size.coerceAtLeast(1))
                val ok = executeStep(step)
                done += if (ok) 1 else 0
                mem.addChat("system", if (ok) "ধাপ ${index + 1}: $step — সম্পন্ন" else "ধাপ ${index + 1}: $step — ব্যর্থ (একবার আবার চেষ্টা করা হয়েছে)")
                delay(600)
            }
            ArohiBus.taskProgress.value = title to 100
            val finishLine = if (done == steps.size) "$title সম্পন্ন — সব ${done}টি ধাপ সফল!"
                else "$title শেষ — $done/${steps.size} ধাপ সফল, বাকিগুলো সম্ভব হয়নি।"
            mem.addChat("arohi", finishLine)
            TtsProxy.speak(finishLine)
            mem.logActivity("AUTOMATION", "$title — শেষ ($done/${steps.size})")
            delay(1200)
            ArohiBus.taskProgress.value = null
        }
    }

    /** One step = local intent → real action; retries once; honest failure. */
    private suspend fun executeStep(step: String): Boolean {
        for (attempt in 1..2) {
            val intent = IntentParser.match(step)
            if (intent != null) {
                val result = com.arohi.ai.commands.ActionExecutor.execute(context, intent.action, intent.params)
                if (result.succeeded) return true
            } else if (step.contains("wait", ignoreCase = true) || step.contains("অপেক্ষা")) {
                delay(3000); return true
            } else if (step.contains("back", ignoreCase = true)) {
                ArohiAccessibilityService.instance?.globalBack(); return true
            } else if (step.contains("home", ignoreCase = true)) {
                ArohiAccessibilityService.instance?.globalHome(); return true
            } else {
                // free-text step: ask Gemini once for a structured action
                val structured = askGeminiForStep(step)
                if (structured != null) {
                    val r = com.arohi.ai.commands.ActionExecutor.execute(context, structured.first, structured.second)
                    if (r.succeeded) return true
                }
            }
            delay(800)
        }
        return false
    }

    private suspend fun askGeminiForStep(step: String): Pair<String, Map<String, String>>? {
        return try {
            val prompt = "Convert this single Android assistant task step to JSON {\"action\":\"...\",\"params\":{...}}. " +
                "Use only known actions (OPEN_APP, SEARCH, URL, WEATHER, NEWS, WIKI, MAPS, FLASHLIGHT, BRIGHTNESS, " +
                "VOLUME, BATTERY, TIME, DATE, WIFI, BLUETOOTH, SCREENSHOT, ALARM, TIMER, CALL, MESSAGE, EMAIL, MEMORY_SAVE, CAMERA_LOOK, SCREEN_READ, STATUS). Step: \"$step\""
            val res = com.arohi.ai.brain.ArohiBrain.chat(context,
                "You are a JSON action converter. Reply with ONLY the JSON object.", emptyList(), prompt)
            val json = res.getOrNull()?.second?.let { com.arohi.ai.brain.GeminiClient.extractJson(it) } ?: return null
            val action = json.optString("action", "NONE")
            val paramsJson = json.optJSONObject("params")
            val params = mutableMapOf<String, String>()
            paramsJson?.keys()?.forEach { k -> params[k] = paramsJson.optString(k) }
            if (action == "NONE") null else action to params
        } catch (_: Exception) { null }
    }

    private object TtsProxy {
        fun speak(text: String) { com.arohi.ai.voice.TtsManager.speak(text) }
    }
}
