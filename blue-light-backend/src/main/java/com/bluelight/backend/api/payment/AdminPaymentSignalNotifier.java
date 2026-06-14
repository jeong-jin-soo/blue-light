package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 결제 관련 ADMIN 신호 알림(E2/E3) 공용 디스패처.
 *
 * <p>{@link com.bluelight.backend.api.admin.AdminPaymentService} 의 A-20 디스패치 패턴을 미러하되,
 * <b>수신자가 ADMIN/SYSTEM_ADMIN 다수</b>이므로 각 admin 별로 {@link NotificationDispatchEvent} 를
 * 1건씩 발행한다 ({@link com.bluelight.backend.api.lew.LewKvaAdjustmentRequestNotificationListener}
 * 의 다수 수신자 루프와 동형). 채널(인앱/이메일)·언어·옵트인 가드는 NotificationOrchestrator +
 * PreferenceResolver 가 결정 — 하드코딩 금지(notification_templates 소비).</p>
 *
 * <h3>왜 per-recipient 이벤트인가</h3>
 * {@code NotificationOrchestrator.onDispatch} 는 단일 수신자(recipientUserSeq) 기준으로 채널·로케일·
 * idempotency_key 를 산정한다. idempotency_key 에 recipientUserSeq + channel 이 포함되므로 admin 마다
 * 별도 이벤트를 발행해도 중복 발송되지 않는다.
 *
 * <h3>실패 격리</h3>
 * 수신자 조회 실패·이벤트 발행 실패가 호출 트랜잭션(증빙 업로드/확인요청)을 롤백시키지 않도록
 * 전체를 try-safe 로 감싼다. orchestrator 자체는 AFTER_COMMIT 단계라 알림 발송 실패는 결제 신호와
 * 무관하게 격리된다.
 */
@Slf4j
public final class AdminPaymentSignalNotifier {

    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private AdminPaymentSignalNotifier() {
    }

    /**
     * ADMIN/SYSTEM_ADMIN 활성 사용자 전원에게 결제 신호 알림을 dispatch 한다.
     *
     * @param publisher      도메인 서비스의 {@link ApplicationEventPublisher}
     * @param userRepository ADMIN 수신자 해석용
     * @param application    대상 신청서 (publicCode 없음 — applicationSeq 를 슬롯에 매핑)
     * @param eventType      {@code NotificationType} enum 명 (예: PAYMENT_EVIDENCE_UPLOADED)
     * @param templateCode   {@code notification_templates.template_code} (예: A-55)
     */
    public static void dispatchToAdmins(ApplicationEventPublisher publisher,
                                        UserRepository userRepository,
                                        Application application,
                                        String eventType,
                                        String templateCode) {
        try {
            Long applicationSeq = application.getApplicationSeq();
            List<User> recipients = userRepository.findByRoleInAndStatus(
                    List.of(UserRole.ADMIN, UserRole.SYSTEM_ADMIN), UserStatus.ACTIVE);
            if (recipients.isEmpty()) {
                log.warn("Admin payment signal: no ADMIN/SYSTEM_ADMIN active recipients, eventType={}, applicationSeq={}",
                        eventType, applicationSeq);
                return;
            }

            User applicant = application.getUser();
            String applicantName = applicant != null ? safeName(applicant) : "Applicant";
            BigDecimal amount = application.getQuoteAmount();

            // payload 키는 카탈로그 변수와 정합 — applicantName/publicCode/amount/ctaUrl.
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("applicantName", applicantName);
            payload.put("publicCode", String.valueOf(applicationSeq));
            payload.put("amount", amount == null ? "" : amount.toPlainString());
            payload.put("ctaUrl", "/admin/applications/" + applicationSeq);

            for (User admin : recipients) {
                try {
                    publisher.publishEvent(new NotificationDispatchEvent(
                            eventType,
                            admin.getUserSeq(),
                            REFERENCE_TYPE_APPLICATION,
                            applicationSeq,
                            templateCode,
                            payload));
                } catch (RuntimeException ex) {
                    // 한 admin 발행 실패가 다른 admin 으로 전파되지 않게.
                    log.warn("Admin payment signal dispatch failed (single admin): adminSeq={}, eventType={}, err={}",
                            admin.getUserSeq(), eventType, ex.getMessage());
                }
            }
            log.info("Admin payment signal dispatched: eventType={}, templateCode={}, applicationSeq={}, recipients={}",
                    eventType, templateCode, applicationSeq, recipients.size());
        } catch (RuntimeException ex) {
            log.error("Admin payment signal dispatch aborted: eventType={}, err={}", eventType, ex.getMessage(), ex);
        }
    }

    private static String safeName(User user) {
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "Applicant" : full;
    }
}
