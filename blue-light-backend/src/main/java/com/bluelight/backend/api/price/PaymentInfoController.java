package com.bluelight.backend.api.price;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 결제 수취 정보 API (public - 인증 불필요)
 * 신청자가 PENDING_PAYMENT 상태에서 결제 안내를 볼 때 사용
 */
@Slf4j
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PaymentInfoController {

    private final SystemSettingRepository systemSettingRepository;
    private final FileStorageService fileStorageService;

    private static final String[] PAYMENT_KEYS = {
            "payment_paynow_uen",
            "payment_paynow_name",
            "payment_paynow_qr",
            "payment_bank_name",
            "payment_bank_account",
            "payment_bank_account_name"
    };

    /**
     * 결제 수취 정보 조회
     * GET /api/public/payment-info
     */
    @GetMapping("/payment-info")
    public ResponseEntity<Map<String, String>> getPaymentInfo() {
        log.debug("Payment info requested");
        Map<String, String> paymentInfo = new HashMap<>();
        for (String key : PAYMENT_KEYS) {
            systemSettingRepository.findById(key)
                    .ifPresent(setting -> {
                        String value = setting.getSettingValue();
                        if ("payment_paynow_qr".equals(key) && value != null && !value.isBlank()) {
                            // QR 이미지는 파일 경로 대신 다운로드 URL 제공
                            // 프런트엔드 VITE_API_BASE_URL에 /api가 포함되므로 상대경로만 반환
                            paymentInfo.put(key, "/public/payment-qr");
                        } else {
                            paymentInfo.put(key, value);
                        }
                    });
        }
        return ResponseEntity.ok(paymentInfo);
    }

    /**
     * PayNow QR 이미지 다운로드
     * GET /api/public/payment-qr
     */
    @GetMapping("/payment-qr")
    public ResponseEntity<Resource> getPaymentQrImage() {
        var setting = systemSettingRepository.findById("payment_paynow_qr");
        if (setting.isEmpty() || setting.get().getSettingValue() == null
                || setting.get().getSettingValue().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        String filePath = setting.get().getSettingValue();
        Resource resource = fileStorageService.loadAsResource(filePath);

        // Determine content type from filename
        String contentType = "image/png";
        if (filePath.endsWith(".jpg") || filePath.endsWith(".jpeg")) {
            contentType = "image/jpeg";
        } else if (filePath.endsWith(".gif")) {
            contentType = "image/gif";
        } else if (filePath.endsWith(".webp")) {
            contentType = "image/webp";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(resource);
    }
}
