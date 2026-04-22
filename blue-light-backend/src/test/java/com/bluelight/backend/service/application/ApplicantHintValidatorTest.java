package com.bluelight.backend.service.application;

import com.bluelight.backend.common.crypto.FieldEncryptionUtil;
import com.bluelight.backend.common.crypto.HmacUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplicantHintValidator 단위 테스트 (LEW Review Form P1.B, 스펙 §5.4).
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>각 필드 유효/무효 분기</li>
 *   <li>MSSL은 enc/hmac/last4 3종 분리 세팅 확인</li>
 *   <li>warning-only: 형식 오류여도 {@link ApplicantHintValidationResult}는 반환되고 throw 안 함</li>
 *   <li>hasGenerator=true + capacity=null → 저장 허용(hasGenerator만), warning 함께 반환</li>
 * </ul>
 * <p>FieldEncryptionUtil/HmacUtil은 key 주입 후 init()해서 진짜 실행 (passthrough 모드는 쓰지 않음 —
 * 실제 동작을 검증하기 위함).</p>
 */
@DisplayName("ApplicantHintValidator - P1.B")
class ApplicantHintValidatorTest {

    private ApplicantHintValidator validator;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        FieldEncryptionUtil enc = new FieldEncryptionUtil();
        ReflectionTestUtils.setField(enc, "encryptionKeyBase64", key);
        enc.init();
        HmacUtil hmac = new HmacUtil();
        ReflectionTestUtils.setField(hmac, "encryptionKeyBase64", key);
        hmac.init();
        validator = new ApplicantHintValidator(enc, hmac);
    }

    @Test
    @DisplayName("모든_필드_유효_입력이면_warning_없이_normalized_반환")
    void all_valid_returns_normalized_without_warnings() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                "123-45-6789-0", 400, "NON_CONTESTABLE", "SP_SERVICES_LIMITED", true, 50);

        assertThat(r.getWarnings()).isEmpty();
        NormalizedHints nh = r.getNormalized();
        assertThat(nh.getMsslEnc()).startsWith("v1:");
        assertThat(nh.getMsslHmac()).hasSize(64);
        assertThat(nh.getMsslLast4()).isEqualTo("7890"); // 뒤 4자리 digit 추출 (78 + 9 + 0, 마지막 체크디지트 포함)
        assertThat(nh.getSupplyVoltage()).isEqualTo(400);
        assertThat(nh.getConsumerType()).isEqualTo("NON_CONTESTABLE");
        assertThat(nh.getRetailer()).isEqualTo("SP_SERVICES_LIMITED");
        assertThat(nh.getHasGenerator()).isTrue();
        assertThat(nh.getGeneratorCapacity()).isEqualTo(50);
    }

    @Test
    @DisplayName("모든_필드_null이면_warning도_normalized도_비어있음")
    void all_null_returns_empty_result() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                null, null, null, null, null, null);
        assertThat(r.getWarnings()).isEmpty();
        NormalizedHints nh = r.getNormalized();
        assertThat(nh.getMsslEnc()).isNull();
        assertThat(nh.getMsslHmac()).isNull();
        assertThat(nh.getMsslLast4()).isNull();
        assertThat(nh.getSupplyVoltage()).isNull();
        assertThat(nh.getConsumerType()).isNull();
        assertThat(nh.getRetailer()).isNull();
        assertThat(nh.getHasGenerator()).isNull();
        assertThat(nh.getGeneratorCapacity()).isNull();
    }

    @Test
    @DisplayName("MSSL_형식_오류면_저장안됨_warning_발생")
    void invalid_mssl_produces_warning_and_not_saved() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                "abc-de-1234-5", null, null, null, null, null);
        assertThat(r.getWarnings()).hasSize(1);
        assertThat(r.getWarnings().get(0).getField()).isEqualTo("msslHint");
        assertThat(r.getWarnings().get(0).getCode()).isEqualTo("INVALID_FORMAT");
        assertThat(r.getNormalized().getMsslEnc()).isNull();
    }

    @Test
    @DisplayName("Voltage_허용_밖이면_저장안됨_warning")
    void invalid_voltage_produces_warning_and_not_saved() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                null, 999, null, null, null, null);
        assertThat(r.getWarnings()).hasSize(1);
        assertThat(r.getWarnings().get(0).getField()).isEqualTo("supplyVoltageHint");
        assertThat(r.getNormalized().getSupplyVoltage()).isNull();
    }

    @Test
    @DisplayName("허용_voltage_모두_통과")
    void all_allowed_voltages_pass() {
        for (Integer v : new Integer[]{230, 400, 6600, 22000}) {
            ApplicantHintValidationResult r = validator.validateAndNormalize(
                    null, v, null, null, null, null);
            assertThat(r.getWarnings()).isEmpty();
            assertThat(r.getNormalized().getSupplyVoltage()).isEqualTo(v);
        }
    }

    @Test
    @DisplayName("consumerType_enum_밖이면_저장안됨_warning")
    void invalid_consumer_type_produces_warning() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                null, null, "INVALID_VALUE_FOO", null, null, null);
        assertThat(r.getWarnings()).hasSize(1);
        assertThat(r.getWarnings().get(0).getField()).isEqualTo("consumerTypeHint");
        assertThat(r.getNormalized().getConsumerType()).isNull();
    }

    @Test
    @DisplayName("retailer_enum_밖이면_저장안됨_warning")
    void invalid_retailer_produces_warning() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                null, null, null, "FAKE_RETAILER", null, null);
        assertThat(r.getWarnings()).hasSize(1);
        assertThat(r.getWarnings().get(0).getField()).isEqualTo("retailerHint");
        assertThat(r.getNormalized().getRetailer()).isNull();
    }

    @Test
    @DisplayName("hasGenerator_true에_capacity_null이면_warning_그러나_hasGenerator_저장")
    void has_generator_without_capacity_warns_but_saves_flag() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                null, null, null, null, true, null);
        assertThat(r.getWarnings()).hasSize(1);
        assertThat(r.getWarnings().get(0).getField()).isEqualTo("generatorCapacityHint");
        assertThat(r.getWarnings().get(0).getCode()).isEqualTo("MISSING_WHEN_HAS_GENERATOR");
        // hasGenerator는 저장됨
        assertThat(r.getNormalized().getHasGenerator()).isTrue();
        assertThat(r.getNormalized().getGeneratorCapacity()).isNull();
    }

    @Test
    @DisplayName("hasGenerator_false에_capacity가_있어도_통과")
    void has_generator_false_with_capacity_still_saves() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                null, null, null, null, false, 30);
        assertThat(r.getWarnings()).isEmpty();
        assertThat(r.getNormalized().getHasGenerator()).isFalse();
        assertThat(r.getNormalized().getGeneratorCapacity()).isEqualTo(30);
    }

    @Test
    @DisplayName("generatorCapacity_음수면_warning_저장안됨")
    void negative_generator_capacity_produces_warning() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                null, null, null, null, true, -5);
        assertThat(r.getWarnings()).extracting("field").contains("generatorCapacityHint");
        assertThat(r.getNormalized().getGeneratorCapacity()).isNull();
    }

    @Test
    @DisplayName("여러_필드_동시_오류시_warning_각각_수집")
    void multiple_invalid_fields_collect_all_warnings() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                "bad-format", 999, "OOPS", "FAKE_CO", null, null);
        assertThat(r.getWarnings()).hasSize(4);
    }

    @Test
    @DisplayName("공백_문자열은_null처럼_처리")
    void blank_strings_treated_as_null() {
        ApplicantHintValidationResult r = validator.validateAndNormalize(
                "   ", null, "  ", "", null, null);
        assertThat(r.getWarnings()).isEmpty();
        assertThat(r.getNormalized().getMsslEnc()).isNull();
        assertThat(r.getNormalized().getConsumerType()).isNull();
        assertThat(r.getNormalized().getRetailer()).isNull();
    }
}
