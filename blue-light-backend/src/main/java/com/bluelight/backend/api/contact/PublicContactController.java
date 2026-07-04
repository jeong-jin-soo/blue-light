package com.bluelight.backend.api.contact;

import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 공개 연락처 정보 API (public - 인증 불필요)
 * 랜딩/서비스 페이지의 WhatsApp 문의 채널이 사용
 * 번호 정본은 system_settings.whatsapp_business_number (설정 우선 원칙)
 */
@Slf4j
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicContactController {

    private final SystemSettingRepository systemSettingRepository;

    /**
     * 공개 연락처 조회
     * GET /api/public/contact-info
     */
    @GetMapping("/contact-info")
    public ResponseEntity<Map<String, String>> getContactInfo() {
        log.debug("Public contact info requested");
        String whatsappNumber = systemSettingRepository.findById("whatsapp_business_number")
                .map(SystemSetting::getSettingValue)
                .orElse("");
        return ResponseEntity.ok(Map.of("whatsappBusinessNumber", whatsappNumber));
    }
}
