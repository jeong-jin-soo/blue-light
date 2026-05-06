package com.bluelight.backend.domain.cof;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CertificateOfFitness 엔티티 도메인 단위 테스트 (LEW Review Form P1.A).
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>빌더 기본값 (ConsumerType=NON_CONTESTABLE, RetailerCode=SP_SERVICES_LIMITED, hasGenerator=false)</li>
 *   <li>{@link CertificateOfFitness#saveDraft()} / {@link CertificateOfFitness#finalize(User, LocalDate)}
 *       / {@link CertificateOfFitness#isFinalized()}</li>
 *   <li>finalize 재호출 → IllegalStateException (AC §9-5)</li>
 *   <li>finalized 후 Draft/필드 수정 차단</li>
 *   <li>Soft delete 플래그 동작 (BaseEntity.softDelete)</li>
 *   <li>LEW null 방어</li>
 *   <li>hasGenerator=false로 전환 시 generatorCapacityKva 정리</li>
 * </ul>
 *
 * <p>JPA persistence / @Version 증가는 영속 컨텍스트가 필요하므로 통합 테스트(P1.C)에서 확인.</p>
 */
@DisplayName("CertificateOfFitness 도메인 - P1.A")
class CertificateOfFitnessTest {

    private User lew() {
        return User.builder()
                .email("lew@b.com").password("h").firstName("L").lastName("Ew")
                .build();
    }

    private Application application() {
        return Application.builder()
                .user(User.builder()
                        .email("a@b.com").password("h").firstName("A").lastName("B")
                        .build())
                .address("1 Test Rd")
                .postalCode("111111")
                .selectedKva(10)
                .quoteAmount(new BigDecimal("100.00"))
                .build();
    }

    private CertificateOfFitness validDraft() {
        return CertificateOfFitness.builder()
                .application(application())
                .supplyVoltageV(400)
                .approvedLoadKva(45)
                .inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.of(2026, 4, 22))
                .build();
    }

    @Test
    @DisplayName("빌더_기본값이_스펙과_일치한다")
    void builder_defaults_match_spec() {
        CertificateOfFitness cof = validDraft();

        assertThat(cof.getConsumerType()).isEqualTo(ConsumerType.NON_CONTESTABLE);
        assertThat(cof.getRetailerCode()).isEqualTo(RetailerCode.SP_SERVICES_LIMITED);
        assertThat(cof.getHasGenerator()).isFalse();
        assertThat(cof.getVersion()).isEqualTo(0);
        assertThat(cof.getCertifiedAt()).isNull();
        assertThat(cof.getCertifiedByLew()).isNull();
        assertThat(cof.getDraftSavedAt()).isNull();
    }

    @Test
    @DisplayName("lewAppointmentDate_null_이면_오늘로_채워진다")
    void appointment_date_defaults_to_today_when_null() {
        CertificateOfFitness cof = CertificateOfFitness.builder()
                .application(application())
                .supplyVoltageV(230)
                .approvedLoadKva(10)
                .inspectionIntervalMonths(12)
                // lewAppointmentDate 생략
                .build();

        assertThat(cof.getLewAppointmentDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("isFinalized_초기에는_false")
    void is_finalized_initially_false() {
        CertificateOfFitness cof = validDraft();
        assertThat(cof.isFinalized()).isFalse();
    }

    @Test
    @DisplayName("saveDraft는_draftSavedAt을_갱신한다")
    void save_draft_updates_timestamp() {
        CertificateOfFitness cof = validDraft();
        cof.saveDraft();
        assertThat(cof.getDraftSavedAt()).isNotNull();
    }

    @Test
    @DisplayName("finalize는_certifiedBy_at_consentDate를_기록한다")
    void finalize_records_all_three() {
        CertificateOfFitness cof = validDraft();
        User lewUser = lew();
        LocalDate consent = LocalDate.of(2026, 4, 20);

        cof.finalize(lewUser, consent);

        assertThat(cof.isFinalized()).isTrue();
        assertThat(cof.getCertifiedByLew()).isSameAs(lewUser);
        assertThat(cof.getCertifiedAt()).isNotNull();
        assertThat(cof.getLewConsentDate()).isEqualTo(consent);
    }

    @Test
    @DisplayName("finalize_consentDate_null이면_today로_대체한다")
    void finalize_null_consent_falls_back_to_today() {
        CertificateOfFitness cof = validDraft();
        cof.finalize(lew(), null);
        assertThat(cof.getLewConsentDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("finalize_재호출은_IllegalStateException_AC_9_5")
    void finalize_twice_throws() {
        CertificateOfFitness cof = validDraft();
        cof.finalize(lew(), null);

        assertThatThrownBy(() -> cof.finalize(lew(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finalized");
    }

    @Test
    @DisplayName("finalize_lewUser_null이면_IllegalArgumentException")
    void finalize_null_lew_throws() {
        CertificateOfFitness cof = validDraft();
        assertThatThrownBy(() -> cof.finalize(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("finalized_이후_saveDraft는_차단")
    void finalized_cof_rejects_save_draft() {
        CertificateOfFitness cof = validDraft();
        cof.finalize(lew(), null);

        assertThatThrownBy(cof::saveDraft)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("finalized_이후_필드_갱신은_차단")
    void finalized_cof_rejects_field_update() {
        CertificateOfFitness cof = validDraft();
        cof.finalize(lew(), null);

        assertThatThrownBy(() -> cof.updateFields(
                ConsumerType.CONTESTABLE, RetailerCode.KEPPEL_ELECTRIC,
                230, 10, false, null, 12, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> cof.updateMssl("enc", "hmac", "0000"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("updateFields_hasGenerator_false로_전환_시_capacity_정리")
    void update_fields_clears_capacity_when_generator_off() {
        CertificateOfFitness cof = CertificateOfFitness.builder()
                .application(application())
                .supplyVoltageV(400)
                .approvedLoadKva(45)
                .inspectionIntervalMonths(12)
                .hasGenerator(true)
                .generatorCapacityKva(50)
                .build();

        cof.updateFields(null, null, null, null, false, null, null, null, null);

        assertThat(cof.getHasGenerator()).isFalse();
        assertThat(cof.getGeneratorCapacityKva()).isNull();
    }

    @Test
    @DisplayName("updateMssl은_3종_필드를_함께_세팅")
    void update_mssl_sets_all_three() {
        CertificateOfFitness cof = validDraft();
        cof.updateMssl("v1:ENCRYPTED", "abc123", "0001");

        assertThat(cof.getMsslAccountNoEnc()).isEqualTo("v1:ENCRYPTED");
        assertThat(cof.getMsslAccountNoHmac()).isEqualTo("abc123");
        assertThat(cof.getMsslAccountNoLast4()).isEqualTo("0001");
    }

    @Test
    @DisplayName("BaseEntity_softDelete_동작")
    void soft_delete_marks_deleted_at() {
        CertificateOfFitness cof = validDraft();
        assertThat(cof.isDeleted()).isFalse();

        cof.softDelete();

        assertThat(cof.isDeleted()).isTrue();
        assertThat(cof.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Application_쪽_역방향_매핑_접근_가능")
    void application_inverse_mapping_accessible() {
        Application app = application();
        // 역방향 필드는 아직 연결되지 않은 상태 — null
        assertThat(app.getCertificateOfFitness()).isNull();
        // 여기서는 getter 존재 자체가 컴파일 시 확인됨. 실제 양방향 persist-merge는 통합 테스트에서.
    }

    @Test
    @DisplayName("Builder_hasGenerator_null이면_false_기본값")
    void builder_has_generator_null_defaults_false() {
        CertificateOfFitness cof = CertificateOfFitness.builder()
                .application(application())
                .supplyVoltageV(230)
                .approvedLoadKva(10)
                .inspectionIntervalMonths(12)
                .lewAppointmentDate(LocalDate.now())
                // hasGenerator 생략 (null)
                .build();

        assertThat(cof.getHasGenerator()).isFalse();
    }
}
