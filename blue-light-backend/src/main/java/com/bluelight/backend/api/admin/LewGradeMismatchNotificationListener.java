package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationRepository;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * kVA 변경으로 배정 LEW 가 등급 초과(미자격)가 됐을 때, ADMIN/SYSTEM_ADMIN 에게 인앱 경고를 보내는
 * 리스너(#5 — 정책 C: 차단/자동해제 없이 경고+플래그).
 *
 * <p>이메일은 보내지 않는다 — 내부 운영용 재배정 신호이며, 영구 가시 플래그
 * ({@code AdminApplicationResponse.assignedLewGradeMismatch})가 상시 노출을 담당한다.
 * ({@code LewKvaAdjustmentRequestNotificationListener} 의 ADMIN 브로드캐스트 패턴 차용, 인앱만.)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LewGradeMismatchNotificationListener {

    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLewGradeMismatch(LewGradeMismatchEvent event) {
        Long applicationSeq = event.getApplicationSeq();
        try {
            List<User> recipients = userRepository.findByRoleInAndStatus(
                    List.of(UserRole.ADMIN, UserRole.SYSTEM_ADMIN), UserStatus.ACTIVE);
            if (recipients.isEmpty()) {
                log.warn("LEW grade mismatch notification: no ADMIN recipients, applicationSeq={}", applicationSeq);
                return;
            }

            String title = "Assigned LEW under-qualified for application #" + applicationSeq;
            String body = "kVA changed to " + event.getNewKva() + "kVA but assigned LEW grade "
                    + event.getLewGradeName() + " handles up to " + event.getLewMaxKva()
                    + "kVA. Reassign to a higher-grade LEW.";

            for (User admin : recipients) {
                notifyOne(admin.getUserSeq(), applicationSeq, title, body);
            }
            log.info("LEW grade mismatch notified: applicationSeq={}, recipients={}, newKva={}, grade={}",
                    applicationSeq, recipients.size(), event.getNewKva(), event.getLewGradeName());
        } catch (RuntimeException ex) {
            log.error("LewGradeMismatchNotificationListener failed: applicationSeq={}, err={}",
                    applicationSeq, ex.getMessage(), ex);
        }
    }

    private void notifyOne(Long adminSeq, Long applicationSeq, String title, String body) {
        try {
            // 멱등성: 같은 (admin, application, type) 이미 있으면 스킵.
            boolean already = notificationRepository
                    .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                            adminSeq, NotificationType.LEW_GRADE_MISMATCH_ADMIN,
                            REFERENCE_TYPE_APPLICATION, applicationSeq);
            if (already) {
                return;
            }
            notificationService.createNotification(
                    adminSeq, NotificationType.LEW_GRADE_MISMATCH_ADMIN,
                    title, body,
                    REFERENCE_TYPE_APPLICATION, applicationSeq);
        } catch (RuntimeException ex) {
            log.warn("LEW grade mismatch in-app notification failed: adminSeq={}, applicationSeq={}, err={}",
                    adminSeq, applicationSeq, ex.getMessage());
        }
    }
}
