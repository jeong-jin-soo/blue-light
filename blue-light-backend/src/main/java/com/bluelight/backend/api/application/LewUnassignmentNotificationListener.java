package com.bluelight.backend.api.application;

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
 * Application 배정 해제/재배정 직후, 떠나는 LEW 에게 인앱 알림 + 이메일을 발송하는 리스너.
 *
 * <p>{@link LewUnassignedEvent} 를 수신한다. {@link LewAssignmentNotificationListener}(새 LEW 통지)
 * 와 짝을 이뤄, 재배정 시 양쪽 LEW 가 모두 통지받게 한다(#4 무알림 해소).</p>
 *
 * <p>떠나는 LEW 는 더 이상 신청에 접근할 수 없으므로 인앱 알림에 딥링크를 달지 않는다
 * (referenceType/referenceId = null). 진행 산출물(EMA/LoA/파일)은 보존되어 새 LEW 가 인계한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LewUnassignmentNotificationListener {

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLewUnassigned(LewUnassignedEvent event) {
        Long lewSeq = event.getPreviousLewUserSeq();
        if (lewSeq == null) {
            return;
        }
        User lew;
        try {
            lew = userRepository.findById(lewSeq).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("LEW unassignment notification — failed to load LEW: lewSeq={}, err={}",
                    lewSeq, ex.getMessage());
            return;
        }
        if (lew == null) {
            log.warn("LEW unassignment notification — LEW user not found: lewSeq={}", lewSeq);
            return;
        }

        // 1) 인앱 알림 (account-level → referenceType/referenceId = null, 접근 불가하므로 딥링크 없음).
        try {
            String body = "Application #" + event.getApplicationSeq()
                    + (event.isReassigned()
                        ? " has been reassigned to another LEW and is no longer in your queue."
                        : " is no longer assigned to you.");
            notificationService.createNotification(
                    lewSeq,
                    NotificationType.APPLICATION_LEW_UNASSIGNED_LEW,
                    "Application removed from your queue",
                    body,
                    null, null);
        } catch (RuntimeException ex) {
            log.warn("LEW unassignment in-app notification failed: lewSeq={}, applicationSeq={}, err={}",
                    lewSeq, event.getApplicationSeq(), ex.getMessage());
        }

        // 2) 이메일.
        try {
            String emailTo = lew.getEmail();
            if (emailTo == null || emailTo.isBlank()) {
                log.warn("LEW unassignment email skipped — no email: lewSeq={}", lewSeq);
                return;
            }
            String fullName = lew.getFullName();
            if (fullName == null || fullName.isBlank()) fullName = "LEW";
            emailService.sendLewUnassignedEmail(emailTo, fullName, event.getApplicationSeq(), event.isReassigned());
        } catch (RuntimeException ex) {
            log.warn("LEW unassignment email failed: lewSeq={}, applicationSeq={}, err={}",
                    lewSeq, event.getApplicationSeq(), ex.getMessage());
        }

        log.info("LEW unassignment notified: applicationSeq={}, lewSeq={}, reassigned={}",
                event.getApplicationSeq(), lewSeq, event.isReassigned());
    }
}
