package com.bluelight.backend.api.application;

import com.bluelight.backend.api.admin.dto.PaymentResponse;
import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.ApplicationSummaryResponse;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.application.dto.UpdateApplicationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Application API controller (authenticated applicants)
 */
@Slf4j
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    /**
     * Create a new licence application
     * POST /api/applications
     */
    @PostMapping
    public ResponseEntity<ApplicationResponse> createApplication(
            Authentication authentication,
            @Valid @RequestBody CreateApplicationRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Create application request: userSeq={}, kva={}", userSeq, request.getSelectedKva());
        ApplicationResponse response = applicationService.createApplication(userSeq, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get my applications list
     * GET /api/applications
     */
    @GetMapping
    public ResponseEntity<List<ApplicationResponse>> getMyApplications(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Get my applications: userSeq={}", userSeq);
        List<ApplicationResponse> applications = applicationService.getMyApplications(userSeq);
        return ResponseEntity.ok(applications);
    }

    /**
     * Get application detail
     * GET /api/applications/:id
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApplicationResponse> getMyApplication(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Get application detail: userSeq={}, applicationSeq={}", userSeq, id);
        ApplicationResponse response = applicationService.getMyApplication(userSeq, id);
        return ResponseEntity.ok(response);
    }

    /**
     * Update and resubmit application (after revision request)
     * PUT /api/applications/:id
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApplicationResponse> updateApplication(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateApplicationRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Update application request: userSeq={}, applicationSeq={}", userSeq, id);
        ApplicationResponse response = applicationService.updateApplication(userSeq, id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get completed applications (갱신 시 원본 선택용)
     * GET /api/applications/completed
     */
    @GetMapping("/completed")
    public ResponseEntity<List<ApplicationResponse>> getCompletedApplications(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Get completed applications: userSeq={}", userSeq);
        List<ApplicationResponse> applications = applicationService.getCompletedApplications(userSeq);
        return ResponseEntity.ok(applications);
    }

    /**
     * Get application summary for dashboard
     * GET /api/applications/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<ApplicationSummaryResponse> getMyApplicationSummary(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Get application summary: userSeq={}", userSeq);
        ApplicationSummaryResponse summary = applicationService.getMyApplicationSummary(userSeq);
        return ResponseEntity.ok(summary);
    }

    /**
     * Get payment history for my application
     * GET /api/applications/:id/payments
     */
    @GetMapping("/{id}/payments")
    public ResponseEntity<List<PaymentResponse>> getMyApplicationPayments(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Get application payments: userSeq={}, applicationSeq={}", userSeq, id);
        List<PaymentResponse> payments = applicationService.getApplicationPayments(userSeq, id);
        return ResponseEntity.ok(payments);
    }
}
