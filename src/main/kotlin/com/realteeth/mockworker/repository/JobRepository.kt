package com.realteeth.mockworker.repository

import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.domain.JobStatus
import org.springframework.data.jpa.repository.JpaRepository

interface JobRepository : JpaRepository<Job, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): Job?

    fun findByStatus(status: JobStatus): List<Job>
}
