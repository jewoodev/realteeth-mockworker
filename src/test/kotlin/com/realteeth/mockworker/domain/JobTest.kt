package com.realteeth.mockworker.domain

import com.realteeth.mockworker.domain.JobStatus.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class JobTest {

    @Test
    fun `transitionTo changes status on valid transition`() {
        val job = Job(idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(PROCESSING)
        assertEquals(job.status, PROCESSING)
    }

    @Test
    fun `transitionTo throws on invalid transition`() {
        val job = Job(idempotencyKey = "key1", imageUrl = "http://img.png")
        assertThrows<IllegalArgumentException> {
            job.transitionTo(COMPLETED)
        }
    }

    @Test
    fun `COMPLETED job cannot transition to any state`() {
        val job = Job(idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(PROCESSING)
        job.transitionTo(COMPLETED)

        assertThrows<IllegalArgumentException> { job.transitionTo(PENDING) }
        assertThrows<IllegalArgumentException> { job.transitionTo(PROCESSING) }
        assertThrows<IllegalArgumentException> { job.transitionTo(FAILED) }
    }

    @Test
    fun `full lifecycle PENDING to PROCESSING to COMPLETED`() {
        val job = Job(idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(PROCESSING)
        job.transitionTo(COMPLETED)
        assertEquals(job.status, COMPLETED)
    }

    @Test
    fun `full lifecycle PENDING to PROCESSING to FAILED to PENDING`() {
        val job = Job(idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(PROCESSING)
        job.transitionTo(FAILED)
        job.transitionTo(PENDING)
        assertEquals(job.status, PENDING)
    }
}
