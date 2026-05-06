package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.email.EmailService;
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
 * PR-3: LEW 의 kVA 변경 요청 직후 ADMIN/SYSTEM_ADMIN 사용자에게 인앱 알림 + 이메일을 발송하는 리스너.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.2 / PR-3.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 비즈니스 트랜잭션({@code KvaPostPaymentService.requestAdjustmentByLew})의 본질은 LEW PENDING row
 * 작성 + audit 기록이며, 알림 발송은 부수 효과다. SMTP/외부 서비스 일시 오류가 LEW 의 요청
 * 트랜잭션을 롤백시켜선 안 된다 (PR-2 {@link com.bluelight.backend.api.admin.KvaOverrideNotificationListener}
 * 와 동일 원칙).
 *
 * <h3>책임</h3>
 * <ol>
 *   <li>{@link UserRole#ADMIN} 또는 {@link UserRole#SYSTEM_ADMIN} 역할의 활성 사용자 전체 조회.</li>
 *   <li>각 ADMIN 별 멱등성 가드(같은 application + 동일 type 이미 존재하면 스킵).</li>
 *   <li>인앱 알림 생성 ({@code referenceType=APPLICATION}, {@code referenceId=applicationSeq}).</li>
 *   <li>이메일 발송 — 인앱과 독립 채널.</li>
 * </ol>
 *
 * <p><b>실패 격리</b>: ADMIN 별 try/catch 로 한 사용자의 실패가 다른 사용자에게 전파되지 않게 한다.
 * 또한 리스너 최외곽 try/catch 로 RuntimeException 이 호출자(이벤트 디스패처)로 새어나가지 않게 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LewKvaAdjustmentRequestNotificationListener {

    /** 인앱 알림 referenceType (NotificationsPage 라우팅 키) */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLewKvaAdjustmentRequested(LewKvaAdjustmentRequestedEvent event) {
        Long applicationSeq = event.getApplicationSeq();
        try {
            // ADMIN/SYSTEM_ADMIN 활성 사용자 전체 조회. SYSTEM_ADMIN 은 운영 정책상 ADMIN 권한을 모두 보유 — 동일 알림 수신.
            List<User> recipients = userRepository.findByRoleInAndStatus(
                    List.of(UserRole.ADMIN, UserRole.SYSTEM_ADMIN), UserStatus.ACTIVE);
            if (recipients.isEmpty()) {
                log.warn("LEW kVA adjustment notification: no ADMIN/SYSTEM_ADMIN active recipients found, applicationSeq={}",
                        applicationSeq);
                return;
            }

            String title = "kVA adjustment requested for application #" + applicationSeq;
            String body = buildInAppBody(event);

            for (User admin : recipients) {
                notifyOneAdmin(admin, applicationSeq, event, title, body);
            }

            log.info("LEW kVA adjustment notification dispatched: applicationSeq={}, recipients={}, adjustmentSeq={}",
                    applicationSeq, recipients.size(), event.getAdjustmentSeq());
        } catch (RuntimeException ex) {
            // AFTER_COMMIT 이므로 비즈니스 트랜잭션은 이미 커밋됨 — 어떤 예외도 결과를 바꾸지 않지만,
            // 호출자(이벤트 디스패처) 로그 노이즈와 혼동 방지를 위해 방어.
            log.error("LewKvaAdjustmentRequestNotificationListener failed: applicationSeq={}, err={}",
                    applicationSeq, ex.getMessage(), ex);
        }
    }

    /**
     * 단일 ADMIN 에 대한 인앱+이메일 발송. 멱등성 가드 + 채널 별 try/catch (실패 격리).
     */
    private void notifyOneAdmin(User admin, Long applicationSeq,
                                 LewKvaAdjustmentRequestedEvent event,
                                 String title, String body) {
        Long adminSeq = admin.getUserSeq();
        try {
            // 멱등성: 같은 (admin, application, type) 알림이 이미 있으면 스킵.
            boolean alreadyNotified = notificationRepository
                    .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                            adminSeq,
                            NotificationType.KVA_ADJUSTMENT_REQUESTED_ADMIN,
                            REFERENCE_TYPE_APPLICATION,
                            applicationSeq);
            if (alreadyNotified) {
                log.info("LEW kVA adjustment notification skipped (idempotent): adminSeq={}, applicationSeq={}",
                        adminSeq, applicationSeq);
                return;
            }

            // 1) 인앱 알림 — 실패해도 이메일 시도까지 막지 않는다.
            try {
                notificationService.createNotification(
                        adminSeq,
                        NotificationType.KVA_ADJUSTMENT_REQUESTED_ADMIN,
                        title,
                        body,
                        REFERENCE_TYPE_APPLICATION,
                        applicationSeq);
            } catch (RuntimeException ex) {
                log.warn("LEW kVA adjustment in-app notification failed: adminSeq={}, applicationSeq={}, err={}",
                        adminSeq, applicationSeq, ex.getMessage());
            }

            // 2) 이메일 — 실패 swallow.
            try {
                String emailTo = admin.getEmail();
                String firstName = admin.getFirstName() != null ? admin.getFirstName() : "";
                String lastName = admin.getLastName() != null ? admin.getLastName() : "";
                String fullName = (firstName + " " + lastName).trim();
                if (fullName.isEmpty()) fullName = "Admin";
                if (emailTo != null && !emailTo.isBlank()) {
                    emailService.sendKvaAdjustmentRequestedToAdminEmail(
                            emailTo, fullName,
                            event.getLewName(),
                            applicationSeq,
                            event.getProposedKva(),
                            event.getCurrentKva(),
                            event.getReason());
                }
            } catch (RuntimeException ex) {
                log.warn("LEW kVA adjustment email failed: adminSeq={}, applicationSeq={}, err={}",
                        adminSeq, applicationSeq, ex.getMessage());
            }
        } catch (RuntimeException ex) {
            // 한 ADMIN 의 실패가 다른 ADMIN 에게 전파되지 않게.
            log.warn("LEW kVA adjustment notification (single admin) failed: adminSeq={}, applicationSeq={}, err={}",
                    adminSeq, applicationSeq, ex.getMessage());
        }
    }

    /**
     * 인앱 알림 본문: "LEW {name} proposed {proposedKva}kVA" — 카탈로그 가이드 ADMIN 톤(짧게, 행동 안내).
     */
    private String buildInAppBody(LewKvaAdjustmentRequestedEvent event) {
        String lewDisplay = (event.getLewName() != null && !event.getLewName().isBlank())
                ? event.getLewName() : "an LEW";
        String proposed = event.getProposedKva() != null ? event.getProposedKva() + "kVA" : "—";
        return "LEW " + lewDisplay + " proposed " + proposed;
    }
}
