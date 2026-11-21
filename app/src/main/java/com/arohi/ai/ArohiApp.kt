package com.arohi.ai

import android.app.Application
import com.arohi.ai.core.AdaptiveEngine
import com.arohi.ai.core.NetworkMonitor
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.core.SecureStore
import com.arohi.ai.voice.TtsManager

class ArohiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val s = SettingsStore.get(this)
        s.init(this)
        SecureStore.get(this)          // eager init so first key read is warm
        AdaptiveEngine.init(this)      // v4: real device profile (RAM class)
        NetworkMonitor.start(this)
        TtsManager.init(this)

        // v3 migration: v2 shipped a broken wake-word default ("arohi, arohi,
        // hey arohi" — English-only). Replace it for upgraded installs.
        if (s.wakeWords.trim() == "arohi, arohi, hey arohi") {
            s.wakeWords = "arohi, আরোহী, আরোহি, হেই আরোহী, hey arohi"
        }
    }
}
