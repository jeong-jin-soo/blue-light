package com.bluelight.backend.domain.notification;

/**
 * 알림 유형
 *
 * VARCHAR 기반 저장이므로 enum 값 추가는 기존 데이터와 호환된다.
 */
public enum NotificationType {
    PAYMENT_CONFIRMED,

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
