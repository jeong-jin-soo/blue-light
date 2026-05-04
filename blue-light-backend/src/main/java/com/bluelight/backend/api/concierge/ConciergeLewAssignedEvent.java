package com.bluelight.backend.api.concierge;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ★ Concierge 강화 + 별도 수금 PR-3 — LEW 배정/재배정 직후 발행되는 도메인 이벤트.
 *
 * <p>스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §5 / §14 PR-3.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 본 트랜잭션의 본질은 ConciergeRequest.assignedLewSeq + status 전이 + audit 기록이며, 알림 발송은
 * 부수 효과다. SMTP/외부 서비스 일시 오류가 LEW 배정 트랜잭션을 롤백시켜선 안 된다 (kVA PR-3
 * {@code LewKvaAdjustmentRequestedEvent} 와 동일 원칙).
 *
 * <h3>본 이벤트가 트리거하는 후속 작업</h3>
 * <ol>
 *   <li>새 LEW 에게 인앱 알림 ({@link com.bluelight.backend.domain.notification.NotificationType#CONCIERGE_LEW_ASSIGNED_LEW})
 *       + 이메일 발송. {@code selfAssigned=true} 면 음소거(중복 방지) — 스펙 §3 S2.</li>
 *   <li>{@code previousLewUserSeq} 가 non-null 이면 이전 LEW 에게 unassign 알림 (동일 NotificationType,
 *       메시지 분기) — 스펙 §10 AC-L4.</li>
 * </ol>
 *
 * @param conciergeRequestSeq  대상 ConciergeRequest PK
 * @param publicCode           C-YYYY-NNNN 공개 코드 (이메일 본문 표시)
 * @param newLewUserSeq        새로 배정된 LEW user_seq
 * @param previousLewUserSeq   재할당 직전 LEW user_seq (최초 배정이면 null)
 * @param assignedByUserSeq    배정을 수행한 actor (CONCIERGE_MANAGER 또는 ADMIN)
 * @param selfAssigned         actor 가 본인을 LEW 로 셀프 할당했는지 (D6=A)
 * @param submitterName        신청자 이름 (이메일 본문 — escape 후 사용)
 * @param submitterEmail       신청자 이메일 (LEW 가 연락할 수 있도록 표기)
 * @param submitterPhone       신청자 전화번호 (LEW 표기용)
 * @param memo                 컨시어지 폼에서 받은 메모 (LEW 가 사전 컨텍스트 파악)
 */
@Getter
@RequiredArgsConstructor
public class ConciergeLewAssignedEvent {
    private final Long conciergeRequestSeq;
    private final String publicCode;
    private final Long newLewUserSeq;
    private final Long previousLewUserSeq;
    private final Long assignedByUserSeq;
    private final boolean selfAssigned;
    private final String submitterName;
    private final String submitterEmail;
    private final String submitterPhone;
    private final String memo;
}
