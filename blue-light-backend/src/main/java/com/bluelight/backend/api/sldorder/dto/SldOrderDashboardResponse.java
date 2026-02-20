package com.bluelight.backend.api.sldorder.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * SLD 주문 대시보드 통계 응답 DTO
 */
@Getter
@Builder
public class SldOrderDashboardResponse {

    private long total;
    private long pendingQuote;
    private long quoteProposed;
    private long pendingPayment;
    private long paid;
    private long inProgress;
    private long sldUploaded;
    private long completed;
}
