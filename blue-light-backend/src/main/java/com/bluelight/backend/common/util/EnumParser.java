package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 안전한 Enum 파싱 유틸리티
 * - 대소문자 무시 변환
 * - 실패 시 BusinessException (BAD_REQUEST) 발생
 */
public final class EnumParser {

    private EnumParser() {
        // Utility class — 인스턴스 생성 방지
    }

    /**
     * 문자열을 Enum 값으로 안전하게 파싱
     *
     * @param enumType  대상 Enum 클래스
     * @param value     파싱할 문자열
     * @param errorCode 실패 시 에러 코드
     * @return 파싱된 Enum 값
     * @throws BusinessException value가 유효하지 않은 경우
     */
    public static <T extends Enum<T>> T parse(Class<T> enumType, String value, String errorCode) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    "Invalid " + enumType.getSimpleName() + ": " + value,
                    HttpStatus.BAD_REQUEST,
                    errorCode
            );
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid " + enumType.getSimpleName() + ": " + value,
                    HttpStatus.BAD_REQUEST,
                    errorCode
            );
        }
    }

    /**
     * 문자열을 Enum 값으로 안전하게 파싱 (nullable)
     * - value가 null이거나 빈 문자열이면 null 반환
     *
     * @param enumType  대상 Enum 클래스
     * @param value     파싱할 문자열 (nullable)
     * @param errorCode 실패 시 에러 코드
     * @return 파싱된 Enum 값 또는 null
     * @throws BusinessException value가 비어있지 않으면서 유효하지 않은 경우
     */
    public static <T extends Enum<T>> T parseNullable(Class<T> enumType, String value, String errorCode) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid " + enumType.getSimpleName() + ": " + value,
                    HttpStatus.BAD_REQUEST,
                    errorCode
            );
        }
    }
}
