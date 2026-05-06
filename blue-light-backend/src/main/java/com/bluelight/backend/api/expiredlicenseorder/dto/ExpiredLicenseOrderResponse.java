package com.bluelight.backend.api.expiredlicenseorder.dto;

import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrder;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseVisitPhoto;
import com.bluelight.backend.domain.file.FileEntity;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ExpiredLicenseOrderResponse {

    private Long expiredLicenseOrderSeq;
    private String address;
    private String postalCode;
    private String buildingType;
    private Integer selectedKva;
    private String applicantNote;
    private String status;
    private boolean onSite;
    private BigDecimal quoteAmount;
    private String quoteNote;
    private String managerNote;
    private Long visitReportFileSeq;
    private String revisitComment;
    private LocalDateTime visitScheduledAt;
    private String visitScheduleNote;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private List<VisitPhotoDto> visitPhotos;
    private List<SupportingDocumentDto> supportingDocuments;
    private String assignedManagerFirstName;
    private String assignedManagerLastName;
    private Long assignedManagerSeq;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Long userSeq;
    private String userFirstName;
    private String userLastName;
    private String userEmail;

    public static ExpiredLicenseOrderResponse from(ExpiredLicenseOrder order,
                                                   List<ExpiredLicenseVisitPhoto> photos,
                                                   List<FileEntity> supportingDocs) {
        List<VisitPhotoDto> photoDtos = photos == null ? List.of()
                : photos.stream().map(VisitPhotoDto::from).toList();
        List<SupportingDocumentDto> docDtos = supportingDocs == null ? List.of()
                : supportingDocs.stream().map(SupportingDocumentDto::from).toList();
        return ExpiredLicenseOrderResponse.builder()
                .expiredLicenseOrderSeq(order.getExpiredLicenseOrderSeq())
                .address(order.getAddress())
                .postalCode(order.getPostalCode())
                .buildingType(order.getBuildingType())
                .selectedKva(order.getSelectedKva())
                .applicantNote(order.getApplicantNote())
                .status(order.getStatus().name())
                .onSite(order.isOnSite())
                .quoteAmount(order.getQuoteAmount())
                .quoteNote(order.getQuoteNote())
                .managerNote(order.getManagerNote())
                .visitReportFileSeq(order.getVisitReportFileSeq())
                .revisitComment(order.getRevisitComment())
                .visitScheduledAt(order.getVisitScheduledAt())
                .visitScheduleNote(order.getVisitScheduleNote())
                .checkInAt(order.getCheckInAt())
                .checkOutAt(order.getCheckOutAt())
                .visitPhotos(photoDtos)
                .supportingDocuments(docDtos)
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

    @Getter
    @Builder
    public static class VisitPhotoDto {
        private Long photoSeq;
        private Long fileSeq;
        private String caption;
        private LocalDateTime uploadedAt;

        public static VisitPhotoDto from(ExpiredLicenseVisitPhoto entity) {
            return VisitPhotoDto.builder()
                    .photoSeq(entity.getPhotoSeq())
                    .fileSeq(entity.getFileSeq())
                    .caption(entity.getCaption())
                    .uploadedAt(entity.getUploadedAt())
                    .build();
        }
    }

    @Getter
    @Builder
    public static class SupportingDocumentDto {
        private Long fileSeq;
        private String fileType;
        private String originalFilename;
        private Long fileSize;
        private LocalDateTime uploadedAt;

        public static SupportingDocumentDto from(FileEntity entity) {
            return SupportingDocumentDto.builder()
                    .fileSeq(entity.getFileSeq())
                    .fileType(entity.getFileType().name())
                    .originalFilename(entity.getOriginalFilename())
                    .fileSize(entity.getFileSize())
                    .uploadedAt(entity.getUploadedAt())
                    .build();
        }
    }
}
