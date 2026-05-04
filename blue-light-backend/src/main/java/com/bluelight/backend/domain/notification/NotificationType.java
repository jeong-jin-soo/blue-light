package com.bluelight.backend.domain.notification;

/**
 * 알림 유형
 *
 * VARCHAR 기반 저장이므로 enum 값 추가는 기존 데이터와 호환된다.
 */
public enum NotificationType {
    PAYMENT_CONFIRMED,
    /**
     * PR4: ADMIN이 결제를 확인하면 배정된 LEW에게 발송되는 인앱 알림.
     * Phase 2(SLD/LOA/CoF) 시작 시점을 LEW가 명시적으로 인지하도록 분리된 신규 타입.
     * (기존 PAYMENT_CONFIRMED 는 신청자 채널과 legacy 호환을 위해 보존)
     */
    PAYMENT_CONFIRMED_LEW,

    // Phase 3 PR#1 — LEW 서류 요청 워크플로 인앱 알림
    DOCUMENT_REQUEST_CREATED,
    DOCUMENT_REQUEST_FULFILLED,
    DOCUMENT_REQUEST_APPROVED,
    DOCUMENT_REQUEST_REJECTED,

    // Phase 5 — LEW kVA 확정 알림 (이메일은 범위 외)
    KVA_CONFIRMED,

    // Phase 6 — 통합 LEW 리뷰 (CoF finalize 및 kVA override 재발급)
    CERTIFICATE_OF_FITNESS_FINALIZED,   // 신청자: CoF 서명 완료 → 결제 단계 진입 안내
    COF_REISSUED_BY_KVA_OVERRIDE,       // LEW/신청자: kVA override로 CoF 재서명 필요

    // PR-2 (kva-postpayment-adjustment-spec §5.4) — 결제 후 ADMIN 의 kVA 변경 → 배정 LEW 통지.
    // CoF re-issue 가 동반되더라도 본 알림 한 건에 통합 메시지 포함 (사용자 인지 부담 최소화).
    KVA_ADJUSTED_BY_ADMIN_LEW,
    // PR-3 (kva-postpayment-adjustment-spec §4.2) — LEW 가 결제 후 kVA 변경을 ADMIN 에게 요청.
    // ADMIN/SYSTEM_ADMIN 역할 사용자에게 인앱 알림 + 이메일 발송. 클릭 시 /admin/applications/{seq}.
    KVA_ADJUSTMENT_REQUESTED_ADMIN,
    // PR-4 (kva-postpayment-adjustment-spec §8 PR-4) — ADMIN 이 settlement 를 마킹한 직후 배정 LEW 통지.
    // 본 알림은 ADMIN 모달에서 notifyLew=true 로 체크된 경우에만 발행된다 (기본값 true).
    // 클릭 시 /lew/applications/{seq} 로 라우팅 — Subject 는 PDPA 최소화를 위해 금액 미포함.
    KVA_ADJUSTMENT_SETTLED_LEW,

    // PR-4 (admin-manual-email-spec.md §8.5 / D4=B) — ADMIN 이 수동 이메일을 발송할 때
    // 시스템 사용자 수신자(APPLICANT/LEW)에게 동반 생성되는 인앱 알림. 옵션 체크박스 기본 ON,
    // EXTERNAL 수신자에게는 발송되지 않는다 (시스템 계정이 없음). referenceType=APPLICATION
    // (relatedApplicationSeq 가 있을 때) 또는 MANUAL_EMAIL (없을 때) 로 라우팅 키를 분기한다.
    ADMIN_MANUAL_EMAIL_NOTICE,

    // Phase 1 — Kaki Concierge Service (v1.5)
    CONCIERGE_REQUEST_SUBMITTED,              // N1/N2: 신청 접수 시 신청자/관리자
    CONCIERGE_REQUEST_ASSIGNED,               // N3: 담당자 배정 시 담당자
    CONCIERGE_ACCOUNT_SETUP_LINK_SENT,        // N-Activation: 계정 설정 링크 발송
    CONCIERGE_LOA_SIGN_REQUIRED,              // N5: LOA 서명 요청 (신청자)
    CONCIERGE_LOA_UPLOAD_CONFIRM,             // N5-UploadConfirm: 대리 업로드 확인 (7일 이의 제기)
    CONCIERGE_LICENCE_PAYMENT_REQUIRED,       // N6b: 라이선스 결제 요청 (신청자)
    CONCIERGE_COMPLETED,                      // N7: 컨시어지 프로세스 완료
    CONCIERGE_CANCELLED,                      // N8: 취소 통보
    CONCIERGE_SLA_BREACH_WARNING              // N9: 24h SLA 위반 경고 (Admin)
}
