package com.bluelight.backend.api.powersocketorder.dto;

import com.bluelight.backend.domain.powersocketorder.PowerSocketOrder;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Power Socket 주문 상세 응답 DTO
 */
@Getter
@Builder
public class PowerSocketOrderResponse {

    private Long powerSocketOrderSeq;
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

    public static PowerSocketOrderResponse from(PowerSocketOrder order) {
        return PowerSocketOrderResponse.builder()
                .powerSocketOrderSeq(order.getPowerSocketOrderSeq())
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
