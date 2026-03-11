package com.realteeth.mockworker.service

import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.repository.JobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
class JobService(
    private val jobRepository: JobRepository,
    private val mockWorkerClient: MockWorkerClient,
    private val backgroundScope: CoroutineScope,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이미지 처리 작업을 생성한다.
     * 동일한 idempotencyKey로 이미 작업이 존재하면 기존 작업을 반환한다.
     */
    @Transactional
    fun createJob(idempotencyKey: String, imageUrl: String): Job {
        // 멱등성 체크: 이미 존재하면 기존 작업 반환
        jobRepository.findByIdempotencyKey(idempotencyKey)?.let { return it }

        val job = try {
            jobRepository.save(Job(idempotencyKey = idempotencyKey, imageUrl = imageUrl))
        } catch (e: DataIntegrityViolationException) {
            // 동시 요청으로 인한 유니크 제약 위반 → 기존 작업 반환
            return jobRepository.findByIdempotencyKey(idempotencyKey)!!
        }

        // 백그라운드에서 Mock Worker 호출
        backgroundScope.launch {
            submitToMockWorker(job.id)
            callMockWorkerAndSave(job.id)
        }

        return job
    }

    fun getJob(jobId: Long): Job {
        return jobRepository.findById(jobId)
            .orElseThrow { JobNotFoundException("Job not found: $jobId") }
    }

    @Transactional(readOnly = true)
    fun listJobs(): List<Job> {
        return jobRepository.findAll()
    }

    /**
     * Mock Worker에 작업을 제출하고 상태를 PROCESSING으로 전환한다.
     */
    fun submitToMockWorker(jobId: Long) {
        transactionTemplate.executeWithoutResult {
            val job = jobRepository.findById(jobId).orElse(null) ?: return@executeWithoutResult
            if (job.status != JobStatus.PENDING) return@executeWithoutResult

            try {
                job.transitionTo(JobStatus.PROCESSING)
                jobRepository.save(job)
            } catch (e: IllegalArgumentException) {
                log.warn("State transition failed for job $jobId: ${e.message}")
            }
        }
    }

    /**
     * Mock Worker를 호출하고 결과를 저장한다.
     */
    suspend fun callMockWorkerAndSave(jobId: Long) {
        val imageUrl = transactionTemplate.execute {
            jobRepository.findById(jobId).orElse(null)?.imageUrl
        } ?: return

        try {
            val response = mockWorkerClient.requestProcess(imageUrl)

            transactionTemplate.executeWithoutResult {
                val job = jobRepository.findById(jobId).orElse(null) ?: return@executeWithoutResult
                job.mockJobId = response.jobId
                jobRepository.save(job)
            }
        } catch (e: Exception) {
            log.error("Failed to call Mock Worker for job $jobId", e)
            markJobFailed(jobId, e.message ?: "Unknown error")
        }
    }

    fun markJobFailed(jobId: Long, errorMessage: String) {
        transactionTemplate.executeWithoutResult {
            val job = jobRepository.findById(jobId).orElse(null) ?: return@executeWithoutResult
            if (job.status == JobStatus.COMPLETED) return@executeWithoutResult

            try {
                if (job.status != JobStatus.FAILED) {
                    job.transitionTo(JobStatus.FAILED)
                }
                job.errorMessage = errorMessage
                jobRepository.save(job)
            } catch (e: IllegalArgumentException) {
                log.warn("Cannot mark job $jobId as FAILED: ${e.message}")
            }
        }
    }

    fun markJobCompleted(jobId: Long, result: String?) {
        transactionTemplate.executeWithoutResult {
            val job = jobRepository.findById(jobId).orElse(null) ?: return@executeWithoutResult
            if (job.status == JobStatus.COMPLETED) return@executeWithoutResult

            try {
                job.transitionTo(JobStatus.COMPLETED)
                job.result = result
                jobRepository.save(job)
            } catch (e: IllegalArgumentException) {
                log.warn("Cannot mark job $jobId as COMPLETED: ${e.message}")
            }
        }
    }
}

class JobNotFoundException(message: String) : RuntimeException(message)
