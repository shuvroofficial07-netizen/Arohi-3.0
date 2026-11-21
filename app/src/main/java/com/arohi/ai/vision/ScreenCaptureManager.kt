package com.arohi.ai.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import com.arohi.ai.core.ArohiBus

/**
 * Real screenshots via MediaProjection. The user must grant the projection
 * dialog once per capture session (Android policy — can't be bypassed).
 * Saves to Pictures/Arohi via MediaStore.
 */
object ScreenCaptureManager {

    fun requestConsent(activity: Activity, requestCode: Int) {
        val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(mgr.createScreenCaptureIntent(), requestCode)
    }

    fun capture(activity: Activity, resultCode: Int, data: Intent): String {
        return try {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)

            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            var virtualDisplay: VirtualDisplay? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            val handler = Handler(Looper.getMainLooper())

            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val bitmap = rgbaImageToBitmap(img, width, height)
                    img.close()
                    val path = saveToGallery(activity, bitmap)
                    virtualDisplay?.release()
                    latch.countDown()
                    ArohiBus.toast(if (path != null) "স্ক্রিনশট সেভ হয়েছে: $path" else "স্ক্রিনশট সেভ ব্যর্থ")
                } catch (e: Exception) {
                    latch.countDown()
                    ArohiBus.toast("স্ক্রিনশট সমস্যা: ${e.message ?: ""}")
                }
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "ArohiScreenshot", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)

            val ok = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            try { reader.close() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            if (ok) "স্ক্রিনশট নিয়েছি এবং গ্যালারিতে সেভ করেছি"
            else "স্ক্রিনশট নিতে সময়মতো ফ্রেম পাইনি — আবার চেষ্টা করুন"
        } catch (e: Exception) {
            "স্ক্রিনশট ব্যর্থ: ${e.message ?: "অনুমতি বাতিল হয়েছে"}"
        }
    }

    private fun rgbaImageToBitmap(img: android.media.Image, w: Int, h: Int): Bitmap {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val bitmapWidth = w + rowPadding / pixelStride
        val bmp = Bitmap.createBitmap(bitmapWidth, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return if (bitmapWidth != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
    }

    private fun saveToGallery(activity: Activity, bmp: Bitmap): String? {
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ArohiShot_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Arohi")
            }
            val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            activity.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "Pictures/Arohi"
        } catch (_: Exception) { null }
    }
}
