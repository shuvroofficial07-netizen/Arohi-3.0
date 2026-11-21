package com.arohi.ai.smarthome

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SmartDevice(
    val id: String,
    val name: String,
    val topic: String,           // command topic, e.g. arohi/home/lamp/set
    val type: String,            // light | fan | plug | rgb | sensor
    val stateTopic: String = ""  // optional telemetry topic (temperature etc.)
)

/**
 * Smart Home device registry (real MQTT wiring — no fake devices).
 * Payload protocol (Home Assistant / ESPHome / Arduino compatible):
 *   ON / OFF                    → lights, fan, plug
 *   {"color":{"r":..,"g":..,"b":..}} → RGB
 *   SPEED_1..SPEED_4            → fan
 *   BRIGHT_<0-100>              → dimmable light
 */
class SmartHomeStore private constructor(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("arohi_smarthome", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        @Volatile private var inst: SmartHomeStore? = null
        fun get(context: Context): SmartHomeStore =
            inst ?: synchronized(this) { inst ?: SmartHomeStore(context.applicationContext).also { inst = it } }
    }

    fun devices(): List<SmartDevice> = try {
        gson.fromJson(sp.getString("devices", "[]"), object : TypeToken<List<SmartDevice>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun add(device: SmartDevice) {
        val list = devices().filter { it.id != device.id } + device
        save(list)
    }

    fun remove(id: String) = save(devices().filter { it.id != id })

    fun find(name: String): SmartDevice? =
        devices().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?:
        devices().firstOrNull { name.lowercase().contains(it.name.lowercase()) || it.name.lowercase().contains(name.lowercase()) }

    private fun save(list: List<SmartDevice>) =
        sp.edit().putString("devices", gson.toJson(list)).apply()
}
