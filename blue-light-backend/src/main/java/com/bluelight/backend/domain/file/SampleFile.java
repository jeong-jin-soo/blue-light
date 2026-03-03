package com.bluelight.backend.domain.file;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 샘플 파일 Entity (카테고리당 1개)
 * - 관리자가 업로드한 참고 파일을 신청자에게 제공
 */
@Entity
@Table(name = "sample_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SampleFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sample_file_seq")
    private Long sampleFileSeq;

    /**
     * 카테고리 키 (예: 'photo', 'sld')
     */
    @Column(name = "category_key", nullable = false, unique = true, length = 30)
    private String categoryKey;

    /**
     * 저장 경로
     */
    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    /**
     * 원본 파일명
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * 파일 크기 (bytes)
     */
    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Builder
    public SampleFile(String categoryKey, String fileUrl, String originalFilename, Long fileSize) {
        this.categoryKey = categoryKey;
        this.fileUrl = fileUrl;
        this.originalFilename = originalFilename;
        this.fileSize = fileSize;
        this.uploadedAt = LocalDateTime.now();
    }

    /**
     * 파일 교체 (재업로드)
     */
    public void updateFile(String fileUrl, String originalFilename, Long fileSize) {
        this.fileUrl = fileUrl;
        this.originalFilename = originalFilename;
        this.fileSize = fileSize;
        this.uploadedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onPrePersist() {
        this.uploadedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
