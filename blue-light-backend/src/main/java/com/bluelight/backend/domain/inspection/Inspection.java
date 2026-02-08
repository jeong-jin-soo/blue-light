package com.bluelight.backend.domain.inspection;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.user.User;
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
 * 현장 점검 결과 Entity
 * - inspections 테이블은 created_at 대신 inspected_at을 사용하므로 BaseEntity를 상속하지 않음
 */
@Entity
@Table(name = "inspections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLDelete(sql = "UPDATE inspections SET deleted_at = NOW() WHERE inspection_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class Inspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inspection_seq")
    private Long inspectionSeq;

    /**
     * 관련 신청 (FK)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

    /**
     * 점검한 LEW(관리자) ID (FK)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspector_user_seq", nullable = false)
    private User inspector;

    /**
     * 점검 항목 결과 (JSON)
     * - DB에서 JSON 타입으로 저장되며, Java에서는 String으로 매핑
     * - 실제 사용 시 ObjectMapper로 파싱하여 사용
     */
    @Column(name = "checklist_data", columnDefinition = "json")
    private String checklistData;

    /**
     * 점검자 종합 의견
     */
    @Column(name = "inspector_comment", columnDefinition = "text")
    private String inspectorComment;

    /**
     * 전자서명 이미지 경로
     */
    @Column(name = "signature_url", length = 255)
    private String signatureUrl;

    /**
     * 점검 일시
     */
    @Column(name = "inspected_at")
    private LocalDateTime inspectedAt;

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
    public Inspection(Application application, User inspector, String checklistData,
                      String inspectorComment, String signatureUrl) {
        this.application = application;
        this.inspector = inspector;
        this.checklistData = checklistData;
        this.inspectorComment = inspectorComment;
        this.signatureUrl = signatureUrl;
        this.inspectedAt = LocalDateTime.now();
    }

    /**
     * 점검 결과 수정
     */
    public void updateInspectionResult(String checklistData, String inspectorComment) {
        this.checklistData = checklistData;
        this.inspectorComment = inspectorComment;
    }

    /**
     * 전자서명 등록
     */
    public void registerSignature(String signatureUrl) {
        this.signatureUrl = signatureUrl;
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
        this.inspectedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
