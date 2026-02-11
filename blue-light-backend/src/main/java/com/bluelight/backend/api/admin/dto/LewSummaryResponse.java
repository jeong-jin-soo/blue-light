package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.user.User;
import lombok.Builder;
import lombok.Getter;

/**
 * LEW 요약 정보 (할당 드롭다운용)
 */
@Getter
@Builder
public class LewSummaryResponse {

    private Long userSeq;
    private String name;
    private String email;
    private String lewLicenceNo;
    private String lewGrade;
    private Integer maxKva;

    public static LewSummaryResponse from(User user) {
        return LewSummaryResponse.builder()
                .userSeq(user.getUserSeq())
                .name(user.getName())
                .email(user.getEmail())
                .lewLicenceNo(user.getLewLicenceNo())
                .lewGrade(user.getLewGrade() != null ? user.getLewGrade().name() : null)
                .maxKva(user.getLewGrade() != null ? user.getLewGrade().getMaxKva() : null)
                .build();
    }
}
