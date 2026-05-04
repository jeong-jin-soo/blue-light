package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.payment.PaymentMethod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — 별도 수금 응답.
 * <p>
 * Invoice 발행은 AFTER_COMMIT 훅에서 비동기적으로 수행되므로, 본 응답에는 invoiceSeq 가 포함되지 않을
 * 수도 있다. 트랜잭션 내에서 동기 발행되는 경우 (예: AC-A1 자동 발행 성공 시) {@code invoiceSeq} 가
 * 채워지고, 미발행/지연/실패 시 null. 프론트는 후속 GET 으로 invoice 를 폴링하거나 인앱 알림을 통해
 * 인지한다.
 */
@Getter
@Builder
public class ManualPaymentResponse {

    private final Long paymentSeq;
    private final BigDecimal amount;
    private final PaymentMethod paymentMethod;
    private final LocalDateTime paidAt;
    private final LocalDateTime recordedAt;

    /** 영수증 자동 발행 요청 여부 (요청 입력값 그대로 회신). */
    private final boolean receiptIssued;

    /** 영수증 발행 결과 — receiptIssue=true 이고 발행 성공한 경우만 non-null. */
    private final Long invoiceSeq;
    private final String invoiceNumber;

    /** Application 결제일 때만 채워짐 — Concierge 결제는 null. */
    private final Long applicationSeq;
    /** Concierge 결제일 때만 채워짐 — Application 결제는 null. */
    private final Long conciergeRequestSeq;
}
