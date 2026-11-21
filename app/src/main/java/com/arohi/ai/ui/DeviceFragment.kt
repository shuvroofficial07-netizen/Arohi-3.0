package com.arohi.ai.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arohi.ai.R
import com.arohi.ai.actions.AppLauncher
import com.arohi.ai.actions.DeviceController
import com.arohi.ai.actions.PeopleHelper
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.databinding.FragmentDeviceBinding
import com.arohi.ai.services.ArohiNotificationListener
import com.arohi.ai.vision.ScreenCaptureManager
import com.arohi.ai.voice.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DEVICE tab — every control here performs a REAL system action.
 */
class DeviceFragment : Fragment() {

    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val screenshotLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val msg = ScreenCaptureManager.capture(requireActivity(), result.resultCode, result.data!!)
                TtsManager.speak(msg)
            } else {
                TtsManager.speak("স্ক্রিনশটের অনুমতি বাতিল হয়েছে")
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildControls()
        startLiveMonitoring()
    }

    private fun say(msg: String) {
        TtsManager.speak(msg)
    }

    private fun buildControls() {
        val ctx = requireContext()
        val c = binding.controls

        c.addView(section("ফ্ল্যাশলাইট ও স্ক্রিন"))
        // Torch switch
        val torch = MaterialSwitch(ctx).apply { text = "টর্চ (Flashlight)" }
        torch.setOnCheckedChangeListener { _, checked -> say(DeviceController.setFlashlight(ctx, checked)) }
        c.addView(card(torch))
        // Brightness slider
        c.addView(card(sliderCard("ব্রাইটনেস", DeviceController.currentBrightness(ctx)) { level ->
            say(DeviceController.setBrightness(ctx, level))
        }, extraHint = if (!DeviceController.canWriteSettings(ctx)) "সেটিংসে WRITE SETTINGS অনুমতি চালু করুন" else null))
        val writeSettingsBtn = button("WRITE SETTINGS অনুমতি চালু করুন") {
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        if (!DeviceController.canWriteSettings(ctx)) c.addView(card(writeSettingsBtn))

        c.addView(section("সাউন্ড"))
        c.addView(card(sliderCard("মিডিয়া ভলিউম", DeviceController.currentVolume(ctx)) { level ->
            say(DeviceController.setVolume(ctx, "media", level))
        }))
        c.addView(card(sliderCard("রিং ভলিউম", DeviceController.currentVolume(
            ctx, android.media.AudioManager.STREAM_RING)) { level ->
            say(DeviceController.setVolume(ctx, "ring", level))
        }))

        c.addView(section("ডিভাইস তথ্য (লাইভ)"))
        val batteryText = TextView(ctx).apply { textSize = 15f }
        val ramText = TextView(ctx).apply { textSize = 15f }
        val cpuText = TextView(ctx).apply { textSize = 15f }
        val storageText = TextView(ctx).apply { textSize = 15f }
        c.addView(card(linear(batteryText, ramText, cpuText, storageText)))

        c.addView(section("দ্রুত কাজ"))
        c.addView(card(linear(
            button("Screenshot") {
                ScreenCaptureManager.requestConsent(requireActivity(), SCREENSHOT_RC)
            },
            button("Wi-Fi সেটিংস") { say(DeviceController.openWifiSettings(ctx)) })))
        c.addView(card(linear(
            button("Bluetooth") { say(DeviceController.openBluetoothSettings(ctx)) },
            button("অ্যালার্ম সেট") { alarmDialog() })))
        c.addView(card(linear(
            button("টাইমার") { timerDialog() },
            button("সময়/তারিখ") { say("এখন ${DeviceController.timeNow()}, ${DeviceController.dateNow()}") })))

        // v5: HOME স্ক্রিনের "স্ক্রিনশট" চিপ এখানে ট্রিগার করে — সত্যিকারের মিডিয়াপ্রজেকশন ফ্লো
        viewLifecycleOwner.lifecycleScope.launch {
            ArohiBus.toasts.collect { msg ->
                if (msg == "TRIGGER_SCREENSHOT") {
                    try { ScreenCaptureManager.requestConsent(requireActivity(), SCREENSHOT_RC) }
                    catch (_: Exception) { ArohiBus.toast("স্ক্রিনশট এখানে ট্রিগার করা যায়নি") }
                }
            }
        }

        c.addView(section("যোগাযোগ"))
        c.addView(card(linear(
            button("কন্টাক্ট") { say(PeopleHelper.openContacts(ctx)) },
            button("ডায়ালার") { say(PeopleHelper.openDialer(ctx)) })))
        c.addView(card(linear(
            button("কল করুন…") { callDialog() },
            button("মেসেজ ড্রাফট") { messageDialog() })))

        c.addView(section("অ্যাপ ও ফাইল"))
        c.addView(card(linear(
            button("ইনস্টল করা অ্যাপ") { appsDialog() },
            button("Download ফাইল") { say(com.arohi.ai.commands.ActionExecutor.openDownloads(ctx)) })))
        c.addView(card(button("নোটিফিকেশন সামারি") {
            val s = ArohiNotificationListener.summaryBn()
            say(s)
        }))

        c.addView(section("স্ক্রিন অটোমেশন (আরোহীর হাত)") )
        c.addView(card(linear(
            button("স্ক্রিনে কী আছে পড়ো") {
                val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                say(if (svc == null) "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই — সেটিংসে অনুমতি দিন"
                    else "স্ক্রিনে লেখা আছে: " + svc.readScreenText(500))
            },
            button("BACK") {
                val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                if (svc == null) say("অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই") else { svc.globalBack(); say("ব্যাক দিলাম") }
            })))
        c.addView(card(linear(
            button("HOME") {
                val svc = com.arohi.ai.services.ArohiAccessibilityService.instance
                if (svc == null) say("অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই") else { svc.globalHome(); say("হোমে গেলাম") }
            },
            button("অ্যাক্সেসিবিলিটি চালু করো") {
                try { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            })))
    }

    private fun alarmDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val hour = android.widget.EditText(ctx).apply {
            hint = "ঘণ্টা (0-23)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val minute = android.widget.EditText(ctx).apply {
            hint = "মিনিট (0-59)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(hour); layout.addView(minute)
        MaterialAlertDialogBuilder(ctx).setTitle("অ্যালার্ম সেট")
            .setView(layout)
            .setPositiveButton("সেট") { _, _ ->
                val h = hour.text.toString().toIntOrNull() ?: 7
                val m = minute.text.toString().toIntOrNull() ?: 0
                say(DeviceController.setAlarm(ctx, h, m, "Arohi alarm"))
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun timerDialog() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "মিনিট লিখুন (যেমন ৫)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 24, 48, 8)
        }
        MaterialAlertDialogBuilder(ctx).setTitle("টাইমার সেট")
            .setView(input)
            .setPositiveButton("সেট") { _, _ ->
                val mins = input.text.toString().toIntOrNull() ?: 5
                say(DeviceController.setTimer(ctx, mins * 60, "Arohi timer"))
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun callDialog() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "নাম বা নাম্বার"; setPadding(48, 24, 48, 8) }
        MaterialAlertDialogBuilder(ctx).setTitle("কল করুন")
            .setView(input)
            .setPositiveButton("কল") { _, _ ->
                val who = input.text.toString().trim()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("নিশ্চিত করুন")
                    .setMessage("$who-কে কল করবো?")
                    .setPositiveButton("হ্যাঁ") { _, _ -> say(PeopleHelper.call(ctx, who)) }
                    .setNegativeButton("না", null).show()
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun messageDialog() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val who = android.widget.EditText(ctx).apply { hint = "প্রাপক (নাম/নাম্বার)" }
        val what = android.widget.EditText(ctx).apply { hint = "মেসেজ লিখুন" }
        layout.addView(who); layout.addView(what)
        MaterialAlertDialogBuilder(ctx).setTitle("মেসেজ ড্রাফট (পাঠানোর আগে আপনি দেখবেন)")
            .setView(layout)
            .setPositiveButton("ড্রাফট খুলুন") { _, _ ->
                say(PeopleHelper.smsDraft(ctx, who.text.toString(), what.text.toString()))
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun appsDialog() {
        val ctx = requireContext()
        val apps = AppLauncher.listApps(ctx)
        val labels = apps.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("ইনস্টল করা অ্যাপ (${apps.size})")
            .setItems(labels) { _, which ->
                say(AppLauncher.open(ctx, apps[which].pkg))
            }
            .setNegativeButton("বন্ধ", null).show()
    }

    // ---------- live monitors ----------
    private fun startLiveMonitoring() {
        scope.launch {
            var cpuFirst = true
            while (isActive && _binding != null) {
                val bat = DeviceController.batterySnapshot(requireContext().applicationContext)
                val ram = DeviceController.ramInfo(requireContext().applicationContext)
                val storage = DeviceController.storageInfo(requireContext().applicationContext)
                val cpu = DeviceController.cpuLoad()
                binding.liveBattery.text = "ব্যাটারি: ${bat.first}% (${bat.second})"
                binding.liveRam.text = ram
                binding.liveStorage.text = storage
                binding.liveCpu.text = if (cpu < 0 && cpuFirst) {
                    cpuFirst = false; "CPU লোড: মাপা হচ্ছে…"
                } else if (cpu < 0) {
                    cpuFirst = false; "CPU লোড: (পরের রিফ্রেশে সঠিক মান)"
                } else "CPU লোড: $cpu%"
                delay(1500)
            }
        }
    }

    // ---------- view builders ----------
    private fun section(title: String): TextView =
        TextView(requireContext()).apply {
            text = title
            textSize = 16f
            setTextColor(requireContext().getColor(R.color.arohi_cyan))
            setPadding(8, 28, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun card(v: View, extraHint: String? = null): View {
        val ctx = requireContext()
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_glass)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 8, 0, 8) }
        }
        wrapper.addView(v)
        if (extraHint != null) {
            wrapper.addView(TextView(ctx).apply {
                text = extraHint; textSize = 12f
                setTextColor(ctx.getColor(R.color.arohi_warn))
            })
        }
        return wrapper
    }

    private fun linear(vararg views: View): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            for (v in views) addView(v)
        }

    private fun button(label: String, onClick: () -> Unit): com.google.android.material.button.MaterialButton =
        com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 8, 0, 8) }
        }

    private fun sliderCard(title: String, initial: Int, onChange: (Int) -> Unit): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(ctx).apply { text = "$title: $initial%"; textSize = 15f }
        val seek = SeekBar(ctx).apply {
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "$title: $p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.let { onChange(it.progress) }
                }
            })
        }
        box.addView(label); box.addView(seek)
        return box
    }

    companion object { const val SCREENSHOT_RC = 771 }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
