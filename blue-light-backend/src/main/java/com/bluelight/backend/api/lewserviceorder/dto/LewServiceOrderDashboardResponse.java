package com.bluelight.backend.api.lewserviceorder.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Request for LEW Service 주문 대시보드 통계 응답 DTO
 */
@Getter
@Builder
public class LewServiceOrderDashboardResponse {

    private long total;
    private long pendingQuote;
    private long quoteProposed;
    private long pendingPayment;
    private long paid;
    private long inProgress;
    private long sldUploaded;
    private long completed;
}
