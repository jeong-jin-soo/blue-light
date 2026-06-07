package com.bluelight.backend.api.notification.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExponentialBackoffPolicy 단위 테스트 (PR-0B).
 * 30 × 2^(n-1) 시퀀스 + maxAttempts 가드 검증.
 */
@DisplayName("ExponentialBackoffPolicy - PR-0B")
class ExponentialBackoffPolicyTest {

    private final ExponentialBackoffPolicy policy = new ExponentialBackoffPolicy();

    @Test
    @DisplayName("attempt=1 - 30초 후")
    void attempt1_delays30Seconds() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 11, 12, 0, 0);
        LocalDateTime next = policy.nextAttemptAt(1, now);
        assertThat(next).isEqualTo(now.plusSeconds(30));
    }

    @Test
    @DisplayName("attempt=2 - 60초 후 (30 × 2)")
    void attempt2_delays60Seconds() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 11, 12, 0, 0);
        assertThat(policy.nextAttemptAt(2, now)).isEqualTo(now.plusSeconds(60));
    }

    @Test
    @DisplayName("attempt=3 - 120초 후")
    void attempt3_delays120Seconds() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 11, 12, 0, 0);
        assertThat(policy.nextAttemptAt(3, now)).isEqualTo(now.plusSeconds(120));
    }

    @Test
    @DisplayName("attempt=6 - 30 × 2^5 = 960초")
    void attempt6_delays960Seconds() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 11, 12, 0, 0);
        assertThat(policy.nextAttemptAt(6, now)).isEqualTo(now.plusSeconds(960));
    }

    @Test
    @DisplayName("attempt <= 0 - 1 로 정규화")
    void attemptZero_treatedAsOne() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 11, 12, 0, 0);
        assertThat(policy.nextAttemptAt(0, now)).isEqualTo(now.plusSeconds(30));
        assertThat(policy.nextAttemptAt(-3, now)).isEqualTo(now.plusSeconds(30));
    }

    @Test
    @DisplayName("isExhausted - attemptCount >= maxAttempts(6) 이면 true")
    void isExhausted_thresholdCheck() {
        assertThat(policy.isExhausted(5)).isFalse();
        assertThat(policy.isExhausted(6)).isTrue();
        assertThat(policy.isExhausted(7)).isTrue();
    }
}
