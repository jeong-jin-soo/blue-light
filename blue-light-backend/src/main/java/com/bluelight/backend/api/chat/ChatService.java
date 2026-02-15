package com.bluelight.backend.api.chat;

import com.bluelight.backend.api.chat.dto.ChatMessageDto;
import com.bluelight.backend.api.chat.dto.ChatRequest;
import com.bluelight.backend.api.chat.dto.ChatResponse;
import com.bluelight.backend.config.GeminiConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AI 챗봇 서비스 — Gemini API 연동
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final GeminiConfig geminiConfig;
    private final WebClient geminiWebClient;

    private String systemPrompt;

    @PostConstruct
    public void init() throws IOException {
        var resource = new ClassPathResource("chat-system-prompt.txt");
        this.systemPrompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.info("Chatbot system prompt loaded ({} chars)", systemPrompt.length());
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

        return ChatResponse.builder()
                .message(responseText)
                .suggestedQuestions(generateSuggestedQuestions(request.getMessage()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildGeminiRequest(ChatRequest request, Long userSeq) {
        // System instruction
        Map<String, Object> systemInstruction = Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
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
