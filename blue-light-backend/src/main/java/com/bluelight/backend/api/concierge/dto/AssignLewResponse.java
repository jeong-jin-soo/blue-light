package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * ★ Concierge 강화 + 별도 수금 PR-3 — LEW 배정 응답 DTO.
 *
 * <p>스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §5.3 / §14 PR-3.</p>
 *
 * <p>{@code previousLewSeq} 가 non-null 이면 재할당으로 간주되고, 이전 LEW 에게는 unassign 알림이 발송된다.
 * {@code selfAssigned=true} 면 D6=A 셀프 할당 케이스 — audit 에 별도 플래그로 기록된다.</p>
 */
@Getter
@Builder
public class AssignLewResponse {

    /** 대상 ConciergeRequest seq */
    private Long conciergeRequestSeq;

    /** 새로 배정된 LEW user_seq */
    private Long assignedLewSeq;

    /** 새로 배정된 LEW 표시 이름 (firstName + lastName) */
    private String assignedLewName;

    /** 새 LEW 배정 시점 */
    private LocalDateTime lewAssignedAt;

    /** 직전에 배정되어 있던 LEW user_seq — 최초 배정이면 null */
    private Long previousLewSeq;

    /** D6=A 셀프 할당 여부 (actor.userSeq == lewUserSeq) */
    private boolean selfAssigned;

    /** 배정 후 ConciergeRequest 상태 (보통 LEW_ASSIGNED) */
    private String status;
}
