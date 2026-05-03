package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * PR-4: settlement 마킹 요청 DTO.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.3 / PR-4.</p>
 *
 * <p>{@link AdminPaymentAdjustment#PENDING} 은 본 엔드포인트로 전이할 수 없다 (도메인 메서드
 * {@link com.bluelight.backend.domain.kva.KvaAdjustmentRecord#markSettlement} 가 finalize 전용).
 * 정정이 필요하면 새 KvaAdjustmentRecord 를 생성하라는 안내 메시지로 D6=거부 정책 유지.</p>
 *
 * <p>{@code notifyLew} 는 ADMIN 의 모달 체크박스(기본 true)와 매핑되며, false 면 본 트랜잭션 후
 * AFTER_COMMIT 알림 발행을 스킵한다.</p>
 */
@Getter
@Setter
public class KvaSettlementUpdateRequest {

    /** PAID_DIFFERENCE / REFUNDED / WAIVED. PENDING/null 은 거부. */
    @NotNull(message = "paymentAdjustment is required")
    private AdminPaymentAdjustment paymentAdjustment;

    /** 실제 송금/환불 금액 (양수). nullable — WAIVED 등 금액 없는 케이스. */
    @Positive(message = "settledAmount must be positive")
    private BigDecimal settledAmount;

    /** PayNow ref 등 외부 채널 참조번호. */
    @Size(max = 100, message = "receiptReferenceNumber must be at most 100 characters")
    private String receiptReferenceNumber;

    /** 정산 메모. */
    @Size(max = 1000, message = "settlementMemo must be at most 1000 characters")
    private String settlementMemo;

    /**
     * LEW 알림 발송 여부. 기본 true (모달에서 체크박스가 체크된 상태).
     * Boolean 박스 타입으로 두어 클라이언트가 명시적으로 false 를 보내야만 발송이 차단되도록 한다.
     */
    private Boolean notifyLew = Boolean.TRUE;
}
