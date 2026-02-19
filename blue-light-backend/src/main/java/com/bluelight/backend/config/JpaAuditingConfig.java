package com.bluelight.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JPA Auditing + Scheduling + Async 설정
 * - createdAt, updatedAt 자동 관리
 * - createdBy, updatedBy는 AuditorAware를 통해 현재 로그인 사용자 ID로 설정
 * - @Scheduled 메서드 활성화 (Rate Limiter cleanup 등)
 * - @Async 메서드 활성화 (감사 로그 비동기 저장)
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
@EnableScheduling
@EnableAsync
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<Long> auditorProvider() {
        return new AuditorAwareImpl();
    }
}
