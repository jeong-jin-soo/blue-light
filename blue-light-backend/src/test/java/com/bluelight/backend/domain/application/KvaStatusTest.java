package com.bluelight.backend.domain.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 PR#1 — {@link KvaStatus} 상태 전이 단위 테스트.
 *
 * <p>전이 규칙: {@code UNKNOWN -> CONFIRMED} 만 허용. 그 외 모두 false.
 * {@code CONFIRMED -> CONFIRMED} 재확정은 도메인이 아닌 {@code force} 플래그로 관리.
 */
class KvaStatusTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1} = {2}")
    @CsvSource({
            "UNKNOWN,   CONFIRMED, true",
            "UNKNOWN,   UNKNOWN,   false",
            "CONFIRMED, CONFIRMED, false",
            "CONFIRMED, UNKNOWN,   false"
    })
    void canTransitionTo_기본_전이_규칙(KvaStatus from, KvaStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to))
                .as("%s -> %s", from, to)
                .isEqualTo(expected);
    }

    @Test
    void null_대상은_항상_false() {
        for (KvaStatus s : KvaStatus.values()) {
            assertThat(s.canTransitionTo(null)).as("%s -> null", s).isFalse();
        }
    }
}
