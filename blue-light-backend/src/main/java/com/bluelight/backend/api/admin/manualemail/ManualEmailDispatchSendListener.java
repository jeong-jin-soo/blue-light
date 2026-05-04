package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatchRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADMIN 수동 이메일 발송 — AFTER_COMMIT SMTP 디스패처.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8.1, §8.7.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 비즈니스 트랜잭션({@code ManualEmailDispatcher.dispatch})의 본질은 row 저장 + audit 기록이며,
 * SMTP 발송은 부수 효과다. SMTP 장애가 row 저장을 롤백시키면 운영 재현 시 어떤 ADMIN 이 어떤
 * 시도를 했는지 추적이 사라진다 — PENDING row 라도 항상 보존되어야 한다.
 *
 * <h3>책임 (PR-2 확장)</h3>
 * <ol>
 *   <li>이벤트 수신 → {@code dispatchSeq} 로 row 재조회.</li>
 *   <li>발송 ADMIN 의 이메일을 {@link UserRepository} 에서 lookup → 자동 푸터 신원 라인에 사용.</li>
 *   <li>row 의 {@code resolveAllRecipientEmails()} 로 전체 수신자 리스트 확보 (단일/다수 통합).</li>
 *   <li>{@link #CHUNK_SIZE} 건씩 청크로 분할 → 각 청크 내에서 순차 SMTP 호출 → 청크 사이 {@link
 *       #CHUNK_DELAY_MS}ms sleep (D7=B 쓰로틀, SMTP rate limit 보호).</li>
 *   <li>각 수신자별 try/catch → 성공 카운트 / 실패 카운트 + {@code email: reason} 형식 멀티라인 누적.</li>
 *   <li>모든 수신자 처리 후 {@link ManualEmailDispatchStatusUpdater#markBatchResult} 로 status
 *       (SENT/PARTIAL_FAILED/FAILED) + sentCount/failedCount/failedReason 일괄 저장.</li>
 * </ol>
 *
 * <h3>실패 격리</h3>
 * 모든 분기를 try/catch 로 감싼다. AFTER_COMMIT 이므로 어떤 예외도 비즈니스 결과를 바꾸지 않지만,
 * 호출자 입장의 예측 가능성을 위한 방어. row mutation 은 별도 트랜잭션({@code REQUIRES_NEW}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualEmailDispatchSendListener {

    /**
     * SMTP 청크 크기 — 5건씩 묶어 sleep 사이에 발송 (D7=B). 한 번에 너무 많은 SMTP 호출이
     * 동기적으로 발생하면 외부 SMTP 의 rate limit 에 걸릴 수 있어 보수적으로 설정. PR-4 에서
     * {@code system_settings} 외부화 가능.
     */
    static final int CHUNK_SIZE = 5;

    /** 청크 사이 sleep 시간 (ms). 5건당 100ms → 100건 발송 시 약 2초 소요 — UX 허용 범위. */
    static final long CHUNK_DELAY_MS = 100L;

    /** failedReason 컬럼은 TEXT 이지만 너무 긴 누적 메시지 회피. 멀티라인 누적 cap. */
    static final int FAILED_REASON_AGGREGATE_MAX = 4000;

    private final ManualEmailDispatchRepository dispatchRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    /**
     * row mutation 은 별도 빈에 위임 — Spring AOP 의 self-invocation 제약 회피.
     */
    private final ManualEmailDispatchStatusUpdater statusUpdater;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDispatchRequested(ManualEmailDispatchRequestedEvent event) {
        Long dispatchSeq = event.getDispatchSeq();
        try {
            // 1) row 재조회 — 본 리스너는 이메일 본문/수신자를 events 가 아닌 DB 에서 읽는다.
            //    이미 PENDING 으로 저장된 row 가 단일 정본.
            Optional<ManualEmailDispatch> rowOpt = dispatchRepository.findById(dispatchSeq);
            if (rowOpt.isEmpty()) {
                log.error("Manual email dispatch row missing — should never happen: dispatchSeq={}", dispatchSeq);
                return;
            }
            ManualEmailDispatch row = rowOpt.get();

            // 2) 발송 ADMIN 이메일 lookup → 자동 푸터에 사용. 미존재 시 placeholder 로 fallback.
            String adminEmail = lookupAdminEmail(row.getSenderUserSeq());

            // 3) 수신자 리스트 — 단일/다수 통합. 빈 리스트면 비정상 row 이므로 FAILED 마킹.
            List<String> recipients = row.resolveAllRecipientEmails();
            if (recipients.isEmpty()) {
                log.error("Manual email dispatch has no recipients: dispatchSeq={}", dispatchSeq);
                statusUpdater.markBatchResult(dispatchSeq, 0, 1, "No recipients resolved from row");
                return;
            }

            // 4) 청크 분할 + 쓰로틀 + 수신자별 try/catch.
            BatchOutcome outcome = sendInChunks(dispatchSeq, recipients, row, adminEmail);

            // 5) 결과 일괄 저장 (단일 트랜잭션 — REQUIRES_NEW).
            statusUpdater.markBatchResult(
                    dispatchSeq,
                    outcome.sentCount,
                    outcome.failedCount,
                    outcome.failedReasonJoined());
            log.info("Manual email batch finished: dispatchSeq={}, sent={}, failed={}, total={}",
                    dispatchSeq, outcome.sentCount, outcome.failedCount, recipients.size());

        } catch (RuntimeException ex) {
            // 트랜잭션이 이미 커밋되어 결과를 바꿀 수 없으므로 단순 ERROR 로깅.
            log.error("ManualEmailDispatchSendListener failure: dispatchSeq={}, err={}",
                    dispatchSeq, ex.getMessage(), ex);
        }
    }

    /**
     * 청크 단위 SMTP 발송 + 쓰로틀.
     *
     * <p>청크 사이 sleep 은 {@link Thread#sleep} 으로 단순 구현 — AFTER_COMMIT 워커 스레드에서
     * 실행되므로 사용자 응답을 블로킹하지 않는다. 인터럽트 발생 시 즉시 중단하고 남은 수신자는
     * 실패 처리하지 않는다 (재시도는 운영자가 History 에서 수동 재발송).</p>
     */
    private BatchOutcome sendInChunks(Long dispatchSeq,
                                      List<String> recipients,
                                      ManualEmailDispatch row,
                                      String adminEmail) {
        BatchOutcome outcome = new BatchOutcome();
        int total = recipients.size();
        int chunkIndex = 0;

        for (int from = 0; from < total; from += CHUNK_SIZE) {
            int to = Math.min(from + CHUNK_SIZE, total);
            List<String> chunk = recipients.subList(from, to);
            chunkIndex++;

            // 첫 번째 청크 이전에는 sleep 없음 — 두 번째 청크부터 청크간 delay.
            if (from > 0) {
                try {
                    Thread.sleep(CHUNK_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Manual email batch interrupted between chunks: dispatchSeq={}, processed={}/{}",
                            dispatchSeq, from, total);
                    return outcome;
                }
            }

            for (String recipientEmail : chunk) {
                try {
                    emailService.sendManualPlainTextEmail(
                            recipientEmail,
                            row.getSubject(),
                            row.getBodyText(),
                            adminEmail);
                    outcome.sentCount++;
                    log.debug("Manual email sent: dispatchSeq={}, to={}", dispatchSeq, recipientEmail);
                } catch (RuntimeException smtpEx) {
                    outcome.failedCount++;
                    String reason = smtpEx.getMessage() == null ? "Unknown SMTP error" : smtpEx.getMessage();
                    outcome.failedLines.add(recipientEmail + ": " + reason);
                    log.warn("Manual email SMTP failed: dispatchSeq={}, to={}, reason={}",
                            dispatchSeq, recipientEmail, reason);
                }
            }

            log.debug("Manual email chunk {} done: dispatchSeq={}, range=[{},{})",
                    chunkIndex, dispatchSeq, from, to);
        }
        return outcome;
    }

    /**
     * 발송 ADMIN 이메일 — 미존재 시 빈 문자열 반환 (자동 푸터에서 빈 표기). audit 로그가 이미 발송
     * 시도를 기록하고 있으므로 운영 추적은 가능.
     */
    private String lookupAdminEmail(Long senderUserSeq) {
        if (senderUserSeq == null) return "";
        try {
            return userRepository.findById(senderUserSeq)
                    .map(User::getEmail)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse("");
        } catch (RuntimeException ex) {
            log.warn("Admin email lookup failed: senderSeq={}, err={}", senderUserSeq, ex.getMessage());
            return "";
        }
    }

    /** loop 결과를 누적하기 위한 mutable 컨테이너 (private 사용). */
    private static final class BatchOutcome {
        int sentCount;
        int failedCount;
        final List<String> failedLines = new ArrayList<>();

        String failedReasonJoined() {
            if (failedLines.isEmpty()) return null;
            String joined = String.join("\n", failedLines);
            if (joined.length() > FAILED_REASON_AGGREGATE_MAX) {
                return joined.substring(0, FAILED_REASON_AGGREGATE_MAX) + "…(truncated)";
            }
            return joined;
        }
    }
}
