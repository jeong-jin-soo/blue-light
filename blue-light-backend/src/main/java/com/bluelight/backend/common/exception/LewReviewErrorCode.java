package com.bluelight.backend.common.exception;

/**
 * LEW Review Form 에러 코드 상수 모음.
 *
 * <p>{@link BusinessException}의 {@code code} 파라미터로 넘기는 문자열을 한 곳에 모은다.
 * (CoF 기능 제거 후 잔존하는 LEW 조회/결제 요청 경로의 가드 코드만 유지한다.)</p>
 */
public final class LewReviewErrorCode {

    /** 인증 LEW가 해당 Application의 배정자가 아님. HTTP 403. */
    public static final String APPLICATION_NOT_ASSIGNED = "APPLICATION_NOT_ASSIGNED";

    /** LEW 결제 요청 시 Application.kvaStatus가 CONFIRMED 아님. HTTP 409. */
    public static final String KVA_NOT_CONFIRMED = "KVA_NOT_CONFIRMED";

    /** LEW 결제 요청 시 미해결 DocumentRequest 존재 (REQUESTED/UPLOADED). HTTP 409. */
    public static final String DOCUMENT_REQUESTS_PENDING = "DOCUMENT_REQUESTS_PENDING";

    /**
     * LEW가 결제 요청을 트리거하기 위한 status 전이 가드 위반. HTTP 409.
     *
     * <p>현재 status가 PENDING_REVIEW/REVISION_REQUESTED 가 아니거나, 이미 PENDING_PAYMENT/PAID 등의
     * 후행 상태일 때 반환. ADMIN의 별도 approveForPayment 와 race가 발생하면 두 번째 호출이 이 코드로 거부된다.</p>
     */
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";

    private LewReviewErrorCode() {
        // no instance
    }
}
