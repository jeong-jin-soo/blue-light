package com.bluelight.backend.service.cof;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessRequest;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessResponse;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.common.crypto.FieldEncryptionUtil;
import com.bluelight.backend.common.crypto.HmacUtil;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.CofErrorCode;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.application.SldOption;
import com.bluelight.backend.domain.application.SldRequest;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.application.SldRequestStatus;
import com.bluelight.backend.domain.cof.CertificateOfFitness;
import com.bluelight.backend.domain.cof.CertificateOfFitnessRepository;
import com.bluelight.backend.domain.cof.ConsumerType;
import com.bluelight.backend.domain.cof.RetailerCode;
import com.bluelight.backend.domain.document.DocumentRequestRepository;
import com.bluelight.backend.domain.document.DocumentRequestStatus;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LewReviewService 단위 테스트 (LEW Review Form P1.B, 스펙 §3).
 *
 * <p>낙관적 락 충돌은 Hibernate/Spring 레이어 책임이라 본 순수 단위 테스트에서는
 * 간접 검증(서비스 경로가 예외 전파 가능한 구조임)만 확인한다. 실제 충돌 시나리오는 P1.C 통합 테스트.</p>
 */
@DisplayName("LewReviewService - P1.B")
class LewReviewServiceTest {

    private ApplicationRepository applicationRepository;
    private CertificateOfFitnessRepository cofRepository;
    private UserRepository userRepository;
    private FieldEncryptionUtil fieldEncryptionUtil;
    private HmacUtil hmacUtil;
    private DocumentRequestRepository documentRequestRepository;
    private SldRequestRepository sldRequestRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private LewReviewService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        cofRepository = mock(CertificateOfFitnessRepository.class);
        userRepository = mock(UserRepository.class);
        documentRequestRepository = mock(DocumentRequestRepository.class);
        sldRequestRepository = mock(SldRequestRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);

        String key = Base64.getEncoder().encodeToString(new byte[32]);
        fieldEncryptionUtil = new FieldEncryptionUtil();
        ReflectionTestUtils.setField(fieldEncryptionUtil, "encryptionKeyBase64", key);
        fieldEncryptionUtil.init();
        hmacUtil = new HmacUtil();
        ReflectionTestUtils.setField(hmacUtil, "encryptionKeyBase64", key);
        hmacUtil.init();

        service = new LewReviewService(applicationRepository, cofRepository, userRepository,
                fieldEncryptionUtil, hmacUtil,
                documentRequestRepository, sldRequestRepository, notificationService,
                emailService);

