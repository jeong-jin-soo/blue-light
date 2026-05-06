package com.bluelight.backend.api.document.dto;

import com.bluelight.backend.domain.document.DocumentTypeCatalog;
import lombok.Builder;
import lombok.Getter;

/**
 * Document Type Catalog 응답 DTO
 */
@Getter
@Builder
public class DocumentTypeDto {

    private String code;
    private String labelEn;
    private String labelKo;
    private String description;
    private String helpText;
    private String acceptedMime;
    private Integer maxSizeMb;
    private String templateUrl;
    private String exampleImageUrl;
    private String requiredFields;
    private String iconEmoji;
    private Integer displayOrder;

    public static DocumentTypeDto from(DocumentTypeCatalog c) {
        return DocumentTypeDto.builder()
                .code(c.getCode())
                .labelEn(c.getLabelEn())
                .labelKo(c.getLabelKo())
                .description(c.getDescription())
                .helpText(c.getHelpText())
                .acceptedMime(c.getAcceptedMime())
                .maxSizeMb(c.getMaxSizeMb())
                .templateUrl(c.getTemplateUrl())
                .exampleImageUrl(c.getExampleImageUrl())
                .requiredFields(c.getRequiredFields())
                .iconEmoji(c.getIconEmoji())
                .displayOrder(c.getDisplayOrder())
                .build();
    }
}
