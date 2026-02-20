package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SldChatMessageResponse;
import com.bluelight.backend.api.admin.dto.SldChatRequest;
import com.bluelight.backend.api.application.dto.SldRequestResponse;
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
 * SLD AI 채팅 API 컨트롤러
 * - LEW/Admin이 AI와 대화하며 SLD를 생성
 * - Spring Boot가 Python AI 서비스를 프록시
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'LEW', 'SYSTEM_ADMIN')")
public class SldChatController {

    private final SldAgentService sldAgentService;

    /**
     * SSE 스트리밍 채팅
     * POST /api/admin/applications/:id/sld-chat/stream
     */
    @PostMapping(value = "/applications/{id}/sld-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @PathVariable Long id,
            @Valid @RequestBody SldChatRequest request,
            Authentication authentication) {

        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD chat stream: applicationSeq={}, userSeq={}, message={}",
                id, userSeq, request.getMessage().substring(0, Math.min(request.getMessage().length(), 50)));

        SseEmitter emitter = new SseEmitter((long) 120_000);
        sldAgentService.chatStream(id, userSeq, request.getMessage(), emitter);

        return emitter;
    }

    /**
     * 채팅 이력 조회
     * GET /api/admin/applications/:id/sld-chat/history
     */
    @GetMapping("/applications/{id}/sld-chat/history")
    public ResponseEntity<List<SldChatMessageResponse>> getChatHistory(@PathVariable Long id) {
        log.info("SLD chat history request: applicationSeq={}", id);
        List<SldChatMessageResponse> history = sldAgentService.getChatHistory(id);
        return ResponseEntity.ok(history);
    }

    /**
     * 대화 초기화
     * POST /api/admin/applications/:id/sld-chat/reset
     */
    @PostMapping("/applications/{id}/sld-chat/reset")
    public ResponseEntity<Map<String, String>> resetChat(@PathVariable Long id) {
        log.info("SLD chat reset: applicationSeq={}", id);
        sldAgentService.resetChat(id);
        return ResponseEntity.ok(Map.of("message", "Chat history cleared"));
    }

    /**
     * SLD 수락 — AI 생성 SLD PDF를 파일 저장소에 저장하고 SldRequest 상태를 UPLOADED로 전환
     * POST /api/admin/applications/:id/sld-chat/accept
     */
    @PostMapping("/applications/{id}/sld-chat/accept")
    public ResponseEntity<SldRequestResponse> acceptSld(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String fileId = body.get("fileId");
        log.info("SLD accept: applicationSeq={}, fileId={}", id, fileId);
        SldRequestResponse response = sldAgentService.acceptSld(id, fileId);
        return ResponseEntity.ok(response);
    }

    /**
     * SVG 미리보기 조회
     * GET /api/admin/applications/:id/sld-chat/preview/:fileId
     */
    @GetMapping(value = "/applications/{id}/sld-chat/preview/{fileId}", produces = "image/svg+xml")
    public ResponseEntity<String> getSvgPreview(
            @PathVariable Long id,
            @PathVariable String fileId) {

        log.info("SLD SVG preview: applicationSeq={}, fileId={}", id, fileId);
        String svg = sldAgentService.getSvgPreview(id, fileId);
        return ResponseEntity.ok(svg);
    }
}
