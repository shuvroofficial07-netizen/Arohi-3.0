package com.arohi.ai.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConvState { IDLE, DORMANT, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR }
// v5: DORMANT = ২৪/৭ ওয়েক-ওয়ার্ড স্ট্যান্ডবাই — মাইক প্যাসিভ, কেবল "আরোহী" কমান্ডে
// জেগে উঠে সত্যিকারের শোনা (LISTENING) শুরু হয়। ইউআই-তে DORMANT কখনোই
// "শুনছি…" দেখাবে না — সততার নিয়ম।

enum class Emotion(val labelBn: String, val colorHex: String) {
    NEUTRAL("স্বাভাবিক", "#00E5FF"),
    HAPPY("খুশি", "#00E676"),
    PLAYFUL("চঞ্চল", "#F50057"),
    FOCUSED("মনোযোগী", "#2979FF"),
    CONFUSED("বিভ্রান্ত", "#FFB300"),
    ANGRY("রাগী", "#FF1744"),
    SAD("বিষণ্ণ", "#536DFE"),
    ERROR("ত্রুটি", "#FF5252"),
    OFFLINE("অফলাইন", "#78909C")
}

/**
 * Central live-state bus. Every UI element and service subscribes here.
 * No fake status is ever pushed: flags are set by the components that own them.
 */
object ArohiBus {
    val state = MutableStateFlow(ConvState.IDLE)
    val emotion = MutableStateFlow(Emotion.NEUTRAL)
    val amplitude = MutableStateFlow(0f)          // 0..1 live voice energy
    val speaking = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val sessionOpen = MutableStateFlow(false)     // continuous conversation session
    val serviceRunning = MutableStateFlow(false)
    val micActive = MutableStateFlow(false)
    val cameraActive = MutableStateFlow(false)
    val screenRecordingActive = MutableStateFlow(false)
    val offline = MutableStateFlow(false)
    val taskProgress = MutableStateFlow<Pair<String, Int>?>(null) // title, 0..100
    val pendingConfirmation = MutableStateFlow<String?>(null)
    val chatChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val adaptiveMode = MutableStateFlow("")       // LOCAL | LIGHT | FULL | VISION (v4 adaptive engine)
    val activeAgent = MutableStateFlow("")        // which agent is handling the current task (v4)
    val activeProvider = MutableStateFlow("")     // v6: কোন ব্রেইন প্রোভাইডার উত্তর দিলো (Gemini/OpenAI)
    val emergencyStopped = MutableStateFlow(false) // v4 emergency stop latch
    val userTone = MutableStateFlow("")           // v5: শুভ্রের কথা বলার টোন (audio prosody + text)

    fun toast(msg: String) { toasts.tryEmit(msg) }
    fun chatRefreshed() { chatChanged.tryEmit(Unit) }

    fun setState(s: ConvState) { state.value = s }
    fun resetToIdle() {
        state.value = ConvState.IDLE
        amplitude.value = 0f
        listening.value = false
    }
    /** v5: wake-standby → honest DORMANT display everywhere. */
    fun resetToDormant() {
        state.value = ConvState.DORMANT
        amplitude.value = 0f
        listening.value = false
        micActive.value = false
    }
}
