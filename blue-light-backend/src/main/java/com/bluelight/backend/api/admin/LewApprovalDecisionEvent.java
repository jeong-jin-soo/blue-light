package com.bluelight.backend.api.admin;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ADMIN 이 LEW 가입을 승인/거절했을 때 발행되는 이벤트.
 *
 * <p>{@link LewApprovalNotificationListener} 가 AFTER_COMMIT 으로 수신해 해당 LEW 본인에게
 * 인앱 + 이메일 통지한다. ({@code LewAssignedEvent} 와 동일한 이벤트→리스너 패턴.)</p>
 */
@Getter
@RequiredArgsConstructor
public class LewApprovalDecisionEvent {
    private final Long lewUserSeq;
    /** true = 승인(APPROVED), false = 거절(REJECTED) */
    private final boolean approved;
}
