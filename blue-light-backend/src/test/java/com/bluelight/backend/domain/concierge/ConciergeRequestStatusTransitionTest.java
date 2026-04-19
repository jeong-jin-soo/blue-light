package com.bluelight.backend.domain.concierge;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConciergeRequest 상태 전이 단위 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 4).
 * <p>
 * PRD §5.1 전이 다이어그램 + §5.2 전이표 + §5.3 전이 제약 검증.
 * BaseEntity.createdAt은 @PrePersist로 DB 저장 시 세팅되므로,
 * 순수 도메인 테스트에서는 ReflectionTestUtils로 조작한다.
 */
@DisplayName("ConciergeRequest 상태 전이 - PR#1 Stage 4")
class ConciergeRequestStatusTransitionTest {

    private User applicant() {
        return User.builder()
            .email("applicant@example.com").password("h")
            .firstName("App").lastName("Licant")
            .build();
    }

    private User manager() {
        return User.builder()
            .email("manager@example.com").password("h")
            .firstName("Man").lastName("Ager")
            .role(UserRole.CONCIERGE_MANAGER)
            .build();
    }

    private ConciergeRequest createRequest() {
        LocalDateTime now = LocalDateTime.now();
        return ConciergeRequest.builder()
            .publicCode("C-2026-0001")
            .submitterName("Test Submitter")
            .submitterEmail("applicant@example.com")
            .submitterPhone("+6512345678")
            .applicantUser(applicant())
            .pdpaConsentAt(now)
            .termsConsentAt(now)
            .signupConsentAt(now)
            .delegationConsentAt(now)
            .build();
    }

    // ============================================================
    // 정상 전이
    // ============================================================

    @Test
    @DisplayName("SUBMITTED → ASSIGNED - assignManager() 호출 + assignedAt 세팅")
    void submittedToAssigned() {
        ConciergeRequest r = createRequest();
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.SUBMITTED);

