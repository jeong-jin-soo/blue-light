package com.bluelight.backend.api.document.dto;

import com.bluelight.backend.domain.document.DocumentRequest;
import com.bluelight.backend.domain.document.DocumentRequestStatus;
import com.bluelight.backend.domain.file.FileEntity;
import lombok.Builder;
import lombok.Getter;

/**
 * 자발적 업로드 응답 DTO (스펙 §4 POST /api/applications/{id}/documents 응답 형식)
 */
@Getter
@Builder
public class VoluntaryUploadResponse {

    private Long documentRequestId;
    private Long documentSeq; // FileEntity.fileSeq
    private DocumentRequestStatus status;
    private String documentTypeCode;
    private String customLabel;
    private String fileName;
    private Long sizeBytes;

    public static VoluntaryUploadResponse from(DocumentRequest dr) {
        FileEntity file = dr.getFulfilledFile();
        return VoluntaryUploadResponse.builder()
                .documentRequestId(dr.getId())
                .documentSeq(file != null ? file.getFileSeq() : null)
                .status(dr.getStatus())
                .documentTypeCode(dr.getDocumentTypeCode())
                .customLabel(dr.getCustomLabel())
                .fileName(file != null ? file.getOriginalFilename() : null)
                .sizeBytes(file != null ? file.getFileSize() : null)
                .build();
    }
}
