package com.readingassistant.domain.quality

import com.readingassistant.domain.camera.SimulatedCameraScenario
import com.readingassistant.domain.camera.SimulatedDocumentCameraController
import com.readingassistant.domain.model.QualityIssue
import com.readingassistant.domain.model.ReadinessDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageQualityEngineTest {

    private lateinit var engine: ImageQualityEngine
    private lateinit var camera: SimulatedDocumentCameraController

    @Before
    fun setUp() {
        engine = ImageQualityEngine()
        camera = SimulatedDocumentCameraController()
    }

    @Test
    fun `sharp page fixture evaluates to Ready`() {
        val frame = camera.emitTestFrameSync(SimulatedCameraScenario.SHARP_PAGE)
        val decision = engine.evaluate(frame)

        assertTrue("Expected Ready but was $decision", decision is ReadinessDecision.Ready)
        val ready = decision as ReadinessDecision.Ready
        assertTrue("Expected sharpness >= 0.5 but was ${ready.score.sharpness}", ready.score.sharpness >= 0.5f)
        assertTrue(ready.score.overallConfidence >= 0.65f)
    }

    @Test
    fun `blurry page fixture evaluates to NotReady with BLURRY issue`() {
        val frame = camera.emitTestFrameSync(SimulatedCameraScenario.BLURRED_PAGE)
        val decision = engine.evaluate(frame)

        assertTrue(decision is ReadinessDecision.NotReady)
        val notReady = decision as ReadinessDecision.NotReady
        assertEquals(QualityIssue.BLURRY, notReady.primaryIssue)
    }

    @Test
    fun `dark page fixture evaluates to NotReady with TOO_DARK issue`() {
        val frame = camera.emitTestFrameSync(SimulatedCameraScenario.DARK_PAGE)
        val decision = engine.evaluate(frame)

        assertTrue(decision is ReadinessDecision.NotReady)
        val notReady = decision as ReadinessDecision.NotReady
        assertEquals(QualityIssue.TOO_DARK, notReady.primaryIssue)
    }

    @Test
    fun `overexposed page fixture evaluates to NotReady with TOO_BRIGHT issue`() {
        val frame = camera.emitTestFrameSync(SimulatedCameraScenario.OVEREXPOSED_PAGE)
        val decision = engine.evaluate(frame)

        assertTrue(decision is ReadinessDecision.NotReady)
        val notReady = decision as ReadinessDecision.NotReady
        assertEquals(QualityIssue.TOO_BRIGHT, notReady.primaryIssue)
    }

    @Test
    fun `glare page fixture evaluates to NotReady with GLARE_DETECTED issue`() {
        val frame = camera.emitTestFrameSync(SimulatedCameraScenario.GLARE_PAGE)
        val decision = engine.evaluate(frame)

        assertTrue(decision is ReadinessDecision.NotReady)
        val notReady = decision as ReadinessDecision.NotReady
        assertEquals(QualityIssue.GLARE_DETECTED, notReady.primaryIssue)
    }

    @Test
    fun `cropped page fixture evaluates to NotReady with PAGE_CROPPED issue`() {
        val frame = camera.emitTestFrameSync(SimulatedCameraScenario.CROPPED_PAGE)
        val decision = engine.evaluate(frame)

        assertTrue(decision is ReadinessDecision.NotReady)
        val notReady = decision as ReadinessDecision.NotReady
        assertEquals(QualityIssue.PAGE_CROPPED, notReady.primaryIssue)
    }
}
