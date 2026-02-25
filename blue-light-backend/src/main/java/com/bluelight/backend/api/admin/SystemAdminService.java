package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.chat.ChatService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.config.GeminiConfig;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 시스템 관리자 전용 서비스
 * - 챗봇 시스템 프롬프트 관리
 * - SLD AI 시스템 프롬프트 관리
 * - Gemini API 키 관리
 * - 이메일 인증 설정 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemAdminService {

    private static final long CACHE_TTL_SECONDS = 60;

    private final SystemSettingRepository systemSettingRepository;
    private final ChatService chatService;
    private final GeminiConfig geminiConfig;

    // SLD 시스템 프롬프트 TTL 캐시 (SldAgentService에서 호출)
    private volatile String cachedSldPrompt;
    private volatile Instant sldPromptCacheExpiry = Instant.MIN;

    // ── 시스템 프롬프트 ──────────────────────────────

    /**
     * 현재 시스템 프롬프트 조회
     * DB에 저장된 값이 있으면 반환, 없으면 파일 기본값 반환
     */
    public String getSystemPrompt() {
        return systemSettingRepository.findById("chat_system_prompt")
                .map(SystemSetting::getSettingValue)
                .filter(v -> !v.isBlank())
                .orElseGet(this::loadDefaultPromptFromFile);
    }

    /**
     * 시스템 프롬프트 업데이트
     */
    @Transactional
    public void updateSystemPrompt(String prompt, Long updatedBy) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(
                    "System prompt cannot be empty",
                    HttpStatus.BAD_REQUEST,
                    "EMPTY_SYSTEM_PROMPT"
            );
        }

        SystemSetting setting = systemSettingRepository.findById("chat_system_prompt")
                .orElseGet(() -> new SystemSetting(
                        "chat_system_prompt", "", "AI Chatbot system prompt"));
        setting.updateValue(prompt, updatedBy);
        systemSettingRepository.save(setting);

        // 같은 서버의 캐시 즉시 무효화 (다른 서버는 TTL 만료 시 자동 반영)
        chatService.invalidatePromptCache();

        log.info("System prompt updated by userSeq={}, length={}", updatedBy, prompt.length());
    }

    /**
     * 시스템 프롬프트를 파일 기본값으로 초기화
     */
    @Transactional
    public String resetSystemPrompt(Long updatedBy) {
        String defaultPrompt = loadDefaultPromptFromFile();

        SystemSetting setting = systemSettingRepository.findById("chat_system_prompt")
                .orElseGet(() -> new SystemSetting(
                        "chat_system_prompt", "", "AI Chatbot system prompt"));
        setting.updateValue(defaultPrompt, updatedBy);
        systemSettingRepository.save(setting);

        chatService.invalidatePromptCache();

        log.info("System prompt reset to default by userSeq={}", updatedBy);
        return defaultPrompt;
    }

    // ── SLD 시스템 프롬프트 ──────────────────────────────

    /**
     * SLD 시스템 프롬프트 조회 (60초 TTL 캐시)
     * SldAgentService / SldOrderAgentService에서 호출
     */
    public String getCachedSldSystemPrompt() {
        Instant now = Instant.now();
        if (now.isBefore(sldPromptCacheExpiry) && cachedSldPrompt != null) {
            return cachedSldPrompt;
        }

        String dbPrompt = systemSettingRepository.findById("sld_system_prompt")
                .map(SystemSetting::getSettingValue)
                .filter(v -> !v.isBlank())
                .orElse(null);

        String resolved = (dbPrompt != null) ? dbPrompt : loadDefaultSldPromptFromFile();
        this.cachedSldPrompt = resolved;
        this.sldPromptCacheExpiry = now.plusSeconds(CACHE_TTL_SECONDS);
        return resolved;
    }

    /**
     * SLD 시스템 프롬프트 직접 조회 (Admin 화면용, 캐시 미사용)
     */
    public String getSldSystemPrompt() {
        return systemSettingRepository.findById("sld_system_prompt")
                .map(SystemSetting::getSettingValue)
                .filter(v -> !v.isBlank())
                .orElseGet(this::loadDefaultSldPromptFromFile);
    }

    /**
     * SLD 시스템 프롬프트 업데이트
     */
    @Transactional
    public void updateSldSystemPrompt(String prompt, Long updatedBy) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(
                    "SLD system prompt cannot be empty",
                    HttpStatus.BAD_REQUEST,
                    "EMPTY_SLD_SYSTEM_PROMPT"
            );
        }

        SystemSetting setting = systemSettingRepository.findById("sld_system_prompt")
                .orElseGet(() -> new SystemSetting(
                        "sld_system_prompt", "", "AI SLD generation system prompt"));
        setting.updateValue(prompt, updatedBy);
        systemSettingRepository.save(setting);

        // 같은 서버의 캐시 즉시 무효화 (다른 서버는 TTL 만료 시 자동 반영)
        this.sldPromptCacheExpiry = Instant.MIN;

        log.info("SLD system prompt updated by userSeq={}, length={}", updatedBy, prompt.length());
    }

    /**
     * SLD 시스템 프롬프트를 파일 기본값으로 초기화
     */
    @Transactional
    public String resetSldSystemPrompt(Long updatedBy) {
        String defaultPrompt = loadDefaultSldPromptFromFile();

        SystemSetting setting = systemSettingRepository.findById("sld_system_prompt")
                .orElseGet(() -> new SystemSetting(
                        "sld_system_prompt", "", "AI SLD generation system prompt"));
        setting.updateValue(defaultPrompt, updatedBy);
        systemSettingRepository.save(setting);

        this.sldPromptCacheExpiry = Instant.MIN;

        log.info("SLD system prompt reset to default by userSeq={}", updatedBy);
        return defaultPrompt;
    }

    // ── Gemini API 키 ──────────────────────────────

    /**
     * Gemini API 키 상태 조회 (마스킹된 값 반환)
     */
    public Map<String, Object> getGeminiApiKeyStatus() {
        // DB에 저장된 오버라이드 키 확인
        String dbKey = systemSettingRepository.findById("gemini_api_key")
                .map(SystemSetting::getSettingValue)
                .filter(v -> !v.isBlank())
                .orElse(null);

        // 환경변수 키 확인
        String envKey = geminiConfig.getEnvApiKey();

        String activeKey = dbKey != null ? dbKey : envKey;
        String source = dbKey != null ? "database" : "environment";

        Map<String, Object> result = new HashMap<>();
        result.put("configured", activeKey != null && !activeKey.isBlank());
        result.put("source", source);
        result.put("maskedKey", maskApiKey(activeKey));
        result.put("model", geminiConfig.getModel());
        result.put("maxTokens", geminiConfig.getMaxTokens());
        result.put("temperature", geminiConfig.getTemperature());
        return result;
    }

    /**
     * Gemini API 키 업데이트 (DB에 저장)
     */
    @Transactional
    public void updateGeminiApiKey(String apiKey, Long updatedBy) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(
                    "API key cannot be empty",
                    HttpStatus.BAD_REQUEST,
                    "EMPTY_API_KEY"
            );
        }

        SystemSetting setting = systemSettingRepository.findById("gemini_api_key")
                .orElseGet(() -> new SystemSetting(
                        "gemini_api_key", "", "Gemini API Key (overrides environment variable)"));
        setting.updateValue(apiKey, updatedBy);
        systemSettingRepository.save(setting);

        // 같은 서버의 캐시 즉시 무효화 (다른 서버는 TTL 만료 시 자동 반영)
        geminiConfig.invalidateCache();

        log.info("Gemini API key updated by userSeq={}", updatedBy);
    }

    /**
     * Gemini API 키 삭제 (환경변수 값으로 복귀)
     */
    @Transactional
    public void clearGeminiApiKey(Long updatedBy) {
        systemSettingRepository.findById("gemini_api_key").ifPresent(setting -> {
            setting.updateValue("", updatedBy);
            systemSettingRepository.save(setting);
        });

        geminiConfig.invalidateCache();

        log.info("Gemini API key cleared (reverted to env) by userSeq={}", updatedBy);
    }

    // ── 이메일 인증 ──────────────────────────────

    /**
     * 이메일 인증 설정 조회
     */
    public boolean isEmailVerificationEnabled() {
        return systemSettingRepository.findById("email_verification_enabled")
                .map(SystemSetting::toBooleanValue)
                .orElse(false);
    }

    /**
     * 이메일 인증 설정 변경
     */
    @Transactional
    public void updateEmailVerification(boolean enabled, Long updatedBy) {
        SystemSetting setting = systemSettingRepository.findById("email_verification_enabled")
                .orElseGet(() -> new SystemSetting(
                        "email_verification_enabled", "false", "Email verification toggle"));
        setting.updateValue(String.valueOf(enabled), updatedBy);
        systemSettingRepository.save(setting);

        log.info("Email verification {} by userSeq={}", enabled ? "enabled" : "disabled", updatedBy);
    }

    // ── AI SLD 생성 ──────────────────────────────

    /**
     * AI SLD 생성 설정 조회
     */
    public boolean isSldAiGenerationEnabled() {
        return systemSettingRepository.findById("sld_ai_generation_enabled")
                .map(SystemSetting::toBooleanValue)
                .orElse(true);
    }

    /**
     * AI SLD 생성 설정 변경
     */
    @Transactional
    public void updateSldAiGeneration(boolean enabled, Long updatedBy) {
        SystemSetting setting = systemSettingRepository.findById("sld_ai_generation_enabled")
                .orElseGet(() -> new SystemSetting(
                        "sld_ai_generation_enabled", "true", "Enable AI-powered SLD generation"));
        setting.updateValue(String.valueOf(enabled), updatedBy);
        systemSettingRepository.save(setting);

        log.info("SLD AI generation {} by userSeq={}", enabled ? "enabled" : "disabled", updatedBy);
    }

    // ── 전체 시스템 설정 조회 ──────────────────────────────

    /**
     * SYSTEM_ADMIN용 시스템 설정 일괄 조회
     */
    public Map<String, Object> getSystemSettings() {
        Map<String, Object> result = new HashMap<>();

        // 이메일 인증 설정
        result.put("emailVerificationEnabled", isEmailVerificationEnabled());

        // AI SLD 생성 설정
        result.put("sldAiGenerationEnabled", isSldAiGenerationEnabled());

        // Gemini API 상태
        result.put("geminiApiKey", getGeminiApiKeyStatus());

        // 시스템 프롬프트 (길이만)
        String prompt = getSystemPrompt();
        result.put("systemPromptLength", prompt.length());

        return result;
    }

    // ── Private helpers ──────────────────────────────

    private String loadDefaultPromptFromFile() {
        try {
            var resource = new ClassPathResource("chat-system-prompt.txt");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load default system prompt from file", e);
            return "";
        }
    }

    private String loadDefaultSldPromptFromFile() {
        try {
            var resource = new ClassPathResource("sld-system-prompt.txt");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load default SLD system prompt from file", e);
            return "";
        }
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "(not set)";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
