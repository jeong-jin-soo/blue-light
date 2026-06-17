package com.bluelight.backend.domain.audit;

/**
 * 감사 로그 액션 유형
 */
public enum AuditAction {
    // Auth
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    SIGNUP,
    PASSWORD_RESET_REQUEST,
    PASSWORD_RESET_COMPLETE,
    EMAIL_VERIFIED,

    // Application lifecycle
    APPLICATION_CREATED,
    APPLICATION_UPDATED,
    APPLICATION_STATUS_CHANGE,
    APPLICATION_REVISION_REQUESTED,
    APPLICATION_APPROVED,
    APPLICATION_COMPLETED,
    APPLICATION_RESUBMITTED,

    // File
    FILE_UPLOADED,
    FILE_DELETED,

    // Document Request (Phase 2)
    DOCUMENT_UPLOADED_VOLUNTARY,
    DOCUMENT_DELETED_VOLUNTARY,

    // Document Request — LEW 워크플로 (Phase 3 PR#1)
    DOCUMENT_REQUEST_CREATED,
    DOCUMENT_REQUEST_FULFILLED,
    DOCUMENT_REQUEST_APPROVED,
    DOCUMENT_REQUEST_REJECTED,
    DOCUMENT_REQUEST_CANCELLED,

    // LOA snapshot (Phase 2 PR#4)
    LOA_SNAPSHOT_CREATED,

    // LOA 교환 모델 (loa-exchange-redesign-spec.md §3.3, PR3b)
    LOA_FORM_SENT,
    LOA_APPLICANT_UPLOADED,
    LOA_FINAL_UPLOADED,
    // Admin 교환 패널 — LoA 파일 등록/교체 (기존 파일 보관, 사유 기록)
    LOA_ADMIN_REPLACED,

    // Admin user management
    LEW_APPROVED,
    LEW_REJECTED,
    LEW_INVITATION_SENT,
    LEW_PAYNOW_VIEWED,
    USER_ROLE_CHANGED,

    // Admin application management
    PAYMENT_CONFIRMED,
    LEW_ASSIGNED,
    LEW_UNASSIGNED,

    // System settings
    SYSTEM_PROMPT_UPDATED,
    SYSTEM_PROMPT_RESET,
    SLD_SYSTEM_PROMPT_UPDATED,
    SLD_SYSTEM_PROMPT_RESET,
    GEMINI_KEY_UPDATED,
    GEMINI_KEY_CLEARED,
    EMAIL_VERIFICATION_TOGGLED,
    SLD_AI_GENERATION_TOGGLED,
    PRICE_UPDATED,
    SETTINGS_UPDATED,
    ROLE_METADATA_UPDATED,

    // PDPA data rights
    DATA_EXPORTED,
    ACCOUNT_DELETED,
    PDPA_CONSENT_WITHDRAWN,
    PROFILE_COMPANY_INFO_UPDATED,
    CORPORATE_INFO_CAPTURED_VIA_JIT,

    // Phase 5: kVA 확정 (security-review §4 — 3종 분리)
    KVA_CONFIRMED_BY_LEW,
    KVA_OVERRIDDEN_BY_ADMIN,
    KVA_CONFIRMATION_DENIED,

    // 결제 후 kVA 사후 변경 (kva-postpayment-adjustment-spec.md PR-1)
    KVA_OVERRIDE_POSTPAYMENT,

    // 결제 후 kVA 사후 변경 — LEW 요청 흐름 (kva-postpayment-adjustment-spec.md §4.2 / PR-3)
    KVA_ADJUSTMENT_REQUESTED_BY_LEW,
    // ADMIN 의 직접 변경에 의해 PENDING LEW 요청이 자동으로 해소(RESOLVED_BY_ADMIN_OVERRIDE) 됨 (AC-L4)
    KVA_LEW_REQUEST_RESOLVED_BY_OVERRIDE,

    // 결제 후 kVA 사후 변경 — Settlement 마킹 (kva-postpayment-adjustment-spec.md §4.3 / PR-4)
    KVA_SETTLEMENT_MARKED,
    // D6 거부 / 잘못된 status row 등 settlement 마킹 거부도 동일 액션에 metadata 로 기록
    KVA_SETTLEMENT_DENIED,

    // Data breach
    DATA_BREACH_REPORTED,
    DATA_BREACH_PDPC_NOTIFIED,
    DATA_BREACH_USERS_NOTIFIED,
    DATA_BREACH_RESOLVED,

