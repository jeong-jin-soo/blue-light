package com.bluelight.backend.api.analytics;

import com.bluelight.backend.api.analytics.dto.AnalyticsOverviewResponse;
import com.bluelight.backend.api.analytics.dto.AnalyticsOverviewResponse.Daily;
import com.bluelight.backend.api.analytics.dto.AnalyticsOverviewResponse.KeyCount;
import com.bluelight.backend.domain.analytics.WebEvent;
import com.bluelight.backend.domain.analytics.WebEventRepository;
import com.bluelight.backend.domain.analytics.WebEventRepository.DailyStat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * admin 유입/문의 분석 (1st-party). ADMIN/LEW/SYSTEM_ADMIN 접근 (/api/admin/**).
 */
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final WebEventRepository repo;

    /**
     * 개요. GET /api/admin/analytics/overview?days=30
     * days 는 1~365 로 클램프.
     */
    @GetMapping("/overview")
    public ResponseEntity<AnalyticsOverviewResponse> overview(
            @RequestParam(name = "days", defaultValue = "30") int days) {
        int d = Math.max(1, Math.min(days, 365));
        LocalDateTime from = LocalDate.now().minusDays(d - 1L).atStartOfDay();

        long totalVisits = repo.countByTypeSince(WebEvent.TYPE_PAGE_VIEW, from);
        long uniqueVisitors = repo.countUniqueSessionsSince(from);
        long clicks = repo.countByTypeSince(WebEvent.TYPE_WHATSAPP_CLICK, from);

        List<Daily> daily = repo.dailyStatsSince(from).stream()
                .map(s -> new Daily(String.valueOf(s.getD()), s.getVisitors(), s.getVisits(), s.getClicks()))
                .toList();

        AnalyticsOverviewResponse body = new AnalyticsOverviewResponse(
                d,
                totalVisits,
                uniqueVisitors,
                clicks,
                daily,
                toKeyCounts(repo.countBySourceSince(WebEvent.TYPE_WHATSAPP_CLICK, from)),
                toKeyCounts(repo.countByCampaignSince(WebEvent.TYPE_WHATSAPP_CLICK, from)),
                toKeyCounts(repo.countClicksByServiceSince(from)),
                toKeyCounts(repo.countBySourceSince(WebEvent.TYPE_PAGE_VIEW, from))
        );
        return ResponseEntity.ok(body);
    }

    private static List<KeyCount> toKeyCounts(List<WebEventRepository.KeyCount> rows) {
        return rows.stream().map(r -> new KeyCount(r.getK(), r.getC())).toList();
    }
}
