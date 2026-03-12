package com.realteeth.mockworker

import com.realteeth.mockworker.domain.JobStatus
import com.realteeth.mockworker.repository.JobRepository
import com.realteeth.mockworker.scheduler.JobStatusPoller
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JobE2ETest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var jobRepository: JobRepository

    @Autowired
    lateinit var jobStatusPoller: JobStatusPoller

    @AfterEach
    fun cleanup() {
        jobRepository.deleteAll()
        mockServer.dispatcher = defaultDispatcher
    }

    companion object {
        private val defaultDispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/mock/auth/issue-key" && request.method == "POST" ->
                        MockResponse()
                            .setBody("""{"apiKey":"test-api-key"}""")
                            .addHeader("Content-Type", "application/json")

                    request.path == "/mock/process" && request.method == "POST" ->
                        MockResponse()
                            .setBody("""{"jobId":"mock-job-123","status":"PROCESSING"}""")
                            .addHeader("Content-Type", "application/json")

                    request.path?.matches(Regex("/mock/process/.+")) == true && request.method == "GET" ->
                        MockResponse()
                            .setBody("""{"jobId":"mock-job-123","status":"COMPLETED","result":"processed result"}""")
                            .addHeader("Content-Type", "application/json")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        private val mockServer = MockWebServer().apply {
            dispatcher = defaultDispatcher
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("mockworker.base-url") { mockServer.url("/").toString().trimEnd('/') }
            registry.add("mockworker.poll-interval-ms") { "999999" }
        }
    }

    private fun createJobRequest(idempotencyKey: String, imageUrl: String = "https://example.com/test.png"): HttpEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", idempotencyKey)
        }
        return HttpEntity("""{"imageUrl":"$imageUrl"}""", headers)
    }

    @Test
    fun `작업 생성부터 완료까지 전체 흐름`() {
        // 1. 작업 생성
        val response = restTemplate.postForEntity(
            "/api/jobs/create",
            createJobRequest("e2e-happy-path"),
            Map::class.java
        )

        assertTrue(response.statusCode == HttpStatus.CREATED || response.statusCode == HttpStatus.OK)
        val jobId = (response.body!!["id"] as Number).toLong()

        // 2. 백그라운드에서 MockWorker 호출 완료 대기 (PROCESSING + mockJobId 할당)
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val job = jobRepository.findById(jobId).get()
            assertEquals(JobStatus.PROCESSING, job.status)
            assertNotNull(job.mockJobId)
        }

        // 3. 폴러를 수동 실행하여 MockWorker 상태 조회 → COMPLETED 전환
        jobStatusPoller.pollProcessingJobs()

        // 4. 비동기 폴링 결과 반영 대기
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val job = jobRepository.findById(jobId).get()
            assertEquals(JobStatus.COMPLETED, job.status)
            assertEquals("processed result", job.result)
        }

        // 5. API로 최종 상태 확인
        val getResponse = restTemplate.getForEntity("/api/jobs/$jobId", Map::class.java)
        assertEquals(HttpStatus.OK, getResponse.statusCode)
        assertEquals("COMPLETED", getResponse.body!!["status"])
        assertEquals("processed result", getResponse.body!!["result"])
    }

    @Test
    fun `동일한 멱등성 키로 중복 요청 시 기존 작업을 반환한다`() {
        val request = createJobRequest("e2e-idempotency")

        val first = restTemplate.postForEntity("/api/jobs/create", request, Map::class.java)
        assertEquals(HttpStatus.CREATED, first.statusCode)
        val jobId = (first.body!!["id"] as Number).toLong()

        val second = restTemplate.postForEntity("/api/jobs/create", request, Map::class.java)
        val secondJobId = (second.body!!["id"] as Number).toLong()
        assertEquals(jobId, secondJobId)
    }

    @Test
    fun `존재하지 않는 작업 조회 시 404를 반환한다`() {
        val response = restTemplate.getForEntity("/api/jobs/99999", Map::class.java)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("Job not found: 99999", response.body!!["detail"])
    }

    @Test
    fun `전체 작업 목록을 조회한다`() {
        restTemplate.postForEntity("/api/jobs/create", createJobRequest("e2e-list-1", "https://example.com/1.png"), Map::class.java)
        restTemplate.postForEntity("/api/jobs/create", createJobRequest("e2e-list-2", "https://example.com/2.png"), Map::class.java)

        val response = restTemplate.getForEntity("/api/jobs", List::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.size >= 2)
    }

    @Test
    fun `MockWorker 호출 실패 시 작업이 FAILED 상태로 전환된다`() {
        // MockWorker가 /mock/process에서 500 에러를 반환하도록 설정
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/mock/auth/issue-key" && request.method == "POST" ->
                        MockResponse()
                            .setBody("""{"apiKey":"test-api-key"}""")
                            .addHeader("Content-Type", "application/json")

                    request.path == "/mock/process" && request.method == "POST" ->
                        MockResponse()
                            .setResponseCode(500)
                            .setBody("Internal Server Error")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val response = restTemplate.postForEntity(
            "/api/jobs/create",
            createJobRequest("e2e-failure"),
            Map::class.java
        )
        val jobId = (response.body!!["id"] as Number).toLong()

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val job = jobRepository.findById(jobId).get()
            assertEquals(JobStatus.FAILED, job.status)
            assertNotNull(job.errorMessage)
        }
    }

    @Test
    fun `폴링 시 MockWorker가 아직 처리 중이면 PROCESSING 상태를 유지한다`() {
        // MockWorker가 상태 조회 시 PROCESSING을 반환하도록 설정
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/mock/auth/issue-key" && request.method == "POST" ->
                        MockResponse()
                            .setBody("""{"apiKey":"test-api-key"}""")
                            .addHeader("Content-Type", "application/json")

                    request.path == "/mock/process" && request.method == "POST" ->
                        MockResponse()
                            .setBody("""{"jobId":"mock-job-still-processing","status":"PROCESSING"}""")
                            .addHeader("Content-Type", "application/json")

                    request.path?.matches(Regex("/mock/process/.+")) == true && request.method == "GET" ->
                        MockResponse()
                            .setBody("""{"jobId":"mock-job-still-processing","status":"PROCESSING","result":null}""")
                            .addHeader("Content-Type", "application/json")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val response = restTemplate.postForEntity(
            "/api/jobs/create",
            createJobRequest("e2e-still-processing"),
            Map::class.java
        )
        val jobId = (response.body!!["id"] as Number).toLong()

        // mockJobId 할당까지 대기
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val job = jobRepository.findById(jobId).get()
            assertEquals(JobStatus.PROCESSING, job.status)
            assertNotNull(job.mockJobId)
        }

        // 폴러 실행 후에도 여전히 PROCESSING
        jobStatusPoller.pollProcessingJobs()

        await.during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted {
            val job = jobRepository.findById(jobId).get()
            assertEquals(JobStatus.PROCESSING, job.status)
        }
    }
}
