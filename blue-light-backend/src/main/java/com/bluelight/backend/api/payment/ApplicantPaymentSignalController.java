package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.file.dto.FileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 신청자 결제 신호 API (E2/E3) — 결제 증빙 업로드 + 결제 확인 요청.
 *
 * <p>경로는 {@code /api/applications/**} (Applicant 권한). 소유자(applicant) 검증과 PENDING_PAYMENT
 * 가드는 {@link ApplicantPaymentSignalService} 가 책임. 두 동작 모두 ADMIN/SYSTEM_ADMIN 전원에게
 * notification_templates(오케스트레이터) 경유로 알림을 발행한다 (E2=A-55, E3=A-56).</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ApplicantPaymentSignalController {

    private final ApplicantPaymentSignalService paymentSignalService;

    /**
     * E2 — 결제 증빙(PAYMENT_RECEIPT) 업로드.
     * POST /api/applications/{id}/payment/evidence (multipart)
     */
    @PostMapping(value = "/api/applications/{id}/payment/evidence",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileResponse> reportPaymentEvidence(
            Authentication authentication,
            @PathVariable("id") Long applicationSeq,
            @RequestPart("file") MultipartFile file) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Report payment evidence: userSeq={}, applicationSeq={}", userSeq, applicationSeq);
        FileResponse response = paymentSignalService.reportPaymentEvidence(userSeq, applicationSeq, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * E3 — "결제 확인 요청" (파일 없이 신호만).
     * POST /api/applications/{id}/payment/request-confirmation
     */
    @PostMapping("/api/applications/{id}/payment/request-confirmation")
    public ResponseEntity<Void> requestPaymentConfirmation(
            Authentication authentication,
            @PathVariable("id") Long applicationSeq) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request payment confirmation: userSeq={}, applicationSeq={}", userSeq, applicationSeq);
        paymentSignalService.requestPaymentConfirmation(userSeq, applicationSeq);
        return ResponseEntity.noContent().build();
    }
}
