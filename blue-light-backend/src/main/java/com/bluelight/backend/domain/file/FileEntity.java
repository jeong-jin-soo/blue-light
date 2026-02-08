package com.bluelight.backend.domain.file;

import com.bluelight.backend.domain.application.Application;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 첨부 파일 관리 Entity
 * - 클래스명을 FileEntity로 지정 (java.io.File과 충돌 방지)
 * - files 테이블은 created_at 대신 uploaded_at을 사용하므로 BaseEntity를 상속하지 않음
 */
@Entity
@Table(name = "files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLDelete(sql = "UPDATE files SET deleted_at = NOW() WHERE file_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class FileEntity {

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
     * 업로드 일시
     */
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    /**
     * 수정 일시
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 생성자 ID
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    /**
     * 수정자 ID
     */
    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    /**
     * 삭제 일시 (Soft Delete)
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public FileEntity(Application application, FileType fileType, String fileUrl, String originalFilename) {
        this.application = application;
        this.fileType = fileType;
        this.fileUrl = fileUrl;
        this.originalFilename = originalFilename;
        this.uploadedAt = LocalDateTime.now();
    }

    /**
     * 파일 URL 변경 (재업로드 시)
     */
    public void updateFileUrl(String fileUrl, String originalFilename) {
        this.fileUrl = fileUrl;
        this.originalFilename = originalFilename;
        this.uploadedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Soft Delete 수행
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 삭제 여부 확인
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
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
