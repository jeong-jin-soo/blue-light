package com.bluelight.backend.domain.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bluelight.backend.domain.document.DocumentRequestStatus.CANCELLED;
import static com.bluelight.backend.domain.document.DocumentRequestStatus.REQUESTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentRequestStatus 상태 머신 단위 테스트.
 *
 * LEW 승인/반려 단계 제거 후(2026-06-18): REQUESTED / UPLOADED / CANCELLED 만 존재.
 *   REQUESTED → UPLOADED | CANCELLED
 *   UPLOADED  → UPLOADED (재업로드)
 *   CANCELLED → (종결)
 */
class DocumentRequestStatusTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1} = {2}")
    @CsvSource({
            // legal
            "REQUESTED, UPLOADED,  true",
            "REQUESTED, CANCELLED, true",
            "UPLOADED,  UPLOADED,  true",   // 재업로드 (덮어쓰기 허용)

            // illegal
            "REQUESTED, REQUESTED, false",
            "UPLOADED,  REQUESTED, false",
            "UPLOADED,  CANCELLED, false",
            "CANCELLED, REQUESTED, false",
            "CANCELLED, UPLOADED,  false",
            "CANCELLED, CANCELLED, false"
    })
    void canTransitionTo_파라미터화_검증(DocumentRequestStatus from,
                                        DocumentRequestStatus to,
                                        boolean expected) {
        assertThat(from.canTransitionTo(to))
                .as("%s -> %s", from, to)
                .isEqualTo(expected);
    }

    @Test
    void null_대상은_항상_false() {
        for (DocumentRequestStatus s : DocumentRequestStatus.values()) {
            assertThat(s.canTransitionTo(null)).as("%s -> null", s).isFalse();
        }
    }

    @Test
    void 종결_상태는_자기_자신으로도_전이_불가() {
        assertThat(CANCELLED.canTransitionTo(CANCELLED)).isFalse();
        assertThat(REQUESTED.canTransitionTo(REQUESTED)).isFalse();
    }
}
