package com.bluelight.backend.api.file.dto;

import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * File response DTO
 */
@Getter
@Builder
public class FileResponse {

    private Long fileSeq;
    private Long applicationSeq;
    private FileType fileType;
    private String originalFilename;
    private Long fileSize;
    private LocalDateTime uploadedAt;

    public static FileResponse from(FileEntity fileEntity) {
        return FileResponse.builder()
                .fileSeq(fileEntity.getFileSeq())
                .applicationSeq(fileEntity.getApplication().getApplicationSeq())
                .fileType(fileEntity.getFileType())
                .originalFilename(fileEntity.getOriginalFilename())
                .fileSize(fileEntity.getFileSize())
                .uploadedAt(fileEntity.getUploadedAt())
                .build();
    }
}
