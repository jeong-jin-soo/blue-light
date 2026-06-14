package com.bluelight.backend.domain.notification;

/**
 * 알림 유형
 *
 * VARCHAR 기반 저장이므로 enum 값 추가는 기존 데이터와 호환된다.
 */
public enum NotificationType {
    PAYMENT_CONFIRMED,
    /** LEW/ADMIN이 결제를 요청하면(→PENDING_PAYMENT) 신청자에게 발송 (A-17, 인앱+이메일). */
    PAYMENT_REQUESTED,
    /** 신청자가 결제 증빙 업로드 → ADMIN 알림 (A-55). */
    PAYMENT_EVIDENCE_UPLOADED,
    /** 신청자가 결제 확인 요청 버튼 → ADMIN 알림 (A-56). */
    PAYMENT_CONFIRMATION_REQUESTED,
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
    CONCIERGE_QUOTE_SENT,                     // A-33: 견적(수수료/PayNow) 발송 (신청자) — PR-W3a
    CONCIERGE_LOA_UPLOAD_CONFIRM,             // N5-UploadConfirm: 대리 업로드 확인 (7일 이의 제기)
    CONCIERGE_LICENCE_PAYMENT_REQUIRED,       // N6b: 라이선스 결제 요청 (신청자)
    CONCIERGE_COMPLETED,                      // N7: 컨시어지 프로세스 완료
    CONCIERGE_CANCELLED,                      // N8: 취소 통보
    CONCIERGE_SLA_BREACH_WARNING,             // N9: 24h SLA 위반 경고 (Admin)

    // ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 — PR-1 placeholder, PR-2/3 에서 발화.
    // (PR-2) ADMIN 이 별도 수금(offline)을 수동 기록한 직후 신청자에게 결제 확인 인앱 알림.
    MANUAL_PAYMENT_CONFIRMED_APPLICANT,
    // (PR-2) 별도 수금 기록 직후 자동 발행된 영수증을 신청자에게 안내.
    INVOICE_ISSUED_APPLICANT,
    // (PR-3) LEW 가 ConciergeRequest 에 셀프/타인 배정될 때 해당 LEW 에게 발송.
    CONCIERGE_LEW_ASSIGNED_LEW,

    // LEW 가 Application 에 배정될 때(자동 단일 적격 배정 또는 ADMIN 수동 배정) 해당 LEW 에게 발송.
    // 기존엔 ADMIN 수동 경로만 이메일을 보냈고 자동 경로는 무알림이었던 누락을 보완 — 두 경로를
    // LewAssignedEvent → LewAssignmentNotificationListener 단일 흐름으로 통일. referenceType=APPLICATION.
    APPLICATION_LEW_ASSIGNED_LEW,

    // ── EMA 제출 추적 (ema-submission-tracking-spec.md §10) — IN_APP 1차 (허점#3 방향 a) ──
    // SUBMITTED/RESUBMITTED 후 ema.reminder.days 무변동 건을 담당 LEW 에게 리마인드.
    // 스케줄러(EmaReminderScheduler)가 1일 1회 멱등 발행. referenceType=APPLICATION.
    EMA_SUBMISSION_REMINDER_LEW,
    // reject(T7) 성공 시 담당 LEW 에게 "반려됨 — 사유 반영 후 재제출" 통지. 신청자에게는 비노출(US-C1).
    // EmaRejectedEvent → EmaRejectedNotificationListener (AFTER_COMMIT). referenceType=APPLICATION.
    EMA_REJECTED_LEW
}
