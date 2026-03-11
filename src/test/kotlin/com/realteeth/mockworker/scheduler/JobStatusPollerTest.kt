package com.realteeth.mockworker.scheduler

import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.client.ProcessStatusResponse
import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.repository.JobRepository
import com.realteeth.mockworker.service.JobService
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JobStatusPollerTest {

    private val jobRepository = mockk<JobRepository>()
    private val jobService = mockk<JobService>(relaxed = true)
    private val mockWorkerClient = mockk<MockWorkerClient>()
    private val backgroundScope = CoroutineScope(UnconfinedTestDispatcher())
    private lateinit var poller: JobStatusPoller

    @BeforeEach
    fun setup() {
        clearAllMocks()
        poller = JobStatusPoller(jobRepository, jobService, mockWorkerClient, backgroundScope)
    }

    @Test
    fun `polls COMPLETED status and marks job completed`() = runTest {
        val job = createProcessingJob(1L, "mock-1")
        every { jobRepository.findByStatus(JobStatus.PROCESSING) } returns listOf(job)
        coEvery { mockWorkerClient.getStatus("mock-1") } returns
            ProcessStatusResponse("mock-1", "COMPLETED", "result-data")

        poller.pollProcessingJobs()

        verify { jobService.markJobCompleted(job.id, "result-data") }
    }

    @Test
    fun `polls FAILED status and marks job failed`() = runTest {
        val job = createProcessingJob(1L, "mock-1")
        every { jobRepository.findByStatus(JobStatus.PROCESSING) } returns listOf(job)
        coEvery { mockWorkerClient.getStatus("mock-1") } returns
            ProcessStatusResponse("mock-1", "FAILED", null)

        poller.pollProcessingJobs()

        verify { jobService.markJobFailed(job.id, "Mock Worker processing failed") }
    }

    @Test
    fun `skips jobs without mockJobId`() = runTest {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        // mockJobId is null
        every { jobRepository.findByStatus(JobStatus.PROCESSING) } returns listOf(job)

        poller.pollProcessingJobs()

        coVerify(exactly = 0) { mockWorkerClient.getStatus(any()) }
    }

    @Test
    fun `PROCESSING status does nothing`() = runTest {
        val job = createProcessingJob(1L, "mock-1")
        every { jobRepository.findByStatus(JobStatus.PROCESSING) } returns listOf(job)
        coEvery { mockWorkerClient.getStatus("mock-1") } returns
            ProcessStatusResponse("mock-1", "PROCESSING", null)

        poller.pollProcessingJobs()

        verify(exactly = 0) { jobService.markJobCompleted(any(), any()) }
        verify(exactly = 0) { jobService.markJobFailed(any(), any()) }
    }

    @Test
    fun `exception during polling does not crash`() = runTest {
        val job = createProcessingJob(1L, "mock-1")
        every { jobRepository.findByStatus(JobStatus.PROCESSING) } returns listOf(job)
        coEvery { mockWorkerClient.getStatus("mock-1") } throws RuntimeException("Network error")

        poller.pollProcessingJobs()

        verify(exactly = 0) { jobService.markJobCompleted(any(), any()) }
        verify(exactly = 0) { jobService.markJobFailed(any(), any()) }
    }

    private fun createProcessingJob(id: Long, mockJobId: String): Job {
        val job = Job(id = id, idempotencyKey = "key-$id", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        job.mockJobId = mockJobId
        return job
    }
}
