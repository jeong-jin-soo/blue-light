package com.bluelight.backend.api.application.dto;

import com.bluelight.backend.domain.application.SldRequest;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * SLD 요청 응답 DTO
 */
@Getter
@Builder
public class SldRequestResponse {

    private Long sldRequestSeq;
    private Long applicationSeq;
    private String status;
    private String applicantNote;
    private String lewNote;
    private Long uploadedFileSeq;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SldRequestResponse from(SldRequest req) {
        return SldRequestResponse.builder()
                .sldRequestSeq(req.getSldRequestSeq())
                .applicationSeq(req.getApplication().getApplicationSeq())
                .status(req.getStatus().name())
                .applicantNote(req.getApplicantNote())
                .lewNote(req.getLewNote())
                .uploadedFileSeq(req.getUploadedFileSeq())
                .createdAt(req.getCreatedAt())
                .updatedAt(req.getUpdatedAt())
                .build();
    }
}
