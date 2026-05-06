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

    /** Phase 6: CoF finalize 시 Application.kvaStatus가 CONFIRMED 아님. HTTP 400. */
    public static final String KVA_NOT_CONFIRMED = "KVA_NOT_CONFIRMED";

    /** Phase 6: CoF finalize 시 미해결 DocumentRequest 존재 (REQUESTED/UPLOADED). HTTP 400. */
    public static final String DOCUMENT_REQUESTS_PENDING = "DOCUMENT_REQUESTS_PENDING";

    /** Phase 6: CoF finalize 시 sldOption=REQUEST_LEW 이고 SLD가 CONFIRMED가 아님. HTTP 400. */
    public static final String SLD_NOT_CONFIRMED = "SLD_NOT_CONFIRMED";

    /**
     * PR3: LEW가 결제 요청을 트리거하기 위한 status 전이 가드 위반. HTTP 409.
     *
     * <p>현재 status가 PENDING_REVIEW/REVISION_REQUESTED 가 아니거나, 이미 PENDING_PAYMENT/PAID 등의
     * 후행 상태일 때 반환. ADMIN의 별도 approveForPayment 와 race가 발생하면 두 번째 호출이 이 코드로 거부된다.</p>
     */
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";

    /**
     * PR3: CoF finalize 결제 게이트 — Application 이 결제 완료(PAID) 또는 시공 중(IN_PROGRESS) 이 아닐 때.
     * HTTP 409.
     *
     * <p>옵션 R 채택 (sg-lew-expert + 사용자 결정): SS 638 §13에 따라 CoF는 시공·테스트 후 발행되어야 하므로,
     * 결제 완료 이전에는 finalize 할 수 없다. PR3 이전 모델(finalize → PENDING_PAYMENT 전이)은 도메인 부정합.</p>
     */
    public static final String APPLICATION_NOT_PAID = "APPLICATION_NOT_PAID";

    private CofErrorCode() {
        // no instance
    }
}
