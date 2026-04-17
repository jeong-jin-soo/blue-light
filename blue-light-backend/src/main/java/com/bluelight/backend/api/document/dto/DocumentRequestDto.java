package com.bluelight.backend.api.document.dto;

import com.bluelight.backend.domain.document.DocumentRequest;
import com.bluelight.backend.domain.document.DocumentRequestStatus;
import com.bluelight.backend.domain.file.FileEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * DocumentRequest 응답 DTO
 */
@Getter
@Builder
public class DocumentRequestDto {

    private Long id;
    private Long applicationSeq;
    private String documentTypeCode;
    private String customLabel;
    private String lewNote;
    private DocumentRequestStatus status;

    // Fulfilled file 요약 정보 (별도 다운로드 엔드포인트로 접근)
    private Long fulfilledFileSeq;
    private String fulfilledFilename;
    private Long fulfilledFileSize;

    private LocalDateTime requestedAt;
    private LocalDateTime fulfilledAt;
    private LocalDateTime reviewedAt;
    private String rejectionReason;

    private LocalDateTime createdAt;

    public static DocumentRequestDto from(DocumentRequest dr) {
        FileEntity file = dr.getFulfilledFile();
        return DocumentRequestDto.builder()
                .id(dr.getId())
                .applicationSeq(dr.getApplication().getApplicationSeq())
                .documentTypeCode(dr.getDocumentTypeCode())
                .customLabel(dr.getCustomLabel())
                .lewNote(dr.getLewNote())
                .status(dr.getStatus())
                .fulfilledFileSeq(file != null ? file.getFileSeq() : null)
                .fulfilledFilename(file != null ? file.getOriginalFilename() : null)
                .fulfilledFileSize(file != null ? file.getFileSize() : null)
                .requestedAt(dr.getRequestedAt())
                .fulfilledAt(dr.getFulfilledAt())
                .reviewedAt(dr.getReviewedAt())
                .rejectionReason(dr.getRejectionReason())
                .createdAt(dr.getCreatedAt())
                .build();
    }
}
