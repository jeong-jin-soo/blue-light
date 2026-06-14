package com.bluelight.backend.api.notification;

import com.bluelight.backend.domain.notification.Notification;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class NotificationResponse {
    private Long notificationSeq;
    private String type;
    private String title;
    private String message;
    private String referenceType;
    private Long referenceId;
    /** 알림 클릭 시 이동할 프론트 라우트 상대경로(+섹션 해시). null 이면 프론트 fallback 라우팅. */
    private String linkUrl;
    private boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public static NotificationResponse from(Notification n) {
        return NotificationResponse.builder()
                .notificationSeq(n.getNotificationSeq())
                .type(n.getType().name())
                .title(n.getTitle())
                .message(n.getMessage())
                .referenceType(n.getReferenceType())
                .referenceId(n.getReferenceId())
                .linkUrl(n.getLinkUrl())
                .isRead(n.isRead())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
