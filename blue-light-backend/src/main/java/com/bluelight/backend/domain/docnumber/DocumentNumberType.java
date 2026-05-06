package com.bluelight.backend.domain.docnumber;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 문서 타입 마스터 엔티티.
 *
 * <p>공통 문서번호 생성기가 발번할 수 있는 문서 종류 목록. 관리자가 Admin UI에서 CRUD 가능
 * (Phase 2). 스펙: {@code doc/Project Analysis/document-number-generator-spec.md §4}.</p>
 *
 * <p>Soft delete만 허용 — 이미 발행된 번호와의 정합성을 위해 물리 삭제 금지.</p>
 */
@Entity
@Table(name = "document_number_types")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE document_number_types SET deleted_at = NOW() WHERE code = ?")
@SQLRestriction("deleted_at IS NULL")
public class DocumentNumberType extends BaseEntity {

    /** 논리 식별자 (예: RECEIPT, SLD_ORDER). {@code [A-Z_]{3,40}}. */
    @Id
    @Column(name = "code", length = 40, nullable = false, updatable = false)
    private String code;

    /** 번호 체계의 2차 접두어 (예: RCP). {@code [A-Z]{2,5}}. */
    @Column(name = "prefix", length = 10, nullable = false)
    private String prefix;

    @Column(name = "label_ko", length = 120, nullable = false)
    private String labelKo;

    @Column(name = "label_en", length = 120, nullable = false)
    private String labelEn;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Builder
    public DocumentNumberType(String code,
                              String prefix,
                              String labelKo,
                              String labelEn,
                              String description,
                              Boolean active,
                              Integer displayOrder) {
        this.code = code;
        this.prefix = prefix;
        this.labelKo = labelKo;
        this.labelEn = labelEn;
        this.description = description;
        this.active = active != null ? active : Boolean.TRUE;
        this.displayOrder = displayOrder != null ? displayOrder : 0;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(this.active);
    }
}
