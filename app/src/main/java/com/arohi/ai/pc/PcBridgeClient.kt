package com.arohi.ai.pc

import android.content.Context
import com.arohi.ai.core.SecureStore
import com.arohi.ai.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * PC Integration bridge (remote command architecture).
 * PROTOCOL (for the future PC companion app):
 *   TCP <pcHost>:<pcPort> → frame = [4-byte big-endian length][AES-256-GCM payload]
 *   payload plaintext = JSON {"token":"<shared secret>","command":"...","ts":<epoch ms>}
 *   response frame = same layout, JSON {"ok":true,"data":"..."}
 * Commands: launch:<app>, type:<text>, screenshot, sysinfo, cpu, ram, shutdown, restart, file:<path>
 *
 * HONESTY RULE: without a running PC companion app this returns a clear
 * "PC app required" message — the Android side never fakes a successful PC command.
 */
object PcBridgeClient {

    suspend fun sendCommand(context: Context, command: String): String = withContext(Dispatchers.IO) {
        val s = SettingsStore.get(context)
        if (s.pcHost.isBlank())
            return@withContext "PC কম্প্যানিয়ন অ্যাপ কনফিগার করা হয়নি — Settings → PC Bridge-এ ঠিকানা দিন। " +
                "PC অ্যাপ চালু না থাকলে এই ফিচার কাজ করে না, আমি মিথ্যা বলবো না।"
        try {
            val token = SecureStore.get(context).pcToken.ifBlank { "arohi-default" }
            val request = """{"token":"$token","command":"${command.replace("\"", "'")}","ts":${System.currentTimeMillis()}}"""

            Socket().use { socket ->
                socket.connect(InetSocketAddress(s.pcHost, s.pcPort), 6000)
                socket.soTimeout = 10000

                val keyMaterial = token.padEnd(32, '#').toByteArray(StandardCharsets.UTF_8).copyOf(32)
                val key = SecretKeySpec(keyMaterial, "AES")
                val iv = ByteArray(12) { Random.nextInt(0, 256).toByte() }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                val encrypted = cipher.doFinal(request.toByteArray(StandardCharsets.UTF_8))

                DataOutputStream(socket.getOutputStream()).use { out ->
                    out.writeInt(12 + encrypted.size)
                    out.write(iv)
                    out.write(encrypted)
                    out.flush()

                    DataInputStream(socket.getInputStream()).use { input ->
                        val len = input.readInt()
                        if (len <= 12 || len > 65536) return@withContext "PC থেকে অস্বাভাবিক রেসপন্স (আসলে PC অ্যাপ চালু আছে কি না চেক করুন)"
                        val ivIn = ByteArray(12); input.readFully(ivIn)
                        val body = ByteArray(len - 12); input.readFully(body)
                        val dec = Cipher.getInstance("AES/GCM/NoPadding")
                        dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ivIn))
                        val response = String(dec.doFinal(body), StandardCharsets.UTF_8)
                        "PC রেসপন্স: ${response.take(300)}"
                    }
                }
            }
        } catch (e: Exception) {
            "PC-এর সাথে কানেক্ট হতে পারিনি (${e.message ?: "timeout"}) — PC কম্প্যানিয়ন অ্যাপ চালু ও ঠিকানা যাচাই করুন"
        }
    }
}
