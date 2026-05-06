package com.bluelight.backend.api.expiredlicenseorder;

import com.bluelight.backend.api.expiredlicenseorder.dto.CreateExpiredLicenseOrderRequest;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderPaymentResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.RevisitRequestDto;
import com.bluelight.backend.api.expiredlicenseorder.dto.UpdateExpiredLicenseOrderRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/expired-license-orders")
@RequiredArgsConstructor
public class ExpiredLicenseOrderController {

    private final ExpiredLicenseOrderService expiredLicenseOrderService;

    @PostMapping
    public ResponseEntity<ExpiredLicenseOrderResponse> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreateExpiredLicenseOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Expired License 주문 생성 요청: userSeq={}", userSeq);
        ExpiredLicenseOrderResponse response = expiredLicenseOrderService.createOrder(userSeq, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ExpiredLicenseOrderResponse>> getMyOrders(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.getMyOrders(userSeq));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExpiredLicenseOrderResponse> getOrder(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.getOrder(id, userSeq));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExpiredLicenseOrderResponse> updateOrder(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateExpiredLicenseOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.updateOrder(id, userSeq, request));
    }

    @PostMapping("/{id}/accept-quote")
    public ResponseEntity<ExpiredLicenseOrderResponse> acceptQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.acceptQuote(id, userSeq));
    }

    @PostMapping("/{id}/reject-quote")
    public ResponseEntity<ExpiredLicenseOrderResponse> rejectQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.rejectQuote(id, userSeq));
    }

    @PostMapping("/{id}/request-revisit")
    public ResponseEntity<ExpiredLicenseOrderResponse> requestRevisit(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody RevisitRequestDto request) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.requestRevisit(id, userSeq, request.getComment()));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ExpiredLicenseOrderResponse> confirmCompletion(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.confirmCompletion(id, userSeq));
    }

    @GetMapping("/{id}/payments")
    public ResponseEntity<List<ExpiredLicenseOrderPaymentResponse>> getPayments(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseOrderService.getPayments(id, userSeq));
    }
}
