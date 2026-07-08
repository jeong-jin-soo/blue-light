package com.bluelight.backend.domain.analytics;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 1st-party 유입/문의 텔레메트리 이벤트.
 *
 * <p>공개 페이지 방문(PAGE_VIEW) 및 WhatsApp 문의 클릭(WHATSAPP_CLICK)을 우리 서버에만 적재한다.
 * 제3자 분석/광고 트래커·쿠키를 쓰지 않으며(개인정보처리방침 준수), 개인정보 최소수집 원칙:
 * IP·전체 URL 미저장, referrer 는 host 만, session_id 는 클라이언트 sessionStorage 랜덤값(쿠키 아님).</p>
 */
@Entity
@Table(name = "web_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_seq")
    private Long eventSeq;

    @Column(name = "event_type", length = 32, nullable = false)
    private String eventType;

    @Column(name = "path", length = 255)
    private String path;

    @Column(name = "utm_source", length = 64)
    private String utmSource;

    @Column(name = "utm_medium", length = 64)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 128)
    private String utmCampaign;

    @Column(name = "utm_content", length = 128)
    private String utmContent;

    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    @Column(name = "service", length = 64)
    private String service;

    @Column(name = "session_id", length = 40)
    private String sessionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 이벤트 종류 상수 (검증·조회 공용) */
    public static final String TYPE_PAGE_VIEW = "PAGE_VIEW";
    public static final String TYPE_WHATSAPP_CLICK = "WHATSAPP_CLICK";

    @Builder
    private WebEvent(String eventType, String path, String utmSource, String utmMedium,
                     String utmCampaign, String utmContent, String referrerHost,
                     String service, String sessionId) {
        this.eventType = eventType;
        this.path = path;
        this.utmSource = utmSource;
        this.utmMedium = utmMedium;
        this.utmCampaign = utmCampaign;
        this.utmContent = utmContent;
        this.referrerHost = referrerHost;
        this.service = service;
        this.sessionId = sessionId;
    }
}
