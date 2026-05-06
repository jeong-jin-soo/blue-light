package com.bluelight.backend.domain.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Notification Repository
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientUserSeqOrderByCreatedAtDesc(Long recipientSeq, Pageable pageable);

    long countByRecipientUserSeqAndIsReadFalse(Long recipientSeq);

    /**
     * PR4: 같은 (수신자, 타입, 참조 엔티티)에 대해 이미 알림이 존재하는지 확인.
     * 결제 확인 이벤트가 어떤 이유로 두 번 발생해도 (예: unconfirm → reconfirm) LEW에게 중복
     * 알림이 발송되지 않도록 멱등성 보장에 사용된다.
     */
    boolean existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
            Long recipientSeq, NotificationType type, String referenceType, Long referenceId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP WHERE n.recipient.userSeq = :userSeq AND n.isRead = false")
    int markAllAsReadByRecipient(@Param("userSeq") Long userSeq);
}
