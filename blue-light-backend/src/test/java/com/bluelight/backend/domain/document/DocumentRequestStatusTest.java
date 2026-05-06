package com.bluelight.backend.domain.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bluelight.backend.domain.document.DocumentRequestStatus.APPROVED;
import static com.bluelight.backend.domain.document.DocumentRequestStatus.CANCELLED;
import static com.bluelight.backend.domain.document.DocumentRequestStatus.REJECTED;
import static com.bluelight.backend.domain.document.DocumentRequestStatus.REQUESTED;
import static com.bluelight.backend.domain.document.DocumentRequestStatus.UPLOADED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentRequestStatus 상태 머신 단위 테스트 — Phase 3 PR#1
 *
 * AC-S1~S6 상태 전이 가드 16케이스 파라미터화 검증.
 */
class DocumentRequestStatusTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1} = {2}")
    @CsvSource({
            // legal
            "REQUESTED, UPLOADED,  true",
            "REQUESTED, CANCELLED, true",
            "UPLOADED,  APPROVED,  true",
            "UPLOADED,  REJECTED,  true",
            "UPLOADED,  UPLOADED,  true",   // 재업로드 (REJECTED→UPLOADED와 동일 경로)
            "REJECTED,  UPLOADED,  true",

            // illegal
            "REQUESTED, APPROVED,  false",
            "REQUESTED, REJECTED,  false",
            "UPLOADED,  REQUESTED, false",
            "UPLOADED,  CANCELLED, false",
            "REJECTED,  APPROVED,  false",
            "REJECTED,  REJECTED,  false",
            "REJECTED,  CANCELLED, false",
            "APPROVED,  REJECTED,  false",
            "APPROVED,  UPLOADED,  false",
            "APPROVED,  CANCELLED, false",
            "CANCELLED, REQUESTED, false",
            "CANCELLED, UPLOADED,  false"
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
        assertThat(APPROVED.canTransitionTo(APPROVED)).isFalse();
        assertThat(CANCELLED.canTransitionTo(CANCELLED)).isFalse();
        assertThat(REQUESTED.canTransitionTo(REQUESTED)).isFalse();
    }
}
