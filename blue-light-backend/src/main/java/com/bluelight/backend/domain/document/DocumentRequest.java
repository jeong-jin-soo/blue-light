package com.bluelight.backend.domain.document;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.common.BaseEntity;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.http.HttpStatus;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 신청서 단위 서류 요청/제출 레코드
 *
 * Phase 2: 신청자 자발적 업로드(status=UPLOADED, requestedBy=null) 사용
 * Phase 3: LEW 요청 → 신청자 fulfill → LEW 승인/반려 흐름 활성화
 *
 * 한 신청 + 한 type에 대해 복수 row 허용 (재업로드/이력 관리)
 */
@Entity
@Table(name = "document_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLDelete(sql = "UPDATE document_request SET deleted_at = NOW() WHERE document_request_id = ?")
@SQLRestriction("deleted_at IS NULL")
public class DocumentRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_request_id")
    private Long id;

    /**
     * 낙관적 락 버전 (B-1 동시 승인/반려 race 방지)
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * 대상 신청서
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

    /**
     * 카탈로그 코드 (FK to document_type_catalog.code)
     * - 직접 String 매핑(엔티티 fetch 비용 회피, 검증은 서비스 계층)
     */
    @Column(name = "document_type_code", length = 40, nullable = false)
    private String documentTypeCode;

    /**
     * OTHER 타입일 때 사용자 정의 라벨 (필수)
     */
    @Column(name = "custom_label", length = 200)
    private String customLabel;

    /**
     * LEW가 요청 시 첨부한 메모 (Phase 3)
     */
    @Column(name = "lew_note", length = 1000)
    private String lewNote;

    /**
     * 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DocumentRequestStatus status;

    /**
     * 첨부된 파일 (UPLOADED 이상에서 설정)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfilled_file_seq")
    private FileEntity fulfilledFile;

    /**
     * 요청자 (LEW). Phase 2 자발적 업로드는 null.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    /**
     * 요청 시점 (LEW 생성 시점, Phase 3)
     */
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    /**
     * 파일 첨부 완료 시점
     */
    @Column(name = "fulfilled_at")
    private LocalDateTime fulfilledAt;

    /**
     * 검토 시점 (Phase 3)
     */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * 검토자 (Phase 3)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    /**
     * 반려 사유 (Phase 3, REJECTED일 때)
     */
    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Builder
    public DocumentRequest(Application application,
                           String documentTypeCode,
                           String customLabel,
                           String lewNote,
                           DocumentRequestStatus status,
                           FileEntity fulfilledFile,
                           User requestedBy,
                           LocalDateTime requestedAt,
                           LocalDateTime fulfilledAt) {
        this.application = application;
        this.documentTypeCode = documentTypeCode;
        this.customLabel = customLabel;
        this.lewNote = lewNote;
        this.status = status != null ? status : DocumentRequestStatus.UPLOADED;
        this.fulfilledFile = fulfilledFile;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.fulfilledAt = fulfilledAt;
    }

    // ----------------------------------------------------------------------
    // 팩터리
    // ----------------------------------------------------------------------

    /**
     * Phase 2 자발적 업로드 팩터리
     */
    public static DocumentRequest forVoluntaryUpload(Application application,
                                                     String documentTypeCode,
                                                     String customLabel,
                                                     FileEntity file) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentRequest.builder()
                .application(application)
                .documentTypeCode(documentTypeCode)
                .customLabel(customLabel)
                .status(DocumentRequestStatus.UPLOADED)
                .fulfilledFile(file)
                .fulfilledAt(now)
                .build();
    }

    /**
     * Phase 3 LEW 요청 팩터리 — status=REQUESTED, requestedBy/At 설정, 파일 미첨부
     */
    public static DocumentRequest forLewRequest(Application application,
                                                String documentTypeCode,
                                                String customLabel,
                                                String lewNote,
                                                User requester) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentRequest.builder()
                .application(application)
                .documentTypeCode(documentTypeCode)
                .customLabel(customLabel)
                .lewNote(lewNote)
                .status(DocumentRequestStatus.REQUESTED)
                .requestedBy(requester)
                .requestedAt(now)
                .build();
    }

    // ----------------------------------------------------------------------
    // 상태 전이 메서드 (중앙 가드)
    // ----------------------------------------------------------------------

    /**
     * 상태 전이 가드. 불법 전이 시 409 INVALID_STATE_TRANSITION.
     */
    private void assertCanTransitionTo(DocumentRequestStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new BusinessException(
                    "Illegal state transition: " + this.status + " -> " + next,
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION");
        }
    }

    /**
     * 신청자 fulfill — REQUESTED/REJECTED/UPLOADED → UPLOADED.
     * 재업로드 시 rejection_reason 은 보존하고 reviewed_by/at 만 초기화한다.
     */
    public void fulfill(FileEntity newFile) {
        assertCanTransitionTo(DocumentRequestStatus.UPLOADED);
        this.fulfilledFile = newFile;
        this.fulfilledAt = LocalDateTime.now();
        // 재업로드(REJECTED → UPLOADED 또는 UPLOADED → UPLOADED) 시 검토 기록 초기화
        this.reviewedAt = null;
        this.reviewedBy = null;
        this.status = DocumentRequestStatus.UPLOADED;
    }

    /**
     * LEW 승인 — UPLOADED → APPROVED
     */
    public void approve(User reviewer) {
        assertCanTransitionTo(DocumentRequestStatus.APPROVED);
        this.status = DocumentRequestStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * LEW 반려 — UPLOADED → REJECTED
     */
    public void reject(User reviewer, String reason) {
        assertCanTransitionTo(DocumentRequestStatus.REJECTED);
        this.status = DocumentRequestStatus.REJECTED;
        this.rejectionReason = reason;
        this.reviewedBy = reviewer;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 신청자가 자발적 업로드 후 삭제 또는 LEW 가 REQUESTED 취소 → CANCELLED
     */
    public void cancel() {
        assertCanTransitionTo(DocumentRequestStatus.CANCELLED);
        this.status = DocumentRequestStatus.CANCELLED;
    }

}
