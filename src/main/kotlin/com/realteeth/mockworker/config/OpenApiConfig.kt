package com.realteeth.mockworker.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("MockWorker API")
                .description("이미지 처리 작업을 관리하는 MockWorker API")
                .version("v1.0.0")
        )
}
