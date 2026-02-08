package com.bluelight.backend.api.application;

import com.bluelight.backend.api.admin.dto.PaymentResponse;
import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.ApplicationSummaryResponse;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Application service for applicants
 * - Create, list, detail, summary, payment history
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final MasterPriceRepository masterPriceRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    /**
     * Create a new licence application
     *
     * @param userSeq authenticated user ID
     * @param request application details
     * @return created application
     */
    @Transactional
    public ApplicationResponse createApplication(Long userSeq, CreateApplicationRequest request) {
        // Find user
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // Calculate price from kVA
        MasterPrice masterPrice = masterPriceRepository.findByKva(request.getSelectedKva())
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + request.getSelectedKva() + " kVA",
                        HttpStatus.BAD_REQUEST,
                        "PRICE_TIER_NOT_FOUND"
                ));

        // Create application
        Application application = Application.builder()
                .user(user)
                .address(request.getAddress())
                .postalCode(request.getPostalCode())
                .buildingType(request.getBuildingType())
                .selectedKva(request.getSelectedKva())
                .quoteAmount(masterPrice.getPrice())
                .build();

        Application saved = applicationRepository.save(application);
        log.info("Application created: applicationSeq={}, userSeq={}, kva={}, amount={}",
                saved.getApplicationSeq(), userSeq, request.getSelectedKva(), masterPrice.getPrice());

        return ApplicationResponse.from(saved);
    }

    /**
     * Get all applications for the authenticated user
     *
     * @param userSeq authenticated user ID
     * @return list of applications
     */
    public List<ApplicationResponse> getMyApplications(Long userSeq) {
        return applicationRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq)
                .stream()
                .map(ApplicationResponse::from)
                .toList();
    }

    /**
     * Get a single application detail
     *
     * @param userSeq authenticated user ID
     * @param applicationSeq application ID
     * @return application detail
     */
    public ApplicationResponse getMyApplication(Long userSeq, Long applicationSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"
                ));

        // Verify ownership
        if (!application.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        return ApplicationResponse.from(application);
    }

    /**
     * Get application summary for dashboard
     *
     * @param userSeq authenticated user ID
     * @return summary with counts by status
     */
    public ApplicationSummaryResponse getMyApplicationSummary(Long userSeq) {
        List<Application> applications = applicationRepository.findByUserUserSeq(userSeq);

        long total = applications.size();
        long pendingPayment = applications.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.PENDING_PAYMENT)
                .count();
        long inProgress = applications.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.PAID || a.getStatus() == ApplicationStatus.IN_PROGRESS)
                .count();
        long completed = applications.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.COMPLETED)
                .count();

        return ApplicationSummaryResponse.builder()
                .total(total)
                .pendingPayment(pendingPayment)
                .inProgress(inProgress)
                .completed(completed)
                .build();
    }

    /**
     * Get payment history for an application (verifies ownership)
     *
     * @param userSeq authenticated user ID
     * @param applicationSeq application ID
     * @return list of payments
     */
    public List<PaymentResponse> getApplicationPayments(Long userSeq, Long applicationSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"
                ));

        // Verify ownership
        if (!application.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        return paymentRepository.findByApplicationApplicationSeq(applicationSeq)
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }
}
