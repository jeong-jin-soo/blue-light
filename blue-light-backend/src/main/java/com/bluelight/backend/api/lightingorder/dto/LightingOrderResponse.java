package com.bluelight.backend.api.lightingorder.dto;

import com.bluelight.backend.domain.lightingorder.LightingOrder;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lighting Layout 주문 상세 응답 DTO
 */
@Getter
@Builder
public class LightingOrderResponse {

    private Long lightingOrderSeq;
    private String address;
    private String postalCode;
    private String buildingType;
    private Integer selectedKva;
    private String applicantNote;
    private Long sketchFileSeq;
    private String status;
    private BigDecimal quoteAmount;
    private String quoteNote;
    private String managerNote;
    private Long uploadedFileSeq;
    private String revisionComment;
    private String assignedManagerFirstName;
    private String assignedManagerLastName;
    private Long assignedManagerSeq;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Applicant info
    private Long userSeq;
    private String userFirstName;
    private String userLastName;
    private String userEmail;

    public static LightingOrderResponse from(LightingOrder order) {
        return LightingOrderResponse.builder()
                .lightingOrderSeq(order.getLightingOrderSeq())
                .address(order.getAddress())
                .postalCode(order.getPostalCode())
                .buildingType(order.getBuildingType())
                .selectedKva(order.getSelectedKva())
                .applicantNote(order.getApplicantNote())
                .sketchFileSeq(order.getSketchFileSeq())
                .status(order.getStatus().name())
                .quoteAmount(order.getQuoteAmount())
                .quoteNote(order.getQuoteNote())
                .managerNote(order.getManagerNote())
                .uploadedFileSeq(order.getUploadedFileSeq())
                .revisionComment(order.getRevisionComment())
                .assignedManagerFirstName(order.getAssignedManager() != null
                        ? order.getAssignedManager().getFirstName() : null)
                .assignedManagerLastName(order.getAssignedManager() != null
                        ? order.getAssignedManager().getLastName() : null)
                .assignedManagerSeq(order.getAssignedManager() != null
                        ? order.getAssignedManager().getUserSeq() : null)
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .userSeq(order.getUser().getUserSeq())
                .userFirstName(order.getUser().getFirstName())
                .userLastName(order.getUser().getLastName())
                .userEmail(order.getUser().getEmail())
                .build();
    }
}
