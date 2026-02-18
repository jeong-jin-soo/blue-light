package com.bluelight.backend.api.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 시스템 관리자 전용 API 컨트롤러 (SYSTEM_ADMIN only)
 * - 챗봇 시스템 프롬프트 관리
 * - Gemini API 키 관리
 * - 이메일 인증 설정 관리
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminController {

    private final SystemAdminService systemAdminService;

    // ── 시스템 설정 전체 조회 ──────────────────────────────

    /**
     * 시스템 설정 일괄 조회
     * GET /api/admin/system/settings
     */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSystemSettings() {
        log.info("System admin get system settings");
        return ResponseEntity.ok(systemAdminService.getSystemSettings());
    }

    // ── 시스템 프롬프트 ──────────────────────────────

    /**
     * 시스템 프롬프트 조회
     * GET /api/admin/system/prompt
     */
    @GetMapping("/prompt")
    public ResponseEntity<Map<String, Object>> getSystemPrompt() {
        log.info("System admin get system prompt");
        String prompt = systemAdminService.getSystemPrompt();
        return ResponseEntity.ok(Map.of(
                "prompt", prompt,
                "length", prompt.length()
        ));
    }

    /**
     * 시스템 프롬프트 업데이트
     * PUT /api/admin/system/prompt
     */
    @PutMapping("/prompt")
    public ResponseEntity<Map<String, Object>> updateSystemPrompt(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        String prompt = request.get("prompt");

        log.info("System admin update system prompt: length={}", prompt != null ? prompt.length() : 0);
        systemAdminService.updateSystemPrompt(prompt, userSeq);

        return ResponseEntity.ok(Map.of(
                "message", "System prompt updated successfully",
                "length", prompt != null ? prompt.length() : 0
        ));
    }

    /**
     * 시스템 프롬프트 기본값 초기화
     * POST /api/admin/system/prompt/reset
     */
    @PostMapping("/prompt/reset")
    public ResponseEntity<Map<String, Object>> resetSystemPrompt(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();

        log.info("System admin reset system prompt to default");
        String defaultPrompt = systemAdminService.resetSystemPrompt(userSeq);

        return ResponseEntity.ok(Map.of(
                "message", "System prompt reset to default",
                "prompt", defaultPrompt,
                "length", defaultPrompt.length()
        ));
    }

    // ── Gemini API 키 ──────────────────────────────

    /**
     * Gemini API 키 상태 조회
     * GET /api/admin/system/gemini-key
     */
    @GetMapping("/gemini-key")
    public ResponseEntity<Map<String, Object>> getGeminiApiKeyStatus() {
        log.info("System admin get Gemini API key status");
        return ResponseEntity.ok(systemAdminService.getGeminiApiKeyStatus());
    }

    /**
     * Gemini API 키 업데이트
     * PUT /api/admin/system/gemini-key
     */
    @PutMapping("/gemini-key")
    public ResponseEntity<Map<String, String>> updateGeminiApiKey(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        String apiKey = request.get("apiKey");

        log.info("System admin update Gemini API key");
        systemAdminService.updateGeminiApiKey(apiKey, userSeq);

        return ResponseEntity.ok(Map.of("message", "Gemini API key updated successfully"));
    }

    /**
     * Gemini API 키 삭제 (환경변수 값으로 복귀)
     * DELETE /api/admin/system/gemini-key
     */
    @DeleteMapping("/gemini-key")
    public ResponseEntity<Map<String, String>> clearGeminiApiKey(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();

        log.info("System admin clear Gemini API key (revert to env)");
        systemAdminService.clearGeminiApiKey(userSeq);

        return ResponseEntity.ok(Map.of("message", "Gemini API key cleared (reverted to environment variable)"));
    }

    // ── 이메일 인증 ──────────────────────────────

    /**
     * 이메일 인증 설정 조회
     * GET /api/admin/system/email-verification
     */
    @GetMapping("/email-verification")
    public ResponseEntity<Map<String, Object>> getEmailVerification() {
        log.info("System admin get email verification setting");
        boolean enabled = systemAdminService.isEmailVerificationEnabled();
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }

    /**
     * 이메일 인증 설정 변경
     * PUT /api/admin/system/email-verification
     */
    @PutMapping("/email-verification")
    public ResponseEntity<Map<String, Object>> updateEmailVerification(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        boolean enabled = Boolean.TRUE.equals(request.get("enabled"));

        log.info("System admin update email verification: enabled={}", enabled);
        systemAdminService.updateEmailVerification(enabled, userSeq);

        return ResponseEntity.ok(Map.of(
                "message", enabled
                        ? "Email verification enabled. New users must verify their email."
                        : "Email verification disabled. New users are auto-verified.",
                "enabled", enabled
        ));
    }
}
