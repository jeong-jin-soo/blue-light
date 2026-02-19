package com.bluelight.backend.domain.ratelimit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Rate Limit 시도 기록 엔티티
 * - DB 기반으로 서버 다중화 환경에서도 일관된 Rate Limiting 제공
 */
@Entity
@Table(name = "rate_limit_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RateLimitAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attempt_seq")
    private Long attemptSeq;

    @Column(name = "limiter_type", nullable = false, length = 20)
    private String limiterType;

    @Column(name = "identifier", nullable = false, length = 100)
    private String identifier;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @Builder
    public RateLimitAttempt(String limiterType, String identifier) {
        this.limiterType = limiterType;
        this.identifier = identifier;
        this.attemptedAt = LocalDateTime.now();
    }
}
