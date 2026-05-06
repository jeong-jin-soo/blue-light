package com.bluelight.backend.api.payment;

import com.bluelight.backend.domain.payment.PaymentMethod;
import com.bluelight.backend.domain.payment.PaymentReferenceType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — ADMIN/CONCIERGE_MANAGER 의 별도 수금 기록 직후
 * 발행되는 도메인 이벤트.
 * <p>
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 결제 트랜잭션의 본질은 Payment row + Application/Concierge 상태 전이이며, 영수증 PDF 렌더 + 첨부 이메일
 * 발송은 부수 효과다. SMTP/PDF 외부 의존 일시 오류가 별도 수금 트랜잭션을 롤백시켜선 안 된다 (스펙 D5=B).
 * <p>
 * <h3>본 이벤트가 트리거하는 후속 작업</h3>
 * <ol>
 *   <li>{@link com.bluelight.backend.api.payment.ManualPaymentInvoiceListener} 가 AFTER_COMMIT 으로 구독하여
 *       Invoice 자동 발행({@code receiptIssue=true} 일 때만) → PDF 첨부 이메일 발송 → 인앱 알림 생성.</li>
 *   <li>실패는 swallow — 결제·invoice 트랜잭션 무관 ({@code AuditAction.INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT}
 *       FAILED status 로 audit 기록).</li>
 * </ol>
 *
 * @param paymentSeq             생성된 Payment 의 PK
 * @param applicantUserSeq       영수증/알림 수령자 user_seq (Application: application.user, Concierge: applicantUser)
 * @param referenceType          APPLICATION / CONCIERGE_REQUEST
 * @param applicationSeq         APPLICATION 결제일 때만 — 그 외 null
 * @param conciergeRequestSeq    CONCIERGE_REQUEST 결제일 때만 — 그 외 null
 * @param amount                 결제 금액 (이메일 본문 노출용)
 * @param paymentMethod          결제 수단 enum
 * @param receiptIssue           영수증 발행 + 이메일 발송 여부 (false 면 listener 가 invoice/email 스킵, AC-A5)
 * @param recordedByUserSeq      ADMIN/MANAGER user_seq (audit 용)
 */
@Getter
@RequiredArgsConstructor
public class ManualPaymentRecordedEvent {
    private final Long paymentSeq;
    private final Long applicantUserSeq;
    private final PaymentReferenceType referenceType;
    private final Long applicationSeq;
    private final Long conciergeRequestSeq;
    private final BigDecimal amount;
    private final PaymentMethod paymentMethod;
    private final boolean receiptIssue;
    private final Long recordedByUserSeq;
}
