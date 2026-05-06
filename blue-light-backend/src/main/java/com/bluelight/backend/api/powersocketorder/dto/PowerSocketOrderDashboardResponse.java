package com.bluelight.backend.api.powersocketorder.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Power Socket 주문 대시보드 통계 응답 DTO
 */
@Getter
@Builder
public class PowerSocketOrderDashboardResponse {

    private long total;
    private long pendingQuote;
    private long quoteProposed;
    private long pendingPayment;
    private long paid;
    private long inProgress;
    private long deliverableUploaded;
    private long completed;
}