        // Phase 6 기본 state: kVA CONFIRMED, 미해결 DocumentRequest 없음, SLD_OPTION SELF_UPLOAD
        // (개별 테스트가 필요하면 override)
        when(documentRequestRepository.countByApplicationAndStatusIn(anyLong(), anyCollection()))
                .thenReturn(0L);
    }

    private User userWithSeq(long seq) {
        User u = User.builder()
                .email("u" + seq + "@b.com").password("h").firstName("F").lastName("L")
                .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private Application applicationAssignedTo(Long appSeq, User lew) {
        Application app = Application.builder()
                .user(userWithSeq(99L))
                .address("1 Test Rd")
                .postalCode("111111")
                .selectedKva(45)
                .quoteAmount(new BigDecimal("100.00"))
                .build();
        ReflectionTestUtils.setField(app, "applicationSeq", appSeq);
        if (lew != null) {
            app.assignLew(lew);
        }
        return app;
    }

    /**
     * PR3 옵션 R: finalize는 PAID/IN_PROGRESS 상태에서만 호출 가능. 테스트 헬퍼.
     * 기본은 PAID 로 세팅 — 개별 테스트가 IN_PROGRESS 등으로 override 가능.
     */
    private Application applicationReadyForFinalize(Long appSeq, User lew) {
        Application app = applicationAssignedTo(appSeq, lew);
        ReflectionTestUtils.setField(app, "status", ApplicationStatus.PAID);
        return app;
    }

    private CertificateOfFitnessRequest validFinalizeRequest() {
        CertificateOfFitnessRequest r = new CertificateOfFitnessRequest();
        r.setMsslAccountNo("123-45-6789-0");
        r.setConsumerType(ConsumerType.NON_CONTESTABLE);
        r.setRetailerCode(RetailerCode.SP_SERVICES_LIMITED);
        r.setSupplyVoltageV(400);
        r.setApprovedLoadKva(45);
        r.setHasGenerator(false);
        r.setInspectionIntervalMonths(12);
        r.setLewAppointmentDate(LocalDate.of(2026, 4, 22));
        return r;
    }

    // ── 권한 ──────────────────────

    @Test
    @DisplayName("배정되지_않은_LEW는_403_APPLICATION_NOT_ASSIGNED")
    void non_assigned_lew_gets_403() {
        User assigned = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, assigned);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        // 다른 LEW seq=99로 요청
        assertThatThrownBy(() -> service.getAssignedApplication(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.APPLICATION_NOT_ASSIGNED);
        assertThatThrownBy(() -> service.saveDraftCof(1L, 99L, validFinalizeRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.APPLICATION_NOT_ASSIGNED);
        assertThatThrownBy(() -> service.finalizeCof(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.APPLICATION_NOT_ASSIGNED);
    }

    @Test
    @DisplayName("Application이_없으면_404")
    void missing_application_yields_404() {
        when(applicationRepository.findById(eq(999L))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAssignedApplication(999L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    // ── Draft Save ──────────────────────

    @Test
    @DisplayName("Draft_Save_신규_insert_후_draftSavedAt_세팅")
    void draft_save_inserts_and_sets_timestamp() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CertificateOfFitnessResponse res = service.saveDraftCof(1L, 10L, validFinalizeRequest());

        assertThat(res.getMsslAccountNo()).isEqualTo("123-45-6789-0");
        assertThat(res.getMsslAccountNoLast4()).isEqualTo("7890");
        assertThat(res.getFinalized()).isFalse();
        assertThat(res.getDraftSavedAt()).isNotNull();
    }

    @Test
    @DisplayName("Draft_Save_MSSL_공란도_성공(finalize_대비)")
    void draft_save_allows_blank_mssl() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CertificateOfFitnessRequest r = validFinalizeRequest();
        r.setMsslAccountNo(null);

        CertificateOfFitnessResponse res = service.saveDraftCof(1L, 10L, r);
        assertThat(res.getMsslAccountNo()).isNull();
        assertThat(res.getMsslAccountNoLast4()).isNull();
    }

    @Test
    @DisplayName("Draft_Save_이미_finalized면_409_COF_ALREADY_FINALIZED")
    void draft_save_on_finalized_cof_409() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness finalized = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .build();
        finalized.finalize(lew, LocalDate.now());
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L)))
                .thenReturn(Optional.of(finalized));

        assertThatThrownBy(() -> service.saveDraftCof(1L, 10L, validFinalizeRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_ALREADY_FINALIZED);
    }

    @Test
    @DisplayName("Draft_Save_voltage_허용_밖이면_400")
    void draft_save_invalid_voltage_400() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());

        CertificateOfFitnessRequest r = validFinalizeRequest();
        r.setSupplyVoltageV(999);

        assertThatThrownBy(() -> service.saveDraftCof(1L, 10L, r))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Draft_Save_MSSL_regex_불일치면_400")
    void draft_save_invalid_mssl_400() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());

        CertificateOfFitnessRequest r = validFinalizeRequest();
        r.setMsslAccountNo("bad-format-xx");

        assertThatThrownBy(() -> service.saveDraftCof(1L, 10L, r))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_VALIDATION_FAILED);
    }

    // ── Finalize ──────────────────────

    @Test
    @DisplayName("PR3_Finalize_성공_시_status는_변경되지_않고_CoF만_finalized된다")
    void finalize_keeps_status_and_marks_cof_finalized() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew); // PAID
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .consumerType(ConsumerType.NON_CONTESTABLE)
                .retailerCode(RetailerCode.SP_SERVICES_LIMITED)
                .hasGenerator(false)
                .build();
        draft.updateMssl("v1:enc", "abc123", "7890");
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApplicationResponse res = service.finalizeCof(1L, 10L);

        // PR3: status 전이 없음 — PAID 유지
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PAID);
        assertThat(res.getStatus()).isEqualTo(ApplicationStatus.PAID);
        assertThat(draft.isFinalized()).isTrue();
    }

    @Test
    @DisplayName("PR3_Finalize_IN_PROGRESS에서도_성공한다")
    void finalize_allowed_at_in_progress() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        ReflectionTestUtils.setField(app, "status", ApplicationStatus.IN_PROGRESS);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.finalizeCof(1L, 10L);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.IN_PROGRESS);
        assertThat(draft.isFinalized()).isTrue();
    }

    @Test
    @DisplayName("PR3_Finalize_PENDING_REVIEW에서_호출하면_409_APPLICATION_NOT_PAID")
    void finalize_at_pending_review_yields_application_not_paid() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew); // 기본 PENDING_REVIEW
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.APPLICATION_NOT_PAID);
    }

    @Test
    @DisplayName("PR3_Finalize_PENDING_PAYMENT에서_호출하면_409_APPLICATION_NOT_PAID")
    void finalize_at_pending_payment_yields_application_not_paid() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        ReflectionTestUtils.setField(app, "status", ApplicationStatus.PENDING_PAYMENT);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.APPLICATION_NOT_PAID);
    }

    @Test
    @DisplayName("Finalize_재호출_409_COF_ALREADY_FINALIZED")
    void finalize_twice_409() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness finalized = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .build();
        finalized.finalize(lew, LocalDate.now());
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(finalized));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_ALREADY_FINALIZED);
    }

    @Test
    @DisplayName("Finalize_MSSL_공란이면_400_COF_VALIDATION_FAILED")
    void finalize_blank_mssl_400() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .consumerType(ConsumerType.NON_CONTESTABLE)
                .build();
        // MSSL 세팅 안 함
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Finalize_Contestable인데_retailer_null이면_400")
    void finalize_contestable_without_retailer_400() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .consumerType(ConsumerType.CONTESTABLE)
                .retailerCode(null) // 명시적 null
                .build();
        // builder default가 SP_SERVICES_LIMITED이므로 reflection으로 null 설정
        ReflectionTestUtils.setField(draft, "retailerCode", null);
        draft.updateMssl("v1:enc", "abc123", "7890");
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Finalize_hasGenerator_true_인데_capacity_null이면_400_AC_9_6")
    void finalize_generator_without_capacity_400() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .consumerType(ConsumerType.NON_CONTESTABLE)
                .hasGenerator(true) // capacity 세팅 없음
                .build();
        draft.updateMssl("v1:enc", "abc123", "7890");
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Finalize_CoF_미존재면_404_COF_NOT_FOUND")
    void finalize_missing_cof_404() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_NOT_FOUND);
    }

    // ── Phase 6: 통합 LEW 리뷰 가드 ──────────────────────

    @Test
    @DisplayName("Phase6_Finalize_kvaStatus가_UNKNOWN이면_400_KVA_NOT_CONFIRMED")
    void phase6_finalize_kva_not_confirmed_400() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        // kvaStatus 를 UNKNOWN 으로 설정 (기본값 CONFIRMED 를 덮어씀)
        ReflectionTestUtils.setField(app, "kvaStatus", KvaStatus.UNKNOWN);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.KVA_NOT_CONFIRMED);
        // 알림 미발송 확인
        verify(notificationService, never())
                .createNotification(anyLong(), any(NotificationType.class),
                        anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Phase6_Finalize_미해결_DocumentRequest_있으면_400_DOCUMENT_REQUESTS_PENDING")
    void phase6_finalize_pending_documents_400() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        // REQUESTED/UPLOADED 가 3건 존재
        when(documentRequestRepository.countByApplicationAndStatusIn(
                eq(1L), eq(Set.of(DocumentRequestStatus.REQUESTED, DocumentRequestStatus.UPLOADED))))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.DOCUMENT_REQUESTS_PENDING);
    }

    @Test
    @DisplayName("Phase6_Finalize_sldOption_REQUEST_LEW_이고_SLD_미확정이면_400_SLD_NOT_CONFIRMED")
    void phase6_finalize_sld_not_confirmed_400() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        ReflectionTestUtils.setField(app, "sldOption", SldOption.REQUEST_LEW);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        // SLD 가 UPLOADED 상태 (CONFIRMED 아님)
        SldRequest sldUploaded = SldRequest.builder().build();
        ReflectionTestUtils.setField(sldUploaded, "status", SldRequestStatus.UPLOADED);
        when(sldRequestRepository.findByApplicationApplicationSeq(eq(1L)))
                .thenReturn(Optional.of(sldUploaded));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.SLD_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("Phase6_Finalize_sldOption_REQUEST_LEW_이고_SLD_레코드_없으면_400_SLD_NOT_CONFIRMED")
    void phase6_finalize_sld_missing_400() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        ReflectionTestUtils.setField(app, "sldOption", SldOption.REQUEST_LEW);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        when(sldRequestRepository.findByApplicationApplicationSeq(eq(1L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.SLD_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("Phase6_Finalize_sldOption_REQUEST_LEW_이고_SLD_CONFIRMED이면_성공")
    void phase6_finalize_sld_confirmed_ok() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        ReflectionTestUtils.setField(app, "sldOption", SldOption.REQUEST_LEW);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SldRequest sldConfirmed = SldRequest.builder().build();
        ReflectionTestUtils.setField(sldConfirmed, "status", SldRequestStatus.CONFIRMED);
        when(sldRequestRepository.findByApplicationApplicationSeq(eq(1L)))
                .thenReturn(Optional.of(sldConfirmed));

        ApplicationResponse res = service.finalizeCof(1L, 10L);

        // PR3: 결제 후(PAID) 호출이므로 status 는 그대로 PAID 유지
        assertThat(res.getStatus()).isEqualTo(ApplicationStatus.PAID);
        assertThat(draft.isFinalized()).isTrue();
    }

    @Test
    @DisplayName("Phase6_Finalize_시_approvedLoadKva가_Application_selectedKva로_스냅샷된다")
    void phase6_finalize_snapshots_approved_load_kva() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        // Application.selectedKva = 45 (기본), CoF.approvedLoadKva 를 다른 값 100 으로 초기 설정
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(100) // 기존 잘못된 값
                .inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .consumerType(ConsumerType.NON_CONTESTABLE)
                .retailerCode(RetailerCode.SP_SERVICES_LIMITED)
                .hasGenerator(false)
                .build();
        draft.updateMssl("v1:enc", "abc123", "7890");
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.finalizeCof(1L, 10L);

        // Application.selectedKva = 45 로 snapshot 되어 CoF.approvedLoadKva 덮어써졌는지 확인
        assertThat(draft.getApprovedLoadKva()).isEqualTo(45);
        assertThat(draft.isFinalized()).isTrue();
    }

    @Test
    @DisplayName("Phase6_Finalize_성공_시_신청자에게_CERTIFICATE_OF_FITNESS_FINALIZED_알림_발송")
    void phase6_finalize_sends_notification_to_applicant() {
        User lew = userWithSeq(10L);
        Application app = applicationReadyForFinalize(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(userRepository.findById(eq(10L))).thenReturn(Optional.of(lew));

        CertificateOfFitness draft = validDraft(app);
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.finalizeCof(1L, 10L);

        verify(notificationService, atLeastOnce()).createNotification(
                eq(99L), // applicant userSeq (applicationAssignedTo 에서 userWithSeq(99L))
                eq(NotificationType.CERTIFICATE_OF_FITNESS_FINALIZED),
                anyString(), anyString(),
                eq("Application"), eq(1L));
    }

    @Test
    @DisplayName("Phase6_SaveDraft_request_approvedLoadKva를_무시하고_Application_selectedKva로_유도한다")
    void phase6_save_draft_derives_approved_load_kva_from_application() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        // Application.selectedKva = 45
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());
        when(cofRepository.save(any(CertificateOfFitness.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CertificateOfFitnessRequest r = validFinalizeRequest();
        r.setApprovedLoadKva(999); // 악의적/잘못된 값 — 무시되어야 함

        CertificateOfFitnessResponse res = service.saveDraftCof(1L, 10L, r);

        // 응답에는 Application.selectedKva(45)가 반영되어야 함
        assertThat(res.getApprovedLoadKva()).isEqualTo(45);
    }

    // Phase 6 테스트 헬퍼 — 유효 상태의 CoF draft
    private CertificateOfFitness validDraft(Application app) {
        CertificateOfFitness draft = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .consumerType(ConsumerType.NON_CONTESTABLE)
                .retailerCode(RetailerCode.SP_SERVICES_LIMITED)
                .hasGenerator(false)
                .build();
        draft.updateMssl("v1:enc", "abc123", "7890");
        return draft;
    }

    // ── getAssignedApplication ──────────────────────

    @Test
    @DisplayName("getAssignedApplication_CoF없을때도_정상_반환")
    void get_assigned_application_without_cof() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());

        var res = service.getAssignedApplication(1L, 10L);
        assertThat(res).isNotNull();
        assertThat(res.getCof()).isNull();
    }

    // ── PR3: requestPayment ──────────────────────

    @Test
    @DisplayName("PR3_requestPayment_PENDING_REVIEW에서_PENDING_PAYMENT로_전이_및_메일_발송")
    void requestPayment_from_pending_review_transitions_and_sends_email() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew); // 기본 PENDING_REVIEW + kVA CONFIRMED
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        ApplicationResponse res = service.requestPayment(1L, 10L);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING_PAYMENT);
        assertThat(res.getStatus()).isEqualTo(ApplicationStatus.PENDING_PAYMENT);

        verify(emailService).sendPaymentRequestEmail(
                anyString(), anyString(), eq(1L), anyString(), any(BigDecimal.class));
    }

    @Test
    @DisplayName("PR3_requestPayment_REVISION_REQUESTED에서도_허용")
    void requestPayment_from_revision_requested_ok() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        ReflectionTestUtils.setField(app, "status", ApplicationStatus.REVISION_REQUESTED);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        service.requestPayment(1L, 10L);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("PR3_requestPayment_PENDING_PAYMENT에서_재호출하면_409_INVALID_STATUS_TRANSITION")
    void requestPayment_at_pending_payment_yields_invalid_transition() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        ReflectionTestUtils.setField(app, "status", ApplicationStatus.PENDING_PAYMENT);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.requestPayment(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.INVALID_STATUS_TRANSITION);
        verify(emailService, never()).sendPaymentRequestEmail(
                anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("PR3_requestPayment_PAID에서_호출하면_409_INVALID_STATUS_TRANSITION")
    void requestPayment_at_paid_yields_invalid_transition() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        ReflectionTestUtils.setField(app, "status", ApplicationStatus.PAID);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.requestPayment(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("PR3_requestPayment_kvaStatus가_UNKNOWN이면_409_KVA_NOT_CONFIRMED")
    void requestPayment_kva_unknown_yields_409() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        ReflectionTestUtils.setField(app, "kvaStatus", KvaStatus.UNKNOWN);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.requestPayment(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.KVA_NOT_CONFIRMED);
        // 가드 위반이므로 전이도 메일 발송도 발생하지 않아야 함
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
        verify(emailService, never()).sendPaymentRequestEmail(
                anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("PR3_requestPayment_미해결_DocumentRequest_있으면_409_DOCUMENT_REQUESTS_PENDING")
    void requestPayment_pending_documents_yields_409() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        when(documentRequestRepository.countByApplicationAndStatusIn(
                eq(1L), eq(Set.of(DocumentRequestStatus.REQUESTED, DocumentRequestStatus.UPLOADED))))
                .thenReturn(2L);

        assertThatThrownBy(() -> service.requestPayment(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.DOCUMENT_REQUESTS_PENDING);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("PR3_requestPayment_배정되지_않은_LEW면_403")
    void requestPayment_non_assigned_lew_403() {
        User assigned = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, assigned);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.requestPayment(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.APPLICATION_NOT_ASSIGNED);
    }

    @Test
    @DisplayName("PR3_requestPayment_메일_발송_실패는_트랜잭션_성공_유지")
    void requestPayment_email_failure_does_not_rollback() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        org.mockito.Mockito.doThrow(new RuntimeException("SMTP down"))
                .when(emailService).sendPaymentRequestEmail(
                        anyString(), anyString(), anyLong(), anyString(), any());

        // 메일 실패해도 호출은 성공해야 함
        ApplicationResponse res = service.requestPayment(1L, 10L);
        assertThat(res.getStatus()).isEqualTo(ApplicationStatus.PENDING_PAYMENT);
    }
}
