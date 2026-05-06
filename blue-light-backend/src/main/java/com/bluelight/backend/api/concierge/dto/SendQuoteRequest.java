package com.bluelight.backend.api.concierge.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 컨시어지 매니저가 통화 후 견적 이메일 발송을 트리거하는 요청 DTO.
 * <p>
 * Phase 1.5 — 신청 폼에서 결제를 분리하고 매니저 통화 후 개별 견적으로 전환.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SendQuoteRequest {

    /** 컨시어지 서비스 수수료 (SGD, 양수) */
    @NotNull(message = "quotedAmount is required")
    @DecimalMin(value = "0.01", message = "quotedAmount must be positive")
    private BigDecimal quotedAmount;

    /** 통화에서 합의한 후속 약속 일정 (선택). 예: 방문 일정, 서명 미팅 등. */
    private LocalDateTime callScheduledAt;

    /** 이메일 본문에 함께 전달할 추가 메모 (선택, 최대 1000자) */
    @Size(max = 1000, message = "note must be 1000 chars or less")
    private String note;
}
