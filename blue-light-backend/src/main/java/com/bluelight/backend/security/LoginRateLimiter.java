package com.bluelight.backend.security;

import com.bluelight.backend.domain.ratelimit.RateLimitAttempt;
import com.bluelight.backend.domain.ratelimit.RateLimitAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 로그인 시도 Rate Limiter (DB 기반)
 * - IP당 15분 내 최대 5회 로그인 시도 허용
 * - DB 기반으로 서버 다중화 환경에서도 일관된 제한 적용
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String LIMITER_TYPE = "LOGIN";
    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MINUTES = 15;

    private final RateLimitAttemptRepository rateLimitAttemptRepository;

    /**
     * 해당 IP가 차단 상태인지 확인
     */
    @Transactional(readOnly = true)
    public boolean isBlocked(String ipAddress) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
        long recentCount = rateLimitAttemptRepository.countRecentAttempts(LIMITER_TYPE, ipAddress, cutoff);
        return recentCount >= MAX_ATTEMPTS;
    }

    /**
     * 실패한 로그인 시도 기록
     */
    @Transactional
    public void recordFailedAttempt(String ipAddress) {
        rateLimitAttemptRepository.save(
                RateLimitAttempt.builder()
                        .limiterType(LIMITER_TYPE)
                        .identifier(ipAddress)
                        .build()
        );
    }

    /**
     * 로그인 성공 시 해당 IP의 시도 기록 초기화
     */
    @Transactional
    public void clearAttempts(String ipAddress) {
        rateLimitAttemptRepository.deleteByLimiterTypeAndIdentifier(LIMITER_TYPE, ipAddress);
    }
}
