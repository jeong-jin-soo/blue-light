package com.bluelight.backend.api.concierge.dto;

import com.bluelight.backend.domain.payment.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — ConciergeRequest 별도 수금 입력.
 * <p>
 * CONCIERGE_MANAGER/ADMIN/SYSTEM_ADMIN 이 컨시어지 서비스 수수료를 외부 채널(은행 송금 등) 로
 * 수금한 후 시스템에 기록할 때 사용. ManualPaymentRequest 와 시그니처는 동일 — concierge 도메인의
 * 별도 패키지 DTO 로 분리한 이유는 (1) 컨트롤러 패키지 응집, (2) 향후 컨시어지 전용 필드(예:
 * {@code splitFee}, {@code waiveServiceCharge}) 가 추가될 수 있는 확장성 보장.
 */
@Getter
@Setter
@NoArgsConstructor
public class ConciergeManualPaymentRequest {

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotNull
    private LocalDate paidAt;

    @NotNull
    private PaymentMethod paymentMethod;

    @Size(max = 500)
    private String referenceNote;

    private Boolean receiptIssue;

    public boolean isReceiptIssue() {
        return receiptIssue == null || Boolean.TRUE.equals(receiptIssue);
    }
}
