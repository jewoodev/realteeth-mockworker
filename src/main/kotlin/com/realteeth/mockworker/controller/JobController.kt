package com.realteeth.mockworker.controller

import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.service.JobNotFoundException
import com.realteeth.mockworker.service.JobService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/jobs")
class JobController(private val jobService: JobService) {

    @PostMapping("/create")
    fun createJob(
        @RequestHeader("X-Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreateJobRequest,
    ): ResponseEntity<JobResponse> {
        val job = jobService.createJob(idempotencyKey, request.imageUrl)

        val isNew = job.status.name == "PENDING"
        return ResponseEntity
            .status(if (isNew) HttpStatus.CREATED else HttpStatus.OK)
            .body(job.toResponse())
    }

    @GetMapping("/{id}")
    fun getJob(@PathVariable id: Long): ResponseEntity<JobResponse> {
        val job = jobService.getJob(id)
        return ResponseEntity.ok(job.toResponse())
    }

    @GetMapping
    fun listJobs(): ResponseEntity<List<JobResponse>> {
        val jobs = jobService.listJobs()
        return ResponseEntity.ok(jobs.map { it.toResponse() })
    }

    @ExceptionHandler(JobNotFoundException::class)
    fun handleNotFound(e: JobNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(e.message ?: "Not found"))
    }
}

data class CreateJobRequest(val imageUrl: String)

data class JobResponse(
    val id: Long,
    val status: String,
    val imageUrl: String,
    val result: String?,
    val errorMessage: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class ErrorResponse(val detail: String)

private fun Job.toResponse() = JobResponse(
    id = id,
    status = status.name,
    imageUrl = imageUrl,
    result = result,
    errorMessage = errorMessage,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
