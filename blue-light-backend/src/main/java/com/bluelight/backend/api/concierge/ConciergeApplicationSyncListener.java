package com.bluelight.backend.api.concierge;

import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Application 상태 전이를 ConciergeRequest 상태에 동기화하는 이벤트 리스너
 * (★ Kaki Concierge v1.5 Phase 1 PR#7, PRD §5 상태 머신).
 * <p>
 * 전이 매핑:
 * <ul>
 *   <li>Application PAID/IN_PROGRESS → ConciergeRequest AWAITING_LICENCE_PAYMENT → IN_PROGRESS
 *       ({@code markLicencePaid})</li>
 *   <li>Application COMPLETED → ConciergeRequest IN_PROGRESS → COMPLETED
 *       ({@code markCompleted})</li>
 *   <li>그 외(REVISION_REQUESTED, PENDING_REVIEW, EXPIRED, PENDING_PAYMENT) → 무시</li>
 * </ul>
 * <p>
 * {@code BEFORE_COMMIT} 페이즈로 동일 트랜잭션에서 일관성 보장. ConciergeRequest의 도메인 가드가
 * 잘못된 전이를 거부하면 로그만 남기고 무시 (Application 상태 전이는 이미 성공했으므로 롤백 대신
 * 경고 처리가 더 적절).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConciergeApplicationSyncListener {

    private final ConciergeRequestRepository conciergeRequestRepository;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onApplicationStatusChanged(ApplicationStatusChangedEvent event) {
        if (event.getViaConciergeRequestSeq() == null) {
            // Concierge 경로가 아닌 일반 신청 — 동기화 대상 아님
            return;
        }

        ConciergeRequest cr = conciergeRequestRepository
            .findById(event.getViaConciergeRequestSeq())
            .orElse(null);
        if (cr == null) {
            log.warn("Concierge sync skipped: ConciergeRequest #{} not found for application #{}",
                event.getViaConciergeRequestSeq(), event.getApplicationSeq());
            return;
        }

        ApplicationStatus newStatus = event.getNewStatus();
        try {
            switch (newStatus) {
                case PAID, IN_PROGRESS -> {
                    if (cr.getStatus() == ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT) {
                        cr.markLicencePaid();
                        log.info("Concierge sync: CR#{} AWAITING_LICENCE_PAYMENT → IN_PROGRESS (app#{} → {})",
                            cr.getConciergeRequestSeq(), event.getApplicationSeq(), newStatus);
                    }
                }
                case COMPLETED -> {
                    if (cr.getStatus() == ConciergeRequestStatus.IN_PROGRESS) {
                        cr.markCompleted();
                        log.info("Concierge sync: CR#{} IN_PROGRESS → COMPLETED (app#{})",
                            cr.getConciergeRequestSeq(), event.getApplicationSeq());
                    }
                }
                default -> {
                    // REVISION_REQUESTED / PENDING_REVIEW / PENDING_PAYMENT / EXPIRED 등 무시
                }
            }
        } catch (IllegalStateException e) {
            // ConciergeRequest 도메인 가드 위반 — Application 전이는 이미 성공했으므로 경고만
            log.warn("Concierge sync transition rejected: appSeq={}, newStatus={}, crStatus={}, err={}",
                event.getApplicationSeq(), newStatus, cr.getStatus(), e.getMessage());
        }
    }
}
