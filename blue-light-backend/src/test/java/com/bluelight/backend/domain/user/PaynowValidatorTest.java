package com.bluelight.backend.domain.user;

import com.bluelight.backend.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PaynowValidator} 단위 테스트 (PR-PN1, AC-13).
 *
 * <p>MOBILE {@code ^[89]\d{7}$}(8자리), COMPANY_UEN {@code ^\d{9}[A-Za-z]$}(10자) 경계 검증.</p>
 */
@DisplayName("PaynowValidator - PR-PN1")
class PaynowValidatorTest {

    @Test
    @DisplayName("리뷰 #5: 정규식 상수 고정 — 프론트 constants/paynow.ts 와 반드시 동일(R-PN5 drift 방지)")
    void regex_constants_pinned() {
        // 이 값이 바뀌면 프론트 constants/paynow.ts(PAYNOW_MOBILE_REGEX/PAYNOW_COMPANY_UEN_REGEX)도
        // 함께 수정해야 한다. 한쪽만 바뀌면 프론트/백 검증이 어긋난다.
        assertThat(PaynowValidator.MOBILE_REGEX).isEqualTo("^[89]\\d{7}$");
        assertThat(PaynowValidator.COMPANY_UEN_REGEX).isEqualTo("^\\d{9}[A-Za-z]$");
    }

    @Test
    @DisplayName("MOBILE 정상: 8/9로 시작하는 8자리")
    void mobile_valid() {
        assertThatCode(() -> PaynowValidator.validate(PaynowType.MOBILE, "97771983")).doesNotThrowAnyException();
        assertThatCode(() -> PaynowValidator.validate(PaynowType.MOBILE, "87654321")).doesNotThrowAnyException();
        // 앞뒤 공백은 trim 후 검증
        assertThatCode(() -> PaynowValidator.validate(PaynowType.MOBILE, "  97771983  ")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9777198",    // 7자리
            "977719833",  // 9자리
            "77771983",   // 7로 시작 (8/9 아님)
            "6777198",    // 6로 시작
            "9777198a",   // 비숫자 포함
    })
    @DisplayName("MOBILE 형식 위반 → 400 INVALID_PAYNOW_VALUE")
    void mobile_invalid(String value) {
        assertThatThrownBy(() -> PaynowValidator.validate(PaynowType.MOBILE, value))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PAYNOW_VALUE");
    }

    @Test
    @DisplayName("COMPANY_UEN 정상: 9자리 숫자 + 끝 영문 1자 (10자)")
    void uen_valid() {
        assertThatCode(() -> PaynowValidator.validate(PaynowType.COMPANY_UEN, "201837490N")).doesNotThrowAnyException();
        assertThatCode(() -> PaynowValidator.validate(PaynowType.COMPANY_UEN, "201837490n")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "20183749N",   // 9자 (8숫자+영문)
            "2018374900N", // 11자
            "2018374901",  // 10자지만 끝이 숫자
            "20183749NN",  // 끝 2영문
            "A01837490N",  // 앞이 영문
    })
    @DisplayName("COMPANY_UEN 형식 위반 → 400 INVALID_PAYNOW_VALUE")
    void uen_invalid(String value) {
        assertThatThrownBy(() -> PaynowValidator.validate(PaynowType.COMPANY_UEN, value))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PAYNOW_VALUE");
    }

    @Test
    @DisplayName("type null → 400 PAYNOW_TYPE_REQUIRED")
    void type_null() {
        assertThatThrownBy(() -> PaynowValidator.validate(null, "97771983"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PAYNOW_TYPE_REQUIRED");
    }

    @Test
    @DisplayName("value blank → 400 PAYNOW_VALUE_REQUIRED")
    void value_blank() {
        assertThatThrownBy(() -> PaynowValidator.validate(PaynowType.MOBILE, "   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PAYNOW_VALUE_REQUIRED");
        assertThatThrownBy(() -> PaynowValidator.validate(PaynowType.MOBILE, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PAYNOW_VALUE_REQUIRED");
    }

    @Test
    @DisplayName("검증 실패는 400 BAD_REQUEST (입력 검증 오류 — 토큰 잠금 미카운트 정책, D-9)")
    void status_is_bad_request() {
        assertThatThrownBy(() -> PaynowValidator.validate(PaynowType.MOBILE, "bad"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(400));
    }
}
