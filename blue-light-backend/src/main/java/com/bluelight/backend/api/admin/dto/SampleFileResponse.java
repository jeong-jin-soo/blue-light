package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.file.SampleFile;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 샘플 파일 응답 DTO
 */
@Getter
@Builder
public class SampleFileResponse {

    private Long sampleFileSeq;
    private String categoryKey;
    private String originalFilename;
    private Long fileSize;
    private LocalDateTime uploadedAt;

    public static SampleFileResponse from(SampleFile sampleFile) {
        return SampleFileResponse.builder()
                .sampleFileSeq(sampleFile.getSampleFileSeq())
                .categoryKey(sampleFile.getCategoryKey())
                .originalFilename(sampleFile.getOriginalFilename())
                .fileSize(sampleFile.getFileSize())
                .uploadedAt(sampleFile.getUploadedAt())
                .build();
    }
}
