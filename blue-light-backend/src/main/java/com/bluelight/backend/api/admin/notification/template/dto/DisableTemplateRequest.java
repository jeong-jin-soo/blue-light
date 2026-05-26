package com.bluelight.backend.api.admin.notification.template.dto;

import jakarta.validation.constraints.Size;

/**
 * enable/disable 공용 요청 — change_reason 만 받는다.
 *
 * <p>D-6 결정에 따라 SECURITY/PAYMENT/MARKETING 카테고리는 서비스 단에서 reason 필수.
 * SECURITY 카테고리 disable 은 추가로 50 자 이상 요구.</p>
 */
public record DisableTemplateRequest(
        @Size(max = 500) String changeReason
) {
}
