package com.bluelight.backend.api.lewserviceorder.dto;

import com.bluelight.backend.domain.lewserviceorder.LewServiceOrder;
import com.bluelight.backend.domain.lewserviceorder.LewServiceVisitPhoto;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Request for LEW Service 주문 상세 응답 DTO
 */
@Getter
@Builder
public class LewServiceOrderResponse {

    private Long lewServiceOrderSeq;
    private String address;
    private String postalCode;
    private String buildingType;
    private Integer selectedKva;
    private String applicantNote;
    private Long sketchFileSeq;
    private String status;
    /**
     * 파생 상태: {@code status=VISIT_SCHEDULED && checkInAt != null} 인 경우 {@code true}.
     * 프론트는 이 플래그를 보고 "ON_SITE" UI (스티키 배너 등) 를 렌더링한다.
     */
    private boolean onSite;
    private BigDecimal quoteAmount;
    private String quoteNote;
    private String managerNote;
    /**
     * 방문 보고서 파일 (PR 3). Legacy {@code uploadedFileSeq} 와 같은 값을 담는다.
     */
    private Long visitReportFileSeq;
    /**
     * @deprecated PR 3 — {@link #visitReportFileSeq} 사용 권장. 하위호환용으로 유지.
     */
    @Deprecated
    private Long uploadedFileSeq;
    /**
     * 재방문 요청 사유 (PR 3 — 기존 {@code revisionComment} rename).
     */
    private String revisitComment;
    /**
     * @deprecated PR 3 — {@link #revisitComment} 사용 권장. 하위호환용 alias.
     */
    @Deprecated
    private String revisionComment;
    // LEW Service 방문형 리스키닝 PR 2 — 방문 일정 예약
    private LocalDateTime visitScheduledAt;
    private String visitScheduleNote;
    // PR 3 — 체크인 / 체크아웃 / 사진
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private List<VisitPhotoDto> visitPhotos;
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

    public static LewServiceOrderResponse from(LewServiceOrder order) {
        return from(order, List.of());
    }

    public static LewServiceOrderResponse from(LewServiceOrder order, List<LewServiceVisitPhoto> photos) {
        List<VisitPhotoDto> photoDtos = photos == null ? List.of()
                : photos.stream().map(VisitPhotoDto::from).toList();
        return LewServiceOrderResponse.builder()
                .lewServiceOrderSeq(order.getLewServiceOrderSeq())
                .address(order.getAddress())
                .postalCode(order.getPostalCode())
                .buildingType(order.getBuildingType())
                .selectedKva(order.getSelectedKva())
                .applicantNote(order.getApplicantNote())
                .sketchFileSeq(order.getSketchFileSeq())
                .status(order.getStatus().name())
                .onSite(order.isOnSite())
                .quoteAmount(order.getQuoteAmount())
                .quoteNote(order.getQuoteNote())
                .managerNote(order.getManagerNote())
                .visitReportFileSeq(order.getVisitReportFileSeq())
                .uploadedFileSeq(order.getUploadedFileSeq())
                .revisitComment(order.getRevisitComment())
                .revisionComment(order.getRevisitComment())
                .visitScheduledAt(order.getVisitScheduledAt())
                .visitScheduleNote(order.getVisitScheduleNote())
                .checkInAt(order.getCheckInAt())
                .checkOutAt(order.getCheckOutAt())
                .visitPhotos(photoDtos)
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

        public static VisitPhotoDto from(LewServiceVisitPhoto entity) {
            return VisitPhotoDto.builder()
                    .photoSeq(entity.getPhotoSeq())
                    .fileSeq(entity.getFileSeq())
                    .caption(entity.getCaption())
                    .uploadedAt(entity.getUploadedAt())
                    .build();
        }
    }
}
