package com.bluelight.backend.api.notification;

import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 결제 요청 알림(A-17) 공용 디스패처.
 *
 * <p>LEW({@code requestPayment})와 ADMIN({@code approveForPayment}) 두 경로가 동일하게
 * 신청자에게 결제 요청 알림(인앱+이메일)을 보내도록 통일한다. 기존 legacy
 * {@code EmailService.sendPaymentRequestEmail}(이메일만)을 대체 — 오케스트레이터가
 * 채널(인앱/이메일)을 결정한다. A-17 EMAIL/IN_APP 템플릿은 이미 시드·활성 상태.</p>
 *
 * <p>알림 실패가 결제 요청 트랜잭션을 롤백하지 않도록 호출부는 try-safe; 여기서도 방어.</p>
 */
@Slf4j
public final class PaymentRequestNotifier {

    private PaymentRequestNotifier() {}

    public static void dispatch(ApplicationEventPublisher publisher, Application application) {
        try {
            User applicant = application.getUser();
            if (applicant == null || applicant.getUserSeq() == null) {
                log.warn("결제 요청 알림 스킵 — 신청자 정보 없음: applicationId={}",
                        application.getApplicationSeq());
                return;
            }
            Long seq = application.getApplicationSeq();
            String name = ((applicant.getFirstName() != null ? applicant.getFirstName() : "") + " "
                    + (applicant.getLastName() != null ? applicant.getLastName() : "")).trim();

            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("applicantName", name);
            payload.put("publicCode", String.valueOf(seq));
            payload.put("amount", application.getQuoteAmount() != null
                    ? application.getQuoteAmount().toPlainString() : "");
            payload.put("kvaLabel", application.getSelectedKva() != null
                    ? application.getSelectedKva() + " kVA" : "");
            payload.put("ctaUrl", "/applications/" + seq);
            // paynow*·deadline 변수는 미해결 시 TemplateRenderer 가 제거(고객 비노출). 결제 화면에서 안내.

            publisher.publishEvent(new NotificationDispatchEvent(
                    "PAYMENT_REQUESTED", applicant.getUserSeq(),
                    "APPLICATION", seq, "A-17", payload));
        } catch (RuntimeException ex) {
            log.warn("결제 요청 알림 디스패치 실패: applicationId={}, err={}",
                    application.getApplicationSeq(), ex.getMessage());
        }
    }
}
