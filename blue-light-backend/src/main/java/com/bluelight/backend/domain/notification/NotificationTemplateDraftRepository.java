package com.bluelight.backend.domain.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 알림 템플릿 Draft Repository.
 *
 * <p>SYSTEM_ADMIN 의 리뷰 큐(PENDING 목록)와 NM 의 본인 draft 조회에 사용한다.</p>
 */
@Repository
public interface NotificationTemplateDraftRepository extends JpaRepository<NotificationTemplateDraft, Long> {

    /** SA 리뷰 큐 — 상태별 페이지네이션. */
    Page<NotificationTemplateDraft> findByStatusOrderBySubmittedAtAsc(TemplateDraftStatus status, Pageable pageable);

    /** NM 본인 draft 목록 — 작성자 + 상태 필터. */
    Page<NotificationTemplateDraft> findBySubmittedByAndStatusOrderBySubmittedAtDesc(
            Long submittedBy, TemplateDraftStatus status, Pageable pageable);

    /** 동일 (code, channel, locale) 에 대한 PENDING draft 존재 여부 — 중복 submit 가드. */
    List<NotificationTemplateDraft> findByTemplateCodeAndChannelAndLocaleAndStatus(
            String templateCode, NotificationChannel channel, String locale, TemplateDraftStatus status);
}
