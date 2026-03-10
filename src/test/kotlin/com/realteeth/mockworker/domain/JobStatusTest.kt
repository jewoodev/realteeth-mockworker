package com.realteeth.mockworker.domain

import com.realteeth.mockworker.domain.JobStatus.*
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobStatusTest {

    @Test
    fun `PENDING can only transition to PROCESSING`() {
        assertTrue(PENDING.canTransitionTo(PROCESSING))
        assertFalse(PENDING.canTransitionTo(COMPLETED))
        assertFalse(PENDING.canTransitionTo(FAILED))
        assertFalse(PENDING.canTransitionTo(PENDING))
    }

    @Test
    fun `PROCESSING can transition to COMPLETED or FAILED`() {
        assertTrue(PROCESSING.canTransitionTo(COMPLETED))
        assertTrue(PROCESSING.canTransitionTo(FAILED))
        assertFalse(PROCESSING.canTransitionTo(PENDING))
        assertFalse(PROCESSING.canTransitionTo(PROCESSING))
    }

    @Test
    fun `COMPLETED is terminal state`() {
        assertFalse(COMPLETED.canTransitionTo(PENDING))
        assertFalse(COMPLETED.canTransitionTo(PROCESSING))
        assertFalse(COMPLETED.canTransitionTo(FAILED))
        assertFalse(COMPLETED.canTransitionTo(COMPLETED))
    }

    @Test
    fun `FAILED can only transition to PENDING`() {
        assertTrue(FAILED.canTransitionTo(PENDING))
        assertFalse(FAILED.canTransitionTo(PROCESSING))
        assertFalse(FAILED.canTransitionTo(COMPLETED))
        assertFalse(FAILED.canTransitionTo(FAILED))
    }
}
