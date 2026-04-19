package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Concierge 요청 목록용 Summary DTO (★ Phase 1 PR#4 Stage A).
 */
@Getter
@Builder
public class ConciergeRequestSummary {

    private Long conciergeRequestSeq;
    private String publicCode;
    private String submitterName;
    private String submitterEmail;
    private String submitterPhone;
    private String status;
    private boolean slaBreached;
    private Long assignedManagerSeq;
    private String assignedManagerName;
    private Long applicationSeq;
    /** 신청자 User.status — PENDING_ACTIVATION 배지 렌더링용 */
    private String applicantUserStatus;
    private LocalDateTime createdAt;
    private LocalDateTime firstContactAt;
}
