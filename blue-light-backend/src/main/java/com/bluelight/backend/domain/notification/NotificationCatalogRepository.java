package com.bluelight.backend.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 알림 카탈로그 메타 Repository.
 *
 * <p>{@code TemplateLinter}가 저장 시점에 변수 화이트리스트를 조회하고,
 * Admin UI 가 코드별 메타(카테고리·중요도·수신자 역할)를 조회할 때 사용한다.</p>
 */
@Repository
public interface NotificationCatalogRepository extends JpaRepository<NotificationCatalog, Long> {

    Optional<NotificationCatalog> findByTemplateCode(String templateCode);

    boolean existsByTemplateCode(String templateCode);
}
