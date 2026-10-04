package com.openreplay.tracker

import com.openreplay.tracker.managers.CaptureGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGateTest {

    @Test
    fun `admits one capture at a time`() {
        val gate = CaptureGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isBusy)
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun `release admits the next capture`() {
        val gate = CaptureGate()
        assertTrue(gate.tryAcquire())
        gate.release()
        assertFalse(gate.isBusy)
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `release without a capture keeps the gate open`() {
        val gate = CaptureGate()
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
