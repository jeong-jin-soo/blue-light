package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.file.FileService;
import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 신청자 결제 신호(E2/E3) 비즈니스 서비스.
 *
 * <ul>
 *   <li><b>E2</b> {@link #reportPaymentEvidence} — 결제 증빙(PAYMENT_RECEIPT) 업로드 후
 *       ADMIN/SYSTEM_ADMIN 전원에게 A-55 알림(오케스트레이터).</li>
 *   <li><b>E3</b> {@link #requestPaymentConfirmation} — "결제 확인 요청" 후 ADMIN/SYSTEM_ADMIN
 *       전원에게 A-56 알림(오케스트레이터).</li>
 * </ul>
 *
 * <p>두 동작 모두 {@code PENDING_PAYMENT} 상태 가드 + 소유자(applicant) 검증을 거친다. 파일 저장은
 * 기존 {@link FileService#uploadFile} 를 재사용 — 소유권/확장자/저장/FileEntity 로직 중복 방지.</p>
 *
 * <p>알림 발행은 {@link AdminPaymentSignalNotifier} 가 admin 별 {@code NotificationDispatchEvent} 를
 * 발행하고, NotificationOrchestrator 가 AFTER_COMMIT 단계에서 채널·로케일·옵트인을 결정해 발송한다.
 * 즉 본 트랜잭션이 롤백되면 알림 자체가 발행되지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicantPaymentSignalService {

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final FileService fileService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * E2 — 신청자가 결제 증빙을 업로드한다.
     * <ol>
     *   <li>소유자 검증 + PENDING_PAYMENT 가드</li>
     *   <li>{@link FileService#uploadFile} 로 PAYMENT_RECEIPT 저장 (소유권 재검증 포함)</li>
     *   <li>ADMIN/SYSTEM_ADMIN 전원에게 A-55 알림 dispatch</li>
     * </ol>
     */
    @Transactional
    public FileResponse reportPaymentEvidence(Long userSeq, Long applicationSeq, MultipartFile file) {
        Application application = requirePendingPaymentOwned(userSeq, applicationSeq);

        // 파일 저장 — FileService 가 소유권/확장자/저장/FileEntity 를 담당.
        FileResponse response = fileService.uploadFile(userSeq, applicationSeq, file, FileType.PAYMENT_RECEIPT);

        AdminPaymentSignalNotifier.dispatchToAdmins(
                eventPublisher, userRepository, application,
                NotificationType.PAYMENT_EVIDENCE_UPLOADED.name(), "A-55");

        log.info("Payment evidence reported: applicationSeq={}, userSeq={}, fileSeq={}",
                applicationSeq, userSeq, response.getFileSeq());
        return response;
    }

    /**
     * E3 — 신청자가 "결제 확인 요청" 버튼을 클릭한다 (파일 없이 신호만).
     * <ol>
     *   <li>소유자 검증 + PENDING_PAYMENT 가드</li>
     *   <li>ADMIN/SYSTEM_ADMIN 전원에게 A-56 알림 dispatch</li>
     * </ol>
     */
    @Transactional
    public void requestPaymentConfirmation(Long userSeq, Long applicationSeq) {
        Application application = requirePendingPaymentOwned(userSeq, applicationSeq);

        AdminPaymentSignalNotifier.dispatchToAdmins(
                eventPublisher, userRepository, application,
                NotificationType.PAYMENT_CONFIRMATION_REQUESTED.name(), "A-56");

        log.info("Payment confirmation requested by applicant: applicationSeq={}, userSeq={}",
                applicationSeq, userSeq);
    }

    /**
     * 신청서 로드 + 소유자 검증 + PENDING_PAYMENT 가드. 위반 시 BusinessException.
     */
    private Application requirePendingPaymentOwned(Long userSeq, Long applicationSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        if (application.getStatus() != ApplicationStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment signals are only allowed while the application is awaiting payment.",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS_FOR_PAYMENT_SIGNAL");
        }
        return application;
    }
}
