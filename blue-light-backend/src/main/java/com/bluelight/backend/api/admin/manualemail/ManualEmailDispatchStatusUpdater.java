package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.domain.manualemail.ManualEmailDispatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * {@link ManualEmailDispatchSendListener} 가 호출하는 row mutation 헬퍼 — 별도 빈으로 분리한 이유는
 * Spring AOP 의 self-invocation 제약(같은 클래스 내 {@code @Transactional} 메서드 호출은 프록시를
 * 통과하지 않아 트랜잭션이 적용되지 않음) 을 회피하기 위함이다.
 *
 * <p>{@code REQUIRES_NEW} 로 분리된 트랜잭션을 사용 — AFTER_COMMIT 단계에서 호출되어도 새 트랜잭션이
 * 열리며 row 의 status/sentCount 갱신이 영속화된다.</p>
 */
@Component
@RequiredArgsConstructor
public class ManualEmailDispatchStatusUpdater {

    /** failed_reason 컬럼은 TEXT 이지만 너무 긴 스택트레이스 저장은 회피 — 1000자 cap. */
    private static final int FAILED_REASON_MAX = 1000;

    private final ManualEmailDispatchRepository dispatchRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(Long dispatchSeq) {
        dispatchRepository.findById(dispatchSeq)
                .ifPresent(r -> r.markSent(LocalDateTime.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long dispatchSeq, String reason) {
        String truncated = reason == null
                ? "Unknown SMTP error"
                : (reason.length() > FAILED_REASON_MAX
                    ? reason.substring(0, FAILED_REASON_MAX) + "…(truncated)"
                    : reason);
        dispatchRepository.findById(dispatchSeq)
                .ifPresent(r -> r.markFailed(LocalDateTime.now(), truncated));
    }

    /**
     * PR-2 MULTI 발송 결과 일괄 마킹.
     *
     * <p>스펙: AC-A4 부분 실패. listener 가 모든 수신자 처리 후 한 번만 호출 — status 결정 규칙은
     * {@code ManualEmailDispatch#markBatchResult} 에 위임.</p>
     *
     * @param dispatchSeq    row PK
     * @param sentCount      성공 수
     * @param failedCount    실패 수
     * @param failedReason   실패 사유 멀티라인 (모두 성공이면 null)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBatchResult(Long dispatchSeq, int sentCount, int failedCount, String failedReason) {
        // failed_reason 컬럼 cap — listener 에서도 cap 하지만 안전망으로 한 번 더 잘라낸다.
        String safeReason = failedReason;
        if (safeReason != null && safeReason.length() > FAILED_REASON_MAX) {
            safeReason = safeReason.substring(0, FAILED_REASON_MAX) + "…(truncated)";
        }
        final String finalReason = safeReason;
        dispatchRepository.findById(dispatchSeq)
                .ifPresent(r -> r.markBatchResult(LocalDateTime.now(), sentCount, failedCount, finalReason));
    }
}
