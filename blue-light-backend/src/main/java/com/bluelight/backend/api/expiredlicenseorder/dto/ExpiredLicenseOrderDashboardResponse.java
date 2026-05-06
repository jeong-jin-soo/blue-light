package com.bluelight.backend.api.expiredlicenseorder.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ExpiredLicenseOrderDashboardResponse {

    private long total;
    private long pendingQuote;
    private long quoteProposed;
    private long pendingPayment;
    private long paid;
    private long visitScheduled;
    private long visitCompleted;
    private long revisitRequested;
    private long completed;
}
