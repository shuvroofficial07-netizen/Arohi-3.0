package com.arohi.ai.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arohi.ai.R
import com.arohi.ai.commands.CommandRouter
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.ConvState
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.databinding.FragmentAssistantBinding
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.services.ConversationHolder
import com.arohi.ai.vision.CameraVisionManager
import com.arohi.ai.vision.VisionAnalyzer
import com.arohi.ai.voice.ConversationEngine
import com.arohi.ai.voice.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantFragment : Fragment() {

    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!
    private val chatAdapter = ChatAdapter()
    private var vision: CameraVisionManager? = null
    private var pendingImage: Bitmap? = null
    private var pendingVisionQuestion: String = ""

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine?.onUserTrigger()
        else ArohiBus.toast(getString(R.string.err_mic_permission))
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleVisionNow(pendingVisionQuestion)
        else ArohiBus.toast("ক্যামেরা পারমিশন ছাড়া আমি দেখতে পারবো না")
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handlePickedImage(it) }
    }

    private val engine: ConversationEngine? by lazy {
        ConversationHolder.engine ?: run {
            val e = ConversationEngine(requireContext().applicationContext)
            ConversationHolder.engine = e
            e
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mem = MemoryRepository.get(requireContext())
        binding.chatList.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.chatList.adapter = chatAdapter

        loadChat(mem)

        binding.micButton.setOnClickListener {
            if (!PermissionHelper.hasMicrophone(requireContext())) micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else engine?.onUserTrigger()
        }
        binding.stopButton.setOnClickListener { TtsManager.stop() }
        binding.sendButton.setOnClickListener {
            val text = binding.textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.textInput.setText("")
                lifecycleScope.launch(Dispatchers.IO) { mem.addChat("user", text) }
                CommandRouter.get(requireContext()).handle(text, source = "text")
            }
        }
        binding.visionToggle.setOnClickListener {
            pendingVisionQuestion = ""
            if (!PermissionHelper.hasCamera(requireContext()))
                cameraPermission.launch(Manifest.permission.CAMERA)
            else toggleVisionNow()
        }
        binding.imagePick.setOnClickListener {
            imagePicker.launch("image/*")
        }
        binding.modeToggle.setOnClickListener {
            val s = SettingsStore.get(requireContext())
            val next = if (s.voiceMode == "voice") "text" else "voice"
            s.voiceMode = next
            binding.modeToggle.text = if (next == "voice") "মোড: ভয়েস" else "মোড: টেক্সট"
            binding.textInputRow.visibility = if (next == "text") View.VISIBLE else View.GONE
            if (next == "text") { engine?.closeSession() }
        }
        binding.modeToggle.text = if (SettingsStore.get(requireContext()).voiceMode == "voice") "মোড: ভয়েস" else "মোড: টেক্সট"
        binding.textInputRow.visibility =
            if (SettingsStore.get(requireContext()).voiceMode == "text") View.VISIBLE else View.GONE

        // confirmation chips
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.pendingConfirmation.collect { c ->
                binding.confirmRow.visibility = if (c != null) View.VISIBLE else View.GONE
                if (c != null) binding.confirmText.text = c
            }
        }
        binding.confirmYes.setOnClickListener { CommandRouter.get(requireContext()).answerConfirmation(true) }
        binding.confirmNo.setOnClickListener { CommandRouter.get(requireContext()).answerConfirmation(false) }

        // live state / waveform-driven widgets
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.state.collect { st ->
                binding.statePill.text = when (st) {
                    ConvState.DORMANT -> "প্রস্তুত — 'আরোহী' বললে শুনবো"
                    ConvState.LISTENING -> "শুনছি…"
                    ConvState.THINKING -> "ভাবছি…"
                    ConvState.EXECUTING -> "কাজ করছি…"
                    ConvState.SPEAKING -> "বলছি…"
                    ConvState.ERROR -> "সমস্যা"
                    else -> "আরোহী প্রস্তুত"
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.offline.collect { off -> binding.offlinePill.visibility = if (off) View.VISIBLE else View.GONE }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.cameraActive.collect { on ->
                binding.visionPreview.visibility = if (on) View.VISIBLE else View.GONE
                binding.visionToggle.text = if (on) "ভিশন বন্ধ" else "ক্যামেরা ভিশন"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.chatChanged.collect { loadChat(mem) }
        }

        // wake-word requests coming from other tabs (e.g. HOME "ক্যামেরা ভিশন" chip)
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.toasts.collect { msg ->
                if (msg.startsWith("CAMERA_LOOK_REQUEST:")) {
                    val q = msg.substringAfter(":")
                    pendingVisionQuestion = q
                    if (PermissionHelper.hasCamera(requireContext())) {
                        if (ArohiBus.cameraActive.value) vision?.setQuestion(q) else toggleVisionNow(q)
                    } else cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private fun loadChat(mem: MemoryRepository) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = try { mem.recentChat(60) } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                chatAdapter.submit(list)
                if (list.isNotEmpty()) binding.chatList.scrollToPosition(list.size - 1)
            }
        }
    }

    private fun toggleVisionNow(question: String = "") {
        if (ArohiBus.cameraActive.value) { vision?.stop(); return }
        vision = CameraVisionManager(
            requireContext(), viewLifecycleOwner, binding.visionPreview,
            onVisionResult = { result ->
                lifecycleScope.launch(Dispatchers.IO) {
                    MemoryRepository.get(requireContext()).addChat("arohi", result)
                }
                TtsManager.speak(result)
            })
        vision?.start(question)
    }

    private fun handlePickedImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = requireContext().contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(stream)
                stream?.close()
                if (bmp == null) return@launch
                val answer = VisionAnalyzer.analyze(requireContext(), bmp, "এই ছবিটা বিশ্লেষণ করো — কী আছে, লেখা থাকলে পড়ে বলো।")
                MemoryRepository.get(requireContext()).addChat("arohi", answer)
                withContext(Dispatchers.Main) { TtsManager.speak(answer) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { ArohiBus.toast("ছবি পড়া যায়নি") }
            }
        }
    }

    override fun onPause() {
        vision?.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        vision?.stop()
        _binding = null
        super.onDestroyView()
    }
}
