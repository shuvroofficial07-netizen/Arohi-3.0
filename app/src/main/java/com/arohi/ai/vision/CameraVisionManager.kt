package com.arohi.ai.vision

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Live Camera Vision Session: CameraX back camera → periodic Gemini analysis.
 * ArohiBus.cameraActive is the ONLY source of the "camera active" indicator —
 * when the session is off, no camera status is ever shown.
 */
class CameraVisionManager(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onVisionResult: (String) -> Unit,
    private val onFrame: (android.graphics.Bitmap) -> Unit = {}
) {

    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private val lastRun = AtomicLong(0)
    @Volatile private var lastQuestion: String = ""

    fun start(question: String = "") {
        if (!PermissionHelper.hasCamera(context)) {
            onVisionResult("ক্যামেরা পারমিশন নেই — অনুমতি দিলে আমি দেখতে পারবো")
            return
        }
        lastQuestion = question
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy ->
                    val now = System.currentTimeMillis()
                    val bmp = VisionAnalyzer.imageProxyToBitmap(proxy)
                    proxy.close()
                    if (bmp != null && now - lastRun.get() > 6000) {
                        lastRun.set(now)
                        val question = lastQuestion
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            val result = VisionAnalyzer.analyze(context, bmp, question)
                            withContext(Dispatchers.Main) {
                                onVisionResult(result)
                            }
                        }
                    }
                    if (bmp != null) onFrame(bmp)
                }
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                registerActive(this@CameraVisionManager)
                ArohiBus.cameraActive.value = true
                ArohiBus.toast("লাইভ ভিশন চালু — আমি এখন দেখছি")
            } catch (e: Exception) {
                onVisionResult("ক্যামেরা চালু করা যায়নি: ${e.message ?: ""}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setQuestion(q: String) { lastQuestion = q }

    fun stop() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
        camera = null
        ArohiBus.cameraActive.value = false
    }

    companion object {
        /** Emergency Stop hook: the active session's stop, callable from anywhere. */
        @Volatile private var activeSession: CameraVisionManager? = null
        fun registerActive(m: CameraVisionManager?) { activeSession = m }
        fun stopGlobal() { try { activeSession?.stop() } catch (_: Exception) {} ; activeSession = null }
    }
}
