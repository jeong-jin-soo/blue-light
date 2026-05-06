package com.bluelight.backend.api.concierge;

import com.bluelight.backend.domain.application.ApplicationStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Application 상태 전이 이벤트 (★ Kaki Concierge v1.5 Phase 1 PR#7).
 * <p>
 * ApplicationService / AdminPaymentService / AdminApplicationService 등에서 Application.status를
 * 변경한 직후 발행한다. {@link ConciergeApplicationSyncListener}가 구독하여 연결된
 * ConciergeRequest의 상태를 자동 전이시킨다.
 * <p>
 * {@code viaConciergeRequestSeq}가 null이면 Concierge 경로가 아니므로 리스너가 즉시 반환.
 * phase=BEFORE_COMMIT으로 처리되어 같은 트랜잭션 내에서 두 엔티티의 상태가 원자적으로 반영된다.
 */
@Getter
@RequiredArgsConstructor
public class ApplicationStatusChangedEvent {

    private final Long applicationSeq;
    /** Concierge 대리 생성인 경우 연결된 ConciergeRequest.seq, 아니면 null */
    private final Long viaConciergeRequestSeq;
    private final ApplicationStatus oldStatus;
    private final ApplicationStatus newStatus;
}
