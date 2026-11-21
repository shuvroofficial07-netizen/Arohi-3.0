package com.arohi.ai.ui

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arohi.ai.R
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.databinding.FragmentVisionBinding
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.services.ArohiAccessibilityService
import com.arohi.ai.vision.CameraVisionManager
import com.arohi.ai.vision.VisionAnalyzer
import com.arohi.ai.voice.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 — VISION screen. The camera runs ONLY while a session is live; the
 * "Camera Active" indicator is driven by ArohiBus.cameraActive (real state),
 * never faked. Includes live camera vision, image analysis, OCR-doc scan,
 * screenshot handoff and screen-read.
 */
class VisionFragment : Fragment() {

    private var _binding: FragmentVisionBinding? = null
    private val binding get() = _binding!!
    private var vision: CameraVisionManager? = null
    private var pendingQuestion = ""

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleVision(pendingQuestion)
        else ArohiBus.toast("ক্যামেরা অনুমতি ছাড়া আরোহী দেখতে পারবে না")
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { analyzePicked(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVisionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val appCtx = ctx.applicationContext

        val col = UiKit.column(ctx)
        binding.visionScroll.addView(col)

        col.addView(UiKit.section(ctx, "লাইভ ক্যামেরা ভিশন"))
        col.addView(UiKit.card(ctx,
            UiKit.field(ctx, "আরোহীকে কী দেখতে বলবে? (খালি রাখলে সব বর্ণনা করবে)", ""),
            UiKit.button(ctx, "👁 ক্যামেরা ভিশন চালু/বন্ধ") {
                pendingQuestion = ""
                if (!PermissionHelper.hasCamera(ctx)) cameraPermission.launch(Manifest.permission.CAMERA)
                else toggleVision()
            },
            UiKit.note(ctx, "ক্যামেরা চালু না থাকলে কোনো 'Camera Active' স্ট্যাটাস দেখানো হয় না — সততার নীতি।")))

        col.addView(UiKit.section(ctx, "ছবি ও ডকুমেন্ট"))
        col.addView(UiKit.card(ctx,
            UiKit.button(ctx, "🖼 ছবি বেছে নিয়ে বিশ্লেষণ (OCR সহ)") {
                imagePicker.launch("image/*")
            },
            UiKit.button(ctx, "📄 স্ক্রিনে কী লেখা আছে পড়ে শোনাও (Screen Read)") {
                val svc = ArohiAccessibilityService.instance
                if (svc == null) ArohiBus.toast("অ্যাক্সেসিবিলিটি চালু নেই — সেটিংসে অনুমতি দিন")
                else {
                    val text = svc.readScreenText(600)
                    TtsManager.speak("স্ক্রিনে লেখা আছে: $text")
                }
            },
            UiKit.button(ctx, "📸 স্ক্রিনশট (DEVICE ট্যাবে MediaProjection আছে)") {
                (activity as? com.arohi.ai.MainActivity)?.let { m ->
                    m.binding.bottomNav.selectedItemId = R.id.nav_device
                    ArohiBus.toast("DEVICE ট্যাবের Screenshot বাটনে চাপ দিন — Android অনুমতি জিজ্ঞেস করবে")
                }
            }))

        col.addView(UiKit.section(ctx, "ভিশন ইঞ্জিনের বাস্তব ক্ষমতা"))
        col.addView(UiKit.card(ctx,
            UiKit.note(ctx, "• Object Recognition — ক্যামেরায় যা দেখে বর্ণনা\n" +
                "• OCR — ল্যাটিন অক্ষরে অন-ডিভাইস (ML Kit), বাংলা লেখায় Gemini Vision\n" +
                "• Document Scanner — ডকুমেন্টের ছবি থেকে টেক্সট বের করা\n" +
                "• Screenshot Analysis — স্ক্রিনের কনটেন্ট ব্যাখ্যা\n" +
                "• Visual Q&A — ছবি দেখে প্রশ্নের উত্তর\n" +
                "• QR/বারকোড — ছবিতে থাকলে পড়ে বলা")))

        // real camera-active indicator
        viewLifecycleOwner.lifecycleScope.launch {
            com.arohi.ai.core.ArohiBus.cameraActive.collect { on ->
                binding.visionPreview.visibility = if (on) View.VISIBLE else View.GONE
                binding.visionState.text = if (on) "🔴 ক্যামেরা চালু — আরোহী দেখছে"
                    else "ক্যামেরা বন্ধ — আরোহী তখনই দেখে, যখন তুমি বলবে"
                binding.visionState.setTextColor(ctx.getColor(if (on) R.color.arohi_error else R.color.arohi_text_dim))
            }
        }
    }

    private fun toggleVision(question: String = "") {
        val ctx = requireContext()
        if (com.arohi.ai.core.ArohiBus.cameraActive.value) { vision?.stop(); return }
        // find the question field if any
        vision = CameraVisionManager(ctx, viewLifecycleOwner, binding.visionPreview,
            onVisionResult = { result ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try { MemoryRepository.get(ctx).addChat("arohi", result) } catch (_: Exception) {}
                }
                TtsManager.speak(result)
            })
        vision?.start(question)
    }

    private fun analyzePicked(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = ctx.contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(stream)
                stream?.close()
                if (bmp == null) return@launch
                val answer = VisionAnalyzer.analyze(ctx, bmp, "এই ছবিটা বিশ্লেষণ করো — কী আছে, লেখা থাকলে পড়ে বলো।")
                MemoryRepository.get(ctx).addChat("arohi", answer)
                withContext(Dispatchers.Main) { TtsManager.speak(answer) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { ArohiBus.toast("ছবি পড়া যায়নি") }
            }
        }
    }

    override fun onPause() { vision?.stop(); super.onPause() }

    override fun onDestroyView() {
        vision?.stop()
        _binding = null
        super.onDestroyView()
    }
}
