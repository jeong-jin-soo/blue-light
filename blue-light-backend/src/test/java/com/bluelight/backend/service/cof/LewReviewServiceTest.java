package com.bluelight.backend.service.cof;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessRequest;
import com.bluelight.backend.api.lew.dto.CertificateOfFitnessResponse;
import com.bluelight.backend.common.crypto.FieldEncryptionUtil;
import com.bluelight.backend.common.crypto.HmacUtil;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.CofErrorCode;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.cof.CertificateOfFitness;
import com.bluelight.backend.domain.cof.CertificateOfFitnessRepository;
import com.bluelight.backend.domain.cof.ConsumerType;
import com.bluelight.backend.domain.cof.RetailerCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private LewReviewService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        cofRepository = mock(CertificateOfFitnessRepository.class);
        userRepository = mock(UserRepository.class);

        String key = Base64.getEncoder().encodeToString(new byte[32]);
        fieldEncryptionUtil = new FieldEncryptionUtil();
        ReflectionTestUtils.setField(fieldEncryptionUtil, "encryptionKeyBase64", key);
        fieldEncryptionUtil.init();
        hmacUtil = new HmacUtil();
        ReflectionTestUtils.setField(hmacUtil, "encryptionKeyBase64", key);
        hmacUtil.init();

        service = new LewReviewService(applicationRepository, cofRepository, userRepository,
                fieldEncryptionUtil, hmacUtil);
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
    @DisplayName("Finalize_성공_PENDING_REVIEW에서_PENDING_PAYMENT로_전이")
    void finalize_transitions_status() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        // app.status는 기본 PENDING_REVIEW
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

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING_PAYMENT);
        assertThat(res.getStatus()).isEqualTo(ApplicationStatus.PENDING_PAYMENT);
        assertThat(draft.isFinalized()).isTrue();
    }

    @Test
    @DisplayName("Finalize_재호출_409_COF_ALREADY_FINALIZED")
    void finalize_twice_409() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
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
        Application app = applicationAssignedTo(1L, lew);
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
        Application app = applicationAssignedTo(1L, lew);
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
        Application app = applicationAssignedTo(1L, lew);
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
    @DisplayName("Finalize_PENDING_PAYMENT에서_호출하면_409")
    void finalize_wrong_status_409() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        ReflectionTestUtils.setField(app, "status", ApplicationStatus.PENDING_PAYMENT);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        CertificateOfFitness draft = CertificateOfFitness.builder()
                .application(app)
                .supplyVoltageV(400).approvedLoadKva(45).inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                .build();
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Finalize_CoF_미존재면_404_COF_NOT_FOUND")
    void finalize_missing_cof_404() {
        User lew = userWithSeq(10L);
        Application app = applicationAssignedTo(1L, lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));
        when(cofRepository.findByApplication_ApplicationSeq(eq(1L))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeCof(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(CofErrorCode.COF_NOT_FOUND);
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
}
