package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 PR#1 / B-1 — {@code approveForPayment} 의 kVA 가드.
 *
 * <p>kvaStatus=UNKNOWN 이면 400 {@code KVA_NOT_CONFIRMED} 로 거부되고,
 * {@code Application.approveForPayment()} 도메인 메서드는 호출되지 않아야 한다.
 */
class AdminApplicationServiceKvaGuardTest {

    private ApplicationRepository applicationRepository;
    private UserRepository userRepository;
    private EmailService emailService;
    private com.bluelight.backend.domain.file.FileRepository fileRepository;
    private AdminApplicationService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        // ★ PR#7: ApplicationEventPublisher mock 추가
        org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
        // PR4 게이트(LICENSE_PDF) + EMA 추적 공용 FileRepository mock (단일 주입).
        fileRepository = mock(com.bluelight.backend.domain.file.FileRepository.class);
        // ── EMA 제출 추적 의존성 (감사/설정) mock ──
        com.bluelight.backend.api.audit.AuditLogService auditLogService =
            mock(com.bluelight.backend.api.audit.AuditLogService.class);
        EmaSubmissionSettings emaSubmissionSettings = mock(EmaSubmissionSettings.class);
        // @RequiredArgsConstructor — 필드 선언 순서: appRepo, userRepo, email, eventPublisher,
        // fileRepository, auditLogService, emaSubmissionSettings
        service = new AdminApplicationService(
            applicationRepository, userRepository, emailService, eventPublisher,
            fileRepository, auditLogService, emaSubmissionSettings);
    }

    @Test
    void approveForPayment_UNKNOWN이면_400_KVA_NOT_CONFIRMED() {
        Application app = mock(Application.class);
        when(app.getStatus()).thenReturn(ApplicationStatus.PENDING_REVIEW);
        when(app.getKvaStatus()).thenReturn(KvaStatus.UNKNOWN);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        // ★ 코드 부채 P0 단일화 — LEW 가드는 컨트롤러 SpEL @appSec.isAssignedLew 로 이관됨.
        // 본 단위 테스트는 KVA_NOT_CONFIRMED 비즈니스 가드만 검증.
        assertThatThrownBy(() -> service.approveForPayment(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("KVA_NOT_CONFIRMED");
                });

        verify(app, never()).approveForPayment();
    }

    // CONFIRMED 성공 경로는 AdminApplicationResponse.from 이 applicationType 등을 요구하므로
    // 통합 테스트(MockMvc) 스코프로 이관하고, 여기서는 B-1 가드만 검증.

    @Test
    void completeApplication_EMA_미승인이면_차단() {
        // 머지된 완료 게이트(EMA PR-E3 가 PR4 LICENSE_PDF 단독 게이트 대체):
        // emaSubmissionStatus != APPROVED 이면 LICENSE_PDF 검사 이전에 EMA_NOT_APPROVED 로 차단.
        Application app = mock(Application.class);
        when(app.getStatus()).thenReturn(ApplicationStatus.IN_PROGRESS);
        when(app.getEmaSubmissionStatus()).thenReturn(
                com.bluelight.backend.domain.application.EmaSubmissionStatus.SUBMITTED);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        var request = new com.bluelight.backend.api.admin.dto.CompleteApplicationRequest();

        assertThatThrownBy(() -> service.completeApplication(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode()).isEqualTo("EMA_NOT_APPROVED"));

        verify(app, never()).issueLicense(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeApplication_EMA승인됐어도_LICENSE_PDF_없으면_차단() {
        // PR4 의도(LICENSE_PDF 필수)는 유지 — 단 EMA=APPROVED 전제 충족 후 LICENSE_PDF_MISSING(400)로 차단.
        Application app = mock(Application.class);
        when(app.getStatus()).thenReturn(ApplicationStatus.IN_PROGRESS);
        when(app.getEmaSubmissionStatus()).thenReturn(
                com.bluelight.backend.domain.application.EmaSubmissionStatus.APPROVED);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        when(fileRepository.findByApplicationApplicationSeqAndFileType(
                1L, com.bluelight.backend.domain.file.FileType.LICENSE_PDF))
                .thenReturn(java.util.List.of());

        var request = new com.bluelight.backend.api.admin.dto.CompleteApplicationRequest();

        assertThatThrownBy(() -> service.completeApplication(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode()).isEqualTo("LICENSE_PDF_MISSING"));

        verify(app, never()).issueLicense(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
