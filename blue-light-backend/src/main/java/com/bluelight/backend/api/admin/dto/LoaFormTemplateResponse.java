package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.loaform.LoaFormTemplate;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * LoA 폼 템플릿 버전 응답 DTO.
 *
 * <p>스펙: {@code loa-exchange-redesign-spec.md} §3.1. admin 목록/업로드 결과 + 신청자/LEW
 * active 폼 소비 응답(§3.2)에 공통으로 사용한다.</p>
 */
@Getter
@Builder
public class LoaFormTemplateResponse {

    private Long loaFormTemplateSeq;
    private String label;
    private Long fileSeq;
    /** Lombok 게터 isActive() → Jackson 기본 직렬화는 "active". 프론트(isActive) 계약 유지 위해 명시. */
    @JsonProperty("isActive")
    private boolean isActive;
    private Long uploadedBy;
    /** 업로더 표시명 (users 조회 결과; 사용자 삭제/미존재 시 null). */
    private String uploadedByName;
    private LocalDateTime uploadedAt;

    public static LoaFormTemplateResponse from(LoaFormTemplate t, String uploadedByName) {
        return LoaFormTemplateResponse.builder()
                .loaFormTemplateSeq(t.getLoaFormTemplateSeq())
                .label(t.getLabel())
                .fileSeq(t.getFileSeq())
                .isActive(t.isActive())
                .uploadedBy(t.getUploadedBy())
                .uploadedByName(uploadedByName)
                .uploadedAt(t.getUploadedAt())
                .build();
    }
}
