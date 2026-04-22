package com.bluelight.backend.common.exception;

/**
 * LEW Review Form P1.B 에러 코드 상수 모음 (lew-review-form-spec.md §3·§9).
 *
 * <p>{@link BusinessException}의 {@code code} 파라미터로 넘기는 문자열을 한 곳에 모은다.
 * 새 enum 타입을 도입하지 않는 이유: 기존 프로젝트 관례가 String code이고,
 * {@code ErrorResponse.code} 컬럼도 String이기 때문.</p>
 */
public final class CofErrorCode {

    /** CoF가 이미 finalize되어 재요청 거부 (AC §9-5). HTTP 409. */
    public static final String COF_ALREADY_FINALIZED = "COF_ALREADY_FINALIZED";

    /** CoF 낙관적 락 충돌 (AC §9-12). HTTP 409. */
    public static final String COF_VERSION_CONFLICT = "COF_VERSION_CONFLICT";

    /** CoF finalize 시 필수 필드 누락/부정합 (AC §9-6, §9-7, §9-9). HTTP 400. */
    public static final String COF_VALIDATION_FAILED = "COF_VALIDATION_FAILED";

    /** CoF 레코드 없음. HTTP 404. */
    public static final String COF_NOT_FOUND = "COF_NOT_FOUND";

    /** 인증 LEW가 해당 Application의 배정자가 아님 (AC §9-3). HTTP 403. */
    public static final String APPLICATION_NOT_ASSIGNED = "APPLICATION_NOT_ASSIGNED";

    private CofErrorCode() {
        // no instance
    }
}
