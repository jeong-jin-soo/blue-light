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
import org.springframework.transaction.annotation.Propagation;
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
     * 알림 생성 — afterCommit 훅에서 호출될 수 있으므로 REQUIRES_NEW로 독립 트랜잭션 보장.
     * saveAndFlush()로 즉시 영속화하여 ID 생성 확인.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification createNotification(Long recipientSeq, NotificationType type,
                                            String title, String message,
                                            String referenceType, Long referenceId) {
        User recipient = userRepository.findById(recipientSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // 딥링크(linkUrl) — 수신자 역할 인지 단일 해석기로 생성. 클릭 시 처리 화면의 해당 위치로 이동.
        String linkUrl = NotificationLinkResolver.resolve(type, referenceType, referenceId, recipient.getRole());

        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(type)
                .title(title)
                .message(message)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .linkUrl(linkUrl)
                .build();

        Notification saved = notificationRepository.saveAndFlush(notification);
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
