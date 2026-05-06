package com.bluelight.backend.api.expiredlicenseorder;

import com.bluelight.backend.api.expiredlicenseorder.dto.CheckOutRequest;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderDashboardResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.expiredlicenseorder.dto.ScheduleVisitRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/expired-license-manager")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
public class ExpiredLicenseManagerController {

    private final ExpiredLicenseManagerService expiredLicenseManagerService;

    @GetMapping("/dashboard")
    public ResponseEntity<ExpiredLicenseOrderDashboardResponse> getDashboard() {
        return ResponseEntity.ok(expiredLicenseManagerService.getDashboard());
    }

    @GetMapping("/orders")
    public ResponseEntity<Page<ExpiredLicenseOrderResponse>> getAllOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                expiredLicenseManagerService.getAllOrders(status, PageRequest.of(page, size)));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<ExpiredLicenseOrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(expiredLicenseManagerService.getOrder(id));
    }

    @PostMapping("/orders/{id}/propose-quote")
    public ResponseEntity<ExpiredLicenseOrderResponse> proposeQuote(
            @PathVariable Long id,
            @Valid @RequestBody ProposeQuoteRequest request) {
        return ResponseEntity.ok(expiredLicenseManagerService.proposeQuote(id, request));
    }

    @PostMapping("/orders/{id}/assign")
    public ResponseEntity<ExpiredLicenseOrderResponse> assignManager(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Long managerUserSeq = body.get("managerUserSeq");
        return ResponseEntity.ok(expiredLicenseManagerService.assignManager(id, managerUserSeq));
    }

    @DeleteMapping("/orders/{id}/assign")
    public ResponseEntity<ExpiredLicenseOrderResponse> unassignManager(@PathVariable Long id) {
        return ResponseEntity.ok(expiredLicenseManagerService.unassignManager(id));
    }

    @PostMapping("/orders/{id}/payment/confirm")
    public ResponseEntity<ExpiredLicenseOrderResponse> confirmPayment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String transactionId = body.get("transactionId");
        String paymentMethod = body.get("paymentMethod");
        return ResponseEntity.ok(expiredLicenseManagerService.confirmPayment(id, transactionId, paymentMethod));
    }

    @PostMapping("/orders/{id}/complete")
    public ResponseEntity<ExpiredLicenseOrderResponse> markComplete(@PathVariable Long id) {
        return ResponseEntity.ok(expiredLicenseManagerService.markComplete(id));
    }

    @PostMapping("/orders/{id}/schedule-visit")
    public ResponseEntity<ExpiredLicenseOrderResponse> scheduleVisit(
            @PathVariable Long id,
            @Valid @RequestBody ScheduleVisitRequest request,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseManagerService.scheduleVisit(id, managerUserSeq, request));
    }

    @PostMapping("/orders/{id}/check-in")
    public ResponseEntity<ExpiredLicenseOrderResponse> checkIn(
            @PathVariable Long id,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseManagerService.checkIn(id, managerUserSeq));
    }

    @PostMapping("/orders/{id}/check-out")
    public ResponseEntity<ExpiredLicenseOrderResponse> checkOut(
            @PathVariable Long id,
            @Valid @RequestBody CheckOutRequest request,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseManagerService.checkOut(id, managerUserSeq, request));
    }

    @PostMapping("/orders/{id}/visit-photos")
    public ResponseEntity<ExpiredLicenseOrderResponse> uploadVisitPhotos(
            @PathVariable Long id,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "captions", required = false) List<String> captions,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(expiredLicenseManagerService.uploadVisitPhotos(id, managerUserSeq, files, captions));
    }
}