        User mgr = manager();
        r.assignManager(mgr);

        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.ASSIGNED);
        assertThat(r.getAssignedManager()).isSameAs(mgr);
        assertThat(r.getAssignedAt()).isNotNull();
    }

    @Test
    @DisplayName("전체 라이프사이클 정상 전이 SUBMITTED → COMPLETED")
    void fullLifecycle_succeeds() {
        ConciergeRequest r = createRequest();

        r.assignManager(manager());
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.ASSIGNED);

        r.markContacted();
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.CONTACTING);
        assertThat(r.getFirstContactAt()).isNotNull();

        r.linkApplication(42L);
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.APPLICATION_CREATED);
        assertThat(r.getApplicationSeq()).isEqualTo(42L);
        assertThat(r.getApplicationCreatedAt()).isNotNull();

        r.requestLoaSign();
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.AWAITING_APPLICANT_LOA_SIGN);
        assertThat(r.getLoaRequestedAt()).isNotNull();

        r.markLoaSigned();
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
        assertThat(r.getLoaSignedAt()).isNotNull();

        r.markLicencePaid();
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.IN_PROGRESS);
        assertThat(r.getLicencePaidAt()).isNotNull();

        r.markCompleted();
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.COMPLETED);
        assertThat(r.getCompletedAt()).isNotNull();
    }

    // ============================================================
    // 불가 전이 (전이 가드)
    // ============================================================

    @Test
    @DisplayName("SUBMITTED → CONTACTING은 불가 (직접 전이 금지 - ASSIGNED 경유 필수)")
    void submittedToContacting_throws() {
        ConciergeRequest r = createRequest();

        assertThatThrownBy(r::markContacted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SUBMITTED")
            .hasMessageContaining("CONTACTING");
    }

    @Test
    @DisplayName("CONTACTING 없이 APPLICATION_CREATED로 직행 불가")
    void assignedToApplicationCreated_throws() {
        ConciergeRequest r = createRequest();
        r.assignManager(manager());

        assertThatThrownBy(() -> r.linkApplication(1L))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("IN_PROGRESS에서 바로 SUBMITTED 역행 불가")
    void terminalStateNoReverse() {
        ConciergeRequest r = createRequest();
        r.assignManager(manager());
        r.markContacted();
        r.linkApplication(1L);
        r.requestLoaSign();
        r.markLoaSigned();
        r.markLicencePaid();
        // 역행 — IN_PROGRESS → ASSIGNED 시도
        assertThatThrownBy(() -> r.assignManager(manager()))
            .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // cancel() 정책
    // ============================================================

    @Test
    @DisplayName("cancel() - SUBMITTED 상태에서 가능")
    void cancel_fromSubmitted() {
        ConciergeRequest r = createRequest();

        r.cancel("user request");

        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.CANCELLED);
        assertThat(r.getCancelledAt()).isNotNull();
        assertThat(r.getCancellationReason()).isEqualTo("user request");
    }

    @Test
    @DisplayName("cancel() - IN_PROGRESS 상태에서도 가능")
    void cancel_fromInProgress() {
        ConciergeRequest r = createRequest();
        r.assignManager(manager());
        r.markContacted();
        r.linkApplication(1L);
        r.requestLoaSign();
        r.markLoaSigned();
        r.markLicencePaid();
        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.IN_PROGRESS);

        r.cancel("admin override");

        assertThat(r.getStatus()).isEqualTo(ConciergeRequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel() - 이미 COMPLETED면 IllegalStateException")
    void cancel_fromCompleted_throws() {
        ConciergeRequest r = createRequest();
        r.assignManager(manager());
        r.markContacted();
        r.linkApplication(1L);
        r.requestLoaSign();
        r.markLoaSigned();
        r.markLicencePaid();
        r.markCompleted();

        assertThatThrownBy(() -> r.cancel("too late"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("cancel() - 이미 CANCELLED면 IllegalStateException")
    void cancel_fromCancelled_throws() {
        ConciergeRequest r = createRequest();
        r.cancel("first");

        assertThatThrownBy(() -> r.cancel("second"))
            .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // SLA 판정
    // ============================================================

    @Test
    @DisplayName("isSlaBreached() - firstContactAt 세팅되면 false")
    void slaBreach_afterContacted_false() {
        ConciergeRequest r = createRequest();
        // createdAt을 25시간 전으로 조작
        ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.now().minusHours(25));
        r.assignManager(manager());
        r.markContacted();

        assertThat(r.isSlaBreached()).isFalse();
    }

    @Test
    @DisplayName("isSlaBreached() - 24h 경과 + firstContactAt null이면 true")
    void slaBreach_after24h_true() {
        ConciergeRequest r = createRequest();
        ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.now().minusHours(25));
        // 연락 없이 SUBMITTED 유지

        assertThat(r.isSlaBreached()).isTrue();
    }

    @Test
    @DisplayName("isSlaBreached() - 접수 직후(24h 미경과)는 false")
    void slaBreach_withinGracePeriod_false() {
        ConciergeRequest r = createRequest();
        ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.now().minusHours(1));

        assertThat(r.isSlaBreached()).isFalse();
    }

    @Test
    @DisplayName("isSlaBreached() - terminal 상태(CANCELLED)는 SLA 판정 대상 아님")
    void slaBreach_terminalState_false() {
        ConciergeRequest r = createRequest();
        ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.now().minusHours(48));
        r.cancel("test");

        assertThat(r.isSlaBreached()).isFalse();
    }

    // ============================================================
    // 타임스탬프 멱등성
    // ============================================================

    @Test
    @DisplayName("markLoaSigned() - 동일 상태 재호출 시 loaSignedAt 최초 값 보존")
    void markLoaSigned_idempotent() {
        ConciergeRequest r = createRequest();
        r.assignManager(manager());
        r.markContacted();
        r.linkApplication(42L);
        r.requestLoaSign();
        r.markLoaSigned();
        LocalDateTime firstLoaSignedAt = r.getLoaSignedAt();

        // 이미 AWAITING_LICENCE_PAYMENT — canTransitionTo에서 same-state 허용(true)
        r.markLoaSigned();

        assertThat(r.getLoaSignedAt()).isEqualTo(firstLoaSignedAt);
    }

    @Test
    @DisplayName("markContacted() - firstContactAt 최초 값 보존")
    void markContacted_idempotent() {
        ConciergeRequest r = createRequest();
        r.assignManager(manager());
        r.markContacted();
        LocalDateTime firstContact = r.getFirstContactAt();

        // 같은 상태로 재호출 — 가드 통과
        r.markContacted();

        assertThat(r.getFirstContactAt()).isEqualTo(firstContact);
    }
}
