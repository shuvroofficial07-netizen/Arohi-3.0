package com.arohi.ai.smarthome

import android.content.Context
import com.arohi.ai.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Real MQTT client for Smart Home (Arduino / ESP32 / ESPHome / Home Assistant).
 * Connection state is ALWAYS truthful: DISCONNECTED / CONNECTING / CONNECTED / ERROR.
 */
object MqttManager {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
    val state = MutableStateFlow(State.DISCONNECTED)
    val lastError = MutableStateFlow("")
    val lastTelemetry = MutableStateFlow<String>("")

    private var client: MqttClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun connect(context: Context) {
        scope.launch {
            val s = SettingsStore.get(context)
            if (s.mqttHost.isBlank()) {
                state.value = State.ERROR
                lastError.value = "MQTT ব্রোকার সেট করা হয়নি — Settings → Smart Home"
                return@launch
            }
            state.value = State.CONNECTING
            try {
                disconnectInternal()
                val uri = if (s.mqttHost.startsWith("tcp://") || s.mqttHost.startsWith("ssl://")) s.mqttHost
                    else "tcp://${s.mqttHost}:${s.mqttPort}"
                val c = MqttClient(uri, "arohi-${System.currentTimeMillis() % 100000}", MemoryPersistence())
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    isAutomaticReconnect = true
                    if (s.mqttUsername.isNotBlank()) {
                        userName = s.mqttUsername
                        password = s.mqttPassword.toCharArray()
                    }
                }
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        state.value = if (isConnectedState()) State.ERROR else State.DISCONNECTED
                        lastError.value = cause?.message ?: "connection lost"
                    }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        lastTelemetry.value = "${topic ?: ""}: ${message?.toString() ?: ""}"
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                c.connect(opts)
                // subscribe to base topic telemetry
                val base = s.mqttBaseTopic
                try { c.subscribe("$base/#", 1) } catch (_: Exception) {}
                client = c
                state.value = State.CONNECTED
                lastError.value = ""
            } catch (e: Exception) {
                state.value = State.ERROR
                lastError.value = e.message ?: "connect failed"
            }
        }
    }

    private fun isConnectedState() = state.value == State.CONNECTED

    fun disconnect(context: Context) {
        scope.launch { disconnectInternal(); state.value = State.DISCONNECTED }
    }

    private fun disconnectInternal() {
        try { client?.disconnect() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    /** Voice/UI entry. Returns an honest, speakable result. */
    suspend fun sendDeviceCommand(context: Context, deviceName: String, command: String): String =
        withContext(Dispatchers.IO) {
            val s = SettingsStore.get(context)
            if (s.mqttHost.isBlank())
                return@withContext "স্মার্ট হোম সেটআপ হয়নি — Settings-এ MQTT ব্রোকার দিন (Arduino/ESP32 ছাড়া এটি চলবে না, এটাই বাস্তবতা)"
            if (state.value != State.CONNECTED) {
                connect(context)
                // give a short window for connect
                var waited = 0
                while (state.value == State.CONNECTING && waited < 8000) { Thread.sleep(250); waited += 250 }
            }
            if (state.value != State.CONNECTED)
                return@withContext "MQTT ব্রোকারে কানেক্ট হতে পারিনি: ${lastError.value}"

            val device = SmartHomeStore.get(context).find(deviceName)
                ?: return@withContext "'$deviceName' নামের ডিভাইস রেজিস্ট্রিতে নেই — Settings → Smart Home-এ যোগ করুন"

            val payload = payloadFor(command)
            try {
                val msg = MqttMessage(payload.toByteArray()).apply { qos = 1 }
                client?.publish(device.topic, msg)
                "স্মার্ট ডিভাইস ${device.name} — কমান্ড পাঠানো হয়েছে: $payload"
            } catch (e: Exception) {
                "কমান্ড পাঠানো ব্যর্থ: ${e.message ?: "unknown"}"
            }
        }

    fun payloadFor(command: String): String {
        val c = command.trim().lowercase()
        return when {
            c in listOf("on", "off") -> c.uppercase()
            c.startsWith("color") || c.startsWith("rgb") -> {
                val color = c.substringAfter("_").ifBlank { "white" }
                val rgb = mapOf(
                    "red" to Triple(255, 0, 0), "লাল" to Triple(255, 0, 0),
                    "green" to Triple(0, 255, 0), "সবুজ" to Triple(0, 255, 0),
                    "blue" to Triple(0, 0, 255), "নীল" to Triple(0, 0, 255),
                    "white" to Triple(255, 255, 255), "সাদা" to Triple(255, 255, 255),
                    "pink" to Triple(255, 105, 180), "গোলাপি" to Triple(255, 105, 180))
                val t = rgb[color] ?: Triple(255, 255, 255)
                """{"color":{"r":${t.first},"g":${t.second},"b":${t.third}}}"""
            }
            c.startsWith("bright") -> "BRIGHT_${c.substringAfter("_").toIntOrNull() ?: 50}"
            c.startsWith("speed") -> "SPEED_${c.substringAfter("_").toIntOrNull() ?: 1}"
            else -> c.uppercase()
        }
    }
}
