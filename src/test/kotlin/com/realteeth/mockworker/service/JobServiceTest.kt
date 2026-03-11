package com.realteeth.mockworker.service

import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.client.ProcessStartResponse
import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.repository.JobRepository
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import java.util.function.Consumer
import kotlin.test.assertEquals

class JobServiceTest {

    private val jobRepository = mockk<JobRepository>()
    private val mockWorkerClient = mockk<MockWorkerClient>()
    private val backgroundScope = CoroutineScope(UnconfinedTestDispatcher())
    private val transactionTemplate = mockk<TransactionTemplate>()
    private lateinit var jobService: JobService

    @BeforeEach
    fun setup() {
        clearAllMocks()

        // TransactionTemplate이 콜백을 즉시 실행하도록 설정
        every { transactionTemplate.execute(any<TransactionCallback<*>>()) } answers {
            firstArg<TransactionCallback<*>>().doInTransaction(mockk())
        }
        every { transactionTemplate.executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            firstArg<Consumer<TransactionStatus>>().accept(mockk())
        }

        jobService = JobService(jobRepository, mockWorkerClient, backgroundScope, transactionTemplate)
    }

    @Test
    fun `createJob returns existing job for duplicate idempotency key`() {
        val existingJob = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        every { jobRepository.findByIdempotencyKey("key1") } returns existingJob

        val result = jobService.createJob("key1", "http://img.png")

        assertEquals(1L, result.id)
        verify(exactly = 0) { jobRepository.save(any()) }
    }

    @Test
    fun `createJob saves new job for new idempotency key`() {
        val newJob = Job(id = 2, idempotencyKey = "key2", imageUrl = "http://img.png")
        every { jobRepository.findByIdempotencyKey("key2") } returns null
        every { jobRepository.save(any()) } returns newJob
        // submitToMockWorker and callMockWorkerAndSave will be called in background
        every { jobRepository.findById(2L) } returns Optional.of(newJob)
        coEvery { mockWorkerClient.requestProcess("http://img.png") } returns
            ProcessStartResponse(jobId = "mock-123", status = "PROCESSING")

        val result = jobService.createJob("key2", "http://img.png")

        assertEquals(2L, result.id)
        // save is called at least once for the initial creation (background may also call save)
        verify(atLeast = 1) { jobRepository.save(any()) }
    }

    @Test
    fun `getJob throws JobNotFoundException when job does not exist`() {
        every { jobRepository.findById(999L) } returns Optional.empty()

        assertThrows<JobNotFoundException> {
            jobService.getJob(999L)
        }
    }

    @Test
    fun `getJob returns job when it exists`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        every { jobRepository.findById(1L) } returns Optional.of(job)

        val result = jobService.getJob(1L)

        assertEquals(1L, result.id)
    }

    @Test
    fun `submitToMockWorker transitions PENDING to PROCESSING`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        every { jobRepository.findById(1L) } returns Optional.of(job)
        every { jobRepository.save(any()) } returns job

        jobService.submitToMockWorker(1L)

        assertEquals(JobStatus.PROCESSING, job.status)
        verify { jobRepository.save(job) }
    }

    @Test
    fun `submitToMockWorker skips non-PENDING jobs`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        every { jobRepository.findById(1L) } returns Optional.of(job)

        jobService.submitToMockWorker(1L)

        verify(exactly = 0) { jobRepository.save(any()) }
    }

    @Test
    fun `callMockWorkerAndSave stores mockJobId on success`() = runTest {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        every { jobRepository.findById(1L) } returns Optional.of(job)
        coEvery { mockWorkerClient.requestProcess("http://img.png") } returns
            ProcessStartResponse(jobId = "mock-123", status = "PROCESSING")
        every { jobRepository.save(any()) } returns job

        jobService.callMockWorkerAndSave(1L)

        verify { jobRepository.save(match { it.mockJobId == "mock-123" }) }
    }

    @Test
    fun `callMockWorkerAndSave marks job FAILED on exception`() = runTest {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        every { jobRepository.findById(1L) } returns Optional.of(job)
        coEvery { mockWorkerClient.requestProcess(any()) } throws RuntimeException("Connection refused")
        every { jobRepository.save(any()) } returns job

        jobService.callMockWorkerAndSave(1L)

        assertEquals(JobStatus.FAILED, job.status)
    }

    @Test
    fun `markJobCompleted transitions PROCESSING to COMPLETED with result`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        every { jobRepository.findById(1L) } returns Optional.of(job)
        every { jobRepository.save(any()) } returns job

        jobService.markJobCompleted(1L, "result-data")

        assertEquals(JobStatus.COMPLETED, job.status)
        assertEquals("result-data", job.result)
    }

    @Test
    fun `markJobCompleted is idempotent for already COMPLETED jobs`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        job.transitionTo(JobStatus.COMPLETED)
        job.result = "original-result"
        every { jobRepository.findById(1L) } returns Optional.of(job)

        jobService.markJobCompleted(1L, "new-result")

        assertEquals("original-result", job.result)
        verify(exactly = 0) { jobRepository.save(any()) }
    }

    @Test
    fun `markJobFailed does not overwrite COMPLETED state`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        job.transitionTo(JobStatus.COMPLETED)
        every { jobRepository.findById(1L) } returns Optional.of(job)

        jobService.markJobFailed(1L, "error")

        assertEquals(JobStatus.COMPLETED, job.status)
        verify(exactly = 0) { jobRepository.save(any()) }
    }
}
