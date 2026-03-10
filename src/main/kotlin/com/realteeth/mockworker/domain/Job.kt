package com.realteeth.mockworker.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "jobs",
    indexes = [
        Index(name = "idx_jobs_status", columnList = "status"),
        Index(name = "idx_jobs_idempotency_key", columnList = "idempotencyKey", unique = true),
    ],
)
class Job(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val idempotencyKey: String,

    @Column(nullable = false)
    val imageUrl: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: JobStatus = JobStatus.PENDING,

    /** Mock Worker가 반환한 jobId */
    var mockJobId: String? = null,

    /** Mock Worker가 반환한 처리 결과 */
    @Column(columnDefinition = "TEXT")
    var result: String? = null,

    /** 실패 시 에러 메시지 */
    var errorMessage: String? = null,

    val createdAt: Instant = Instant.now(),

    var updatedAt: Instant = Instant.now(),
) {
    fun transitionTo(next: JobStatus) {
        require(status.canTransitionTo(next)) {
            "Invalid state transition: $status -> $next"
        }
        status = next
        updatedAt = Instant.now()
    }
}
