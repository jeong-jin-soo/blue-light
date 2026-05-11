package com.bluelight.backend.domain.notification.whatsapp;

/**
 * WhatsApp 메시지의 배달 상태.
 *
 * <p>Meta WhatsApp Cloud API 의 webhook payload {@code statuses[].status} 값과 매핑된다:
 * {@code accepted/sent} → SENT, {@code delivered} → DELIVERED, {@code read} → READ,
 * {@code failed} → FAILED.</p>
 *
 * <pre>
 *   QUEUED (provider 에 enqueue 직후) → SENT (provider→사용자 발송 완료)
 *                                       → DELIVERED (사용자 단말 수신)
 *                                       → READ (사용자가 메시지 열람)
 *
 *   QUEUED → FAILED (provider 측 거절: 정책/번호 오류/템플릿 미승인 등)
 * </pre>
 */
public enum WhatsappDeliveryStatus {
    /** provider 에 enqueue 직후, 아직 발송 콜백 없음. */
    QUEUED,
    /** provider → 수신 단말로 발송 완료. */
    SENT,
    /** 수신 단말 수신 완료. */
    DELIVERED,
    /** 수신자가 메시지를 열람 (사용자가 read receipt 활성화 시에만 수신). */
    READ,
    /** 발송 실패 — error_code/error_message 컬럼 참조. */
    FAILED
}
