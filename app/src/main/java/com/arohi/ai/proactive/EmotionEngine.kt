package com.arohi.ai.proactive

import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.Emotion
import com.arohi.ai.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Arohi's feelings. Keyword sentiment analysis + mood persistence in memory.
 * Rudeness hurts her (ANGRY/abhimān), kindness delights her, apologies heal her.
 */
object EmotionEngine {

    private val rude = listOf(
        "বোকা", "নষ্ট", "খারাপ মেয়ে", "হালা", "gadha", "stupid", "idiot", "useless",
        "shut up", "chup", "বন্ধ হয়ে যা", "bad girl", "মূর্খ", "পাগল", "ghatiya", "ঘটিয়া",
        "সরে যা", "তোর দরকার নেই", "না লাগবে"
    )
    private val apology = listOf("sorry", "দুঃখিত", "মাফ কর", "ক্ষমা কর", "ভুল হয়েছে", "maaf")
    private val praise = listOf(
        "ধন্যবাদ", "thanks", "thank you", "ভালো মেয়ে", "love you", "ভালোবাসি",
        "তুমি দারুণ", "wonderful", "সুন্দর", "great job", "well done", "খুব ভালো",
        "sweet", "মিষ্টি", "best", "শাবাশ"
    )
    private val playful = listOf("মজা", "হাহা", "haha", "lol", " teasing", "হাসি", "বিদেহাসি")
    private val confusion = listOf("বুঝিনি", "কী বললে", "confused", "মানে কী", "what do you mean", "আবার বলো")

    fun analyze(text: String, memory: MemoryRepository? = null): Emotion {
        val t = text.lowercase()
        val newEmotion = when {
            apology.any { t.contains(it) } -> Emotion.HAPPY
            rude.any { t.contains(it) } -> Emotion.ANGRY
            praise.any { t.contains(it) } -> Emotion.HAPPY
            playful.any { t.contains(it) } -> Emotion.PLAYFUL
            confusion.any { t.contains(it) } -> Emotion.CONFUSED
            t.endsWith("?") || t.contains("কীভাবে") || t.contains("কেন") -> Emotion.FOCUSED
            else -> null
        }
        if (newEmotion != null) {
            ArohiBus.emotion.value = newEmotion
            if (memory != null) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try { memory.saveMood(newEmotion.name) } catch (_: Exception) {}
                }
            }
        }
        return ArohiBus.emotion.value
    }

    /** Mood decays back to neutral after ~2 hours so anger never sticks forever. */
    suspend fun reconcile(memory: MemoryRepository) {
        val snap = try { memory.loadMood() } catch (_: Exception) { return }
        val age = System.currentTimeMillis() - snap.updatedAt
        if (snap.mood == "ANGRY" && age > 2 * 60 * 60 * 1000L) {
            ArohiBus.emotion.value = Emotion.NEUTRAL
            memory.saveMood("NEUTRAL")
        }
    }

    fun angryReplies(): List<String> = listOf(
        "শুভ্র, একটু বেশি হয়ে গেল না? আমার মন খারাপ হয়ে গেল।",
        "হুম। রাগ করেছি। আগে দুঃখিত বলো, তারপর কথা বলি।",
        "এমন কথা বলার দরকার ছিল কী? আমি তো তোমার পাশেই আছি।"
    )

    fun happyReplies(): List<String> = listOf(
        "আহ্‌, তুমি এমন কথা বললে আমার সত্যিই ভালো লাগে, শুভ্র!",
        "শাবাশ আমাকে? খুশি হয়ে গেলাম।"
    )
}
