package com.bluelight.backend.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * JPA Auditing + Scheduling + Async + ShedLock 설정
 * - createdAt, updatedAt 자동 관리
 * - createdBy, updatedBy는 AuditorAware를 통해 현재 로그인 사용자 ID로 설정
 * - @Scheduled 메서드 활성화 (Rate Limiter cleanup 등)
 * - @Async 메서드 활성화 (감사 로그 비동기 저장)
 * - ShedLock: 다중 서버 환경에서 스케줄러 중복 실행 방지
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
@EnableScheduling
@EnableAsync
@EnableSchedulerLock(defaultLockAtMostFor = "30m")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<Long> auditorProvider() {
        return new AuditorAwareImpl();
    }

    /**
     * ShedLock — JDBC 기반 분산 잠금 제공자
     * - DB 서버 시각 기준 (usingDbTime) → 서버 간 시각 차이 문제 방지
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
