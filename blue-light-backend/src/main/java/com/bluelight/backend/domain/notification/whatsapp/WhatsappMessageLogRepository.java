package com.bluelight.backend.domain.notification.whatsapp;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * WhatsApp 발송 로그 Repository.
 *
 * <p>Webhook 핸들러는 {@code providerMessageId} 로 row 를 찾아 상태를 갱신한다.
 * 운영 콘솔(admin)은 user_seq 기준으로 이력을 조회한다.</p>
 */
@Repository
public interface WhatsappMessageLogRepository extends JpaRepository<WhatsappMessageLog, Long> {

    Optional<WhatsappMessageLog> findByProviderMessageId(String providerMessageId);

    /** outbox row 와 1:1 — 재발송/중복 가드 확인용. */
    Optional<WhatsappMessageLog> findByOutboxSeq(Long outboxSeq);

    Page<WhatsappMessageLog> findByUserSeqOrderByCreatedAtDesc(Long userSeq, Pageable pageable);

    List<WhatsappMessageLog> findByUserSeqAndStatusOrderByCreatedAtDesc(
            Long userSeq, WhatsappDeliveryStatus status);
}
