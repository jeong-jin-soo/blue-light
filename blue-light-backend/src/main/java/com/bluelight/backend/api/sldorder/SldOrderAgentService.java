package com.bluelight.backend.api.sldorder;

import com.bluelight.backend.api.admin.SystemAdminService;
import com.bluelight.backend.api.admin.dto.SldChatMessageResponse;
import com.bluelight.backend.api.sldorder.dto.SldOrderResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.config.SldAgentConfig;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.sldchat.SldChatMessage;
import com.bluelight.backend.domain.sldchat.SldChatMessageRepository;
import com.bluelight.backend.domain.sldorder.SldOrder;
import com.bluelight.backend.domain.sldorder.SldOrderRepository;
import com.bluelight.backend.api.file.FileStorageService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;

/**
 * SLD 전용 주문 AI Agent 서비스
 * - Python FastAPI 서비스와 REST/SSE 통신
 * - 채팅 이력 관리 (MySQL, sld_order_seq 기반)
 * - PDF 파일 수락 처리 (Python -> Spring Boot -> FileStorageService)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SldOrderAgentService {

    private final WebClient sldAgentWebClient;
    private final SldAgentConfig sldAgentConfig;
    private final SldChatMessageRepository sldChatMessageRepository;
    private final SldOrderRepository sldOrderRepository;
    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SystemAdminService systemAdminService;

    /**
     * SSE 스트리밍 채팅 -- Python AI Agent 프록시
     * - 사용자 메시지를 DB 저장
     * - Python 서비스로 SSE 요청 -> 프런트엔드로 재전송
     * - AI 응답 완료 시 DB 저장
     *
     * NOTE: @Transactional을 메서드 레벨에서 제거.
     * WebClient subscribe()의 비동기 콜백은 트랜잭션 범위 밖에서 실행되므로,
     * 동기 DB 작업과 비동기 스트리밍을 분리하여 각각 별도 트랜잭션으로 처리.
     */
    public void chatStream(Long sldOrderSeq, Long userSeq, String message, SseEmitter emitter) {
        // AI SLD 생성 토글 확인
        if (!systemAdminService.isSldAiGenerationEnabled()) {
            throw new BusinessException(
                    "AI SLD generation is currently disabled by system administrator",
                    HttpStatus.BAD_REQUEST, "SLD_AI_GENERATION_DISABLED");
        }

        // 동기 트랜잭션: 주문 정보 조회 + 사용자 메시지 저장 + 상태 전환
        Map<String, Object> sldOrderInfo = transactionTemplate.execute(status -> {
            SldOrder order = sldOrderRepository.findById(sldOrderSeq)
                    .orElseThrow(() -> new BusinessException(
                            "SLD order not found", HttpStatus.NOT_FOUND, "SLD_ORDER_NOT_FOUND"));

            ensureInProgress(order);
            sldChatMessageRepository.save(SldChatMessage.builder()
                    .sldOrderSeq(sldOrderSeq)
                    .userSeq(userSeq)
                    .role("user")
                    .content(message)
                    .build());

            return buildSldOrderInfo(order);
        });

        // SLD 시스템 프롬프트 조회 (60초 TTL 캐시)
        String sldSystemPrompt = systemAdminService.getCachedSldSystemPrompt();

        // Python 서비스 SSE 스트리밍 요청
        var requestBody = new java.util.HashMap<String, Object>();
        requestBody.put("application_seq", sldOrderSeq);
        requestBody.put("user_seq", userSeq);
        requestBody.put("message", message);
        requestBody.put("application_info", sldOrderInfo);
        if (sldSystemPrompt != null && !sldSystemPrompt.isBlank()) {
            requestBody.put("system_prompt", sldSystemPrompt);
        }

        StringBuilder fullResponse = new StringBuilder();

        Disposable subscription = sldAgentWebClient
                .post()
                .uri("/api/chat/stream")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> {
                            // Python SSE 청크를 프런트엔드로 재전송
                            try {
                                Map<String, Object> parsed = objectMapper.readValue(
                                        chunk, new TypeReference<Map<String, Object>>() {});
                                String type = (String) parsed.get("type");

                                // Heartbeat — 연결 유지용, 프런트엔드 전달 불필요
                                if ("heartbeat".equals(type)) {
                                    return;
                                }

                                // AI 응답 텍스트 누적 (최종 저장용)
                                if ("token".equals(type)) {
                                    String content = (String) parsed.get("content");
                                    if (content != null) {
                                        fullResponse.append(content);
                                    }
                                }

                                // done 이벤트에서 전체 응답 추출
                                if ("done".equals(type)) {
                                    String doneContent = (String) parsed.get("content");
                                    if (doneContent != null && !doneContent.isEmpty()) {
                                        fullResponse.setLength(0);
                                        fullResponse.append(doneContent);
                                    }
                                }

                                sendSseEvent(emitter, type != null ? type : "message", parsed);
                            } catch (Exception e) {
                                // JSON 파싱 실패 시 원본 텍스트 전달
                                sendSseEvent(emitter, "message", Map.of("type", "message", "content", chunk));
                            }
                        },
                        error -> {
                            log.error("SLD Order Agent streaming error: sldOrderSeq={}", sldOrderSeq, error);
                            String errorMsg = "AI service is temporarily unavailable. Please try again later.";
                            if (error instanceof WebClientResponseException wce) {
                                log.error("Response body: {}", wce.getResponseBodyAsString());
                            }
                            sendSseEvent(emitter, "error", Map.of("type", "error", "content", errorMsg));
                            emitter.complete();
                        },
                        () -> {
                            try {
                                // AI 응답 DB 저장 (별도 트랜잭션)
                                String aiResponse = fullResponse.toString();
                                if (!aiResponse.isEmpty()) {
                                    saveAssistantMessage(sldOrderSeq, userSeq, aiResponse);
                                }
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("Error completing SLD Order chat stream", e);
                                emitter.completeWithError(e);
                            }
                        }
                );

        // 클라이언트 연결 해제 시 구독 정리
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
    }

    /**
     * 채팅 이력 조회
     */
    public List<SldChatMessageResponse> getChatHistory(Long sldOrderSeq) {
        validateSldOrderExists(sldOrderSeq);
        return sldChatMessageRepository.findBySldOrderSeqOrderByCreatedAtAsc(sldOrderSeq)
                .stream()
                .map(SldChatMessageResponse::from)
                .toList();
    }

    /**
     * 대화 초기화 -- MySQL 이력 + Python 체크포인트 모두 삭제
     */
    @Transactional
    public void resetChat(Long sldOrderSeq) {
        validateSldOrderExists(sldOrderSeq);

        // MySQL 이력 삭제
        sldChatMessageRepository.deleteBySldOrderSeq(sldOrderSeq);
        log.info("SLD Order chat history cleared from MySQL: sldOrderSeq={}", sldOrderSeq);

        // Python 체크포인트 초기화 (비동기, 실패해도 무시)
        try {
            sldAgentWebClient
                    .post()
                    .uri("/api/chat/reset/" + sldOrderSeq)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("SLD Order chat checkpoint cleared from Python: sldOrderSeq={}", sldOrderSeq);
        } catch (Exception e) {
            log.warn("Failed to reset Python checkpoint (non-critical): sldOrderSeq={}, error={}",
                    sldOrderSeq, e.getMessage());
        }
    }

    /**
     * SLD PDF 수락 -- Python에서 생성된 PDF 파일을 가져와서 FileStorageService로 저장
     * -> SldOrder를 SLD_UPLOADED 상태로 전환
     */
    @Transactional
    public SldOrderResponse acceptSld(Long sldOrderSeq, String fileId) {
        SldOrder order = sldOrderRepository.findById(sldOrderSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD order not found", HttpStatus.NOT_FOUND, "SLD_ORDER_NOT_FOUND"));

        // IN_PROGRESS 또는 SLD_UPLOADED 상태에서 SLD 수락 가능 (신청자 확인 전 재작업 허용)
        if (order.getStatus() != com.bluelight.backend.domain.sldorder.SldOrderStatus.IN_PROGRESS
                && order.getStatus() != com.bluelight.backend.domain.sldorder.SldOrderStatus.SLD_UPLOADED) {
            throw new BusinessException(
                    "SLD can only be accepted when order status is IN_PROGRESS or SLD_UPLOADED",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_ORDER_STATUS");
        }

        // Python 서비스에서 PDF 파일 다운로드
        byte[] pdfBytes;
        try {
            pdfBytes = sldAgentWebClient
                    .get()
                    .uri("/api/files/" + fileId)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to download PDF from Python service: fileId={}", fileId, e);
            throw new BusinessException(
                    "Failed to retrieve generated PDF file",
                    HttpStatus.INTERNAL_SERVER_ERROR, "PDF_DOWNLOAD_FAILED");
        }

        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new BusinessException(
                    "Generated PDF file is empty",
                    HttpStatus.INTERNAL_SERVER_ERROR, "PDF_EMPTY");
        }

        // FileStorageService로 저장 (바이트 배열 직접 저장)
        String filename = "sld_order_" + sldOrderSeq + ".pdf";
        String subDirectory = "sld-orders/" + sldOrderSeq;
        String storedPath = fileStorageService.storeBytes(pdfBytes, filename, subDirectory);

        // FileEntity 생성 (DB 기록)
        FileEntity fileEntity = FileEntity.builder()
                .sldOrder(order)
                .fileType(FileType.DRAWING_SLD)
                .fileUrl(storedPath)
                .originalFilename(filename)
                .fileSize((long) pdfBytes.length)
                .build();

        FileEntity savedFile = fileRepository.save(fileEntity);
        log.info("AI-generated SLD PDF saved for order: fileSeq={}, sldOrderSeq={}, size={}",
                savedFile.getFileSeq(), sldOrderSeq, pdfBytes.length);

        // SldOrder 상태 전환 -> SLD_UPLOADED
        order.uploadSld(savedFile.getFileSeq(), "AI-generated SLD");

        // Python 임시 파일 정리 (비동기, 실패해도 무시)
        cleanupTempFile(fileId);

        return SldOrderResponse.from(order);
    }

    /**
     * SVG 미리보기 조회 -- Python 서비스에서 SVG 문자열 가져오기
     */
    public String getSvgPreview(Long sldOrderSeq, String fileId) {
        validateSldOrderExists(sldOrderSeq);

        try {
            return sldAgentWebClient
                    .get()
                    .uri("/api/files/" + fileId + "/svg")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get SVG preview: sldOrderSeq={}, fileId={}", sldOrderSeq, fileId, e);
            throw new BusinessException(
                    "Failed to retrieve SLD preview",
                    HttpStatus.INTERNAL_SERVER_ERROR, "SVG_PREVIEW_FAILED");
        }
    }

    // ──────────────────────────────────────
    // Private helpers (트랜잭션 분리)
    // ──────────────────────────────────────

    /**
     * AI 응답 DB 저장 (비동기 콜백에서 호출, 별도 트랜잭션)
     * - 같은 클래스 내부 호출이므로 TransactionTemplate 사용
     */
    private void saveAssistantMessage(Long sldOrderSeq, Long userSeq, String content) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                sldChatMessageRepository.save(SldChatMessage.builder()
                        .sldOrderSeq(sldOrderSeq)
                        .userSeq(userSeq)
                        .role("assistant")
                        .content(content)
                        .build())
            );
        } catch (Exception e) {
            log.warn("Failed to save AI response to DB: sldOrderSeq={}, error={}", sldOrderSeq, e.getMessage());
        }
    }

    /**
     * PAID -> IN_PROGRESS 자동 전환
     */
    private void ensureInProgress(SldOrder order) {
        order.ensureInProgress();
    }

    private void validateSldOrderExists(Long sldOrderSeq) {
        if (!sldOrderRepository.existsById(sldOrderSeq)) {
            throw new BusinessException(
                    "SLD order not found",
                    HttpStatus.NOT_FOUND,
                    "SLD_ORDER_NOT_FOUND"
            );
        }
    }

    /**
     * SLD 생성에 필요한 주문 정보를 Map으로 구성 (Python 서비스에 전달)
     * - sld_only_mode = true: Python에서 title block을 비워둠
     */
    private Map<String, Object> buildSldOrderInfo(SldOrder order) {
        var builder = new java.util.HashMap<String, Object>();
        builder.put("sldOrderSeq", order.getSldOrderSeq());
        builder.put("selectedKva", order.getSelectedKva() != null ? order.getSelectedKva() : 0);
        builder.put("address", order.getAddress() != null ? order.getAddress() : "");
        builder.put("postalCode", order.getPostalCode() != null ? order.getPostalCode() : "");
        builder.put("buildingType", order.getBuildingType() != null ? order.getBuildingType() : "");
        builder.put("sld_only_mode", true);

        // 신청자 메모
        if (order.getApplicantNote() != null && !order.getApplicantNote().isBlank()) {
            builder.put("applicantNote", order.getApplicantNote());
        }

        // 스케치 파일
        if (order.getSketchFileSeq() != null) {
            builder.put("hasSketchFile", true);
            builder.put("sketchFileSeq", order.getSketchFileSeq());
        }

        return builder;
    }

    /**
     * Python 서비스의 임시 파일(PDF + SVG) 정리
     * - 파일 저장 성공 후 호출 (비동기, 실패해도 무시)
     */
    private void cleanupTempFile(String fileId) {
        try {
            sldAgentWebClient
                    .delete()
                    .uri("/api/files/" + fileId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            result -> log.info("Python temp file cleaned up: fileId={}", fileId),
                            error -> log.warn("Failed to cleanup Python temp file (non-critical): fileId={}, error={}",
                                    fileId, error.getMessage())
                    );
        } catch (Exception e) {
            log.warn("Failed to request Python temp file cleanup (non-critical): fileId={}, error={}",
                    fileId, e.getMessage());
        }
    }

    private void sendSseEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.debug("Failed to send SSE event: {}", e.getMessage());
        }
    }
}
