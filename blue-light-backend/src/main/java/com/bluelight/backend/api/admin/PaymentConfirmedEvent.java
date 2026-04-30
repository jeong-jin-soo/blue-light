package com.bluelight.backend.api.admin;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 확인(ADMIN → PAID) 직후 발행되는 도메인 이벤트 (PR4).
 *
 * <p>{@link AdminPaymentService#confirmPayment} 트랜잭션 커밋 이후
 * {@link LewPaymentNotificationListener}가 이를 구독하여 배정된 LEW에게 인앱 알림 + 이메일을
 * 발송한다.</p>
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} 로 처리되므로 결제 트랜잭션
 * 자체가 알림 발송 실패의 영향을 받지 않는다. 이는 {@link com.bluelight.backend.api.concierge.ApplicationStatusChangedEvent}
 * (BEFORE_COMMIT, 동일 트랜잭션 내 정합성)와는 의도가 다르다 — 결제 처리 본질에 알림은
 * 부수 효과(observability/UX)이지 정합성 요구가 아니므로 분리한다.</p>
 *
 * @param applicationSeq 결제가 확인된 신청서 PK
 * @param paymentSeq     생성된 결제 레코드 PK
 * @param amount         결제 금액 (audit/email 본문 노출용)
 * @param confirmedAt    결제 확인 시각 (서비스 호출 시점)
 */
@Getter
@RequiredArgsConstructor
public class PaymentConfirmedEvent {
    private final Long applicationSeq;
    private final Long paymentSeq;
    private final BigDecimal amount;
    private final LocalDateTime confirmedAt;
}
