package com.bluelight.backend.domain.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * web_event 집계 조회. 모든 조회는 기간(from) 필터 위에서 동작한다.
 * 그룹 집계는 native query + interface projection.
 */
public interface WebEventRepository extends JpaRepository<WebEvent, Long> {

    /** 키-카운트 투영 (utm_source/campaign/service 별 집계) */
    interface KeyCount {
        String getK();
        long getC();
    }

    /** 일자별 방문/클릭 투영 */
    interface DailyStat {
        String getD();
        long getVisits();
        long getClicks();
    }

    @Query(value = "SELECT COUNT(*) FROM web_event WHERE event_type = :type AND created_at >= :from",
            nativeQuery = true)
    long countByTypeSince(@Param("type") String type, @Param("from") LocalDateTime from);

    @Query(value = "SELECT COUNT(DISTINCT session_id) FROM web_event " +
            "WHERE session_id IS NOT NULL AND created_at >= :from", nativeQuery = true)
    long countUniqueSessionsSince(@Param("from") LocalDateTime from);

    @Query(value = "SELECT COALESCE(NULLIF(utm_source, ''), '(direct)') AS k, COUNT(*) AS c " +
            "FROM web_event WHERE event_type = :type AND created_at >= :from " +
            "GROUP BY k ORDER BY c DESC", nativeQuery = true)
    List<KeyCount> countBySourceSince(@Param("type") String type, @Param("from") LocalDateTime from);

    @Query(value = "SELECT COALESCE(NULLIF(utm_campaign, ''), '(none)') AS k, COUNT(*) AS c " +
            "FROM web_event WHERE event_type = :type AND created_at >= :from " +
            "GROUP BY k ORDER BY c DESC", nativeQuery = true)
    List<KeyCount> countByCampaignSince(@Param("type") String type, @Param("from") LocalDateTime from);

    @Query(value = "SELECT COALESCE(NULLIF(service, ''), '(none)') AS k, COUNT(*) AS c " +
            "FROM web_event WHERE event_type = 'WHATSAPP_CLICK' AND created_at >= :from " +
            "GROUP BY k ORDER BY c DESC", nativeQuery = true)
    List<KeyCount> countClicksByServiceSince(@Param("from") LocalDateTime from);

    @Query(value = "SELECT DATE(created_at) AS d, " +
            "SUM(event_type = 'PAGE_VIEW') AS visits, " +
            "SUM(event_type = 'WHATSAPP_CLICK') AS clicks " +
            "FROM web_event WHERE created_at >= :from " +
            "GROUP BY DATE(created_at) ORDER BY d ASC", nativeQuery = true)
    List<DailyStat> dailyStatsSince(@Param("from") LocalDateTime from);
}
