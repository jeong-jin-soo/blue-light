package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 신청자(User) 활성화 상태 요약 (★ Phase 1 PR#4 Stage A).
 * Manager 대시보드의 "PENDING_ACTIVATION 배지" + "재발송 버튼" 표시 분기에 사용.
 */
@Getter
@Builder
public class ApplicantStatusInfo {

    /** User.status (PENDING_ACTIVATION / ACTIVE / SUSPENDED / DELETED) */
    private String userStatus;
    private boolean emailVerified;
    private LocalDateTime activatedAt;
    private LocalDateTime firstLoggedInAt;
    /** AccountSetupToken.isUsable()=true 인 토큰이 1건 이상 존재 */
    private boolean hasActiveSetupToken;
    /** 가장 최근 활성 토큰 만료 시각 (없으면 null) */
    private LocalDateTime setupTokenExpiresAt;
}
