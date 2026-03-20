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

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP WHERE n.recipient.userSeq = :userSeq AND n.isRead = false")
    int markAllAsReadByRecipient(@Param("userSeq") Long userSeq);
}
