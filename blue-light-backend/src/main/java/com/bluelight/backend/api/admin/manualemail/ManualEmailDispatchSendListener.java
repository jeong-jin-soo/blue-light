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

import java.util.Optional;

/**
 * ADMIN 수동 이메일 발송 — AFTER_COMMIT SMTP 디스패처.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8.1, §8.7.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 비즈니스 트랜잭션({@code ManualEmailDispatcher.dispatch})의 본질은 row 저장 + audit 기록이며,
 * SMTP 발송은 부수 효과다. SMTP 장애가 row 저장을 롤백시키면 운영 재현 시 어떤 ADMIN 이 어떤
 * 시도를 했는지 추적이 사라진다 — PENDING row 라도 항상 보존되어야 한다 (PR-2 의 KvaOverride
 * 알림 리스너와 동일 원칙).
 *
 * <h3>책임</h3>
 * <ol>
 *   <li>이벤트 수신 → {@code dispatchSeq} 로 row 재조회.</li>
 *   <li>발송 ADMIN 의 이메일을 {@link UserRepository} 에서 lookup → 자동 푸터 신원 라인에 사용.</li>
 *   <li>{@link EmailService#sendManualPlainTextEmail} 호출.</li>
 *   <li>성공 → {@link ManualEmailDispatch#markSent}, 실패 → {@link ManualEmailDispatch#markFailed}.</li>
 * </ol>
 *
 * <h3>실패 격리</h3>
 * 모든 분기를 try/catch 로 감싼다. AFTER_COMMIT 이므로 어떤 예외도 비즈니스 결과를 바꾸지 않지만,
 * 호출자(이벤트 디스패처) 입장의 예측 가능성을 위한 방어. row.status 갱신은 별도 트랜잭션
 * ({@code REQUIRES_NEW}) 으로 보장 — 발송 후 status 는 항상 SENT/FAILED 중 하나가 되어야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualEmailDispatchSendListener {

    private final ManualEmailDispatchRepository dispatchRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    /**
     * row mutation 은 별도 빈에 위임 — Spring AOP 의 self-invocation 제약 회피.
     * (같은 클래스 내 {@code @Transactional} 메서드 호출은 프록시를 통과하지 않아 트랜잭션이
     * 적용되지 않는다.)
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

            // 3) SMTP 호출 + status 갱신 (별도 트랜잭션, statusUpdater 빈 위임).
            try {
                emailService.sendManualPlainTextEmail(
                        row.getRecipientEmail(),
                        row.getSubject(),
                        row.getBodyText(),
                        adminEmail);
                statusUpdater.markSent(dispatchSeq);
                log.info("Manual email sent: dispatchSeq={}, to={}, adminSeq={}",
                        dispatchSeq, row.getRecipientEmail(), row.getSenderUserSeq());
            } catch (RuntimeException smtpEx) {
                String reason = smtpEx.getMessage() == null ? "Unknown SMTP error" : smtpEx.getMessage();
                statusUpdater.markFailed(dispatchSeq, reason);
                log.warn("Manual email SMTP failed: dispatchSeq={}, to={}, reason={}",
                        dispatchSeq, row.getRecipientEmail(), reason);
            }
        } catch (RuntimeException ex) {
            // 트랜잭션이 이미 커밋되어 결과를 바꿀 수 없으므로 단순 ERROR 로깅.
            log.error("ManualEmailDispatchSendListener failure: dispatchSeq={}, err={}",
                    dispatchSeq, ex.getMessage(), ex);
        }
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
}
