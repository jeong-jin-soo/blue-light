package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.user.User;
import lombok.Builder;
import lombok.Getter;

/**
 * ADMIN PayNow 전체값 조회 응답 (reveal — 지급 실행용, 열람 감사 기록과 함께).
 */
@Getter
@Builder
public class PaynowRevealResponse {

    private Long userSeq;
    private String paynowType;
    private String paynowValue;

    public static PaynowRevealResponse from(User user) {
        return PaynowRevealResponse.builder()
                .userSeq(user.getUserSeq())
                .paynowType(user.getPaynowType() != null ? user.getPaynowType().name() : null)
                .paynowValue(user.getPaynowValue())
                .build();
    }
}
