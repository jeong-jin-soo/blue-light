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
 * Application LEW 배정 직후 인앱 알림 + 이메일 발송 리스너.
 *
 * <p>{@link LewAssignedEvent} 를 수신해 자동/수동 배정 두 경로 모두에서 배정된 LEW 에게
 * 동일하게 통보한다. ({@code ConciergeLewAssignmentNotificationListener} 와 같은 패턴.)</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 배정 트랜잭션이 커밋된 뒤에만 LEW 가 실제로 조회 가능한 상태가 되며, SMTP/NotificationService
 * 일시 오류가 배정 자체를 롤백시키면 안 되기 때문이다.
 *
 * <h3>실패 격리</h3>
 * 인앱/이메일 각 단계를 독립 try/catch 로 감싸 한 채널의 실패가 다른 채널이나 배정 트랜잭션에
 * 전파되지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LewAssignmentNotificationListener {

    /** 인앱 알림 referenceType (NotificationsPage 라우팅 키 — LEW 워크스페이스 신청 상세) */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLewAssigned(LewAssignedEvent event) {
        Long lewSeq = event.getLewUserSeq();
        User lew;
        try {
            lew = userRepository.findById(lewSeq).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("LEW assignment notification — failed to load LEW: lewSeq={}, err={}",
                    lewSeq, ex.getMessage());
            return;
        }
        if (lew == null) {
            log.warn("LEW assignment notification — LEW user not found: lewSeq={}", lewSeq);
            return;
        }

        // 1) 인앱 알림.
        try {
            String title = event.isReassigned()
                    ? "Application reassigned to you"
                    : "New application assigned to you";
            String body = "Application #" + event.getApplicationSeq()
                    + " — applicant " + safe(event.getApplicantName())
                    + (event.isReassigned() ? " (reassigned — work may already be in progress)" : "");
            notificationService.createNotification(
                    lewSeq,
                    NotificationType.APPLICATION_LEW_ASSIGNED_LEW,
                    title,
                    body,
                    REFERENCE_TYPE_APPLICATION, event.getApplicationSeq());
        } catch (RuntimeException ex) {
            log.warn("LEW assignment in-app notification failed: lewSeq={}, applicationSeq={}, err={}",
                    lewSeq, event.getApplicationSeq(), ex.getMessage());
        }

        // 2) 이메일.
        try {
            String emailTo = lew.getEmail();
            if (emailTo == null || emailTo.isBlank()) {
                log.warn("LEW assignment email skipped — no email: lewSeq={}", lewSeq);
                return;
            }
            String fullName = lew.getFullName();
            if (fullName == null || fullName.isBlank()) fullName = "LEW";
            emailService.sendLewAssignedEmail(
                    emailTo, fullName,
                    event.getApplicationSeq(), event.getAddress(), safe(event.getApplicantName()));
        } catch (RuntimeException ex) {
            log.warn("LEW assignment email failed: lewSeq={}, applicationSeq={}, err={}",
                    lewSeq, event.getApplicationSeq(), ex.getMessage());
        }

        log.info("LEW assignment notified: applicationSeq={}, lewSeq={}, autoAssigned={}",
                event.getApplicationSeq(), lewSeq, event.isAutoAssigned());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
