package com.realteeth.mockworker.domain

enum class JobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    fun canTransitionTo(next: JobStatus): Boolean = when (this) {
        PENDING -> next == PROCESSING
        PROCESSING -> next in setOf(COMPLETED, FAILED)
        FAILED -> next == PENDING
        COMPLETED -> false
    }
}
