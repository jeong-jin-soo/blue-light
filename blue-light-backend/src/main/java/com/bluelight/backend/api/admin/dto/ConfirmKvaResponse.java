package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.application.Application;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Phase 5 — LEW/ADMIN kVA 확정 응답 DTO.
 *
 * <p>Spec: {@code phase5-kva-ux/01-spec.md §4} (AC-A1).
 */
@Getter
@Builder
public class ConfirmKvaResponse {

    private Long applicationId;
    private String kvaStatus;
    private String kvaSource;
    private Integer selectedKva;
    private BigDecimal quoteAmount;
    private Long kvaConfirmedBy;
    private LocalDateTime kvaConfirmedAt;

    public static ConfirmKvaResponse from(Application app) {
        return ConfirmKvaResponse.builder()
                .applicationId(app.getApplicationSeq())
                .kvaStatus(app.getKvaStatus() != null ? app.getKvaStatus().name() : null)
                .kvaSource(app.getKvaSource() != null ? app.getKvaSource().name() : null)
                .selectedKva(app.getSelectedKva())
                .quoteAmount(app.getQuoteAmount())
                .kvaConfirmedBy(app.getKvaConfirmedBy() != null
                        ? app.getKvaConfirmedBy().getUserSeq() : null)
                .kvaConfirmedAt(app.getKvaConfirmedAt())
                .build();
    }
}
