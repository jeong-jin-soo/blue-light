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
 * Google Gemini API 설정
 * - 환경변수 기본값 + DB 런타임 오버라이드 지원
 */
@Configuration
@Getter
@Slf4j
public class GeminiConfig {

    @Value("${gemini.api-key}")
    private String envApiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${gemini.max-tokens}")
    private int maxTokens;

    @Value("${gemini.temperature}")
    private double temperature;

    /**
     * DB에서 설정된 런타임 API 키 (null이면 환경변수 사용)
     */
    private volatile String runtimeApiKey;

    /**
     * 현재 활성 API 키 반환 (DB 오버라이드 > 환경변수)
     */
    public String getApiKey() {
        String runtime = this.runtimeApiKey;
        if (runtime != null && !runtime.isBlank()) {
            return runtime;
        }
        return envApiKey;
    }

    /**
     * 런타임 API 키 설정 (DB에서 로드)
     */
    public void setRuntimeApiKey(String apiKey) {
        this.runtimeApiKey = apiKey;
        log.info("Gemini runtime API key updated");
    }

    /**
     * 런타임 API 키 초기화 (환경변수로 복귀)
     */
    public void clearRuntimeApiKey() {
        this.runtimeApiKey = null;
        log.info("Gemini runtime API key cleared (reverted to env)");
    }

    @Bean
    public WebClient geminiWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
    }
}
