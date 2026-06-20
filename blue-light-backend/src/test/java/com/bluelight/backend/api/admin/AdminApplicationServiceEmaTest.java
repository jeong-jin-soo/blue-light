package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.EmaSubmissionStatus;
import com.bluelight.backend.domain.application.KvaSource;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EMA 제출 추적 — {@link AdminApplicationService} 서비스 분기 테스트.
 *
 * <ul>
 *   <li>IN_PROGRESS 게이트(NG3): COMPLETED 등에서 전이 거부(EMA_NOT_IN_PROGRESS)</li>
 *   <li>접수번호/queryNote 필수 검증</li>
 *   <li>ack.required 플래그 분기: true + EMA_ACK 미첨부 → EMA_ACK_REQUIRED, 첨부 있으면 통과</li>
 *   <li>정상 전이 시 감사 액션 기록 + actorRole detail</li>
 * </ul>
 */
class AdminApplicationServiceEmaTest {

    private ApplicationRepository applicationRepository;
    private UserRepository userRepository;
    private EmailService emailService;
    private ApplicationEventPublisher eventPublisher;
    private AuditLogService auditLogService;
    private FileRepository fileRepository;
    private EmaSubmissionSettings emaSubmissionSettings;
    private AdminApplicationService service;

    private static final Long APP_ID = 42L;
    private static final Long LEW_SEQ = 7L;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        auditLogService = mock(AuditLogService.class);
        fileRepository = mock(FileRepository.class);
        emaSubmissionSettings = mock(EmaSubmissionSettings.class);
        // @RequiredArgsConstructor — 선언 순서: appRepo, userRepo, email, eventPublisher,
        // fileRepository, auditLogService, emaSubmissionSettings, sldRequestRepository, kvaAdjustmentRepository.
        service = new AdminApplicationService(
                applicationRepository, userRepository, emailService, eventPublisher,
                fileRepository, auditLogService, emaSubmissionSettings,
                mock(com.bluelight.backend.domain.application.SldRequestRepository.class),
                mock(com.bluelight.backend.domain.kva.KvaAdjustmentRepository.class));
    }

    private Application inProgressApp() {
        User user = mock(User.class);
        Application app = Application.builder()
                .user(user)
                .address("1 Blk Test")
                .postalCode("560001")
                .buildingType("HDB_FLAT")
                .selectedKva(100)
                .quoteAmount(new BigDecimal("650.00"))
                .kvaStatus(KvaStatus.CONFIRMED)
                .kvaSource(KvaSource.USER_INPUT)
                .build();
        app.approveForPayment();
        app.markAsPaid();
        app.startInspection(); // IN_PROGRESS
        return app;
    }

    private Application completedApp() {
        Application app = inProgressApp();
        app.issueLicense("LIC-001", java.time.LocalDate.now().plusYears(1));
        return app;
    }

    // ── IN_PROGRESS 게이트 ───────────────────────────────────────

    @Test
    void markEmaSubmitted_COMPLETED면_EMA_NOT_IN_PROGRESS() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(completedApp()));

        assertThatThrownBy(() ->
                service.markEmaSubmitted(APP_ID, "ELISE-001", LEW_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("EMA_NOT_IN_PROGRESS"));
    }

    // ── 필수 검증 ────────────────────────────────────────────────

    @Test
    void markEmaSubmitted_접수번호_blank면_EMA_REFERENCE_REQUIRED() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(inProgressApp()));

        assertThatThrownBy(() ->
                service.markEmaSubmitted(APP_ID, "   ", LEW_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("EMA_REFERENCE_REQUIRED"));
    }

    @Test
    void raiseEmaQuery_queryNote_blank면_EMA_QUERY_NOTE_REQUIRED() {
        Application app = inProgressApp();
        app.markEmaSubmitted("ELISE-001", LEW_SEQ);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() ->
                service.raiseEmaQuery(APP_ID, "", LEW_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("EMA_QUERY_NOTE_REQUIRED"));
    }

    // ── ack.required 플래그 분기 ─────────────────────────────────

    @Test
    void markEmaSubmitted_ack_required_true_그리고_EMA_ACK_미첨부면_EMA_ACK_REQUIRED() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(inProgressApp()));
        when(emaSubmissionSettings.isAckRequired()).thenReturn(true);
        when(fileRepository.findByApplicationApplicationSeqAndFileType(APP_ID, FileType.EMA_ACK))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() ->
                service.markEmaSubmitted(APP_ID, "ELISE-001", LEW_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("EMA_ACK_REQUIRED"));
    }

    @Test
    void markEmaSubmitted_ack_required_true_그리고_EMA_ACK_첨부있으면_통과() {
        Application app = inProgressApp();
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        when(emaSubmissionSettings.isAckRequired()).thenReturn(true);
        when(fileRepository.findByApplicationApplicationSeqAndFileType(APP_ID, FileType.EMA_ACK))
                .thenReturn(List.of(mock(com.bluelight.backend.domain.file.FileEntity.class)));

        service.markEmaSubmitted(APP_ID, "ELISE-001", LEW_SEQ, "ROLE_LEW");

        assertThat(app.getEmaSubmissionStatus()).isEqualTo(EmaSubmissionStatus.SUBMITTED);
    }

    @Test
    void markEmaSubmitted_ack_required_false면_첨부없어도_통과() {
        Application app = inProgressApp();
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        when(emaSubmissionSettings.isAckRequired()).thenReturn(false);

        service.markEmaSubmitted(APP_ID, "ELISE-001", LEW_SEQ, "ROLE_LEW");

        assertThat(app.getEmaSubmissionStatus()).isEqualTo(EmaSubmissionStatus.SUBMITTED);
        // ack false 면 첨부 조회조차 하지 않음 (early-return)
    }

    // ── 감사 기록 + actorRole ───────────────────────────────────

    @Test
    void approveEma_정상전이시_EMA_APPROVED_감사기록_그리고_actorRole_detail() {
        Application app = inProgressApp();
        app.markEmaSubmitted("ELISE-001", LEW_SEQ);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        service.approveEma(APP_ID, LEW_SEQ, "ROLE_LEW");

        assertThat(app.getEmaSubmissionStatus()).isEqualTo(EmaSubmissionStatus.APPROVED);
        ArgumentCaptor<Object> detailCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditLogService).logAsync(
                eq(LEW_SEQ), eq(AuditAction.EMA_APPROVED), any(),
                eq("Application"), eq(String.valueOf(APP_ID)), any(),
                any(), detailCaptor.capture(),
                any(), any(), any(), any(), eq(200));
        assertThat(detailCaptor.getValue().toString()).contains("ROLE_LEW");
    }

    @Test
    void revertEmaDecision_ADMIN대행_복원_그리고_EMA_DECISION_REVERTED_감사() {
        Application app = inProgressApp();
        app.markEmaSubmitted("ELISE-001", LEW_SEQ);
        app.approveEma(); // 슬롯 = SUBMITTED
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        service.revertEmaDecision(APP_ID, 1L, "ROLE_ADMIN");

        assertThat(app.getEmaSubmissionStatus()).isEqualTo(EmaSubmissionStatus.SUBMITTED);
        verify(auditLogService).logAsync(
                eq(1L), eq(AuditAction.EMA_DECISION_REVERTED), any(),
                eq("Application"), eq(String.valueOf(APP_ID)), any(),
                any(), any(), any(), any(), any(), any(), eq(200));
    }

    // ── PR-E3: completeApplication 종료 게이트 회귀 ───────────────

    private com.bluelight.backend.api.admin.dto.CompleteApplicationRequest completeReq() {
        var req = mock(com.bluelight.backend.api.admin.dto.CompleteApplicationRequest.class);
        when(req.getLicenseNumber()).thenReturn("LIC-2026-001");
        when(req.getLicenseExpiryDate()).thenReturn(java.time.LocalDate.now().plusYears(1));
        return req;
    }

    private void stubFile(FileType type, boolean present) {
        when(fileRepository.findByApplicationApplicationSeqAndFileType(APP_ID, type))
                .thenReturn(present
                        ? List.of(mock(com.bluelight.backend.domain.file.FileEntity.class))
                        : Collections.emptyList());
    }

    @Test
    void completeApplication_ema가_APPROVED아니면_EMA_NOT_APPROVED() {
        Application app = inProgressApp();
        app.markEmaSubmitted("ELISE-001", LEW_SEQ); // SUBMITTED, not APPROVED
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.completeApplication(APP_ID, completeReq()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("EMA_NOT_APPROVED"));
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.IN_PROGRESS); // 종료 안 됨
    }

    @Test
    void completeApplication_ema_APPROVED지만_LICENSE_PDF_없으면_LICENSE_PDF_MISSING() {
        Application app = inProgressApp();
        app.markEmaSubmitted("ELISE-001", LEW_SEQ);
        app.approveEma(); // APPROVED
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        stubFile(FileType.LICENSE_PDF, false);

        assertThatThrownBy(() -> service.completeApplication(APP_ID, completeReq()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("LICENSE_PDF_MISSING"));
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.IN_PROGRESS);
    }

    @Test
    void completeApplication_ema_APPROVED_그리고_LICENSE_PDF_있으면_정상_COMPLETED() {
        Application app = inProgressApp();
        app.markEmaSubmitted("ELISE-001", LEW_SEQ);
        app.approveEma();
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        stubFile(FileType.LICENSE_PDF, true);

        service.completeApplication(APP_ID, completeReq());

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.COMPLETED);
        assertThat(app.getLicenseNumber()).isEqualTo("LIC-2026-001");
    }

    @Test
    void completeApplication_IN_PROGRESS아니면_INVALID_STATUS_FOR_COMPLETION_먼저() {
        Application app = inProgressApp();
        app.markEmaSubmitted("ELISE-001", LEW_SEQ);
        app.approveEma();
        app.issueLicense("X", java.time.LocalDate.now()); // COMPLETED
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.completeApplication(APP_ID, completeReq()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("INVALID_STATUS_FOR_COMPLETION"));
    }

    // ── GET 조회 응답 ───────────────────────────────────────────

    /** 단위 테스트 엔티티는 ID 가 null 이므로 buildEmaResponse 의 파일 조회 키를 맞추기 위해 reflection 으로 주입. */
    private void setApplicationSeq(Application app, Long seq) {
        try {
            var f = Application.class.getDeclaredField("applicationSeq");
            f.setAccessible(true);
            f.set(app, seq);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getEmaSubmission_canComplete와_ack필드_계산() {
        Application app = inProgressApp();
        setApplicationSeq(app, APP_ID);
        app.markEmaSubmitted("ELISE-001", LEW_SEQ);
        app.approveEma(); // APPROVED + decisionAt set + referenceNo set → grandfathered=false
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        stubFile(FileType.LICENSE_PDF, true);
        stubFile(FileType.EMA_ACK, false);
        when(emaSubmissionSettings.isAckRequired()).thenReturn(true);

        var resp = service.getEmaSubmission(APP_ID);

        assertThat(resp.getEmaSubmissionStatus()).isEqualTo(EmaSubmissionStatus.APPROVED);
        assertThat(resp.isLicensePdfPresent()).isTrue();
        assertThat(resp.isEmaAckPresent()).isFalse();
        assertThat(resp.isEmaAckRequired()).isTrue();
        assertThat(resp.isCanComplete()).isTrue();        // APPROVED + LICENSE_PDF + IN_PROGRESS
        assertThat(resp.isEmaGrandfathered()).isFalse();  // referenceNo·decisionAt 채워짐
    }

    // ── PR-E5: 반려 시 EmaRejectedEvent 발행 (담당 LEW seq 포함) ──

    @Test
    void rejectEma_담당LEW_있으면_EmaRejectedEvent_발행() {
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(LEW_SEQ);
        Application app = inProgressApp();
        app.assignLew(lew);
        app.markEmaSubmitted("ELISE-001", 99L);
        setApplicationSeq(app, APP_ID);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        service.rejectEma(APP_ID, "Capacity exceeds limit", 1L, "ROLE_ADMIN");

        ArgumentCaptor<Object> evtCap = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(evtCap.capture());
        Object evt = evtCap.getValue();
        assertThat(evt).isInstanceOf(com.bluelight.backend.api.application.EmaRejectedEvent.class);
        var rejected = (com.bluelight.backend.api.application.EmaRejectedEvent) evt;
        assertThat(rejected.getApplicationSeq()).isEqualTo(APP_ID);
        assertThat(rejected.getLewUserSeq()).isEqualTo(LEW_SEQ); // 담당 LEW
        assertThat(rejected.getReason()).isEqualTo("Capacity exceeds limit");
    }

    @Test
    void rejectEma_담당LEW_없으면_lewSeq_null로_발행() {
        Application app = inProgressApp(); // assignedLew 없음
        app.markEmaSubmitted("ELISE-001", 99L);
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        service.rejectEma(APP_ID, null, 1L, "ROLE_ADMIN");

        ArgumentCaptor<Object> evtCap = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(evtCap.capture());
        var rejected = (com.bluelight.backend.api.application.EmaRejectedEvent) evtCap.getValue();
        assertThat(rejected.getLewUserSeq()).isNull(); // 리스너가 skip → 신청자 미발송
    }
}
