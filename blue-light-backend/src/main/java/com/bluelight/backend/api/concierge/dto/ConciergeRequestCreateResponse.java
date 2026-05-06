package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Concierge 신청 접수 응답 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * 클라이언트는 {@code accountSetupRequired}와 {@code existingUser} 플래그 조합으로
 * 성공 페이지 문구 분기 처리 (§2.3).
 */
@Getter
@Builder
public class ConciergeRequestCreateResponse {

    /**
     * 공개 식별자 (예: "C-2026-0001") — 성공 페이지/이메일에 표시.
     */
    private String publicCode;

    /**
     * 초기 상태 — 항상 "SUBMITTED".
     */
    private String status;

    /**
     * true: 기존 계정 연결 (C2 또는 C3)
     * false: 신규 자동 생성 (C1)
     */
    private boolean existingUser;

    /**
     * true: AccountSetup 토큰 발급됨 → 활성화 링크 이메일 발송 (C1, C3)
     * false: 기존 ACTIVE 계정 연결 (C2)
     */
    private boolean accountSetupRequired;

    /**
     * 클라이언트 표시용 안내 문구.
     */
    private String message;
}
