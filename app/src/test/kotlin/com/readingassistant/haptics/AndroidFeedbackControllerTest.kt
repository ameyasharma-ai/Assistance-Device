package com.readingassistant.haptics

import android.content.Context
import android.content.ContextWrapper
import com.readingassistant.domain.model.PromptPriority
import com.readingassistant.domain.model.SpokenPrompt
import com.readingassistant.domain.model.TactilePattern
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFeedbackControllerTest {

    private class TestContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun emitHaptic_updatesLastTactilePattern() = runTest {
        val context = TestContext()
        val controller = AndroidFeedbackController(context)

        controller.emitHaptic(TactilePattern.DOCUMENT_READY)
        assertEquals(TactilePattern.DOCUMENT_READY, controller.lastTactilePattern.value)

        controller.emitHaptic(TactilePattern.CAPTURED)
        assertEquals(TactilePattern.CAPTURED, controller.lastTactilePattern.value)

        controller.emitHaptic(TactilePattern.PROCESSING_COMPLETED)
        assertEquals(TactilePattern.PROCESSING_COMPLETED, controller.lastTactilePattern.value)
    }

    @Test
    fun speak_updatesLastSpokenPromptAndInvokesDelegate() = runTest {
        val context = TestContext()
        var delegateCalled = false
        var receivedPrompt: SpokenPrompt? = null

        val controller = AndroidFeedbackController(context) { prompt ->
            delegateCalled = true
            receivedPrompt = prompt
        }

        val prompt = SpokenPrompt("test_1", "Page framed and ready", PromptPriority.IMMEDIATE_INTERRUPT)
        controller.speak(prompt)

        assertEquals(prompt, controller.lastSpokenPrompt.value)
        assertTrue(delegateCalled)
        assertNotNull(receivedPrompt)
        assertEquals("Page framed and ready", receivedPrompt?.message)
    }
}
