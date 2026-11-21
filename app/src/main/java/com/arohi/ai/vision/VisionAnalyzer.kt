package com.arohi.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Visual intelligence: Gemini Vision for understanding, ML Kit OCR for reading text.
 */
object VisionAnalyzer {

    /** YUV frame from CameraX → JPEG Bitmap (downscaled). */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap? = try {
        val nv21 = yuv420ToNv21(image)
        val yuv = android.graphics.YuvImage(nv21, ImageFormat.YUV_420_888,
            image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 85, out)
        val bytes = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bmp?.let { scaleDown(it, 768) }
    } catch (_: Exception) { null }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        v.get(nv21, ySize, vSize)
        u.get(nv21, ySize + vSize, uSize)
        return nv21
    }

    private fun scaleDown(bmp: Bitmap, maxW: Int): Bitmap {
        if (bmp.width <= maxW) return bmp
        val ratio = maxW.toFloat() / bmp.width
        return Bitmap.createScaledBitmap(bmp, maxW, (bmp.height * ratio).toInt(), true)
    }

    /** Vision via the active brain provider — honest error when no key / offline. */
    suspend fun analyze(context: Context, bitmap: Bitmap, question: String): String {
        val compressed = scaleDown(bitmap, 768)
        val bos = ByteArrayOutputStream()
        compressed.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val b64 = Base64.getEncoder().encodeToString(bos.toByteArray())
        val system = com.arohi.ai.brain.SystemPromptBuilder.build(context, mode = "vision")
        val result = com.arohi.ai.brain.ArohiBrain.chat(context, system, emptyList(),
            question.ifBlank { "দেখো আমি কী করছি বা আশপাশে কী আছে, ২-৩ বাক্যে বলো।" }, imageBase64 = b64)
        return result.fold(
            onSuccess = { it.second.take(600) },
            onFailure = { e ->
                if (e.message == "offline") "অফলাইন — ভিশন বিশ্লেষণে ইন্টারনেট দরকার"
                else if (e.message == "no_key") "ব্রেইন কী ছাড়া ছবি বুঝতে পারছি না — API CENTER-এ কী দিন"
                else "ছবি বিশ্লেষণে সমস্যা হয়েছে: ${e.message ?: ""}"
            }
        )
    }

    /**
     * On-device OCR (ML Kit Latin). Bengali/other-script text falls back to
     * Gemini Vision OCR — ML Kit v2 doesn't ship a bundled Bengali model.
     */
    fun ocr(context: Context, bitmap: Bitmap, onText: (String) -> Unit) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        latin.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                latin.close()
                if (text.isNotBlank()) {
                    onText(text)
                } else {
                    // Gemini Vision OCR fallback (Bengali + any script)
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        val answer = analyze(context, bitmap, "ছবির সব লেখা হুবহু পড়ে বাংলায়/মূল ভাষায় লিখে দাও। শুধু লেখাটা দাও।")
                        withContext(Dispatchers.Main) {
                            onText(answer)
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                latin.close()
                onText("OCR ব্যর্থ: ${e.message ?: ""}")
            }
    }
}
