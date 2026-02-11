package com.bluelight.backend.api.price;

import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final String[] PAYMENT_KEYS = {
            "payment_paynow_uen",
            "payment_paynow_name",
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
                    .ifPresent(setting -> paymentInfo.put(key, setting.getSettingValue()));
        }
        return ResponseEntity.ok(paymentInfo);
    }
}
