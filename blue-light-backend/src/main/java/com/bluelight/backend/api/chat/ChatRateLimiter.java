package com.bluelight.backend.api.chat;

import com.bluelight.backend.domain.ratelimit.RateLimitAttempt;
import com.bluelight.backend.domain.ratelimit.RateLimitAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 챗봇 요청 Rate Limiter (DB 기반)
 * - 익명: IP당 15분 내 최대 20회
 * - 인증: IP당 15분 내 최대 40회
 * - DB 기반으로 서버 다중화 환경에서도 일관된 제한 적용
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRateLimiter {

    private static final String LIMITER_TYPE = "CHAT";
    private static final int MAX_ATTEMPTS_ANONYMOUS = 20;
    private static final int MAX_ATTEMPTS_AUTHENTICATED = 40;
    private static final long WINDOW_MINUTES = 15;

    private final RateLimitAttemptRepository rateLimitAttemptRepository;

    /**
     * 해당 IP가 차단 상태인지 확인
     */
    @Transactional(readOnly = true)
    public boolean isBlocked(String ipAddress, boolean authenticated) {
        int limit = authenticated ? MAX_ATTEMPTS_AUTHENTICATED : MAX_ATTEMPTS_ANONYMOUS;
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
        long recentCount = rateLimitAttemptRepository.countRecentAttempts(LIMITER_TYPE, ipAddress, cutoff);
        return recentCount >= limit;
    }

    /**
     * 요청 시도 기록
     */
    @Transactional
    public void recordAttempt(String ipAddress) {
        rateLimitAttemptRepository.save(
                RateLimitAttempt.builder()
                        .limiterType(LIMITER_TYPE)
                        .identifier(ipAddress)
                        .build()
        );
    }
}
