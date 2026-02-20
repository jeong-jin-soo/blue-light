package com.bluelight.backend.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * SLD AI Agent (Python FastAPI) 연동 설정
 * - WebClient 빈 + 서비스 설정값
 */
@Configuration
@Getter
@Slf4j
public class SldAgentConfig {

    @Value("${sld.agent.url:http://localhost:8100}")
    private String agentUrl;

    @Value("${sld.agent.service-key:dev-service-key}")
    private String serviceKey;

    @Value("${sld.agent.timeout-seconds:120}")
    private int timeoutSeconds;

    @Bean
    public WebClient sldAgentWebClient() {
        return WebClient.builder()
                .baseUrl(agentUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Service-Key", serviceKey)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))  // 2MB (SVG 응답 대응)
                .build();
    }
}
