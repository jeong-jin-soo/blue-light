package com.bluelight.backend.api.auth.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * {@code POST /api/auth/login/request-activation} 응답 DTO
 * (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage C).
 * <p>
 * 5케이스(PENDING/ACTIVE/SUSPENDED/DELETED/미존재) 모두 동일 고정 메시지 반환 →
 * 이메일 존재 여부 유출 방지 (§4.4).
 */
@Getter
@Builder
public class ActivationLinkResponse {

    private String message;
}
