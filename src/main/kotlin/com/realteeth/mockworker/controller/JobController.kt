package com.realteeth.mockworker.controller

import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.service.JobNotFoundException
import com.realteeth.mockworker.service.JobService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "Job", description = "이미지 처리 작업 관리 API")
@RestController
@RequestMapping("/api/jobs")
class JobController(private val jobService: JobService) {

    @Operation(summary = "작업 생성", description = "이미지 처리 작업을 생성합니다. 동일한 멱등성 키로 중복 요청 시 기존 작업을 반환합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "작업 생성 성공"),
        ApiResponse(responseCode = "200", description = "이미 존재하는 작업 반환 (멱등성)"),
    )
    @PostMapping("/create")
    fun createJob(
        @Parameter(description = "멱등성 키", required = true, example = "unique-key-123")
        @RequestHeader("X-Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreateJobRequest,
    ): ResponseEntity<JobResponse> {
        val job = jobService.createJob(idempotencyKey, request.imageUrl)

        val isNew = job.status.name == "PENDING"
        return ResponseEntity
            .status(if (isNew) HttpStatus.CREATED else HttpStatus.OK)
            .body(job.toResponse())
    }

    @Operation(summary = "작업 조회", description = "ID로 작업을 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "작업을 찾을 수 없음", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    )
    @GetMapping("/{id}")
    fun getJob(@Parameter(description = "작업 ID", example = "1") @PathVariable id: Long): ResponseEntity<JobResponse> {
        val job = jobService.getJob(id)
        return ResponseEntity.ok(job.toResponse())
    }

    @Operation(summary = "전체 작업 목록 조회", description = "모든 작업 목록을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
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

@Schema(description = "작업 생성 요청")
data class CreateJobRequest(
    @field:Schema(description = "처리할 이미지 URL", example = "https://example.com/image.png")
    val imageUrl: String,
)

@Schema(description = "작업 응답")
data class JobResponse(
    @field:Schema(description = "작업 ID", example = "1")
    val id: Long,
    @field:Schema(description = "작업 상태", example = "PENDING", allowableValues = ["PENDING", "PROCESSING", "COMPLETED", "FAILED"])
    val status: String,
    @field:Schema(description = "이미지 URL", example = "https://example.com/image.png")
    val imageUrl: String,
    @field:Schema(description = "처리 결과", nullable = true)
    val result: String?,
    @field:Schema(description = "에러 메시지", nullable = true)
    val errorMessage: String?,
    @field:Schema(description = "생성 시각", example = "2026-03-12T00:00:00Z")
    val createdAt: String,
    @field:Schema(description = "수정 시각", example = "2026-03-12T00:00:00Z")
    val updatedAt: String,
)

@Schema(description = "에러 응답")
data class ErrorResponse(
    @field:Schema(description = "에러 상세 메시지", example = "Job not found: 1")
    val detail: String,
)

private fun Job.toResponse() = JobResponse(
    id = id,
    status = status.name,
    imageUrl = imageUrl,
    result = result,
    errorMessage = errorMessage,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
