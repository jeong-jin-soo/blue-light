package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.domain.notification.NotificationCategory;

/**
 * H-S3 — SECURITY/PAYMENT 카테고리 템플릿이 disable 된 직후 발행되는 Spring 이벤트.
 *
 * <p>리스너가 Slack/email 로 보안팀에 자동 통지한다 (PR-T2 는 stub 로그 리스너).
 * 침해 은폐 시도(예: A-04 비번 변경 통보를 끄는 행위) 를 보안팀이 즉시 감지하기 위함.</p>
 *
 * @param templateSeq      대상 템플릿
 * @param templateCode     예: A-04
 * @param category         SECURITY 또는 PAYMENT 일 때만 발행됨
 * @param actorUserSeq     disable 한 SYSTEM_ADMIN 의 user_seq
 * @param actorIp          요청 IP (감사 증거)
 * @param changeReason     필수 사유 (서비스 단에서 검증됨)
 */
public record SecurityTemplateDisableEvent(Long templateSeq,
                                           String templateCode,
                                           NotificationCategory category,
                                           Long actorUserSeq,
                                           String actorIp,
                                           String changeReason) {
}
