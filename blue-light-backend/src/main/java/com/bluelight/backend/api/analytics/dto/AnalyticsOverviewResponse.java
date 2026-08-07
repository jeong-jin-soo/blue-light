package com.bluelight.backend.api.analytics.dto;

import java.util.List;

/**
 * admin 유입 분석 개요 응답.
 * 기간(days) 내 방문·문의클릭 총계 + 일자별 추이 + 출처/캠페인/서비스별 분포.
 */
public record AnalyticsOverviewResponse(
        int days,
        long totalVisits,
        long uniqueVisitors,
        long whatsappClicks,
        List<Daily> daily,
        List<KeyCount> clicksBySource,
        List<KeyCount> clicksByCampaign,
        List<KeyCount> clicksByService,
        List<KeyCount> visitsBySource
) {
    public record KeyCount(String key, long count) {}
    public record Daily(String date, long visitors, long visits, long clicks) {}
}
