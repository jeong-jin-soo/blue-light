package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.RevisionRequestDto;
import com.bluelight.backend.api.admin.dto.UpdateStatusRequest;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-T8 (보안 감사 H-2) — LEW cross-tenant 변조 차단 검증.
 *
 * <p>임의 승인 LEW 가 타인 배정 신청서의 mutate API 를 호출 시 403 (ACCESS_DENIED) 가
 * 발생하는지 검증한다. ADMIN/SYSTEM_ADMIN 호출은 가드를 통과.</p>
 */
@DisplayName("AdminApplicationService LEW cross-tenant 가드 - PR-T8")
class AdminApplicationServiceLewGuardTest {

    private static final Long LEW_A_SEQ = 100L;
    private static final Long LEW_B_SEQ = 200L;

    private ApplicationRepository applicationRepository;
    private AdminApplicationService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        EmailService emailService = mock(EmailService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        service = new AdminApplicationService(
                applicationRepository, userRepository, emailService, eventPublisher);
    }

    private Application appAssignedTo(Long lewSeq) {
        Application app = mock(Application.class);
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(lewSeq);
        when(app.getAssignedLew()).thenReturn(lew);
        when(app.getStatus()).thenReturn(ApplicationStatus.PENDING_REVIEW);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        return app;
    }

    private Application appWithNoAssignedLew() {
        Application app = mock(Application.class);
        when(app.getAssignedLew()).thenReturn(null);
        when(app.getStatus()).thenReturn(ApplicationStatus.PENDING_REVIEW);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        return app;
    }

    // ============================================================
    // updateStatus
    // ============================================================

    @Test
    @DisplayName("updateStatus - LEW 가 타인 배정 신청서 호출 → 403 ACCESS_DENIED, mutate 호출 안 됨")
    void updateStatus_lewCrossTenantBlocked() {
        Application app = appAssignedTo(LEW_A_SEQ);
        UpdateStatusRequest req = new UpdateStatusRequest();

        assertThatThrownBy(() -> service.updateStatus(1L, req, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
        verify(app, never()).changeStatus(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("updateStatus - assignedLew 가 null 인 신청서를 LEW 가 호출 → 403")
    void updateStatus_unassignedApplicationBlocksLew() {
        Application app = appWithNoAssignedLew();
        UpdateStatusRequest req = new UpdateStatusRequest();

        assertThatThrownBy(() -> service.updateStatus(1L, req, LEW_A_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
        verify(app, never()).changeStatus(org.mockito.ArgumentMatchers.any());
    }

    // ============================================================
    // approveForPayment
    // ============================================================

    @Test
    @DisplayName("approveForPayment - LEW 가 타인 배정 신청서 호출 → 403, approveForPayment() 호출 안 됨")
    void approveForPayment_lewCrossTenantBlocked() {
        Application app = appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.approveForPayment(1L, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
        verify(app, never()).approveForPayment();
    }

    @Test
    @DisplayName("approveForPayment - ADMIN 호출은 가드 통과 (다음 상태 가드에서 BusinessException — 별개)")
    void approveForPayment_adminBypassesLewGuard() {
        // ADMIN 은 가드 통과하나 KVA_NOT_CONFIRMED 같은 다른 가드는 별도. 본 테스트는 LEW 가드만 격리.
        Application app = appAssignedTo(LEW_A_SEQ);
        when(app.getKvaStatus()).thenReturn(com.bluelight.backend.domain.application.KvaStatus.UNKNOWN);

        // ADMIN 은 LEW 가드 통과 → KVA_NOT_CONFIRMED 로 거부. ACCESS_DENIED 가 아닌지 검증.
        assertThatThrownBy(() -> service.approveForPayment(1L, 999L, "ROLE_ADMIN"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo("KVA_NOT_CONFIRMED");
                });
    }

    // ============================================================
    // completeApplication
    // ============================================================

    @Test
    @DisplayName("completeApplication - LEW 가 타인 배정 신청서 호출 → 403")
    void completeApplication_lewCrossTenantBlocked() {
        Application app = appAssignedTo(LEW_A_SEQ);
        com.bluelight.backend.api.admin.dto.CompleteApplicationRequest req =
                mock(com.bluelight.backend.api.admin.dto.CompleteApplicationRequest.class);

        assertThatThrownBy(() -> service.completeApplication(1L, req, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
        verify(app, never()).issueLicense(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ============================================================
    // requestRevision
    // ============================================================

    @Test
    @DisplayName("requestRevision - LEW 가 타인 배정 신청서 호출 → 403, requestRevision() 호출 안 됨")
    void requestRevision_lewCrossTenantBlocked() {
        Application app = appAssignedTo(LEW_A_SEQ);
        RevisionRequestDto req = new RevisionRequestDto();

        assertThatThrownBy(() -> service.requestRevision(1L, req, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo("ACCESS_DENIED");
                });
        verify(app, never()).requestRevision(org.mockito.ArgumentMatchers.any());
    }

    // ============================================================
    // SYSTEM_ADMIN bypass
    // ============================================================

    @Test
    @DisplayName("LEW 본인 배정 신청서 호출은 가드 통과 (status 가드 등 다른 사유로만 거부 가능)")
    void lewOwnAssignedApplicationPassesGuard() {
        Application app = appAssignedTo(LEW_A_SEQ);
        when(app.getKvaStatus()).thenReturn(com.bluelight.backend.domain.application.KvaStatus.UNKNOWN);

        assertThatThrownBy(() -> service.approveForPayment(1L, LEW_A_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    // LEW 가드는 통과, KVA 가드에서 거부 — 본인 배정 신청서임이 증명됨.
                    org.assertj.core.api.Assertions.assertThat(be.getCode()).isEqualTo("KVA_NOT_CONFIRMED");
                });
    }
}
