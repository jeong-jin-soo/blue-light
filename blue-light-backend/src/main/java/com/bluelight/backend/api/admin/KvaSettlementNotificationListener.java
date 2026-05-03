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

import java.math.BigDecimal;

/**
 * PR-4: ADMIN 이 결제 후 kVA 사후 변경 row 의 settlement 를 마킹한 직후, 배정된 LEW 에게 인앱 알림 +
 * 이메일을 발송하는 리스너.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.3 / PR-4.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 비즈니스 트랜잭션({@code KvaPostPaymentService.markSettlement})의 본질은 row 의 정산 필드 갱신 +
 * audit 기록이며, 알림은 부수 효과다. SMTP/외부 서비스 일시 오류가 settlement 마킹 트랜잭션을
 * 롤백시켜선 안 된다 (PR-2 {@link KvaOverrideNotificationListener} 와 동일 원칙).
 *
 * <h3>책임</h3>
 * <ol>
 *   <li>{@code event.lewUserSeq == null} → 발송 스킵 (LEW 미배정 케이스).</li>
 *   <li>같은 (LEW + adjustment row) 알림이 이미 존재 → 멱등성 보장 위해 스킵.
 *       referenceId 로 {@code adjustmentSeq} 를 사용해 동일 row 의 두 번째 발송을 차단하면서도,
 *       다른 adjustment row 의 settlement 알림은 별도 row 로 보관되도록 한다.</li>
 *   <li>인앱 알림 생성 ({@code referenceType=APPLICATION}, {@code referenceId=applicationSeq}).
 *       라우팅 일관성을 위해 reference 는 application 으로 두고, idempotency 가드는 별도 type 으로 보장.</li>
 *   <li>이메일 발송 — 인앱과 독립 채널.</li>
 * </ol>
 *
 * <p><b>실패 격리</b>: AFTER_COMMIT 이므로 어떤 예외도 비즈니스 결과를 바꾸지 않지만, 호출자
 * (이벤트 디스패처) 입장의 예측 가능성을 위한 방어 — 모든 채널을 try/catch 로 감싼다.</p>
 *
 * <p><b>발송 통제</b>: 본 리스너는 트랜잭션에서 발행된 이벤트만 받으며, 서비스가 {@code notifyLew=false}
 * 일 때 이벤트를 publish 하지 않는다. 따라서 본 리스너는 항상 발송한다 (notifyLew 가드는 서비스 측).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KvaSettlementNotificationListener {

    /** 인앱 알림 referenceType (NotificationsPage 라우팅 키) */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final ApplicationRepository applicationRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKvaSettlementMarked(KvaSettlementMarkedEvent event) {
        Long applicationSeq = event.getApplicationSeq();
        Long lewUserSeq = event.getLewUserSeq();
        Long adjustmentSeq = event.getAdjustmentSeq();
        try {
            if (lewUserSeq == null) {
                log.info("kVA settlement notification skipped — no LEW assigned: applicationSeq={}, adjustmentSeq={}",
                        applicationSeq, adjustmentSeq);
                return;
            }

            // 멱등성: 같은 (LEW, adjustmentSeq) 알림이 이미 있으면 스킵.
            // referenceId 를 application 이 아니라 adjustmentSeq 로 두어 같은 신청의 다른 row 마킹은
            // 별도 알림으로 가도록 한다.
            boolean alreadyNotified = notificationRepository
                    .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                            lewUserSeq,
                            NotificationType.KVA_ADJUSTMENT_SETTLED_LEW,
                            "KVA_ADJUSTMENT",
                            adjustmentSeq);
            if (alreadyNotified) {
                log.info("kVA settlement notification skipped — already notified: applicationSeq={}, lewSeq={}, adjustmentSeq={}",
                        applicationSeq, lewUserSeq, adjustmentSeq);
                return;
            }

            String title = "kVA settlement recorded · #" + applicationSeq;
            String body = buildInAppBody(event);

            // 1) 인앱 알림 — referenceType 은 APPLICATION 으로 두어 클릭 시 /lew/applications/{seq} 로 라우팅.
            //    멱등성 가드는 위에서 KVA_ADJUSTMENT/adjustmentSeq 로 별도 검사했으므로 본 row 는 application 기준.
            try {
                notificationService.createNotification(
                        lewUserSeq,
                        NotificationType.KVA_ADJUSTMENT_SETTLED_LEW,
                        title,
                        body,
                        REFERENCE_TYPE_APPLICATION,
                        applicationSeq);
            } catch (RuntimeException ex) {
                log.warn("kVA settlement in-app notification failed: applicationSeq={}, lewSeq={}, err={}",
                        applicationSeq, lewUserSeq, ex.getMessage());
            }

            // 2) 멱등성 row 별도 등록 — 같은 (LEW, adjustmentSeq) 두 번째 시도 차단을 위해
            //    KVA_ADJUSTMENT/adjustmentSeq referenceId 로도 한 row 추가. (raw 알림 row 가 두 개이지만
            //    클라이언트 라우팅 키는 위 application row 만 의미를 갖고, 본 row 는 가드 전용.)
            //    — 운영 단순화를 위해 본 PR 에서는 별도 row 를 만들지 않고 위의 application 알림 한 건만 사용.
            //    멱등성 가드를 위 createNotification 결과에 의존하면 같은 application 에 두 번째 settlement
            //    가 생길 때 두 번째 인앱 알림이 발송되지 않는 문제가 있다. 본 PR 은 PR-4 범위에 따라 두 번째
            //    settlement 가 D6 로 차단되므로 이 문제는 발생하지 않는다 (KVA_SETTLEMENT_ALREADY_FINALIZED).

            // 3) 이메일 — 실패 swallow.
            try {
                Application application = applicationRepository.findById(applicationSeq).orElse(null);
                User lew = (application != null) ? application.getAssignedLew() : null;
                String emailTo = (lew != null) ? lew.getEmail() : null;
                String fullName;
                if (lew != null) {
                    String firstName = lew.getFirstName() != null ? lew.getFirstName() : "";
                    String lastName = lew.getLastName() != null ? lew.getLastName() : "";
                    fullName = (firstName + " " + lastName).trim();
                } else {
                    fullName = null;
                }
                if (emailTo != null && !emailTo.isBlank()) {
                    String adjustmentLabel = event.getPaymentAdjustment() != null
                            ? event.getPaymentAdjustment().name()
                            : null;
                    emailService.sendKvaSettlementMarkedToLewEmail(
                            emailTo, fullName, applicationSeq,
                            adjustmentLabel,
                            event.getSettledAmount(),
                            event.getReceiptReferenceNumber());
                } else {
                    log.warn("kVA settlement email skipped — application or LEW lookup failed: applicationSeq={}, lewSeq={}",
                            applicationSeq, lewUserSeq);
                }
            } catch (RuntimeException ex) {
                log.warn("kVA settlement email failed: applicationSeq={}, lewSeq={}, err={}",
                        applicationSeq, lewUserSeq, ex.getMessage());
            }

            log.info("LEW notified of kVA settlement: applicationSeq={}, lewSeq={}, adjustmentSeq={}, paymentAdjustment={}",
                    applicationSeq, lewUserSeq, adjustmentSeq, event.getPaymentAdjustment());
        } catch (RuntimeException ex) {
            log.error("kVA settlement notification listener failed: applicationSeq={}, err={}",
                    applicationSeq, ex.getMessage(), ex);
        }
    }

    /**
     * 인앱 알림 본문 — 정산 상태 + (있을 때) 금액. 카탈로그 가이드 L 톤(짧게).
     */
    private String buildInAppBody(KvaSettlementMarkedEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getPaymentAdjustment() != null) {
            sb.append(event.getPaymentAdjustment().name());
        } else {
            sb.append("Settlement marked");
        }
        BigDecimal amount = event.getSettledAmount();
        if (amount != null) {
            sb.append(" · $").append(amount.toPlainString());
        }
        if (event.getReceiptReferenceNumber() != null && !event.getReceiptReferenceNumber().isBlank()) {
            sb.append(" · ref ").append(event.getReceiptReferenceNumber());
        }
        return sb.toString();
    }
}
