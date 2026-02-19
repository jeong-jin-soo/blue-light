package com.bluelight.backend.api.chat;

import com.bluelight.backend.api.chat.dto.ChatMessageDto;
import com.bluelight.backend.api.chat.dto.ChatRequest;
import com.bluelight.backend.api.chat.dto.ChatResponse;
import com.bluelight.backend.config.GeminiConfig;
import com.bluelight.backend.domain.chat.ChatMessage;
import com.bluelight.backend.domain.chat.ChatMessageRepository;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * AI 챗봇 서비스 — Gemini API 연동
 * 시스템 프롬프트: DB(system_settings) 우선, 없으면 파일 fallback
 * - DB 기반 TTL 캐시 (60초) — 다중 서버 환경에서도 일관성 보장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final long CACHE_TTL_SECONDS = 60;

    private final GeminiConfig geminiConfig;
    private final WebClient geminiWebClient;
    private final ChatMessageRepository chatMessageRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final ObjectMapper objectMapper;

    /** TTL 캐시: DB 조회 결과를 60초간 보관 */
    private volatile String cachedSystemPrompt;
    private volatile Instant promptCacheExpiry = Instant.MIN;

    /**
     * 시스템 프롬프트 조회 (DB 우선, 파일 fallback, 60초 TTL 캐시)
     */
    private String getSystemPrompt() {
        Instant now = Instant.now();
        if (now.isBefore(promptCacheExpiry) && cachedSystemPrompt != null) {
            return cachedSystemPrompt;
        }

        // DB에서 조회
        String dbPrompt = systemSettingRepository.findById("chat_system_prompt")
                .map(s -> s.getSettingValue())
                .filter(v -> !v.isBlank())
                .orElse(null);

        String resolved;
        if (dbPrompt != null) {
            resolved = dbPrompt;
        } else {
            resolved = loadDefaultPromptFromFile();
        }

        this.cachedSystemPrompt = resolved;
        this.promptCacheExpiry = now.plusSeconds(CACHE_TTL_SECONDS);
        return resolved;
    }

    /**
     * 캐시 무효화 (설정 변경 시 즉시 반영용 — 같은 서버에서만 효과)
     */
    public void invalidatePromptCache() {
        this.promptCacheExpiry = Instant.MIN;
        log.info("System prompt cache invalidated");
    }

    private String loadDefaultPromptFromFile() {
        try {
            var resource = new ClassPathResource("chat-system-prompt.txt");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load system prompt from file", e);
            return "You are a helpful assistant for the LicenseKaki platform.";
        }
    }

    public ChatResponse chat(ChatRequest request, Long userSeq) {
        // Gemini API 키 미설정 시 안내 메시지 반환
        if (geminiConfig.getApiKey() == null || geminiConfig.getApiKey().isBlank()) {
            log.warn("Gemini API key not configured");
            return ChatResponse.builder()
                    .message("The AI assistant is currently unavailable. Please try again later.")
                    .suggestedQuestions(getDefaultSuggestions())
                    .build();
        }

        Map<String, Object> body = buildGeminiRequest(request, userSeq);
        String responseText = callGeminiApi(body);

        // 대화 기록 저장
        saveMessages(request.getSessionId(), userSeq, request.getMessage(), responseText);

        return ChatResponse.builder()
                .message(responseText)
                .suggestedQuestions(generateSuggestedQuestions(request.getMessage()))
                .build();
    }

    /**
     * SSE 스트리밍 챗봇 응답
     */
    public void chatStream(ChatRequest request, Long userSeq, SseEmitter emitter) {
        if (geminiConfig.getApiKey() == null || geminiConfig.getApiKey().isBlank()) {
            sendSseEvent(emitter, "error", Map.of("type", "error",
                    "content", "The AI assistant is currently unavailable."));
            emitter.complete();
            return;
        }

        Map<String, Object> body = buildGeminiRequest(request, userSeq);
        String path = "/models/" + geminiConfig.getModel() + ":streamGenerateContent";

        StringBuilder fullResponse = new StringBuilder();

        Disposable subscription = geminiWebClient
                .post()
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParam("key", geminiConfig.getApiKey())
                        .queryParam("alt", "sse")
                        .build())
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> {
                            String text = extractTextFromChunk(chunk);
                            if (text != null && !text.isEmpty()) {
                                fullResponse.append(text);
                                sendSseEvent(emitter, "token", Map.of(
                                        "type", "token", "content", text));
                            }
                        },
                        error -> {
                            log.error("Gemini streaming error", error);
                            sendSseEvent(emitter, "error", Map.of(
                                    "type", "error",
                                    "content", "Sorry, an error occurred. Please try again."));
                            emitter.complete();
                        },
                        () -> {
                            try {
                                String complete = fullResponse.toString();
                                List<String> suggestions = generateSuggestedQuestions(request.getMessage());
                                sendSseEvent(emitter, "done", Map.of(
                                        "type", "done",
                                        "content", complete,
                                        "suggestedQuestions", suggestions));
                                emitter.complete();

                                // DB 저장
                                saveMessages(request.getSessionId(), userSeq,
                                        request.getMessage(), complete);
                            } catch (Exception e) {
                                log.error("Error completing SSE stream", e);
                                emitter.completeWithError(e);
                            }
                        }
                );

        // 클라이언트 연결 해제 시 구독 정리
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromChunk(String jsonChunk) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    jsonChunk, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) parsed.get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;
            Map<String, Object> content =
                    (Map<String, Object>) candidates.get(0).get("content");
            if (content == null) return null;
            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return null;
            return (String) parts.get(0).get("text");
        } catch (Exception e) {
            log.debug("Failed to parse Gemini chunk: {}", e.getMessage());
            return null;
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

    void saveMessages(String sessionId, Long userSeq, String userMessage, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) return;

        try {
            chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .userSeq(userSeq)
                    .role("user")
                    .content(userMessage)
                    .build());

            chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .userSeq(userSeq)
                    .role("assistant")
                    .content(assistantMessage)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to save chat messages: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildGeminiRequest(ChatRequest request, Long userSeq) {
        // System instruction
        Map<String, Object> systemInstruction = Map.of(
                "parts", List.of(Map.of("text", getSystemPrompt()))
        );

        // Conversation contents
        List<Map<String, Object>> contents = new ArrayList<>();

        // 이전 대화 히스토리 추가
        if (request.getHistory() != null) {
            for (ChatMessageDto msg : request.getHistory()) {
                String role = "user".equals(msg.getRole()) ? "user" : "model";
                contents.add(Map.of(
                        "role", role,
                        "parts", List.of(Map.of("text", msg.getContent()))
                ));
            }
        }

        // 현재 사용자 메시지
        String userMessage = request.getMessage();
        if (userSeq != null) {
            userMessage = "[Logged-in user] " + userMessage;
        }
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
        ));

        // Generation config
        Map<String, Object> generationConfig = Map.of(
                "maxOutputTokens", geminiConfig.getMaxTokens(),
                "temperature", geminiConfig.getTemperature()
        );

        return Map.of(
                "system_instruction", systemInstruction,
                "contents", contents,
                "generationConfig", generationConfig
        );
    }

    @SuppressWarnings("unchecked")
    private String callGeminiApi(Map<String, Object> body) {
        try {
            String path = "/models/" + geminiConfig.getModel() + ":generateContent";

            Map<String, Object> response = geminiWebClient
                    .post()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("key", geminiConfig.getApiKey())
                            .build())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                log.error("Gemini API returned null response");
                return "Sorry, I couldn't process your request. Please try again.";
            }

            // candidates[0].content.parts[0].text 파싱
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                log.warn("Gemini API returned no candidates: {}", response);
                return "Sorry, I couldn't generate a response. Please try rephrasing your question.";
            }

            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            return (String) parts.get(0).get("text");

        } catch (WebClientResponseException e) {
            log.error("Gemini API error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return "Sorry, the AI service is temporarily unavailable. Please try again later.";
        } catch (Exception e) {
            log.error("Unexpected error calling Gemini API", e);
            return "Sorry, an unexpected error occurred. Please try again later.";
        }
    }

    private List<String> generateSuggestedQuestions(String lastMessage) {
        String lower = lastMessage.toLowerCase();

        if (lower.contains("apply") || lower.contains("new") || lower.contains("신청") || lower.contains("새")) {
            return List.of(
                    "What documents do I need for a new licence?",
                    "How long does the application process take?",
                    "What is the cost for my kVA capacity?"
            );
        }
        if (lower.contains("renew") || lower.contains("갱신") || lower.contains("renewal")) {
            return List.of(
                    "When should I start the renewal process?",
                    "What documents are needed for renewal?",
                    "Is the renewal fee different from new application?"
            );
        }
        if (lower.contains("price") || lower.contains("cost") || lower.contains("fee") || lower.contains("가격") || lower.contains("비용")) {
            return List.of(
                    "How is the kVA tier determined?",
                    "What payment methods are available?",
                    "Are there any additional fees?"
            );
        }
        if (lower.contains("document") || lower.contains("upload") || lower.contains("서류") || lower.contains("sld")) {
            return List.of(
                    "What is an SLD (Single Line Diagram)?",
                    "How do I get a Letter of Appointment?",
                    "What file formats are accepted?"
            );
        }
        if (lower.contains("lew") || lower.contains("licensed") || lower.contains("worker") || lower.contains("기술자")) {
            return List.of(
                    "What does a LEW do?",
                    "How is a LEW assigned to my application?",
                    "Can I choose my own LEW?"
            );
        }
        if (lower.contains("payment") || lower.contains("pay") || lower.contains("결제")) {
            return List.of(
                    "How do I make a PayNow payment?",
                    "When will my payment be confirmed?",
                    "What is the UEN reference number?"
            );
        }

        return getDefaultSuggestions();
    }

    private List<String> getDefaultSuggestions() {
        return List.of(
                "How do I apply for a new EMA licence?",
                "What documents do I need to submit?",
                "How is the pricing determined?"
        );
    }
}
