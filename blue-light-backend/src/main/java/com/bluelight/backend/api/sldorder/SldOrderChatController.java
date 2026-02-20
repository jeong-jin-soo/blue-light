package com.bluelight.backend.api.sldorder;

import com.bluelight.backend.api.admin.dto.SldChatMessageResponse;
import com.bluelight.backend.api.admin.dto.SldChatRequest;
import com.bluelight.backend.api.sldorder.dto.SldOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * SLD 전용 주문 AI 채팅 API 컨트롤러
 * - SLD_MANAGER / ADMIN / SYSTEM_ADMIN이 AI와 대화하며 SLD를 생성
 * - SldOrderAgentService를 통해 Python AI 서비스와 통신
 */
@Slf4j
@RestController
@RequestMapping("/api/sld-manager/orders/{id}/sld-chat")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
public class SldOrderChatController {

    private final SldOrderAgentService sldOrderAgentService;

    /**
     * SSE 스트리밍 채팅
     * POST /api/sld-manager/orders/{id}/sld-chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @PathVariable Long id,
            @Valid @RequestBody SldChatRequest request,
            Authentication authentication) {

        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD Order chat stream: sldOrderSeq={}, userSeq={}, message={}",
                id, userSeq, request.getMessage().substring(0, Math.min(request.getMessage().length(), 50)));

        SseEmitter emitter = new SseEmitter((long) 120_000);
        sldOrderAgentService.chatStream(id, userSeq, request.getMessage(), emitter);

        return emitter;
    }

    /**
     * 채팅 이력 조회
     * GET /api/sld-manager/orders/{id}/sld-chat/history
     */
    @GetMapping("/history")
    public ResponseEntity<List<SldChatMessageResponse>> getChatHistory(@PathVariable Long id) {
        log.info("SLD Order chat history: sldOrderSeq={}", id);
        List<SldChatMessageResponse> history = sldOrderAgentService.getChatHistory(id);
        return ResponseEntity.ok(history);
    }

    /**
     * 대화 초기화
     * POST /api/sld-manager/orders/{id}/sld-chat/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetChat(@PathVariable Long id) {
        log.info("SLD Order chat reset: sldOrderSeq={}", id);
        sldOrderAgentService.resetChat(id);
        return ResponseEntity.ok(Map.of("message", "Chat history cleared"));
    }

    /**
     * SLD 수락 -- AI 생성 SLD PDF를 파일 저장소에 저장하고 SldOrder 상태를 SLD_UPLOADED로 전환
     * POST /api/sld-manager/orders/{id}/sld-chat/accept
     */
    @PostMapping("/accept")
    public ResponseEntity<SldOrderResponse> acceptSld(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String fileId = body.get("fileId");
        log.info("SLD Order accept: sldOrderSeq={}, fileId={}", id, fileId);
        SldOrderResponse response = sldOrderAgentService.acceptSld(id, fileId);
        return ResponseEntity.ok(response);
    }

    /**
     * SVG 미리보기 조회
     * GET /api/sld-manager/orders/{id}/sld-chat/preview/{fileId}
     */
    @GetMapping(value = "/preview/{fileId}", produces = "image/svg+xml")
    public ResponseEntity<String> getSvgPreview(
            @PathVariable Long id,
            @PathVariable String fileId) {

        log.info("SLD Order SVG preview: sldOrderSeq={}, fileId={}", id, fileId);
        String svg = sldOrderAgentService.getSvgPreview(id, fileId);
        return ResponseEntity.ok(svg);
    }
}
