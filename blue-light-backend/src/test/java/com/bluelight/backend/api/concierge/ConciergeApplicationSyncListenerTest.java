package com.bluelight.backend.api.concierge;

import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * ConciergeApplicationSyncListener 단위 테스트 (★ Kaki Concierge v1.5 Phase 1 PR#7).
 * <p>
 * Application 상태 전이 이벤트를 받아 ConciergeRequest를 자동 전이시키는 로직을 검증.
 */
@DisplayName("ConciergeApplicationSyncListener - PR#7")
class ConciergeApplicationSyncListenerTest {

    private ConciergeRequestRepository conciergeRepository;
    private ConciergeApplicationSyncListener listener;

    @BeforeEach
    void setUp() {
        conciergeRepository = mock(ConciergeRequestRepository.class);
        listener = new ConciergeApplicationSyncListener(conciergeRepository);
    }

    private ConciergeRequest makeRequestAt(ConciergeRequestStatus targetStatus) {
        User applicant = User.builder()
            .email("a@b.com").password("h").firstName("A").lastName("B")
            .build();
        User manager = User.builder()
            .email("m@b.com").password("h").firstName("M").lastName("Gr")
            .build();
        ReflectionTestUtils.setField(manager, "userSeq", 10L);
        ReflectionTestUtils.setField(applicant, "userSeq", 20L);

        LocalDateTime now = LocalDateTime.now();
        ConciergeRequest cr = ConciergeRequest.builder()
            .publicCode("C-2026-0001")
            .submitterName("T").submitterEmail("a@b.com").submitterPhone("+65")
            .applicantUser(applicant)
            .pdpaConsentAt(now).termsConsentAt(now)
            .signupConsentAt(now).delegationConsentAt(now)
            .build();
        ReflectionTestUtils.setField(cr, "conciergeRequestSeq", 100L);

        // 목표 상태까지 도메인 메서드로 전이
        switch (targetStatus) {
            case SUBMITTED -> { /* 이미 SUBMITTED */ }
            case ASSIGNED -> cr.assignManager(manager);
            case CONTACTING -> {
                cr.assignManager(manager);
                cr.markContacted();
            }
            case APPLICATION_CREATED -> {
                cr.assignManager(manager);
                cr.markContacted();
                cr.linkApplication(42L);
            }
            case AWAITING_APPLICANT_LOA_SIGN -> {
                cr.assignManager(manager);
                cr.markContacted();
                cr.linkApplication(42L);
                cr.requestLoaSign();
            }
            case AWAITING_LICENCE_PAYMENT -> {
                cr.assignManager(manager);
                cr.markContacted();
                cr.linkApplication(42L);
                cr.requestLoaSign();
                cr.markLoaSigned();
            }
            case IN_PROGRESS -> {
                cr.assignManager(manager);
                cr.markContacted();
                cr.linkApplication(42L);
                cr.requestLoaSign();
                cr.markLoaSigned();
                cr.markLicencePaid();
            }
            case COMPLETED, CANCELLED -> {
                // terminal states — 개별 테스트에서 필요 없음
                throw new IllegalArgumentException("Use other factory for terminal states");
            }
        }
        return cr;
    }

    // ============================================================
    // viaConciergeRequestSeq=null → no-op
    // ============================================================

    @Test
    @DisplayName("viaConciergeRequestSeq=null → 리스너 no-op (조회 안 함)")
    void nonConciergeApplication_noOp() {
        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, null, ApplicationStatus.PENDING_PAYMENT, ApplicationStatus.PAID);

        listener.onApplicationStatusChanged(event);

        verify(conciergeRepository, never()).findById(anyLong());
    }

    // ============================================================
    // PAID / IN_PROGRESS → markLicencePaid
    // ============================================================

    @Test
    @DisplayName("Application PAID + CR AWAITING_LICENCE_PAYMENT → markLicencePaid 호출 → IN_PROGRESS")
    void paid_onAwaitingPayment_marksLicencePaid() {
        ConciergeRequest cr = makeRequestAt(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, 100L, ApplicationStatus.PENDING_PAYMENT, ApplicationStatus.PAID);

        listener.onApplicationStatusChanged(event);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.IN_PROGRESS);
        assertThat(cr.getLicencePaidAt()).isNotNull();
    }

    @Test
    @DisplayName("Application IN_PROGRESS + CR AWAITING_LICENCE_PAYMENT → markLicencePaid 호출")
    void inProgress_onAwaitingPayment_marksLicencePaid() {
        ConciergeRequest cr = makeRequestAt(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, 100L, ApplicationStatus.PAID, ApplicationStatus.IN_PROGRESS);

        listener.onApplicationStatusChanged(event);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.IN_PROGRESS);
    }

    // ============================================================
    // COMPLETED → markCompleted
    // ============================================================

    @Test
    @DisplayName("Application COMPLETED + CR IN_PROGRESS → markCompleted 호출")
    void completed_onInProgress_marksCompleted() {
        ConciergeRequest cr = makeRequestAt(ConciergeRequestStatus.IN_PROGRESS);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, 100L, ApplicationStatus.IN_PROGRESS, ApplicationStatus.COMPLETED);

        listener.onApplicationStatusChanged(event);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.COMPLETED);
        assertThat(cr.getCompletedAt()).isNotNull();
    }

    // ============================================================
    // 상태 가드 실패 시 로그만 (예외 전파 안 함)
    // ============================================================

    @Test
    @DisplayName("COMPLETED 이벤트인데 CR이 AWAITING_LICENCE_PAYMENT면 매핑 조건 false → 전이 없음")
    void completed_onAwaitingPayment_noTransition() {
        // COMPLETED는 CR IN_PROGRESS일 때만 전이 — AWAITING_LICENCE_PAYMENT에선 매핑 조건 false
        ConciergeRequest cr = makeRequestAt(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, 100L, ApplicationStatus.IN_PROGRESS, ApplicationStatus.COMPLETED);

        listener.onApplicationStatusChanged(event);

        // 상태 유지 (매핑 조건이 false이므로 markCompleted 미호출)
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
    }

    @Test
    @DisplayName("PAID 이벤트인데 CR이 SUBMITTED면 매핑 조건 false → 전이 없음")
    void paid_onSubmitted_noTransition() {
        ConciergeRequest cr = makeRequestAt(ConciergeRequestStatus.SUBMITTED);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, 100L, ApplicationStatus.PENDING_PAYMENT, ApplicationStatus.PAID);

        listener.onApplicationStatusChanged(event);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.SUBMITTED);
    }

    // ============================================================
    // 무시해야 할 상태 전이
    // ============================================================

    @Test
    @DisplayName("REVISION_REQUESTED 전이 → 무시 (전이 없음, IllegalState 안 남)")
    void revisionRequested_ignored() {
        ConciergeRequest cr = makeRequestAt(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, 100L, ApplicationStatus.PENDING_REVIEW, ApplicationStatus.REVISION_REQUESTED);

        listener.onApplicationStatusChanged(event);

        // 상태 유지
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
    }

    // ============================================================
    // ConciergeRequest 누락
    // ============================================================

    @Test
    @DisplayName("ConciergeRequest 없음 → no-op (경고 로그만)")
    void conciergeRequestMissing_noOp() {
        when(conciergeRepository.findById(999L)).thenReturn(Optional.empty());

        ApplicationStatusChangedEvent event = new ApplicationStatusChangedEvent(
            42L, 999L, ApplicationStatus.PAID, ApplicationStatus.IN_PROGRESS);

        listener.onApplicationStatusChanged(event);

        verify(conciergeRepository).findById(999L);
        // 예외 전파 없음
    }
}
