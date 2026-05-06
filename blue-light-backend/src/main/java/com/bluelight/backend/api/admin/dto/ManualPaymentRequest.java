package com.bluelight.backend.api.admin.dto;

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
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — Application 별도 수금 입력.
 * <p>
 * ADMIN/SYSTEM_ADMIN 이 외부 채널(은행 송금/PayNow 오프라인/현금/기타)로 받은 결제를 수동 기록할 때
 * 사용. {@code paymentMethod} 는 offline 4종({@link PaymentMethod#BANK_TRANSFER},
 * {@link PaymentMethod#PAYNOW_OFFLINE}, {@link PaymentMethod#CASH}, {@link PaymentMethod#OTHER}) 만
 * 허용 — {@link PaymentMethod#PAYNOW_ONLINE} 은 서비스 레이어에서 거부한다.
 * <p>
 * {@code receiptIssue=true} (기본) 인 경우, 결제 트랜잭션 커밋 후 AFTER_COMMIT 훅에서 영수증 PDF
 * 자동 발행 + 영수증 이메일이 발송된다 (스펙 §7, §8, AC-A1).
 */
@Getter
@Setter
@NoArgsConstructor
public class ManualPaymentRequest {

    /** 결제 금액 (양수, 필수). 견적 금액과 다른 경우 audit 에 차이 기록 (D4=B). */
    @NotNull
    @Positive
    private BigDecimal amount;

    /** 결제일 (필수). LocalDate — 시간 정보는 LocalDateTime.atStartOfDay() 로 변환. */
    @NotNull
    private LocalDate paidAt;

    /** 결제 수단 (offline 4종만 허용 — 서비스 레이어에서 검증). */
    @NotNull
    private PaymentMethod paymentMethod;

    /** 송금 참조번호 등 메모. 최대 500자, optional. */
    @Size(max = 500)
    private String referenceNote;

    /** 영수증 자동 발행 여부 (기본 true). false 이면 결제만 기록 + 영수증/이메일 미발송 (AC-A5). */
    private Boolean receiptIssue;

    public boolean isReceiptIssue() {
        // 명시적 null → 기본 true (AC-A5 의 명시적 false 만 차단).
        return receiptIssue == null || Boolean.TRUE.equals(receiptIssue);
    }
}
