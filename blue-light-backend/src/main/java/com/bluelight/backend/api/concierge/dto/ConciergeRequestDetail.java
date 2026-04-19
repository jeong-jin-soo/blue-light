package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Concierge 요청 상세 DTO (★ Phase 1 PR#4 Stage A).
 * Summary 필드 + 노트 타임라인 + 전이 타임스탬프 + 신청자 활성화 상태.
 */
@Getter
@Builder
public class ConciergeRequestDetail {

    // ── Summary 필드 (편의 중복) ──
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
    private LocalDateTime createdAt;
    private LocalDateTime firstContactAt;

    // ── 상세 필드 ──
    private String memo;
    private boolean marketingOptIn;
    private LocalDateTime assignedAt;
    private LocalDateTime applicationCreatedAt;
    private LocalDateTime loaRequestedAt;
    private LocalDateTime loaSignedAt;
    private LocalDateTime licencePaidAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private String cancellationReason;

    /** 노트 타임라인 (최신순) */
    private List<NoteResponse> notes;

    /** 신청자 활성화 상태 정보 */
    private ApplicantStatusInfo applicantStatus;
}
