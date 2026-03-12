package com.realteeth.mockworker.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.realteeth.mockworker.client.MockWorkerClient
import com.realteeth.mockworker.domain.Job
import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.service.JobNotFoundException
import com.realteeth.mockworker.service.JobService
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(JobController::class)
class JobControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var jobService: JobService

    @Test
    fun `POST creates job and returns 201`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        every { jobService.createJob("key1", "http://img.png") } returns job

        mockMvc.post("/api/jobs/create") {
            header("X-Idempotency-Key", "key1")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateJobRequest("http://img.png"))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.imageUrl") { value("http://img.png") }
        }
    }

    @Test
    fun `POST returns 200 for duplicate idempotency key`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        job.transitionTo(JobStatus.PROCESSING)
        every { jobService.createJob("key1", "http://img.png") } returns job

        mockMvc.post("/api/jobs/create") {
            header("X-Idempotency-Key", "key1")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateJobRequest("http://img.png"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.status") { value("PROCESSING") }
        }
    }

    @Test
    fun `POST without idempotency key returns 400`() {
        mockMvc.post("/api/jobs/create") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateJobRequest("http://img.png"))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `GET returns job by id`() {
        val job = Job(id = 1, idempotencyKey = "key1", imageUrl = "http://img.png")
        every { jobService.getJob(1L) } returns job

        mockMvc.get("/api/jobs/1").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.status") { value("PENDING") }
        }
    }

    @Test
    fun `GET returns 404 for non-existent job`() {
        every { jobService.getJob(999L) } throws JobNotFoundException("Job not found: 999")

        mockMvc.get("/api/jobs/999").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Job not found: 999") }
        }
    }

    @Test
    fun `GET list returns all jobs`() {
        val jobs = listOf(
            Job(id = 1, idempotencyKey = "key1", imageUrl = "http://a.png"),
            Job(id = 2, idempotencyKey = "key2", imageUrl = "http://b.png"),
        )
        every { jobService.listJobs() } returns jobs

        mockMvc.get("/api/jobs").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].id") { value(1) }
            jsonPath("$[1].id") { value(2) }
        }
    }
}