    // Phase 1 — Kaki Concierge Service (v1.5)
    CONCIERGE_REQUEST_SUBMITTED,
    CONCIERGE_ACCOUNT_AUTO_CREATED,
    CONCIERGE_EXISTING_USER_LINKED,
    CONCIERGE_MANAGER_ASSIGNED,
    CONCIERGE_STATUS_TRANSITION,
    CONCIERGE_NOTE_ADDED,
    CONCIERGE_CANCELLED,
    CONCIERGE_QUOTE_EMAIL_SENT,
    USER_CONSENT_RECORDED,
    ACCOUNT_SETUP_TOKEN_ISSUED,
    ACCOUNT_SETUP_TOKEN_FAILED_ATTEMPT,    // H-3
    ACCOUNT_SETUP_TOKEN_LOCKED,            // H-3
    ACCOUNT_ACTIVATED,
    ACCOUNT_ACTIVATION_REQUEST_SENT,       // H-1, §4.4 옵션 B
    ACCOUNT_ACTIVATION_REQUEST_NO_MATCH,   // H-1, 이메일 미존재도 동일 응답 (감사 내부 기록)
    APPLICATION_CREATED_ON_BEHALF,
    LOA_SIGNATURE_UPLOADED_BY_MANAGER,
    LOA_SIGNATURE_IMPLICIT_CONSENT_LAPSED, // O-15, 7일 이의 제기 창구 만료
    LOGIN_FAILED_UNKNOWN_EMAIL,            // v1.5 AC-29 관련
    LOGIN_FAILED_BAD_PASSWORD,             // v1.5 AC-29 관련
    LOGIN_FAILED_DELETED,                  // v1.5 AC-29 관련

    // LEW Review Form — 배정 신청 조회 감사 로그
    APPLICATION_VIEWED_BY_LEW,

    // PR3: LEW가 명시적으로 결제 요청을 트리거 (옵션 R — Phase 1 종료 후)
    // CoF finalize 와 분리되어 status PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT 전이를 일으킨다.
    APPLICATION_PAYMENT_REQUESTED_BY_LEW,

    // E-Invoice (invoice-spec.md §9 감사 로그)
    INVOICE_GENERATED,
    INVOICE_DOWNLOADED,
    INVOICE_REGENERATED,
    INVOICE_GENERATION_FAILED,

    // LEW Service 방문형 리스키닝 (lew-service-visit-redesign-spec.md PR 2+)
    LEW_SERVICE_VISIT_SCHEDULED,
    // PR 3 — 체크인/아웃 + 재방문 요청
    LEW_SERVICE_CHECKED_IN,
    LEW_SERVICE_CHECKED_OUT,
    LEW_SERVICE_REVISIT_REQUESTED,

    // ADMIN Manual Email Dispatch (admin-manual-email-spec.md §6 AC-A1, §13.2)
    // ADMIN/SYSTEM_ADMIN 이 신청자/LEW/외부 수신자에게 ad-hoc 이메일 발송 시 1건 기록.
    // metadata: dispatchSeq, recipientType, recipientEmail, relatedApplicationSeq, subject(원문), bodyText 길이 등.
    MANUAL_EMAIL_DISPATCHED,

    // ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 — PR-1 인프라, PR-2/3 에서 호출.
    // ADMIN 이 별도 수금(은행 송금/현금 등)을 수동으로 기록한 시점 (PR-2 metadata: paymentSeq, method, amount, reference).
    MANUAL_PAYMENT_RECORDED,
    // LEW 셀프 할당 또는 ADMIN 의 LEW 배정 (PR-3, D6=A). metadata: conciergeRequestSeq, lewUserSeq, previousLewUserSeq.
    CONCIERGE_LEW_ASSIGNED,
    // 별도 수금 기록 직후 자동 발행된 영수증 (PR-2). metadata: invoiceSeq, paymentSeq, paymentMethod.
    INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT,

    // ★ 트랙 1.4 — 인가 거부(403) 기록. P0 SpEL 단일화 후 @PreAuthorize 가
    // 컨트롤러 메서드 진입 전 거부하면서 @Auditable @Around 가 더이상 cross-tenant
    // 시도를 기록하지 못하던 공백을 GlobalExceptionHandler 에서 메운다.
    // metadata(requestUri/method/ip/userAgent)로 침해 시도 SQL 조회 가능.
    ACCESS_DENIED,

    // ── EMA ELISE 제출 추적 (ema-submission-tracking-spec.md §3 전이표 T1~T10) ──
    // 모든 전이는 actor userSeq + actor role(LEW 본인 vs ADMIN/SYSTEM_ADMIN 대행)을 metadata 로 기록해
    // 사후 구분 가능하게 한다(§3.2). emaQueryNote/접수번호 등 옛 사유는 재제출 시 컬럼에서 클리어되지만
    // 전체 이력은 아래 감사 액션으로 무손실 추적된다(허점#4/#5).
    EMA_SUBMITTED,            // T1: NOT_SUBMITTED → SUBMITTED
    EMA_QUERY_RAISED,         // T2/T4: SUBMITTED/RESUBMITTED → QUERY_RAISED
    EMA_RESUBMITTED,          // T3/T10: QUERY_RAISED/REJECTED → RESUBMITTED
    EMA_APPROVED,             // T5/T6: SUBMITTED/RESUBMITTED → APPROVED
    EMA_REJECTED,             // T7: SUBMITTED/RESUBMITTED → REJECTED (종착 아님, T10 재진입)
    EMA_WITHDRAWN,            // T8: SUBMITTED/QUERY_RAISED/RESUBMITTED → WITHDRAWN
    EMA_DECISION_REVERTED     // T9: APPROVED/WITHDRAWN → 직전 상태 복원 (ADMIN 전용 오기입 정정)
}
