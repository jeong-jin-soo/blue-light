package com.bluelight.backend.api.admin;

import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 결제 후 kVA 사후 변경 row 의 settlement 가 ADMIN 에 의해 마킹된 직후 발행되는 도메인 이벤트 (PR-4).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.3 / PR-4.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 본 트랜잭션의 본질은 {@code KvaAdjustmentRecord} settlement 필드 갱신 + audit 기록이며,
 * 알림 발송은 부수 효과다. SMTP/외부 서비스 일시 오류가 settlement 마킹 트랜잭션을
 * 롤백시켜선 안 된다. 그래서 이벤트 구독을 {@code AFTER_COMMIT} 으로 분리한다
 * (PR-2 {@link KvaOverrideAppliedEvent} 와 동일 원칙).
 *
 * <p>{@code lewUserSeq == null} 이면 listener 가 발송 스킵한다 (LEW 미배정 신청 케이스).</p>
 *
 * @param applicationSeq        대상 신청 PK
 * @param adjustmentSeq         settlement 마킹된 KvaAdjustmentRecord PK
 * @param lewUserSeq            알림 수신자(배정된 LEW)의 userSeq. {@code null} 이면 발송 스킵.
 * @param paymentAdjustment     마킹된 정산 상태 (PAID_DIFFERENCE / REFUNDED / WAIVED)
 * @param settledAmount         실제 송금/환불 금액 (양수 절댓값, nullable)
 * @param receiptReferenceNumber 외부 채널 참조번호 (nullable)
 * @param triggeredByUserSeq    마킹 주체 ADMIN userSeq
 */
@Getter
@RequiredArgsConstructor
public class KvaSettlementMarkedEvent {
    private final Long applicationSeq;
    private final Long adjustmentSeq;
    private final Long lewUserSeq;
    private final AdminPaymentAdjustment paymentAdjustment;
    private final BigDecimal settledAmount;
    private final String receiptReferenceNumber;
    private final Long triggeredByUserSeq;
}
