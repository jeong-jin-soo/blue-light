package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
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

    // ── Phase 1.5 Quote Workflow ──
    /** 통화 후 합의한 후속 일정 */
    private LocalDateTime callScheduledAt;
    /** 컨시어지 서비스 수수료 견적 (SGD) */
    private BigDecimal quotedAmount;
    /** 견적 이메일 발송 시점 (null = 미발송) */
    private LocalDateTime quoteSentAt;
    /** 피싱 방지 검증 문구 — 통화 · 이메일에서 신청자와 상호 확인용 (매니저 UI에만 노출) */
    private String verificationPhrase;

    /** 노트 타임라인 (최신순) */
    private List<NoteResponse> notes;

    /** 신청자 활성화 상태 정보 */
    private ApplicantStatusInfo applicantStatus;
}
