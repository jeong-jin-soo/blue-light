package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 결제 후 kVA 사후 변경 요청 DTO.
 *
 * <p>Endpoint: {@code POST /api/admin/applications/{id}/kva-override-postpayment}<br>
 * 스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.1.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class KvaPostPaymentOverrideRequest {

    /**
     * 새 kVA tier. {@code master_prices} 에 등록된 활성 tier 만 허용.
     * 서비스에서 {@link com.bluelight.backend.domain.price.MasterPriceRepository#findByKva}
     * 로 검증 — 없으면 400 {@code INVALID_KVA_TIER}.
     */
    @NotNull(message = "newKva is required")
    @Positive(message = "newKva must be positive")
    private Integer newKva;

    /**
     * 변경 사유 (필수). 운영 절차상 ADMIN 의 책임 추적을 위해 필수.
     */
    @NotBlank(message = "reason is required")
    @Size(max = 1000, message = "reason must be 1000 characters or less")
    private String reason;

    /**
     * ADMIN 의 운영 메모 (선택). 외부 정산 안내문, 신청자 클레임 정황 등.
     * PDPA: NRIC/UEN 등 민감정보 입력 금지 (UI helper).
     */
    @Size(max = 2000, message = "adminMemo must be 2000 characters or less")
    private String adminMemo;

    /**
     * 정산 처리 상태 (선택). null 이면 PENDING 으로 기록.
     */
    private AdminPaymentAdjustment paymentAdjustment;

    /**
     * 실제 송금/환불 금액 (선택). settledAmount 만 있고 paymentAdjustment 가 null 이면 PENDING 으로 처리.
     */
    private BigDecimal settledAmount;

    /** 외부 결제 채널 참조번호 (선택). PayNow ref 등. */
    @Size(max = 100, message = "receiptReferenceNumber must be 100 characters or less")
    private String receiptReferenceNumber;
}
