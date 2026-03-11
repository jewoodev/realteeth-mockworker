package com.realteeth.mockworker.scheduler

import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.repository.JobRepository
import com.realteeth.mockworker.service.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 서버 재시작 시 중단된 작업을 복구한다.
 *
 * - PENDING 상태 (Mock Worker 호출 전 다운): 다시 Mock Worker 제출
 * - PROCESSING 상태 (mockJobId 없음, Mock Worker 호출 전 다운): PENDING으로 되돌려 재제출
 * - PROCESSING 상태 (mockJobId 있음): 폴러가 자동으로 상태를 추적하므로 별도 처리 불필요
 */
@Order(2)
@Component
class JobRecovery(
    private val jobRepository: JobRepository,
    private val jobService: JobService,
    private val backgroundScope: CoroutineScope,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments?) {
        recoverStuckJobs()
    }

    @Transactional
    fun recoverStuckJobs() {
        // PROCESSING이지만 mockJobId가 없는 경우: Mock Worker 호출 전에 서버가 다운된 것
        val stuckProcessing = jobRepository.findByStatus(JobStatus.PROCESSING)
            .filter { it.mockJobId == null }

        for (job in stuckProcessing) {
            log.info("Recovering stuck PROCESSING job ${job.id} -> PENDING")
            job.forceTransitionWhenRecoverOnly()
            jobRepository.save(job)
        }

        // PENDING 상태의 작업: Mock Worker에 재제출
        val pendingJobs = jobRepository.findByStatus(JobStatus.PENDING)
        for (job in pendingJobs) {
            log.info("Re-submitting PENDING job ${job.id} to Mock Worker")
            backgroundScope.launch {
                jobService.submitToMockWorker(job.id)
                jobService.callMockWorkerAndSave(job.id)
            }
        }

        if (stuckProcessing.isNotEmpty() || pendingJobs.isNotEmpty()) {
            log.info("Recovery complete: ${stuckProcessing.size} stuck, ${pendingJobs.size} pending re-submitted")
        }
    }

    /** Rule: 서버 장애 상황을 회복을 위해서만 사용되어야 함 */
    private fun Job.forceTransitionWhenRecoverOnly() {
        status = JobStatus.PENDING // 상태 전이 규칙(transitionTo 메서드) 우회
    }
}
