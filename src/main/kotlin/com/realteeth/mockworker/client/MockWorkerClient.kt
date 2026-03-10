package com.realteeth.mockworker.client

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class MockWorkerClient(
    webClientBuilder: WebClient.Builder,
    @Value($$"${mockworker.base-url}") baseUrl: String
) {
    private val client = webClientBuilder.baseUrl(baseUrl).build()
    private var apiKey: String? = null

    @EventListener(ApplicationReadyEvent::class)
    private suspend fun setApiKey() {
        apiKey = client.post()
            .uri("/mock/auth/issue-key")
            .bodyValue(KeyRequest())
            .retrieve()
            .onStatus(HttpStatusCode::isError) { response ->
                response.bodyToMono<String>().map { body ->
                    MockWorkerException("Mock Worker error: ${response.statusCode()} - $body")
                }
            }
            .bodyToMono<KeyResponse>()
            .awaitSingle()
            .apiKey
    }

    suspend fun requestProcess(imageUrl: String): ProcessStartResponse {
        return client.post()
            .uri("/mock/process")
            .header("X-API-KEY", apiKey)
            .bodyValue(ProcessRequest(imageUrl))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { response ->
                response.bodyToMono<String>().map { body ->
                    MockWorkerException("Mock Worker error: ${response.statusCode()} - $body")
                }
            }
            .awaitBody()
    }

    suspend fun getStatus(jobId: String): ProcessStatusResponse {
        return client.get()
            .uri("/mock/process/{jobId}", jobId)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { response ->
                response.bodyToMono<String>().map { body ->
                    MockWorkerException("Mock Worker error: ${response.statusCode()} - $body")
                }
            }
            .awaitBody()
    }
}
data class KeyRequest(val candidateName: String = "신제우", val email: String = "jewoos15@gmail.com")
data class KeyResponse(val apiKey: String)
data class ProcessRequest(val imageUrl: String)
data class ProcessStartResponse(val jobId: String, val status: String)
data class ProcessStatusResponse(val jobId: String, val status: String, val result: String?)

class MockWorkerException(message: String) : RuntimeException(message)
