package com.bluelight.backend.domain.file;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 첨부 파일 관리 Entity
 * - 클래스명을 FileEntity로 지정 (java.io.File과 충돌 방지)
 */
@Entity
@Table(name = "files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE files SET deleted_at = NOW() WHERE file_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class FileEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "file_seq")
    private Long fileSeq;

    /**
     * 관련 신청 (FK)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

    /**
     * 파일 종류
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false)
    private FileType fileType;

    /**
     * S3 저장 경로
     */
    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    /**
     * 원본 파일명
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * 업로드 일시 (BaseEntity의 createdAt과 별도로 명시적 관리)
     */
    @Column(name = "uploaded_at")
    private java.time.LocalDateTime uploadedAt;

    @Builder
    public FileEntity(Application application, FileType fileType, String fileUrl, String originalFilename) {
        this.application = application;
        this.fileType = fileType;
        this.fileUrl = fileUrl;
        this.originalFilename = originalFilename;
        this.uploadedAt = java.time.LocalDateTime.now();
    }

    /**
     * 파일 URL 변경 (재업로드 시)
     */
    public void updateFileUrl(String fileUrl, String originalFilename) {
        this.fileUrl = fileUrl;
        this.originalFilename = originalFilename;
        this.uploadedAt = java.time.LocalDateTime.now();
    }
}
