package com.bluelight.backend.api.admin.notification.template.dto;

/**
 * Test-send 응답 — outbox row seq (감사·추적 용도) + 일일 사용량.
 *
 * @param outboxSeq     적재된 outbox row 의 PK (admin 이 발송 상태 추적 가능)
 * @param dailyQuotaUsed  당일 사용량 (50 이내 권장 — Daily quota)
 * @param dailyQuotaMax   당일 최대 (50)
 */
public record TemplateTestSendResponse(
        Long outboxSeq,
        int dailyQuotaUsed,
        int dailyQuotaMax
) {
}
