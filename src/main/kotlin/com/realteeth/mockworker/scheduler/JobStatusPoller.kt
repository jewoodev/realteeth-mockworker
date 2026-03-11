package com.realteeth.mockworker.scheduler

import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.repository.JobRepository
import com.realteeth.mockworker.service.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * PROCESSING 상태의 작업들을 주기적으로 Mock Worker에 폴링하여 상태를 업데이트한다.
 */
@Component
class JobStatusPoller(
    private val jobRepository: JobRepository,
    private val jobService: JobService,
    private val mockWorkerClient: MockWorkerClient,
    private val backgroundScope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3000)
    fun pollProcessingJobs() {
        val processingJobs = jobRepository.findByStatus(JobStatus.PROCESSING)
            .filter { it.mockJobId != null }

        for (job in processingJobs) {
            backgroundScope.launch {
                try {
                    val response = mockWorkerClient.getStatus(job.mockJobId!!)
                    when (response.status) {
                        "COMPLETED" -> jobService.markJobCompleted(job.id, response.result)
                        "FAILED" -> jobService.markJobFailed(job.id, "Mock Worker processing failed")
                        "PROCESSING" -> { /* 아직 처리 중, 다음 폴링까지 대기 */ }
                        else -> log.warn("Unknown status from Mock Worker: ${response.status}")
                    }
                } catch (e: Exception) {
                    log.error("Failed to poll status for job ${job.id} (mockJobId=${job.mockJobId})", e)
                }
            }
        }
    }
}
