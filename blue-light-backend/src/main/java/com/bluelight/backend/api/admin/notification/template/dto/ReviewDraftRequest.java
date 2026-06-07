package com.bluelight.backend.api.admin.notification.template.dto;

import jakarta.validation.constraints.Size;

/**
 * Draft approve/reject 공용 요청.
 *
 * <p>approve: reviewNote 옵션 (D-6 카테고리는 필수 — 서비스 단 검증).
 * reject: reviewNote 필수 (엔티티 단 검증, IllegalArgumentException).</p>
 */
public record ReviewDraftRequest(
        @Size(max = 500) String reviewNote
) {
}
