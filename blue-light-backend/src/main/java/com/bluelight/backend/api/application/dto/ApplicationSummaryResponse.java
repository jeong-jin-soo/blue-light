package com.bluelight.backend.api.application.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Application summary for dashboard
 */
@Getter
@Builder
public class ApplicationSummaryResponse {

    private long total;
    private long pendingPayment;
    private long inProgress;
    private long completed;
}
