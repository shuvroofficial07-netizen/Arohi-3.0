package com.arohi.ai.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.arohi.ai.core.ArohiBus
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Real Android device control — every function below calls a genuine system API.
 * Where a permission is missing the caller gets an honest error string back.
 */
object DeviceController {

    private var torchCamId: String? = null

    // ---------- Battery ----------
    fun batterySnapshot(context: Context): Pair<Int, String> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = batteryStatus(context)
        return level.coerceIn(0, 100) to charging
    }

    fun batteryStatus(context: Context): String {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return when {
            plugged == BatteryManager.BATTERY_PLUGGED_USB -> "চার্জে (USB)"
            plugged == BatteryManager.BATTERY_PLUGGED_AC -> "চার্জে (AC)"
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "চার্জে (ওয়্যারলেস)"
            status == BatteryManager.BATTERY_STATUS_FULL -> "ফুল চার্জ"
            else -> "ব্যাটারিতে চলছে"
        }
    }

    fun batteryDetails(context: Context): String {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val healthRaw = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
        val pct = if (scale > 0) level * 100 / scale else 0
        val health = when (healthRaw) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "ভালো"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "ওভারহিট"
            BatteryManager.BATTERY_HEALTH_DEAD -> "নিষ্ক্রিয়"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "ওভার-ভোল্টেজ"
            BatteryManager.BATTERY_HEALTH_COLD -> "ঠান্ডা"
            else -> "অজানা"
        }
        return "ব্যাটারি $pct% • ${batteryStatus(context)} • স্বাস্থ্য: $health • তাপমাত্রা ${temp}°C • ভোল্টেজ ${voltage}V"
    }

    // ---------- Storage / RAM / CPU ----------
    fun storageInfo(context: Context): String = try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes / (1024L * 1024L * 1024L)
        val free = stat.availableBytes / (1024L * 1024L * 1024L)
        val used = total - free
        "স্টোরেজ: ব্যবহৃত ${used}GB / মোট ${total}GB • ফাঁকা ${free}GB"
    } catch (_: Exception) { "স্টোরেজ তথ্য পাওয়া যায়নি" }

    fun ramInfo(context: Context): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalGb = mi.totalMem / (1024L * 1024L * 1024L)
        val freeGb = mi.availMem / (1024L * 1024L * 1024L)
        "RAM: ফাঁকা ${freeGb}GB / মোট ${totalGb}GB • ব্যবহৃত ${totalGb - freeGb}GB"
    } catch (_: Exception) { "RAM তথ্য পাওয়া যায়নি" }

    var lastCpuSample: LongArray? = null

    fun cpuLoad(): Int {
        return try {
            val lines = File("/proc/stat").readLines()
            val cpu = lines.firstOrNull { it.startsWith("cpu ") } ?: return -1
            val parts = cpu.split(Regex("\\s+")).drop(1).map { it.toLong() }
            val idle = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }  // idle + iowait
            val total = parts.sum()
            val prev = lastCpuSample
            lastCpuSample = longArrayOf(idle, total)
            if (prev == null) -1 else {
                val dTotal = (total - prev[1]).coerceAtLeast(1)
                val dIdle = idle - prev[0]
                (((dTotal - dIdle) * 100f) / dTotal).toInt().coerceIn(0, 100)
            }
        } catch (_: Exception) { -1 }
    }

    // ---------- Flashlight ----------
    fun setFlashlight(context: Context, on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = torchCamId ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return "ফ্ল্যাশলাইট এই ডিভাইসে নেই"
            torchCamId = camId
            cm.setTorchMode(camId, on)
            if (on) "টর্চ অন করেছি" else "টর্চ বন্ধ করেছি"
        } catch (e: Exception) {
            "টর্চ নিয়ন্ত্রণ ব্যর্থ: ${e.message ?: "অজানা কারণ"}"
        }
    }

    // ---------- Brightness ----------
    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun setBrightness(context: Context, level: Int): String {
        if (!canWriteSettings(context))
            return "ব্রাইটনেস পরিবর্তনের অনুমতি দরকার — WRITE SETTINGS পারমিশন চালু করুন"
        return try {
            val v = (level.coerceIn(0, 100) * 255 / 100).coerceAtLeast(2)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            "ব্রাইটনেস ${level.coerceIn(0, 100)}% করা হয়েছে"
        } catch (e: Exception) { "ব্রাইটনেস ব্যর্থ: ${e.message}" }
    }

    fun currentBrightness(context: Context): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
    } catch (_: Exception) { 50 }

    // ---------- Volume ----------
    fun setVolume(context: Context, stream: String, level: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream.lowercase(Locale.getDefault())) {
            "ring", "রিং" -> AudioManager.STREAM_RING
            "alarm", "অ্যালার্ম" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        return try {
            val max = am.getStreamMaxVolume(s)
            am.setStreamVolume(s, (level.coerceIn(0, 100) * max / 100), 0)
            "ভলিউম ${level.coerceIn(0, 100)}% করা হয়েছে"
        } catch (e: Exception) { "ভলিউম ব্যর্থ: ${e.message}" }
    }

    fun currentVolume(context: Context, stream: Int = AudioManager.STREAM_MUSIC): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(stream) * 100 / am.getStreamMaxVolume(stream).coerceAtLeast(1)
    }

    // ---------- Time / Date ----------
    fun timeNow(): String =
        java.text.SimpleDateFormat("HH:mm", Locale("bn")).format(Calendar.getInstance().time)

    fun dateNow(): String =
        java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale("bn")).format(Calendar.getInstance().time)

    // ---------- Wi-Fi / Bluetooth (settings panels) ----------
    fun openWifiSettings(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "ওয়াই-ফাই সেটিংস খুলে দিয়েছি"
        } catch (_: Exception) { "ওয়াই-ফাই সেটিংস খোলা যায়নি" }
    }

    fun openBluetoothSettings(context: Context): String {
        return try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ব্লুটুথ সেটিংস খুলে দিয়েছি"
        } catch (_: Exception) { "ব্লুটুথ সেটিংস খোলা যায়নি" }
    }

    // ---------- Alarm / Timer ----------
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String): String {
        return try {
            val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            "অ্যালার্ম $hour:${"%02d".format(minute)} সেট করতে যাচ্ছি — কনফার্ম করে নিন"
        } catch (_: Exception) { "এই ডিভাইসে অ্যালার্ম অ্যাপ পাওয়া যায়নি" }
    }

    fun setTimer(context: Context, seconds: Int, label: String): String {
        return try {
            val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            "টাইমার সেট করতে যাচ্ছি"
        } catch (_: Exception) { "এই ডিভাইসে টাইমার অ্যাপ পাওয়া যায়নি" }
    }

    // ---------- Full report ----------
    fun fullStatus(context: Context): String {
        val bat = batterySnapshot(context)
        val cpu = cpuLoad()
        val net = if (com.arohi.ai.core.NetworkMonitor.isOnline) "অনলাইন" else "অফলাইন"
        val health = systemHealth(context)
        return "স্ট্যাটাস রিপোর্ট — ব্যাটারি ${bat.first}% (${bat.second}), $net, " +
            "CPU লোড ${if (cpu >= 0) "$cpu%" else "মাপা যায়নি"}, $health"
    }

    fun systemHealth(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ramFreePct = (mi.availMem * 100 / mi.totalMem).toInt()
        val score = (ramFreePct / 2) + (50 - (cpuLoad().coerceAtLeast(0) / 2))
        return when {
            score >= 65 -> "সিস্টেম হেলথ চমৎকার"
            score >= 40 -> "সিস্টেম হেলথ ভালো"
            else -> "সিস্টেম একটু চাপে আছে"
        }
    }
}
