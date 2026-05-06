package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.notification.NotificationRepository;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PR4: ADMIN이 결제를 확인한 직후, 배정된 LEW에게 인앱 알림 + 이메일을 발송하는 리스너.
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 결제 트랜잭션의 본질은 Payment 레코드 + Application.status 전이이며, 알림 발송은 부수 효과다.
 * 이메일 송신 실패 / NotificationService 의 일시 오류가 결제 확정 자체를 롤백시키면 안 된다.
 * 따라서 이벤트를 {@link TransactionPhase#AFTER_COMMIT} 으로 구독하여 트랜잭션 분리.
 *
 * <h3>책임</h3>
 * <ol>
 *   <li>{@code application.assignedLew == null} → 발송 스킵 + 이유를 로그로 남김</li>
 *   <li>같은 application + LEW + type 알림이 이미 존재 → 멱등성 보장 위해 스킵</li>
 *   <li>인앱 알림 생성 (referenceType=APPLICATION) — 프론트는 type을 보고 LEW 라우트로 deeplink</li>
 *   <li>이메일 발송 — 실패해도 swallow (인앱 알림은 정상 생성된 상태)</li>
 * </ol>
 *
 * <p>리스너 자체에서 RuntimeException 이 빠져나가지 않도록 try/catch 로 감싼다 — AFTER_COMMIT 단계에서
 * 예외가 발생해도 이미 커밋된 결제는 롤백되지 않지만, 로그 노이즈와 호출자 혼동을 방지하기 위한 방어.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LewPaymentNotificationListener {

    /** 인앱 알림 referenceType (NotificationsPage 등 클라이언트 라우팅 키) */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final ApplicationRepository applicationRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        Long applicationSeq = event.getApplicationSeq();
        try {
            Application application = applicationRepository.findById(applicationSeq).orElse(null);
            if (application == null) {
                log.warn("LEW notification skipped — application not found: applicationSeq={}", applicationSeq);
                return;
            }

            User lew = application.getAssignedLew();
            if (lew == null) {
                // 가능 시나리오: ADMIN이 LEW 배정 없이 결제 확인 (예: 임시 결제 처리). 알림 대상이 없으므로 스킵.
                log.info("LEW notification skipped — no LEW assigned: applicationSeq={}", applicationSeq);
                return;
            }

            // 멱등성 가드: 같은 application + 동일 타입 알림이 이미 존재하면 발송하지 않는다.
            // (예: 추후 unconfirm → reconfirm 흐름이 도입되어도 LEW가 같은 알림을 두 번 받지 않도록)
            boolean alreadyNotified = notificationRepository
                    .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                            lew.getUserSeq(),
                            NotificationType.PAYMENT_CONFIRMED_LEW,
                            REFERENCE_TYPE_APPLICATION,
                            applicationSeq);
            if (alreadyNotified) {
                log.info("LEW notification skipped — already notified: applicationSeq={}, lewSeq={}",
                        applicationSeq, lew.getUserSeq());
                return;
            }

            // 1) 인앱 알림
            String title = "Payment confirmed — Application #" + applicationSeq;
            String body = "Ready for SLD/LOA/CoF. Tap to start Phase 2.";
            try {
                notificationService.createNotification(
                        lew.getUserSeq(),
                        NotificationType.PAYMENT_CONFIRMED_LEW,
                        title,
                        body,
                        REFERENCE_TYPE_APPLICATION,
                        applicationSeq);
            } catch (RuntimeException ex) {
                // 인앱 알림 발송 실패는 이메일 시도까지 막지 않는다 (둘은 독립 채널)
                log.warn("LEW in-app notification failed: applicationSeq={}, lewSeq={}, err={}",
                        applicationSeq, lew.getUserSeq(), ex.getMessage());
            }

            // 2) 이메일 — 실패는 swallow
            try {
                String firstName = lew.getFirstName() != null ? lew.getFirstName() : "";
                String lastName = lew.getLastName() != null ? lew.getLastName() : "";
                String fullName = (firstName + " " + lastName).trim();
                emailService.sendPaymentConfirmedToLewEmail(
                        lew.getEmail(),
                        fullName,
                        applicationSeq,
                        application.getAddress(),
                        event.getAmount());
            } catch (RuntimeException ex) {
                log.warn("LEW payment-confirmed email failed: applicationSeq={}, to={}, err={}",
                        applicationSeq, lew.getEmail(), ex.getMessage());
            }

            log.info("LEW notified of payment: applicationSeq={}, lewSeq={}", applicationSeq, lew.getUserSeq());
        } catch (RuntimeException ex) {
            // AFTER_COMMIT 이므로 결제 트랜잭션은 이미 커밋됨 — 어떤 예외도 비즈니스 결과를 바꾸지 않지만
            // 호출자 입장의 예측 가능성을 위해 리스너에서 빠져나가지 않도록 방어.
            log.error("LEW payment notification listener failed: applicationSeq={}, err={}",
                    applicationSeq, ex.getMessage(), ex);
        }
    }
}
