package com.bluelight.backend.domain.concierge;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConciergeRequest LEW 배정 단위 테스트
 * (★ Concierge 강화 + 별도 수금 PR-1, D6=A 셀프 할당).
 */
@DisplayName("ConciergeRequest LEW 배정 - PR-1 (D6=A)")
class ConciergeRequestLewAssignmentTest {

    private ConciergeRequest makeRequest() {
        User applicant = User.builder()
            .email("applicant@example.com")
            .password("hash")
            .firstName("App")
            .lastName("Licant")
            .role(UserRole.APPLICANT)
            .build();
        LocalDateTime now = LocalDateTime.of(2026, 5, 1, 9, 0);
        return ConciergeRequest.builder()
            .publicCode("C-2026-0001")
            .submitterName("App Licant")
            .submitterEmail("applicant@example.com")
            .submitterPhone("+6591234567")
            .applicantUser(applicant)
            .pdpaConsentAt(now)
            .termsConsentAt(now)
            .signupConsentAt(now)
            .delegationConsentAt(now)
            .marketingOptIn(false)
            .verificationPhrase("apple-banana-cherry-date")
            .build();
    }

    // ============================================================
    // assignLew — 최초 배정
    // ============================================================

    @Test
    @DisplayName("assignLew() — 최초 배정 시 assignedLewSeq + lewAssignedAt 세팅")
    void assignLew_firstAssignment_setsBothFields() {
        ConciergeRequest cr = makeRequest();
        assertThat(cr.isLewAssigned()).isFalse();

        LocalDateTime now = LocalDateTime.of(2026, 5, 5, 10, 0);
        cr.assignLew(42L, now);

        assertThat(cr.isLewAssigned()).isTrue();
        assertThat(cr.getAssignedLewSeq()).isEqualTo(42L);
        assertThat(cr.getLewAssignedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("assignLew() — 재할당 시 이전 LEW 덮어쓰기, 시각도 갱신")
    void assignLew_reassignment_overwrites() {
        ConciergeRequest cr = makeRequest();

        LocalDateTime t1 = LocalDateTime.of(2026, 5, 5, 10, 0);
        cr.assignLew(42L, t1);
        assertThat(cr.getAssignedLewSeq()).isEqualTo(42L);
        assertThat(cr.getLewAssignedAt()).isEqualTo(t1);

        // 재할당
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 6, 11, 30);
        cr.assignLew(99L, t2);

        assertThat(cr.getAssignedLewSeq()).isEqualTo(99L);
        assertThat(cr.getLewAssignedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("assignLew() — now=null 이면 LocalDateTime.now() 로 대체")
    void assignLew_nullNow_usesCurrentTime() {
        ConciergeRequest cr = makeRequest();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        cr.assignLew(42L, null);

        assertThat(cr.getLewAssignedAt()).isAfter(before);
    }

    // ============================================================
    // assignLew — 가드
    // ============================================================

    @Test
    @DisplayName("assignLew(null, ...) — IllegalArgumentException")
    void assignLew_nullLewUserSeq_throws() {
        ConciergeRequest cr = makeRequest();

        assertThatThrownBy(() -> cr.assignLew(null, LocalDateTime.now()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lewUserSeq");
    }

    // ============================================================
    // 배정은 status 를 건드리지 않는다 (PR-3 가 책임)
    // ============================================================

    @Test
    @DisplayName("assignLew() — status 는 변경되지 않는다 (PR-1 은 도메인 진입점만)")
    void assignLew_doesNotChangeStatus() {
        ConciergeRequest cr = makeRequest();
        ConciergeRequestStatus before = cr.getStatus();

        cr.assignLew(42L, LocalDateTime.now());

        assertThat(cr.getStatus()).isEqualTo(before);
    }
}
