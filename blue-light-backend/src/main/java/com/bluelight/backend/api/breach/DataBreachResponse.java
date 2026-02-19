package com.bluelight.backend.api.breach;

import com.bluelight.backend.domain.breach.DataBreachNotification;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 데이터 유출 통보 응답 DTO
 */
@Getter
@Builder
public class DataBreachResponse {
    private Long breachSeq;
    private String title;
    private String description;
    private String severity;
    private String status;
    private Integer affectedCount;
    private String dataTypesAffected;
    private String containmentActions;
    private LocalDateTime pdpcNotifiedAt;
    private String pdpcReferenceNo;
    private LocalDateTime usersNotifiedAt;
    private LocalDateTime resolvedAt;
    private Long reportedBy;
    private boolean pdpcOverdue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DataBreachResponse from(DataBreachNotification breach) {
        return DataBreachResponse.builder()
                .breachSeq(breach.getBreachSeq())
                .title(breach.getTitle())
                .description(breach.getDescription())
                .severity(breach.getSeverity().name())
                .status(breach.getStatus().name())
                .affectedCount(breach.getAffectedCount())
                .dataTypesAffected(breach.getDataTypesAffected())
                .containmentActions(breach.getContainmentActions())
                .pdpcNotifiedAt(breach.getPdpcNotifiedAt())
                .pdpcReferenceNo(breach.getPdpcReferenceNo())
                .usersNotifiedAt(breach.getUsersNotifiedAt())
                .resolvedAt(breach.getResolvedAt())
                .reportedBy(breach.getReportedBy())
                .pdpcOverdue(breach.isPdpcNotificationOverdue())
                .createdAt(breach.getCreatedAt())
                .updatedAt(breach.getUpdatedAt())
                .build();
    }
}
