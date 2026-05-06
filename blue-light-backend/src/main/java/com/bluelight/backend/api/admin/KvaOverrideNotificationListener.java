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
 * PR-2: 결제 후 ADMIN 이 kVA 를 변경한 직후, 배정된 LEW 에게 인앱 알림 + 이메일을 발송하는 리스너.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §5.4, §8 PR-2.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 비즈니스 트랜잭션({@code KvaPostPaymentService.overrideKva})의 본질은 ledger 작성 + Application
 * 갱신 + Invoice 재발행 + (필요시) CoF unfinalize 이며, 알림 발송은 부수 효과다. SMTP 장애·외부
 * 서비스 일시 오류가 ADMIN 의 변경 트랜잭션을 롤백시켜선 안 된다. 그래서 이벤트 구독을
 * {@link TransactionPhase#AFTER_COMMIT} 으로 분리한다 (PR4 {@link LewPaymentNotificationListener}
 * 와 동일 원칙).
 *
 * <h3>책임</h3>
 * <ol>
 *   <li>{@code event.assignedLewUserSeq == null} → 발송 스킵 (LEW 미배정 케이스).</li>
 *   <li>같은 application + LEW + type 알림이 이미 존재 → 멱등성 보장 위해 스킵.</li>
 *   <li>인앱 알림 생성 ({@code referenceType=APPLICATION}, {@code referenceId=applicationSeq}).</li>
 *   <li>이메일 발송 — 인앱과 독립 채널 (둘 중 하나만 실패해도 다른 쪽은 진행).</li>
 *   <li>{@code cofReissueTriggered=true} 인 경우 메시지에 CoF 재서명 안내 라인 통합 (별도 알림 미발행).</li>
 * </ol>
 *
 * <p><b>실패 격리</b>: 리스너에서 RuntimeException 이 빠져나가지 않도록 모든 채널을 try/catch 로
 * 감싼다. AFTER_COMMIT 이므로 어떤 예외도 비즈니스 결과를 바꾸지 않지만, 호출자(이벤트 디스패처)
 * 입장의 예측 가능성을 위한 방어.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KvaOverrideNotificationListener {

    /** 인앱 알림 referenceType (NotificationsPage 라우팅 키) */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final ApplicationRepository applicationRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKvaOverrideApplied(KvaOverrideAppliedEvent event) {
        Long applicationSeq = event.getApplicationSeq();
        Long lewUserSeq = event.getAssignedLewUserSeq();
        try {
            if (lewUserSeq == null) {
                // 배정 LEW 가 없는 신청에 대한 변경 — 알림 대상이 없으므로 스킵 (감사 로그는 본 트랜잭션에서 이미 기록됨).
                log.info("kVA override notification skipped — no LEW assigned: applicationSeq={}, adjustmentSeq={}",
                        applicationSeq, event.getAdjustmentSeq());
                return;
            }

            // 멱등성 가드: 같은 application + 동일 타입 알림이 이미 존재하면 발송하지 않는다.
            // 보통 동일 변경에 두 번 알림이 가지 않도록 방어하는 패턴 — 같은 신청에 두 번째 변경이 일어나면
            // 그 때는 새로운 adjustmentSeq 가 생기지만 인앱 알림은 동일 (applicationSeq, type) 로
            // 스킵되므로, 운영상 LEW 가 알림을 두 번 받지 않게 하려면 ADMIN 측 운영 절차로 외부 채널 안내를
            // 보강해야 한다. (PR-4 의 settled 알림은 별도 type 으로 분리될 예정.)
            boolean alreadyNotified = notificationRepository
                    .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                            lewUserSeq,
                            NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW,
                            REFERENCE_TYPE_APPLICATION,
                            applicationSeq);
            if (alreadyNotified) {
                log.info("kVA override notification skipped — already notified: applicationSeq={}, lewSeq={}, adjustmentSeq={}",
                        applicationSeq, lewUserSeq, event.getAdjustmentSeq());
                return;
            }

            // 1) 인앱 알림 — title/body 는 본문 가이드(notification-copy-templates.en.md L 톤)에 맞춘 단문.
            String title = "kVA adjusted on application #" + applicationSeq;
            String body = buildInAppBody(event);
            try {
                notificationService.createNotification(
                        lewUserSeq,
                        NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW,
                        title,
                        body,
                        REFERENCE_TYPE_APPLICATION,
                        applicationSeq);
            } catch (RuntimeException ex) {
                // 인앱 알림 발송 실패는 이메일 시도까지 막지 않는다 (둘은 독립 채널).
                log.warn("kVA override in-app notification failed: applicationSeq={}, lewSeq={}, err={}",
                        applicationSeq, lewUserSeq, ex.getMessage());
            }

            // 2) 이메일 — 실패는 swallow.
            try {
                Application application = applicationRepository.findById(applicationSeq).orElse(null);
                User lew = (application != null) ? application.getAssignedLew() : null;
                String emailTo;
                String fullName;
                if (lew != null) {
                    // application.assignedLew 와 event.assignedLewUserSeq 가 동일해야 정상 — 이벤트 발행 직후
                    // ADMIN 이 LEW 재배정을 했다면 application 쪽이 최신. 알림 주소는 최신값을 따른다.
                    emailTo = lew.getEmail();
                    String firstName = lew.getFirstName() != null ? lew.getFirstName() : "";
                    String lastName = lew.getLastName() != null ? lew.getLastName() : "";
                    fullName = (firstName + " " + lastName).trim();
                } else {
                    log.warn("kVA override email skipped — application or LEW lookup failed: applicationSeq={}, lewSeq={}",
                            applicationSeq, lewUserSeq);
                    emailTo = null;
                    fullName = null;
                }
                if (emailTo != null && !emailTo.isBlank()) {
                    emailService.sendKvaAdjustedToLewEmail(
                            emailTo, fullName, applicationSeq,
                            event.getPreviousKva(), event.getNewKva(),
                            event.getPreviousQuoteAmount(), event.getNewQuoteAmount(),
                            event.getAmountDifference(),
                            event.isCofReissueTriggered(), event.getReason());
                }
            } catch (RuntimeException ex) {
                log.warn("kVA override email failed: applicationSeq={}, lewSeq={}, err={}",
                        applicationSeq, lewUserSeq, ex.getMessage());
            }

            log.info("LEW notified of kVA override: applicationSeq={}, lewSeq={}, adjustmentSeq={}, cofReissue={}",
                    applicationSeq, lewUserSeq, event.getAdjustmentSeq(), event.isCofReissueTriggered());
        } catch (RuntimeException ex) {
            // AFTER_COMMIT 이므로 비즈니스 트랜잭션은 이미 커밋됨 — 이 단계의 어떤 예외도 결과를 바꾸지 않지만,
            // 호출자(이벤트 디스패처) 로그 노이즈와 혼동 방지를 위해 방어.
            log.error("kVA override notification listener failed: applicationSeq={}, err={}",
                    applicationSeq, ex.getMessage(), ex);
        }
    }

    /**
     * 인앱 알림 본문 — 한 줄 요약 + (선택) CoF 재서명 안내. 카탈로그 가이드 L 톤(짧게, 행동 안내).
     */
    private String buildInAppBody(KvaOverrideAppliedEvent event) {
        String prev = event.getPreviousKva() != null ? event.getPreviousKva() + "kVA" : "—";
        String next = event.getNewKva() != null ? event.getNewKva() + "kVA" : "—";
        String diff = formatAmountDifference(event.getAmountDifference());
        StringBuilder sb = new StringBuilder();
        sb.append("Previous: ").append(prev).append(" → New: ").append(next);
        if (diff != null) {
            sb.append(" · ").append(diff);
        }
        if (event.isCofReissueTriggered()) {
            sb.append(" · CoF re-issue required");
        }
        return sb.toString();
    }

    /** "+$200.00" / "−$50.00" / null (차액 없음 또는 표시 불가). */
    private String formatAmountDifference(BigDecimal amountDifference) {
        if (amountDifference == null) {
            return null;
        }
        if (amountDifference.signum() > 0) {
            return "+$" + amountDifference.toPlainString();
        }
        if (amountDifference.signum() < 0) {
            return "−$" + amountDifference.abs().toPlainString();
        }
        return "$0.00";
    }
}
