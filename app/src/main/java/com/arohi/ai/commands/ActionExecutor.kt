package com.arohi.ai.commands

import android.content.Context
import android.content.Intent
import com.arohi.ai.actions.AppLauncher
import com.arohi.ai.actions.DeviceController
import com.arohi.ai.actions.PeopleHelper
import com.arohi.ai.automation.TaskEngine
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.net.InternetClients
import com.arohi.ai.smarthome.MqttManager
import com.arohi.ai.pc.PcBridgeClient
import com.arohi.ai.services.ArohiNotificationListener

/**
 * Executes one action with real system calls and returns an honest
 * ExecResult(succeeded, speakText). Never fabricates success.
 */
object ActionExecutor {

    data class ExecResult(val succeeded: Boolean, val speak: String)

    suspend fun execute(context: Context, action: String, params: Map<String, String>): ExecResult {
        val p = { k: String -> params[k] ?: "" }
        return try {
            when (action.uppercase()) {
                "OPEN_APP" -> ok(AppLauncher.open(context, p("app").ifBlank { p("query") }))

                "PLAY_YOUTUBE" -> ok(AppLauncher.openYouTubeSearch(context, p("query")))

                "SEARCH" -> ok(AppLauncher.googleSearch(context, p("query")))

                "URL" -> ok(AppLauncher.openUrl(context, p("url")))

                "WEATHER" -> ok(InternetClients.weather(p("place")))

                "NEWS" -> ok(InternetClients.news())

                "WIKI" -> ok(InternetClients.wiki(p("topic")))

                "MAPS" -> ok(AppLauncher.mapsSearch(context, p("query")))

                "FLASHLIGHT" -> ok(DeviceController.setFlashlight(context, p("on").toBooleanStrictOrNull() ?: true))

                "BRIGHTNESS" -> ok(DeviceController.setBrightness(context, p("level").toIntOrNull() ?: 50))

                "VOLUME" -> ok(DeviceController.setVolume(context, p("stream").ifBlank { "media" }, p("level").toIntOrNull() ?: 50))

                "BATTERY" -> ok(DeviceController.batteryDetails(context))

                "STORAGE" -> ok(DeviceController.storageInfo(context))

                "RAM" -> ok(DeviceController.ramInfo(context))

                "CPU" -> {
                    val load = DeviceController.cpuLoad()
                    ok(if (load >= 0) "CPU লোড এখন $load শতাংশ" else "CPU লোড প্রথমবার মাপা যায়নি, আবার বললে দেখাবো")
                }

                "TIME" -> ok("এখন সময় ${DeviceController.timeNow()}")

                "DATE" -> ok("আজ ${DeviceController.dateNow()}")

                "WIFI" -> ok(DeviceController.openWifiSettings(context))

                "BLUETOOTH" -> ok(DeviceController.openBluetoothSettings(context))

                "SCREENSHOT" -> ExecResult(false,
                    "স্ক্রিনশট নিতে Android-এর MediaProjection অনুমতি দরকার — DEVICE ট্যাবের Screenshot বাটনে চাপ দিলে আমি সাথে সাথে নিয়ে ফেলবো")

                "ALARM" -> ok(DeviceController.setAlarm(context,
                    p("hour").toIntOrNull() ?: 7, p("minute").toIntOrNull() ?: 0, p("label").ifBlank { "Arohi alarm" }))

                "TIMER" -> ok(DeviceController.setTimer(context,
                    p("seconds").toIntOrNull() ?: 60, p("label").ifBlank { "Arohi timer" }))

                "APPS" -> {
                    val apps = AppLauncher.listApps(context)
                    ok(if (apps.isEmpty()) "ইনস্টল করা অ্যাপ লিস্ট পাইনি"
                        else "${apps.size}টি অ্যাপ পাই — DEVICE ট্যাবে সম্পূর্ণ লিস্ট দেখতে পারবেন। বড়গুলো হলো: " +
                            apps.take(6).joinToString(", ") { it.label })
                }

                "FILES" -> ok(openDownloads(context))

                // v5 — ফাইল খোঁজ (সম্পূর্ণ ফোন অ্যাক্সেস)
                "FILES_FIND" -> {
                    val q = p("query")
                    if (!com.arohi.ai.files.PhoneFiles.hasMediaPermission(context) &&
                        !com.arohi.ai.files.PhoneFiles.hasAllFilesAccess(context))
                        ExecResult(false, "ফাইল খোঁজার অনুমতি নেই — FILES স্ক্রিনে গিয়ে 'সব ফাইল অ্যাক্সেস' চালু করুন")
                    else {
                        com.arohi.ai.core.ArohiBus.toast("NAVIGATE:nav_files")
                        val results = com.arohi.ai.files.PhoneFiles.search(q)
                        if (results.isEmpty()) ok("\"$q\" নামের কোনো ফাইল পাইনি")
                        else ok("${results.size}টি ফাইল পেলাম — FILES স্ক্রিনে দেখাচ্ছি। প্রথমগুলো: " +
                            results.take(3).joinToString(", ") { it.name })
                    }
                }

                // v5 — গ্যালারি অ্যাক্সেস
                "GALLERY" -> {
                    com.arohi.ai.core.ArohiBus.toast("NAVIGATE:nav_files")
                    ok(com.arohi.ai.files.PhoneFiles.gallerySummaryBn(context))
                }

                "NOTIF_SUMMARY" -> ok(ArohiNotificationListener.summaryBn())

                "CALL" -> ok(PeopleHelper.call(context, p("contact").ifBlank { p("number") }))

                "MESSAGE" -> {
                    val platform = p("platform").lowercase()
                    if (platform.contains("whatsapp") || p("app").contains("whatsapp"))
                        ok(PeopleHelper.whatsappDraft(context, p("contact"), p("message")))
                    else ok(PeopleHelper.smsDraft(context, p("contact"), p("message")))
                }

                "EMAIL" -> ok(PeopleHelper.emailDraft(context, p("to"),
                    p("subject").ifBlank { "Message from Arohi" }, p("body")))

                "MEMORY_SAVE" -> {
                    val content = p("content").ifBlank { p("query") }
                    if (content.isBlank()) ExecResult(false, "কী মনে রাখতে হবে বলেন তো")
                    else {
                        MemoryRepository.get(context).addMemory(content, "fact", 4)
                        ok("মনে রেখেছি: $content")
                    }
                }

                "MEMORY_FIND" -> {
                    val mem = MemoryRepository.get(context)
                    val q = p("query")
                    val all = if (q.isBlank()) mem.topMemories(10) else
                        com.arohi.ai.memory.MemoryDatabase.get(context).memoryDao().topMemories(50)
                            .filter { it.content.contains(q, ignoreCase = true) }.take(5)
                    ok(if (all.isEmpty()) "এই বিষয়ে স্মৃতিতে কিছু পাইনি"
                        else "আমার মনে আছে: " + all.joinToString("; ") { it.content })
                }

                "CAMERA_LOOK" -> {
                    com.arohi.ai.core.ArohiBus.toast("CAMERA_LOOK_REQUEST:" + p("question"))
                    ok("ক্যামেরা ভিশন চালু করছি — স্ক্রিনে অনুমতি চাইলে দিয়ে দিন")
                }

                "SCREEN_READ" -> {
                    val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                    if (svc == null) ExecResult(false, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই — সেটিংসে অনুমতি দিন")
                    else ok("স্ক্রিনে লেখা আছে: " + svc.readScreenText(600))
                }

                "TAP" -> {
                    val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                    if (svc == null) ExecResult(false, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই — সেটিংসে অনুমতি দিন")
                    else {
                        val x = p("x").toFloatOrNull()
                        val y = p("y").toFloatOrNull()
                        if (x == null || y == null) ExecResult(false, "টাপের স্থানাঙ্ক (x,y) বুঝতে পারিনি")
                        else {
                            svc.tap(x, y)
                            ok("স্ক্রিনের (${x.toInt()}, ${y.toInt()}) স্থানে টাচ করলাম")
                        }
                    }
                }

                "SWIPE" -> {
                    val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                    if (svc == null) ExecResult(false, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই — সেটিংসে অনুমতি দিন")
                    else {
                        val x1 = p("x1").toFloatOrNull(); val y1 = p("y1").toFloatOrNull()
                        val x2 = p("x2").toFloatOrNull(); val y2 = p("y2").toFloatOrNull()
                        if (x1 == null || y1 == null || x2 == null || y2 == null)
                            ExecResult(false, "সোয়াইপ স্থানাঙ্ক বুঝতে পারিনি")
                        else {
                            svc.swipe(x1, y1, x2, y2)
                            ok("সোয়াইপ করলাম")
                        }
                    }
                }

                "BACK" -> {
                    val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                    if (svc == null) ExecResult(false, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই")
                    else { svc.globalBack(); ok("ব্যাক দিলাম") }
                }

                "HOME" -> {
                    val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                    if (svc == null) ExecResult(false, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই")
                    else { svc.globalHome(); ok("হোম স্ক্রিনে গেলাম") }
                }

                "TYPE_TEXT" -> {
                    val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                    if (svc == null) ExecResult(false, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই")
                    else ok(svc.typeText(p("text")))
                }

                "REPLY_LAST" -> {
                    val item = ArohiNotificationListener.latestReplyable()
                    if (item == null) ExecResult(false, "রিপ্লাই দেওয়ার মতো কোনো মেসেজ/নোটিফিকেশন জমা নেই")
                    else {
                        val message = p("message").ifBlank { p("text") }
                        if (message.isBlank()) ExecResult(false, "কী রিপ্লাই পাঠাবো বলো তো")
                        else {
                            val outcome = com.arohi.ai.automation.AutoReplyEngine.sendToItem(context, item, message)
                            if (outcome.success)
                                ok("${item.appLabel}-এ ${item.title}-কে রিপ্লাই পাঠিয়েছি: $message")
                            else ExecResult(false, "রিপ্লাই পাঠানো যায়নি — ${outcome.detail}")
                        }
                    }
                }

                "EMERGENCY_STOP" -> {
                    com.arohi.ai.core.EmergencyStop.execute(context)
                    ok("জরুরি বন্ধ চালু হয়েছে — সব কাজ, কথা বলা আর অটোমেশন থামিয়ে দিয়েছি। আবার শুরু করতে বললেই হবে।")
                }

                "AGENTS_STATUS" -> {
                    val list = com.arohi.ai.agents.AgentOrchestrator.AGENTS
                    ok("আমার ${list.size}টি এজেন্ট আছে: " + list.joinToString(", ") { it.nameBn } +
                        "। এখন সক্রিয়: ${com.arohi.ai.core.ArohiBus.activeAgent.value.ifBlank { "ব্যক্তিগত সহকারী" }}")
                }

                "ADAPTIVE_STATUS" -> {
                    val mode = com.arohi.ai.core.AdaptiveEngine.decide(context)
                    ok("অ্যাডাপটিভ মোড এখন ${mode.labelBn} — ডিভাইস ${com.arohi.ai.core.AdaptiveEngine.ramClassBn()}, " +
                        "ব্যাটারি ${com.arohi.ai.core.AdaptiveEngine.batteryPct(context)}%, নেটওয়ার্ক ${com.arohi.ai.core.NetworkMonitor.networkLabel}")
                }

                "PLAN" -> {
                    val stepsRaw = params["steps"] ?: ""
                    val steps = stepsRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (steps.isEmpty()) ExecResult(false, "ধাপগুলো বুঝতে পারিনি, আবার বলুন")
                    else {
                        TaskEngine.get(context).runPlan(p("title").ifBlank { "টাস্ক" }, steps)
                        ok("প্ল্যান নিচ্ছি — ${steps.size}টি ধাপ ধারাবাহিকভাবে করবো")
                    }
                }

                "SMARTHOME" -> {
                    val device = p("device"); val command = p("command").ifBlank { "on" }
                    val result = MqttManager.sendDeviceCommand(context, device, command)
                    ok(result)
                }

                "PC" -> ok(PcBridgeClient.sendCommand(context, p("command").ifBlank { "status" }))

                "STATUS" -> ok(DeviceController.fullStatus(context))

                "NONE" -> ExecResult(true, "")

                else -> ExecResult(false, "এই অ্যাকশনটি আমার এখনো শেখা হয়নি: $action")
            }
        } catch (e: Exception) {
            ExecResult(false, "কাজটি করতে গিয়ে সমস্যা: ${e.message ?: "অজানা"}")
        }
    }

    private fun ok(speak: String) = ExecResult(true, speak)

    fun openDownloads(context: Context): String = try {
        context.startActivity(Intent("android.intent.action.VIEW_DOWNLOADS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ডাউনলোড ফোল্ডার খুলে দিয়েছি"
    } catch (_: Exception) { "ডাউনলোড ফোল্ডার খোলা যায়নি" }
}
