package com.arohi.ai

import com.arohi.ai.voice.EchoGuard
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v7 — ECHO GUARD tests.
 *
 * The barge-in monitor necessarily listens while Arohi speaks, so its results
 * contain her own voice. If echo filtering were wrong in either direction the
 * app would either cut her off constantly (too strict) or never accept a real
 * interruption (too loose). These tests pin down both directions using only the
 * text-level logic, which is the part that can be tested without a microphone.
 */
class EchoGuardTest {

    @Before
    fun setUp() = EchoGuard.reset()

    @After
    fun tearDown() = EchoGuard.reset()

    @Test
    fun `her own sentence heard back is treated as echo`() {
        EchoGuard.onSpeechStarted("শুভ্র, আজকের আবহাওয়া বেশ সুন্দর এবং বাইরে হালকা বাতাস বইছে।")
        assertTrue(EchoGuard.looksLikeEcho("আজকের আবহাওয়া বেশ সুন্দর"))
        assertTrue(EchoGuard.looksLikeEcho("আজকের আবহাওয়া বেশ সুন্দর এবং বাইরে হালকা বাতাস"))
    }

    @Test
    fun `a genuinely different user sentence is not echo`() {
        EchoGuard.onSpeechStarted("আজকের আবহাওয়া বেশ সুন্দর এবং বাইরে হালকা বাতাস বইছে।")
        assertFalse(EchoGuard.looksLikeEcho("একটু থামো তো"))
        assertFalse(EchoGuard.looksLikeEcho("আমার একটা প্রশ্ন আছে"))
        assertFalse(EchoGuard.looksLikeEcho("open youtube please"))
    }

    @Test
    fun `short noise fragments never count as echo`() {
        EchoGuard.onSpeechStarted("হ্যাঁ শুভ্র, আমি শুনছি।")
        assertFalse(EchoGuard.looksLikeEcho("ও"))
        assertFalse(EchoGuard.looksLikeEcho("একটা"))
    }

    @Test
    fun `wake is refused during the speaker tail window`() {
        EchoGuard.onSpeechStarted("কিছু একটা উত্তর")
        EchoGuard.onSpeechEnded()
        // the tail window is 750 ms — an immediate wake must be refused
        assertFalse(EchoGuard.acceptWake("আরোহী"))
    }

    @Test
    fun `wake is allowed once the tail window has passed`() {
        EchoGuard.onSpeechStarted("কিছু একটা উত্তর")
        EchoGuard.onSpeechEnded()
        Thread.sleep(EchoGuard.TAIL_MS + 120)
        assertTrue(EchoGuard.acceptWake("আরোহী"))
    }
}
