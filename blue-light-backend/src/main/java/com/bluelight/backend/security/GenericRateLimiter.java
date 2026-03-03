package com.bluelight.backend.security;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.ratelimit.RateLimitAttempt;
import com.bluelight.backend.domain.ratelimit.RateLimitAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 범용 Rate Limiter (DB 기반)
 * - 다양한 엔드포인트에 재사용 가능
 * - LoginRateLimiter와 동일한 RateLimitAttempt 엔티티 사용
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenericRateLimiter {

    private final RateLimitAttemptRepository rateLimitAttemptRepository;

    /**
     * Rate limit 검사 및 기록 (하나의 호출로 검사 + 기록)
     * - 제한 초과 시 BusinessException(TOO_MANY_REQUESTS) 발생
     *
     * @param type          Rate limit 구분 키 (예: "FILE_UPLOAD", "PAYMENT_CONFIRM")
     * @param identifier    식별자 (예: IP주소, userId 등)
     * @param maxAttempts   윈도우 내 최대 허용 횟수
     * @param windowMinutes 시간 윈도우 (분)
     */
    @Transactional
    public void checkAndRecord(String type, String identifier, int maxAttempts, long windowMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(windowMinutes);
        long recentCount = rateLimitAttemptRepository.countRecentAttempts(type, identifier, cutoff);

        if (recentCount >= maxAttempts) {
            log.warn("Rate limit exceeded: type={}, identifier={}, count={}/{}", type, identifier, recentCount, maxAttempts);
            throw new BusinessException(
                    "Too many requests. Please try again later.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMIT_EXCEEDED");
        }

        rateLimitAttemptRepository.save(
                RateLimitAttempt.builder()
                        .limiterType(type)
                        .identifier(identifier)
                        .build()
        );
    }

    /**
     * Rate limit 검사만 (기록하지 않음)
     */
    @Transactional(readOnly = true)
    public boolean isBlocked(String type, String identifier, int maxAttempts, long windowMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(windowMinutes);
        long recentCount = rateLimitAttemptRepository.countRecentAttempts(type, identifier, cutoff);
        return recentCount >= maxAttempts;
    }
}
