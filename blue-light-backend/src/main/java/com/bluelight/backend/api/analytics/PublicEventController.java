package com.bluelight.backend.api.analytics;

import com.bluelight.backend.api.analytics.dto.EventIngestRequest;
import com.bluelight.backend.domain.analytics.WebEvent;
import com.bluelight.backend.domain.analytics.WebEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 공개 1st-party 텔레메트리 인제스트 (인증 불필요, /api/public/**).
 *
 * <p>공개 페이지의 방문/문의클릭을 우리 서버에만 적재한다. 제3자 트래커·쿠키 미사용.
 * 클라이언트 신뢰 불가 입력이므로 이벤트 타입 화이트리스트 + 길이 제한으로 방어하고,
 * 어떤 경우에도 4xx/5xx 로 클라이언트를 방해하지 않는다(텔레메트리는 best-effort).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicEventController {

    private final WebEventRepository webEventRepository;

    private static final Set<String> ALLOWED_TYPES =
            Set.of(WebEvent.TYPE_PAGE_VIEW, WebEvent.TYPE_WHATSAPP_CLICK);

    /**
     * 이벤트 적재. 항상 204 를 반환한다(수집 실패가 사용자 경험을 해치지 않도록).
     * POST /api/public/events
     */
    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@RequestBody(required = false) EventIngestRequest req) {
        try {
            if (req == null || req.getType() == null || !ALLOWED_TYPES.contains(req.getType())) {
                return ResponseEntity.noContent().build();
            }
            WebEvent event = WebEvent.builder()
                    .eventType(req.getType())
                    .path(cap(req.getPath(), 255))
                    .utmSource(cap(req.getUtmSource(), 64))
                    .utmMedium(cap(req.getUtmMedium(), 64))
                    .utmCampaign(cap(req.getUtmCampaign(), 128))
                    .utmContent(cap(req.getUtmContent(), 128))
                    .referrerHost(cap(req.getReferrerHost(), 255))
                    .service(cap(req.getService(), 64))
                    .sessionId(cap(req.getSessionId(), 40))
                    .build();
            webEventRepository.save(event);
        } catch (Exception e) {
            // 텔레메트리 실패는 조용히 무시
            log.debug("web_event ingest ignored: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** 공백 정리 후 최대 길이 제한. 빈 값은 null 로. */
    private static String cap(String v, int max) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
