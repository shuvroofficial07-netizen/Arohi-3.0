package com.arohi.ai

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.arohi.ai.core.ApiHealth
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.EmergencyStop
import com.arohi.ai.core.PermissionHelper
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.databinding.ActivityMainBinding
import com.arohi.ai.security.AppLockManager
import com.arohi.ai.services.ArohiForegroundService
import com.arohi.ai.ui.ApiCenterFragment
import com.arohi.ai.ui.AssistantFragment
import com.arohi.ai.ui.AutomationFragment
import com.arohi.ai.ui.DeviceFragment
import com.arohi.ai.ui.FilesFragment
import com.arohi.ai.ui.HomeFragment
import com.arohi.ai.ui.MemoryFragment
import com.arohi.ai.ui.MessagesFragment
import com.arohi.ai.ui.NotificationsFragment
import com.arohi.ai.ui.SettingsFragment
import com.arohi.ai.ui.SupportCenterFragment
import com.arohi.ai.ui.SystemHealthFragment
import com.arohi.ai.ui.VisionFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ---------- bottom nav (5 primary destinations) ----------
        binding.bottomNav.setOnItemSelectedListener { item ->
            val frag = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_assistant -> AssistantFragment()
                R.id.nav_device -> DeviceFragment()
                R.id.nav_memory -> MemoryFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
            switchTo(frag, titleFor(item.itemId))
            true
        }

        // ---------- full drawer (11 destinations + emergency) ----------
        binding.navView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            handleDrawerItem(item)
        }

        binding.topBar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }

        // v5: actions/voice can request navigation (e.g. FILES_FIND → FILES screen)
        lifecycleScope.launch {
            ArohiBus.toasts.collect { msg ->
                if (msg.startsWith("NAVIGATE:")) {
                    val id = msg.substringAfter("NAVIGATE:").trim()
                    val navId = resources.getIdentifier(id, "id", packageName)
                    if (navId != 0) openDestination(navId)
                }
            }
        }

        // First-run essentials: mic + notifications
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionHelper.request(this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 101)
        } else {
            PermissionHelper.request(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        // 24/7 service resumes on app open if the user enabled it before
        val s = SettingsStore.get(this)
        if (s.startOnBoot && !ArohiForegroundService.isRunning && PermissionHelper.hasMicrophone(this)) {
            try { ArohiForegroundService.start(this) } catch (_: Exception) {}
        }
    }

    private fun titleFor(itemId: Int): String = when (itemId) {
        R.id.nav_home -> "আরোহী AI"
        R.id.nav_assistant -> "ASSISTANT — কথোপকথন"
        R.id.nav_device -> "DEVICE — ডিভাইস কন্ট্রোল"
        R.id.nav_memory -> "MEMORY — স্মৃতি"
        R.id.nav_automation -> "AUTOMATION — অটোমেশন"
        R.id.nav_vision -> "VISION — ভিশন"
        R.id.nav_notifications -> "NOTIFICATIONS — নোটিফিকেশন"
        R.id.nav_messages -> "MESSAGES — মেসেজ"
        R.id.nav_files -> "FILES — ফাইল ও গ্যালারি"
        R.id.nav_api_center -> "API CENTER"
        R.id.nav_system_health -> "SYSTEM HEALTH"
        R.id.nav_settings -> "SETTINGS"
        R.id.nav_support -> "SUPPORT"
        else -> "আরোহী AI"
    }

    /** v4: public hook so fragments can open any drawer destination directly. */
    fun openDestination(navId: Int) {
        val item = binding.navView.menu.findItem(navId) ?: return
        item.isChecked = true
        handleDrawerItem(item)
    }

    /** Shared drawer handler — one truth for both drawer taps and openDestination. */
    private fun handleDrawerItem(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_emergency -> { confirmEmergency(); return true }
            R.id.nav_support -> { switchTo(SupportCenterFragment(), "SUPPORT"); return true }
        }
        val frag = when (item.itemId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_assistant -> AssistantFragment()
            R.id.nav_device -> DeviceFragment()
            R.id.nav_memory -> MemoryFragment()
            R.id.nav_automation -> AutomationFragment()
            R.id.nav_vision -> VisionFragment()
            R.id.nav_notifications -> NotificationsFragment()
            R.id.nav_messages -> MessagesFragment()
            R.id.nav_files -> FilesFragment()
            R.id.nav_api_center -> ApiCenterFragment()
            R.id.nav_system_health -> SystemHealthFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> null
        }
        if (frag != null) {
            switchTo(frag, titleFor(item.itemId))
            // keep bottom nav in sync where a tab exists
            val tab = when (item.itemId) {
                R.id.nav_home -> R.id.nav_home
                R.id.nav_assistant -> R.id.nav_assistant
                R.id.nav_device -> R.id.nav_device
                R.id.nav_memory -> R.id.nav_memory
                R.id.nav_settings -> R.id.nav_settings
                else -> null
            }
            if (tab != null && binding.bottomNav.selectedItemId != tab) {
                binding.bottomNav.selectedItemId = tab
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (AppLockManager.shouldLock(this)) {
            binding.root.visibility = View.INVISIBLE
            AppLockManager.showLock(this) {
                binding.root.visibility = View.VISIBLE
            }
        } else {
            binding.root.visibility = View.VISIBLE
        }
    }

    private fun confirmEmergency() {
        MaterialAlertDialogBuilder(this)
            .setTitle("EMERGENCY STOP?")
            .setMessage("সব কাজ, কথা বলা, অটোমেশন আর ব্যাকগ্রাউন্ড শোনা — সব থামিয়ে দেওয়া হবে। আবার চালু করতে হবে START AROHI চেপে।")
            .setPositiveButton("হ্যাঁ, সব থামাও") { _, _ ->
                EmergencyStop.execute(this)
                ArohiBus.toast("সবকিছু বন্ধ করা হয়েছে")
            }
            .setNegativeButton("না", null)
            .show()
    }

    /** One-tap setup wizard — every permission Arohi needs, explained + opened. */
    private data class SetupRow(val name: String, val why: String, val on: Boolean, val act: () -> Unit)

    fun showSetupWizard() {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val rows = listOf(
            SetupRow("মাইক্রোফোন", "ভয়েস শুনতে", PermissionHelper.hasMicrophone(ctx)) {
                PermissionHelper.request(ctx, arrayOf(Manifest.permission.RECORD_AUDIO), 103)
            },
            SetupRow("নোটিফিকেশন লিসেনার", "নোটিফিকেশন পড়ে বলা ও অটো-রিপ্লাই", PermissionHelper.isNotificationListenerEnabled(ctx)) {
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
            },
            SetupRow("অ্যাক্সেসিবিলিটি", "স্ক্রিনে টাচ ও রিপ্লাই পাঠানো", PermissionHelper.isAccessibilityEnabled(ctx)) {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            },
            SetupRow("ব্যাটারি আনরেস্ট্রিক্টেড", "২৪/৭ না মরা", ApiHealth.isBatteryUnrestricted(ctx)) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"))) } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        )
        rows.forEach { (name, why, on, act) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(0, 16, 0, 16)
            }
            val tv = TextView(ctx).apply {
                text = "$name — $why\n${if (on) "✔ চালু আছে" else "✖ বন্ধ — ট্যাপ করে চালু করো"}"
                textSize = 14f
                setTextColor(getColor(if (on) R.color.arohi_success else R.color.arohi_warn))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            row.setOnClickListener { if (!on) act() }
            box.addView(row)
        }
        box.addView(TextView(ctx).apply {
            text = "\nসব চালু হয়ে গেলে আরোহী ২৪ ঘণ্টা ব্যাকগ্রাউন্ডে তোমার ভয়েস শুনবে, " +
                "নোটিফিকেশন পড়ে বাংলায় জানাবে এবং মেসেজে নিজে থেকেই রিপ্লাই করবে।"
            textSize = 13f
            setTextColor(getColor(R.color.arohi_text_dim))
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle("আরোহী সেটআপ — ৪টি অনুমতি")
            .setView(box)
            .setPositiveButton("ঠিক আছে", null)
            .show()
    }

    private fun switchTo(fragment: Fragment, title: String = "আরোহী AI") {
        binding.topBar.title = title
        // v6 screenshot header: subtitle only on Home ("JARVIS + AI Companion")
        binding.topBar.subtitle = if (fragment is HomeFragment)
            "JARVIS + AI Companion — Always With You ♡" else null
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitAllowingStateLoss()
    }
}
