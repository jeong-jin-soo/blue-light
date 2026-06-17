package com.bluelight.backend.domain.user;

import com.bluelight.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

/**
 * LEW PayNow 입력 검증 — 백엔드 단일 검증 소스(R-PN5).
 * <p>
 * 프론트엔드 {@code constants/paynow.ts} 와 <b>동일한 정규식</b>을 공유해 drift 를 방지한다.
 * 형식·자리수는 싱가포르 PayNow 의 법적·고정 형식이므로 상수로 정의한다.
 * <ul>
 *   <li>MOBILE: 8 / 9 로 시작하는 8자리 숫자 — {@value #MOBILE_REGEX} (예 {@code 97771983})</li>
 *   <li>COMPANY_UEN: 9자리 숫자 + 끝 영문 1자 = 10자 — {@value #COMPANY_UEN_REGEX} (예 {@code 201837490N})</li>
 * </ul>
 * 검증 실패는 모두 400 {@link BusinessException} 으로 던진다(입력 검증 오류 — 토큰 잠금 미카운트, D-9).
 */
public final class PaynowValidator {

    /** 싱가포르 휴대폰 PayNow: 8 또는 9 로 시작하는 8자리 숫자. */
    public static final String MOBILE_REGEX = "^[89]\\d{7}$";

    /** Company UEN PayNow: 9자리 숫자 + 끝 영문 1자(로컬 회사 UEN 형식), 총 10자. */
    public static final String COMPANY_UEN_REGEX = "^\\d{9}[A-Za-z]$";

    private static final Pattern MOBILE = Pattern.compile(MOBILE_REGEX);
    private static final Pattern COMPANY_UEN = Pattern.compile(COMPANY_UEN_REGEX);

    private PaynowValidator() {
    }

    /**
     * PayNow type/value 를 검증한다. 실패 시 400 BusinessException.
     *
     * @param type  COMPANY_UEN / MOBILE (null 금지)
     * @param value trim 전 원본 값 (내부에서 trim 하여 검증)
     */
    public static void validate(PaynowType type, String value) {
        if (type == null) {
            throw new BusinessException(
                    "PayNow type is required",
                    HttpStatus.BAD_REQUEST,
                    "PAYNOW_TYPE_REQUIRED"
            );
        }
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    "PayNow value is required",
                    HttpStatus.BAD_REQUEST,
                    "PAYNOW_VALUE_REQUIRED"
            );
        }
        String trimmed = value.trim();
        boolean ok = switch (type) {
            case MOBILE -> MOBILE.matcher(trimmed).matches();
            case COMPANY_UEN -> COMPANY_UEN.matcher(trimmed).matches();
        };
        if (!ok) {
            throw new BusinessException(
                    "PayNow value format is invalid for type " + type,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAYNOW_VALUE"
            );
        }
    }
}
