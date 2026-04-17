package com.bluelight.backend.domain.document;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 신청서 첨부 가능한 표준 서류 종류 카탈로그
 * - PK는 코드 문자열 (예: SP_ACCOUNT, LOA, OTHER)
 * - 운영 중 추가/비활성화 가능 (active=false)
 * - DocumentRequest가 FK로 참조
 */
@Entity
@Table(name = "document_type_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class DocumentTypeCatalog extends BaseEntity {

    /**
     * 코드 (PK). 예: SP_ACCOUNT, LOA, OTHER
     */
    @Id
    @Column(name = "code", length = 40, nullable = false)
    private String code;

    /**
     * 영문 라벨 (필수)
     */
    @Column(name = "label_en", length = 120, nullable = false)
    private String labelEn;

    /**
     * 한글 라벨 (필수)
     */
    @Column(name = "label_ko", length = 120, nullable = false)
    private String labelKo;

    /**
     * 짧은 설명
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 사용자 도움말 (업로드 화면 노출용)
     */
    @Column(name = "help_text", length = 1000)
    private String helpText;

    /**
     * 허용 MIME 타입 (쉼표 구분: "application/pdf,image/png")
     */
    @Column(name = "accepted_mime", length = 200, nullable = false)
    private String acceptedMime;

    /**
     * 최대 크기 (MB). 서버 검증용.
     */
    @Column(name = "max_size_mb", nullable = false)
    private Integer maxSizeMb;

    /**
     * 양식 다운로드 URL (nullable)
     */
    @Column(name = "template_url", length = 500)
    private String templateUrl;

    /**
     * 예시 이미지 URL (nullable)
     */
    @Column(name = "example_image_url", length = 500)
    private String exampleImageUrl;

    /**
     * 필수 메타데이터 필드 정의 (JSON, nullable)
     */
    @Column(name = "required_fields", columnDefinition = "JSON")
    private String requiredFields;

    /**
     * 아이콘 이모지 (UI 노출)
     */
    @Column(name = "icon_emoji", length = 16)
    private String iconEmoji;

    /**
     * 표시 순서 (오름차순)
     */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /**
     * 활성화 여부
     */
    @Column(name = "active", nullable = false)
    private Boolean active;

    @Builder
    public DocumentTypeCatalog(String code,
                               String labelEn,
                               String labelKo,
                               String description,
                               String helpText,
                               String acceptedMime,
                               Integer maxSizeMb,
                               String templateUrl,
                               String exampleImageUrl,
                               String requiredFields,
                               String iconEmoji,
                               Integer displayOrder,
                               Boolean active) {
        this.code = code;
        this.labelEn = labelEn;
        this.labelKo = labelKo;
        this.description = description;
        this.helpText = helpText;
        this.acceptedMime = acceptedMime;
        this.maxSizeMb = maxSizeMb != null ? maxSizeMb : 10;
        this.templateUrl = templateUrl;
        this.exampleImageUrl = exampleImageUrl;
        this.requiredFields = requiredFields;
        this.iconEmoji = iconEmoji;
        this.displayOrder = displayOrder != null ? displayOrder : 0;
        this.active = active != null ? active : Boolean.TRUE;
    }
}
