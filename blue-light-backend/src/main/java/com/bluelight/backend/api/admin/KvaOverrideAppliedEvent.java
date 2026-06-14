package com.bluelight.backend.api.admin;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 결제 후 ADMIN 의 직접 kVA 변경({@code POST /api/admin/applications/{id}/kva-override-postpayment})
 * 트랜잭션 커밋 직후 발행되는 도메인 이벤트 (PR-2).
 *
 * <p>{@link com.bluelight.backend.service.kva.KvaPostPaymentService#overrideKva} 의
 * 단일 트랜잭션이 커밋되면, {@link KvaOverrideNotificationListener} 가 이를 구독하여
 * 배정된 LEW 에게 인앱 알림 + 이메일을 발송한다.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 본 트랜잭션의 본질은 {@code KvaAdjustmentRecord} 작성 + {@code Application.kva}/quoteAmount
 * 갱신 + Invoice 재발행이다. 알림 발송은 부수 효과이며 SMTP/외부
 * 서비스 장애가 비즈니스 트랜잭션을 롤백시켜선 안 된다. 그래서 AFTER_COMMIT 단계로 분리한다.
 * (PR4 의 {@link PaymentConfirmedEvent} 와 동일 원칙.)
 *
 * @param applicationSeq        대상 신청 PK
 * @param adjustmentSeq         생성된 {@code KvaAdjustmentRecord} PK
 * @param assignedLewUserSeq    알림 수신자(배정된 LEW)의 userSeq. {@code null} 이면 발송 스킵.
 * @param previousKva           변경 전 kVA
 * @param newKva                변경 후 kVA
 * @param previousQuoteAmount   변경 전 견적가
 * @param newQuoteAmount        변경 후 견적가 (master_prices 변경 시점 현재가)
 * @param amountDifference      newQuote − previousQuote (signed)
 * @param reason                LEW 에게 표시할 사유 (ADMIN 입력값)
 * @param triggeredByUserSeq    변경 주체 user_seq (ADMIN userSeq)
 * @param triggeredByRole       변경 주체 역할 ("ADMIN")
 */
@Getter
@RequiredArgsConstructor
public class KvaOverrideAppliedEvent {
    private final Long applicationSeq;
    private final Long adjustmentSeq;
    private final Long assignedLewUserSeq;
    private final Integer previousKva;
    private final Integer newKva;
    private final BigDecimal previousQuoteAmount;
    private final BigDecimal newQuoteAmount;
    private final BigDecimal amountDifference;
    private final String reason;
    private final Long triggeredByUserSeq;
    private final String triggeredByRole;
}
