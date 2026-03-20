package com.bluelight.backend.api.notification;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.notification.Notification;
import com.bluelight.backend.domain.notification.NotificationRepository;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /**
     * 알림 생성
     */
    @Transactional
    public Notification createNotification(Long recipientSeq, NotificationType type,
                                            String title, String message,
                                            String referenceType, Long referenceId) {
        User recipient = userRepository.findById(recipientSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(type)
                .title(title)
                .message(message)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .build();

        Notification saved = notificationRepository.save(notification);
        log.info("Notification created: seq={}, type={}, recipientSeq={}", saved.getNotificationSeq(), type, recipientSeq);
        return saved;
    }

    /**
     * 내 알림 목록 조회
     */
    public Page<NotificationResponse> getMyNotifications(Long userSeq, Pageable pageable) {
        return notificationRepository.findByRecipientUserSeqOrderByCreatedAtDesc(userSeq, pageable)
                .map(NotificationResponse::from);
    }

    /**
     * 읽지 않은 알림 건수
     */
    public long getUnreadCount(Long userSeq) {
        return notificationRepository.countByRecipientUserSeqAndIsReadFalse(userSeq);
    }

    /**
     * 단건 읽음 처리
     */
    @Transactional
    public void markAsRead(Long userSeq, Long notificationSeq) {
        Notification notification = notificationRepository.findById(notificationSeq)
                .orElseThrow(() -> new BusinessException("Notification not found", HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND"));

        if (!notification.getRecipient().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (!notification.isRead()) {
            notification.markAsRead();
        }
    }

    /**
     * 전체 읽음 처리
     */
    @Transactional
    public void markAllAsRead(Long userSeq) {
        int updated = notificationRepository.markAllAsReadByRecipient(userSeq);
        log.info("Marked {} notifications as read for userSeq={}", updated, userSeq);
    }
}
