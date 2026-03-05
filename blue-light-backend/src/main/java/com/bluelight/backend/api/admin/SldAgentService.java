package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SldChatMessageResponse;
import com.bluelight.backend.api.application.dto.SldRequestResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.config.GeminiConfig;
import com.bluelight.backend.config.SldAgentConfig;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.SldRequest;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.application.SldRequestStatus;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.sldchat.SldChatMessage;
import com.bluelight.backend.domain.sldchat.SldChatMessageRepository;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SLD AI Agent 서비스
 * - Python FastAPI 서비스와 REST/SSE 통신
 * - 채팅 이력 관리 (MySQL)
 * - PDF 파일 수락 처리 (Python → Spring Boot → FileStorageService)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SldAgentService {

    private final WebClient sldAgentWebClient;
    private final SldAgentConfig sldAgentConfig;
    private final SldChatMessageRepository sldChatMessageRepository;
    private final ApplicationRepository applicationRepository;
    private final SldRequestRepository sldRequestRepository;
    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SystemAdminService systemAdminService;
    private final GeminiConfig geminiConfig;

    /**
     * SSE 스트리밍 채팅 — Python AI Agent 프록시
     * - 사용자 메시지를 DB 저장
     * - Python 서비스로 SSE 요청 → 프런트엔드로 재전송
     * - AI 응답 완료 시 DB 저장
     *
     * NOTE: @Transactional을 메서드 레벨에서 제거.
     * WebClient subscribe()의 비동기 콜백은 트랜잭션 범위 밖에서 실행되므로,
     * 동기 DB 작업과 비동기 스트리밍을 분리하여 각각 별도 트랜잭션으로 처리.
     */
    public void chatStream(Long applicationSeq, Long userSeq, String message, SseEmitter emitter) {
        // AI SLD 생성 토글 확인
        if (!systemAdminService.isSldAiGenerationEnabled()) {
            throw new BusinessException(
                    "AI SLD generation is currently disabled by system administrator",
                    HttpStatus.BAD_REQUEST, "SLD_AI_GENERATION_DISABLED");
        }

        // 동기 트랜잭션: 신청 정보 조회 + 사용자 메시지 저장 + SLD 상태 전환
        // Lazy 연관(User, AssignedLew)을 트랜잭션 내에서 접근하기 위해 묶어서 처리
        Map<String, Object> applicationInfo = transactionTemplate.execute(status -> {
            Application application = applicationRepository.findById(applicationSeq)
                    .orElseThrow(() -> new BusinessException(
                            "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

            ensureAiGeneratingStatus(applicationSeq);
            sldChatMessageRepository.save(SldChatMessage.builder()
                    .applicationSeq(applicationSeq)
                    .userSeq(userSeq)
                    .role("user")
                    .content(message)
                    .build());

            // Lazy 연관 엔티티를 트랜잭션 내에서 접근 → applicationInfo 구성
            return buildApplicationInfo(application);
        });

        // SLD 시스템 프롬프트 조회 (60초 TTL 캐시)
        String sldSystemPrompt = systemAdminService.getCachedSldSystemPrompt();

        // Python 서비스 SSE 스트리밍 요청
        var requestBody = new java.util.HashMap<String, Object>();
        requestBody.put("application_seq", applicationSeq);
        requestBody.put("user_seq", userSeq);
        requestBody.put("message", message);
        requestBody.put("application_info", applicationInfo);
        if (sldSystemPrompt != null && !sldSystemPrompt.isBlank()) {
            requestBody.put("system_prompt", sldSystemPrompt);
        }
        // DB에서 관리하는 Gemini API Key를 Python 서비스에 전달
        String apiKey = geminiConfig.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBody.put("api_key", apiKey);
        }

        StringBuilder fullResponse = new StringBuilder();
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        Disposable subscription = sldAgentWebClient
                .post()
                .uri("/api/chat/stream")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> {
                            // 클라이언트 연결 끊김 시 처리 중단
                            if (clientDisconnected.get()) return;

                            if (chunk == null || chunk.isBlank()) return;

                            // Python SSE 청크를 프런트엔드로 재전송
                            try {
                                Map<String, Object> parsed = objectMapper.readValue(
                                        chunk, new TypeReference<Map<String, Object>>() {});
                                String type = (String) parsed.get("type");

                                // Heartbeat — 프런트엔드 SSE 타임아웃 방지를 위해 전달
                                if ("heartbeat".equals(type)) {
                                    if (!sendSseEvent(emitter, "heartbeat", parsed)) {
                                        handleClientDisconnect(subscriptionRef, clientDisconnected, applicationSeq);
                                    }
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

                                if (!sendSseEvent(emitter, type != null ? type : "message", parsed)) {
                                    handleClientDisconnect(subscriptionRef, clientDisconnected, applicationSeq);
                                }
                            } catch (Exception e) {
                                // JSON 파싱 실패 시 원본 텍스트 전달
                                sendSseEvent(emitter, "message", Map.of("type", "message", "content", chunk));
                            }
                        },
                        error -> {
                            log.error("SLD Agent streaming error: applicationSeq={}", applicationSeq, error);
                            String errorMsg = "AI service is temporarily unavailable. Please try again later.";
                            if (error instanceof WebClientResponseException wce) {
                                log.error("Response body: {}", wce.getResponseBodyAsString());
                            }
                            sendSseEvent(emitter, "error", Map.of("type", "error", "content", errorMsg));
                            completeEmitter(emitter);
                        },
                        () -> {
                            // AI 응답 DB 저장 (별도 트랜잭션, 실패해도 SSE 종료에 영향 없음)
                            try {
                                String aiResponse = fullResponse.toString();
                                if (!aiResponse.isEmpty()) {
                                    saveAssistantMessage(applicationSeq, userSeq, aiResponse);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to save AI response: applicationSeq={}, error={}",
                                        applicationSeq, e.getMessage());
                            }
                            completeEmitter(emitter);
                        }
                );

        subscriptionRef.set(subscription);

        // 클라이언트 연결 해제 시 구독 정리
        emitter.onCompletion(() -> {
            clientDisconnected.set(true);
            subscription.dispose();
        });
        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out: applicationSeq={}", applicationSeq);
            sendSseEvent(emitter, "error", Map.of(
                    "type", "error",
                    "content", "Connection timed out. The AI processing took too long. Please try again."));
            clientDisconnected.set(true);
            subscription.dispose();
        });
        emitter.onError(t -> {
            log.warn("SSE emitter error: applicationSeq={}, error={}", applicationSeq, t.getMessage());
            clientDisconnected.set(true);
            subscription.dispose();
        });
    }

    /**
     * 채팅 이력 조회
     */
    public List<SldChatMessageResponse> getChatHistory(Long applicationSeq) {
        validateApplicationExists(applicationSeq);
        return sldChatMessageRepository.findByApplicationSeqOrderByCreatedAtAsc(applicationSeq)
                .stream()
                .map(SldChatMessageResponse::from)
                .toList();
    }

    /**
     * 대화 초기화 — MySQL 이력 + Python 체크포인트 + temp 파일 모두 삭제
     * Reset 후 다음 메시지는 완전히 새로운 AI 대화로 시작됨
     */
    @Transactional
    public void resetChat(Long applicationSeq) {
        validateApplicationExists(applicationSeq);

        // 1. MySQL 이력 삭제
        sldChatMessageRepository.deleteByApplicationSeq(applicationSeq);
        log.info("SLD chat history cleared from MySQL: applicationSeq={}", applicationSeq);

        // 2. Python 체크포인트 + temp 파일 초기화
        try {
            String response = sldAgentWebClient
                    .post()
                    .uri("/api/chat/reset/" + applicationSeq)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("SLD chat reset from Python: applicationSeq={}, response={}", applicationSeq, response);
        } catch (Exception e) {
            log.warn("Failed to reset Python state (non-critical): applicationSeq={}, error={}",
                    applicationSeq, e.getMessage());
        }
    }

    /**
     * SLD PDF 수락 — Python에서 생성된 PDF 파일을 가져와서 FileStorageService로 저장
     * → SldRequest를 UPLOADED 상태로 전환
     */
    @Transactional
    public SldRequestResponse acceptSld(Long applicationSeq, String fileId) {
        validateApplicationExists(applicationSeq);

        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD request not found", HttpStatus.NOT_FOUND, "SLD_REQUEST_NOT_FOUND"));

        if (sldRequest.getStatus() != SldRequestStatus.AI_GENERATING
                && sldRequest.getStatus() != SldRequestStatus.UPLOADED) {
            throw new BusinessException(
                    "SLD can only be accepted when status is AI_GENERATING or UPLOADED",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_STATUS");
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
        String filename = "sld_" + applicationSeq + ".pdf";
        String subDirectory = "applications/" + applicationSeq;
        String storedPath = fileStorageService.storeBytes(pdfBytes, filename, subDirectory);

        // FileEntity 생성 (DB 기록)
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(FileType.DRAWING_SLD)
                .fileUrl(storedPath)
                .originalFilename(filename)
                .fileSize((long) pdfBytes.length)
                .build();

        FileEntity savedFile = fileRepository.save(fileEntity);
        log.info("AI-generated SLD PDF saved: fileSeq={}, applicationSeq={}, size={}",
                savedFile.getFileSeq(), applicationSeq, pdfBytes.length);

        // SldRequest 상태 전환 → UPLOADED
        sldRequest.markUploaded(savedFile.getFileSeq(), "AI-generated SLD");

        // Python 임시 파일 정리 (비동기, 실패해도 무시)
        cleanupTempFile(fileId);

        return SldRequestResponse.from(sldRequest);
    }

    /**
     * SVG 미리보기 조회 — Python 서비스에서 SVG 문자열 가져오기
     */
    public String getSvgPreview(Long applicationSeq, String fileId) {
        validateApplicationExists(applicationSeq);

        try {
            return sldAgentWebClient
                    .get()
                    .uri("/api/files/" + fileId + "/svg")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get SVG preview: applicationSeq={}, fileId={}", applicationSeq, fileId, e);
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
    private void saveAssistantMessage(Long applicationSeq, Long userSeq, String content) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                sldChatMessageRepository.save(SldChatMessage.builder()
                        .applicationSeq(applicationSeq)
                        .userSeq(userSeq)
                        .role("assistant")
                        .content(content)
                        .build())
            );
        } catch (Exception e) {
            log.warn("Failed to save AI response to DB: applicationSeq={}, error={}", applicationSeq, e.getMessage());
        }
    }

    /**
     * SLD 요청이 REQUESTED 또는 UPLOADED 상태이면 AI_GENERATING으로 자동 전환
     * (UPLOADED 상태에서 AI 재생성 허용)
     */
    private void ensureAiGeneratingStatus(Long applicationSeq) {
        sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .ifPresent(sldRequest -> {
                    if (sldRequest.getStatus() == SldRequestStatus.REQUESTED
                            || sldRequest.getStatus() == SldRequestStatus.UPLOADED) {
                        sldRequest.startAiGeneration();
                        log.info("SLD status auto-transitioned to AI_GENERATING: applicationSeq={}", applicationSeq);
                    }
                });
    }

    private void validateApplicationExists(Long applicationSeq) {
        if (!applicationRepository.existsById(applicationSeq)) {
            throw new BusinessException(
                    "Application not found",
                    HttpStatus.NOT_FOUND,
                    "APPLICATION_NOT_FOUND"
            );
        }
    }

    /**
     * SLD 생성에 필요한 신청 정보를 Map으로 구성 (Python 서비스에 전달)
     */
    private Map<String, Object> buildApplicationInfo(Application app) {
        var builder = new java.util.HashMap<String, Object>();
        builder.put("applicationSeq", app.getApplicationSeq());
        builder.put("selectedKva", app.getSelectedKva() != null ? app.getSelectedKva() : 0);
        builder.put("address", app.getAddress() != null ? app.getAddress() : "");
        builder.put("postalCode", app.getPostalCode() != null ? app.getPostalCode() : "");
        builder.put("buildingType", app.getBuildingType() != null ? app.getBuildingType() : "");
        builder.put("applicationType", app.getApplicationType() != null ? app.getApplicationType().name() : "NEW");
        builder.put("spAccountNo", app.getSpAccountNo() != null ? app.getSpAccountNo() : "");
        builder.put("sldOption", app.getSldOption() != null ? app.getSldOption().name() : "");
        // 신청자 정보
        if (app.getUser() != null) {
            builder.put("userCompanyName", app.getUser().getCompanyName() != null ? app.getUser().getCompanyName() : "");
        }
        // 담당 LEW 정보
        if (app.getAssignedLew() != null) {
            builder.put("assignedLewName", app.getAssignedLew().getFullName());
            builder.put("assignedLewLicenceNo", app.getAssignedLew().getLewLicenceNo() != null
                    ? app.getAssignedLew().getLewLicenceNo() : "");
        }
        // SLD 요청의 신청자 메모 + 스케치 파일 (있는 경우)
        sldRequestRepository.findByApplicationApplicationSeq(app.getApplicationSeq())
                .ifPresent(sldReq -> {
                    if (sldReq.getApplicantNote() != null && !sldReq.getApplicantNote().isBlank()) {
                        builder.put("applicantNote", sldReq.getApplicantNote());
                    }
                    if (sldReq.getSketchFileSeq() != null) {
                        builder.put("hasSketchFile", true);
                        builder.put("sketchFileSeq", sldReq.getSketchFileSeq());
                    }
                });
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

    /**
     * SSE 이벤트 전송. 성공 시 true, 실패(클라이언트 연결 끊김 등) 시 false 반환.
     */
    private boolean sendSseEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
            return true;
        } catch (Exception e) {
            log.debug("Failed to send SSE event ({}): {}", eventName, e.getMessage());
            return false;
        }
    }

    /**
     * 클라이언트 연결 끊김 처리 — 구독 취소하여 Python 에이전트 리소스 해제.
     */
    private void handleClientDisconnect(
            AtomicReference<Disposable> subscriptionRef,
            AtomicBoolean clientDisconnected,
            Long applicationSeq) {
        if (clientDisconnected.compareAndSet(false, true)) {
            log.info("Client disconnected, cancelling Python agent subscription: applicationSeq={}", applicationSeq);
            Disposable sub = subscriptionRef.get();
            if (sub != null && !sub.isDisposed()) {
                sub.dispose();
            }
        }
    }

    /**
     * SseEmitter 안전 종료 (이미 닫힌 연결이면 무시).
     */
    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE emitter already completed: {}", e.getMessage());
        }
    }
}
