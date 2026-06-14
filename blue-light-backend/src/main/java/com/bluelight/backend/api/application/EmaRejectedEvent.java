package com.bluelight.backend.api.application;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * EMA 제출이 반려(T7)된 직후 발행되는 도메인 이벤트 (ema-submission-tracking-spec.md §10).
 *
 * <p>담당 LEW 에게 "반려됨 — 사유 반영 후 재제출" 통지를 트리거한다. {@link LewAssignedEvent} 와 동일하게
 * AFTER_COMMIT 리스너({@link EmaRejectedNotificationListener})가 IN_APP 알림을 직접 발행한다(허점#3 방향 a).
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 본 트랜잭션의 본질은 {@code Application.rejectEma()} 상태 전이이며, 알림 발송은 부수 효과다.
 * NotificationService(REQUIRES_NEW) 일시 오류가 반려 전이를 롤백시켜선 안 된다(선례와 동일 원칙).
 *
 * <h3>비노출 정책 (US-C1)</h3>
 * 신청자에게는 EMA 중간/반려 상태를 알리지 않는다 — 본 이벤트의 수신자는 담당 LEW 뿐이다.
 *
 * @param applicationSeq  대상 Application PK (인앱 알림 referenceId + CTA URL)
 * @param lewUserSeq      담당 LEW user_seq (배정 LEW 없으면 null — 리스너가 skip)
 * @param reason          반려 사유 (emaQueryNote, 본문 표시용 — null 가능)
 */
@Getter
@RequiredArgsConstructor
public class EmaRejectedEvent {
    private final Long applicationSeq;
    private final Long lewUserSeq;
    private final String reason;
}
