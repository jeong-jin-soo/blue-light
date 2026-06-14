package com.bluelight.backend.domain.loaform;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * LoA(Letter of Appointment) 폼 템플릿 버전 row.
 *
 * <p>스펙: {@code doc/Project Analysis/loa-exchange-redesign-spec.md} §2.1 (PR2).
 * 설정 우선 원칙(SSOT)에 따라 admin 이 관리하는 최신 LoA 폼을 버전으로 보존하고,
 * 신청별로 사용 폼 버전을 추적(PR3)할 수 있게 한다. (NEW 전용)</p>
 *
 * <h2>설계 메모</h2>
 * <ul>
 *   <li><b>active 단일성</b> — MySQL 8.0 은 부분 유니크 인덱스를 지원하지 않으므로 동시 active 1건
 *       보장은 서비스 레벨에서("신규 활성화 시 기존 active 를 동일 트랜잭션 내 비활성화") 처리한다.
 *       PayNow QR 의 "기존 삭제 후 신규" 패턴과 동형
 *       ({@code AdminPriceSettingsController#uploadPaymentQr}).</li>
 *   <li><b>soft delete</b> — 프로젝트 표준({@code @SQLDelete + @SQLRestriction}). 신청에 참조된 버전은
 *       법적 추적성을 위해 hard delete 금지. (PR2 에서는 Application.loaFormTemplateSeq 가 아직 없어
 *       "참조 중이면 409 LOA_FORM_IN_USE" 가드는 PR3 에서 추가.)</li>
 *   <li><b>파일 저장</b> — {@code fileStorageService.store(file, "loa-form-templates")} 로 저장된
 *       PDF 의 {@code files.file_seq} 를 {@link #fileSeq} 로 참조한다.</li>
 *   <li><b>BaseEntity audit</b> — createdAt/updatedAt/createdBy/updatedBy 자동 채움.</li>
 * </ul>
 */
@Entity
@Table(
        name = "loa_form_templates",
        indexes = {
                @Index(name = "idx_loa_form_active", columnList = "is_active"),
                @Index(name = "idx_loa_form_uploaded_at", columnList = "uploaded_at DESC")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE loa_form_templates SET deleted_at = NOW() WHERE loa_form_template_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class LoaFormTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "loa_form_template_seq")
    private Long loaFormTemplateSeq;

    /** 운영용 표시 라벨 (예: "EMA NEW LoA v2026.06"). */
    @Column(name = "label", nullable = false, length = 150)
    private String label;

    /** files.file_seq FK — 저장된 폼 PDF. */
    @Column(name = "file_seq", nullable = false)
    private Long fileSeq;

    /** 현재 active 폼 여부. 동시 active 1건은 서비스 레벨 보장. */
    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /** 업로드한 admin/system_admin user_seq (FK users.user_seq). */
    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Builder
    private LoaFormTemplate(String label, Long fileSeq, boolean isActive, Long uploadedBy) {
        this.label = label;
        this.fileSeq = fileSeq;
        this.isActive = isActive;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = LocalDateTime.now();
    }

    /** 활성화 — 동시 active 1건 보장은 서비스 레벨에서 기존 active 를 먼저 비활성화한 뒤 호출. */
    public void activate() {
        this.isActive = true;
    }

    /** 비활성화. */
    public void deactivate() {
        this.isActive = false;
    }
}
