package com.arohi.ai

import com.arohi.ai.brain.BengaliGuard
import com.arohi.ai.brain.OpenAIClient
import com.arohi.ai.util.Extensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7 — REAL unit tests (JVM, no device) for the pure logic that decides
 * behaviour: wake-word matching, stop-word matching, echo rejection, Bengali
 * guard and model-family parameter selection.
 *
 * These exist because "it looks right in the source" is not verification. They
 * run with `./gradlew testDebugUnitTest` in CI.
 */
class LogicContractTest {

    // ---------------------------------------------------------------- wake word
    private val wakeWords = "arohi, আরোহী, আরোহি, হেই আরোহী, hey arohi"

    @Test
    fun `every documented wake word variant is recognised`() {
        listOf(
            "আরোহী", "আরোহি", "হেই আরোহি", "hey arohi", "Hey Arohi, তুমি কেমন আছো",
            "arohi", "আরোহী তুমি শুনছো?", "এরোহি", "অরোহী"
        ).forEach { phrase ->
            assertTrue("should wake on: $phrase", Extensions.matchesWakeWord(phrase, wakeWords))
        }
    }

    @Test
    fun `ordinary speech containing the name mid sentence does not wake`() {
        listOf(
            "আমি ভাবছিলাম আরোহী নামটা কোথা থেকে এলো জানো",
            "কাল সকালে আমার বন্ধু বলেছিল এই অ্যাপটা নাকি খুব ভালো কাজ করে",
            "the weather today is quite pleasant actually"
        ).forEach { phrase ->
            assertFalse("should NOT wake on: $phrase", Extensions.matchesWakeWord(phrase, wakeWords))
        }
    }

    @Test
    fun `wake word is stripped so the command reaches the brain clean`() {
        assertEquals("আজ কী আছে", Extensions.stripWakeWords("আরোহী আজ কী আছে", wakeWords))
        assertEquals("open youtube", Extensions.stripWakeWords("hey arohi open youtube", wakeWords))
    }

    // --------------------------------------------------------------- stop words
    @Test
    fun `real stop commands are recognised`() {
        listOf("থামো", "থামো একটু", "স্টপ", "stop", "stop it", "বন্ধ করো", "চুপ করো")
            .forEach { assertTrue("should stop on: $it", Extensions.isStopCommand(it)) }
    }

    @Test
    fun `long sentences and questions are never treated as stop commands`() {
        listOf(
            "stop the music later, first explain the plan to me",
            "থামো বলতে কী বোঝায় সেটা একটু বুঝিয়ে দাও প্লিজ",
            "কীভাবে একটা কাজ বন্ধ করবো",
            "amar kotha shune tumi ki stop korte parbe na"
        ).forEach { assertFalse("must NOT stop on: $it", Extensions.isStopCommand(it)) }
    }

    // ------------------------------------------------------------- bengali guard
    @Test
    fun `english dominant answers are flagged but technical terms are accepted`() {
        assertTrue(BengaliGuard.isMostlyEnglish("Hello boss, the system is working fine now."))
        assertFalse(BengaliGuard.isMostlyEnglish("শুভ্র, ইউটিউব খুলে দিয়েছি। Wi-Fi চালু আছে।"))
        assertFalse(BengaliGuard.isMostlyEnglish(""))
    }

    // ------------------------------------------------------------ model params
    @Test
    fun `reasoning models are detected so the right parameter set is sent`() {
        listOf("o1", "o1-mini", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini")
            .forEach { assertTrue("reasoning family: $it", OpenAIClient.isReasoningModel(it)) }
        listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-3.5-turbo")
            .forEach { assertFalse("classic chat family: $it", OpenAIClient.isReasoningModel(it)) }
    }
}
