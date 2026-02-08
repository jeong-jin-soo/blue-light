package com.bluelight.backend.api.admin.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Admin dashboard summary DTO
 */
@Getter
@Builder
public class AdminDashboardResponse {

    private long totalApplications;
    private long pendingReview;
    private long revisionRequested;
    private long pendingPayment;
    private long paid;
    private long inProgress;
    private long completed;
    private long totalUsers;
    private long unassigned;
}
