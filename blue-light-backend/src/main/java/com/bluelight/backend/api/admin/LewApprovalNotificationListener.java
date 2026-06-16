package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * LEW 가입 승인/거절 직후 인앱 알림 + 이메일 발송 리스너.
 *
 * <p>{@link LewApprovalDecisionEvent} 를 수신해 해당 LEW 본인에게 통지한다.
 * ({@code LewAssignmentNotificationListener} 와 동일한 패턴.)</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 승인/거절 트랜잭션이 커밋된 뒤에만 변경이 실제 반영되며, SMTP/NotificationService 일시 오류가
 * 승인 자체를 롤백시키면 안 되기 때문이다.
 *
 * <h3>실패 격리</h3>
 * 인앱/이메일 각 단계를 독립 try/catch 로 감싸 한 채널의 실패가 다른 채널이나 트랜잭션에
 * 전파되지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LewApprovalNotificationListener {

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLewApprovalDecision(LewApprovalDecisionEvent event) {
        Long lewSeq = event.getLewUserSeq();
        User lew;
        try {
            lew = userRepository.findById(lewSeq).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("LEW approval notification — failed to load LEW: lewSeq={}, err={}",
                    lewSeq, ex.getMessage());
            return;
        }
        if (lew == null) {
            log.warn("LEW approval notification — LEW user not found: lewSeq={}", lewSeq);
            return;
        }

        boolean approved = event.isApproved();

        // 1) 인앱 알림 (account-level → referenceType/referenceId = null).
        try {
            if (approved) {
                notificationService.createNotification(
                        lewSeq,
                        NotificationType.LEW_APPROVED,
                        "Your LEW registration is approved",
                        "Welcome aboard. You can now sign in and start managing applications as a Licensed Electrical Worker.",
                        null, null);
            } else {
                notificationService.createNotification(
                        lewSeq,
                        NotificationType.LEW_REJECTED,
                        "Your LEW registration was not approved",
                        "Your LEW registration has been reviewed and was not approved at this time. Please contact support if you believe this is a mistake.",
                        null, null);
            }
        } catch (RuntimeException ex) {
            log.warn("LEW approval in-app notification failed: lewSeq={}, approved={}, err={}",
                    lewSeq, approved, ex.getMessage());
        }

        // 2) 이메일.
        try {
            String emailTo = lew.getEmail();
            if (emailTo == null || emailTo.isBlank()) {
                log.warn("LEW approval email skipped — no email: lewSeq={}", lewSeq);
                return;
            }
            String fullName = lew.getFullName();
            if (fullName == null || fullName.isBlank()) fullName = "LEW";
            emailService.sendLewApprovalDecisionEmail(emailTo, fullName, approved);
        } catch (RuntimeException ex) {
            log.warn("LEW approval email failed: lewSeq={}, approved={}, err={}",
                    lewSeq, approved, ex.getMessage());
        }

        log.info("LEW approval notified: lewSeq={}, approved={}", lewSeq, approved);
    }
}
