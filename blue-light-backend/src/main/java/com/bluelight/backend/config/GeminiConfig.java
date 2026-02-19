package com.bluelight.backend.config;

import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;

/**
 * Google Gemini API 설정
 * - 환경변수 기본값 + DB 런타임 오버라이드 지원
 * - DB 기반 TTL 캐시 (60초) — 다중 서버 환경에서도 일관성 보장
 */
@Configuration
@Getter
@Slf4j
public class GeminiConfig {

    private static final long CACHE_TTL_SECONDS = 60;

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

    private final SystemSettingRepository systemSettingRepository;

    /**
     * TTL 캐시: DB 조회 결과를 60초간 보관
     */
    private volatile String cachedApiKey;
    private volatile Instant cacheExpiry = Instant.MIN;

    public GeminiConfig(SystemSettingRepository systemSettingRepository) {
        this.systemSettingRepository = systemSettingRepository;
    }

    /**
     * 현재 활성 API 키 반환 (DB 오버라이드 > 환경변수)
     * - 60초 TTL 캐시 적용: 다중 서버에서도 DB 변경이 최대 60초 내 반영
     */
    public String getApiKey() {
        Instant now = Instant.now();
        if (now.isBefore(cacheExpiry) && cachedApiKey != null) {
            return cachedApiKey;
        }

        // DB에서 조회
        String dbKey = systemSettingRepository.findById("gemini_api_key")
                .map(s -> s.getSettingValue())
                .filter(v -> !v.isBlank())
                .orElse(null);

        String resolved = (dbKey != null) ? dbKey : envApiKey;
        this.cachedApiKey = resolved;
        this.cacheExpiry = now.plusSeconds(CACHE_TTL_SECONDS);
        return resolved;
    }

    /**
     * 캐시 무효화 (설정 변경 시 즉시 반영용 — 같은 서버에서만 효과)
     */
    public void invalidateCache() {
        this.cacheExpiry = Instant.MIN;
        log.info("Gemini API key cache invalidated");
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
