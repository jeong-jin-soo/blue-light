package com.bluelight.backend.api.chat;

import com.bluelight.backend.api.chat.dto.ChatRequest;
import com.bluelight.backend.api.chat.dto.ChatResponse;
import com.bluelight.backend.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 챗봇 API 컨트롤러 (Public — 로그인 불필요)
 */
@Slf4j
@RestController
@RequestMapping("/api/public/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatRateLimiter chatRateLimiter;

    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {

        String ip = httpRequest.getRemoteAddr();

        // 인증 사용자 확인 (JWT 필터가 SecurityContext에 설정했을 수 있음)
        Long userSeq = null;
        boolean authenticated = false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            userSeq = (Long) auth.getPrincipal();
            authenticated = true;
        }

        // Rate limiting
        if (chatRateLimiter.isBlocked(ip, authenticated)) {
            throw new BusinessException(
                    "Too many requests. Please try again later.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED"
            );
        }
        chatRateLimiter.recordAttempt(ip);

        log.info("Chat request: ip={}, authenticated={}, message={}", ip, authenticated,
                request.getMessage().substring(0, Math.min(request.getMessage().length(), 50)));

        ChatResponse response = chatService.chat(request, userSeq);
        return ResponseEntity.ok(response);
    }
}
