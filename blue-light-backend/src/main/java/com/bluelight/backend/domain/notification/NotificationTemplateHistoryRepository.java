package com.bluelight.backend.domain.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 알림 템플릿 변경 이력 Repository.
 *
 * <p>append-only. soft delete 미적용 — {@code findAll} 류는 모든 행을 반환한다.
 * Admin UI 의 History 탭이 사용하는 주 진입점은 {@link #findByTemplateSeqOrderByChangedAtDesc}.</p>
 */
@Repository
public interface NotificationTemplateHistoryRepository extends JpaRepository<NotificationTemplateHistory, Long> {

    Page<NotificationTemplateHistory> findByTemplateSeqOrderByChangedAtDesc(Long templateSeq, Pageable pageable);

    Page<NotificationTemplateHistory> findByActorUserSeqOrderByChangedAtDesc(Long actorUserSeq, Pageable pageable);
}
