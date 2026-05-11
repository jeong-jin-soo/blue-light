package com.bluelight.backend.api.notification.outbox;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Outbox 재시도 지수 백오프 정책.
 *
 * <p>다음 시도 시각 = now + (baseSeconds × 2^(attempt-1)). 최대 시도 횟수를 넘으면
 * {@link #isExhausted}=true → 디스패처가 DEAD 처리한다.</p>
 *
 * <h2>기본 파라미터</h2>
 * <ul>
 *   <li>baseSeconds = 30s</li>
 *   <li>maxAttempts = 6</li>
 *   <li>backoff 시퀀스: 30s → 1m → 2m → 4m → 8m → 16m (이후 DEAD)</li>
 * </ul>
 */
@Component
public class ExponentialBackoffPolicy {

    /** 1차 재시도 지연 (초). */
    public static final int BASE_DELAY_SECONDS = 30;
    /** 최대 시도 횟수. attemptCount 가 본 값에 도달하면 다음은 DEAD. */
    public static final int MAX_ATTEMPTS = 6;

    /**
     * @param attempt 직전 시도 회차 (1 이상). 1 이면 첫 실패 후 30초 뒤 재시도.
     * @return 다음 재시도 시각. attempt <= 0 이면 baseDelay 즉시 적용.
     */
    public LocalDateTime nextAttemptAt(int attempt, LocalDateTime now) {
        int safeAttempt = Math.max(attempt, 1);
        long seconds = BASE_DELAY_SECONDS * (1L << (safeAttempt - 1)); // 30 * 2^(n-1)
        return now.plus(Duration.ofSeconds(seconds));
    }

    /** 더 이상 재시도하지 말지 결정. attemptCount 가 {@link #MAX_ATTEMPTS} 이상이면 true. */
    public boolean isExhausted(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS;
    }

    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }
}
