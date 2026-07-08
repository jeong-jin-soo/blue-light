package com.bluelight.backend.api.analytics.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공개 텔레메트리 인제스트 요청 (인증 불필요).
 * 클라이언트(공개 페이지)가 sendBeacon 으로 전송. 모든 필드는 선택적이며,
 * 서버에서 길이 제한·화이트리스트로 방어적으로 정제한다.
 */
@Getter
@Setter
@NoArgsConstructor
public class EventIngestRequest {
    private String type;          // PAGE_VIEW | WHATSAPP_CLICK
    private String path;
    private String utmSource;
    private String utmMedium;
    private String utmCampaign;
    private String utmContent;
    private String referrerHost;
    private String service;
    private String sessionId;
}
