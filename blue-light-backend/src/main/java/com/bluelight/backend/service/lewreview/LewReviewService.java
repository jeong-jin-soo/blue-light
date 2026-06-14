package com.bluelight.backend.service.lewreview;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.lew.dto.LewApplicationResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.exception.LewReviewErrorCode;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.document.DocumentRequestRepository;
import com.bluelight.backend.domain.document.DocumentRequestStatus;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * LEW Review Form — 배정 신청 조회 + 결제 요청 서비스.
 *
 * <p>접근 제어는 컨트롤러의 {@code @PreAuthorize("@appSec.isAssignedLew(#id, authentication)")}에서
 * 일차 방어하고, 서비스 진입 시 {@link #assertAssignedLew(Application, Long)}로 이중 방어한다.</p>
 *
 * <h3>흐름</h3>
 * <ul>
 *   <li>{@link #getAssignedApplication} — 조회, 감사는 컨트롤러 어노테이션으로</li>
 *   <li>{@link #requestPayment} — Phase 1(검토·서류·kVA) 종료 후 LEW가 결제 단계로 전이를 트리거.
 *       PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LewReviewService {

    /** Phase 1 종료 가드: DocumentRequest 미해결 판정에 사용 */
    private static final Set<DocumentRequestStatus> DOCUMENT_PENDING_STATUSES =
            Set.of(DocumentRequestStatus.REQUESTED, DocumentRequestStatus.UPLOADED);

    private final ApplicationRepository applicationRepository;
    // Phase 1 종료 가드
    private final DocumentRequestRepository documentRequestRepository;
    // LEW가 결제 요청 트리거 시 신청자 메일 발송 (ADMIN 흐름과 동일)
    private final EmailService emailService;

    /** 배정 신청 상세 조회. */
    public LewApplicationResponse getAssignedApplication(Long applicationSeq, Long lewUserSeq) {
        Application application = loadApplication(applicationSeq);
        assertAssignedLew(application, lewUserSeq);

        // EncryptedStringConverter는 읽을 때 이미 복호화된 평문을 엔티티 getter로 돌려준다.
        String landlordPlain = application.getLandlordEiLicenceNo();
        String[] correspondencePlain = new String[]{
                application.getCorrespondenceAddressBlock(),
                application.getCorrespondenceAddressUnit(),
                application.getCorrespondenceAddressStreet(),
                application.getCorrespondenceAddressBuilding()
        };
        // 신청자 hint MSSL 평문 — 엔티티 getter로 이미 복호화된 값.
        String msslHintPlain = application.getApplicantMsslHintEnc();

        return LewApplicationResponse.from(application, landlordPlain, correspondencePlain,
                msslHintPlain);
    }

    /**
     * LEW가 명시적으로 결제 요청을 트리거 (옵션 R).
     *
     * <p>Phase 1 (검토 + 서류 보강 + kVA 확정) 종료 후, LEW가 직접 호출하여 status 를
     * {@code PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT} 로 전이시킨다.</p>
     *
     * <h3>가드 (서버측 재검증 필수)</h3>
     * <ol>
     *   <li>현재 status ∈ {PENDING_REVIEW, REVISION_REQUESTED} 가 아니면 → 409 {@code INVALID_STATUS_TRANSITION}.
     *       ADMIN의 별도 approveForPayment 와 race 발생 시 두 번째 호출이 이 코드로 거부된다.</li>
     *   <li>{@code Application.kvaStatus != CONFIRMED} → 409 {@code KVA_NOT_CONFIRMED}</li>
     *   <li>미해결 DocumentRequest(REQUESTED/UPLOADED) 존재 → 409 {@code DOCUMENT_REQUESTS_PENDING}</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationResponse requestPayment(Long applicationSeq, Long lewUserSeq) {
        Application application = loadApplication(applicationSeq);
        assertAssignedLew(application, lewUserSeq);

        // 1) status 가드: PENDING_REVIEW 또는 REVISION_REQUESTED 만 허용
        ApplicationStatus cur = application.getStatus();
        if (cur != ApplicationStatus.PENDING_REVIEW && cur != ApplicationStatus.REVISION_REQUESTED) {
            throw new BusinessException(
                    "Payment can only be requested from PENDING_REVIEW or REVISION_REQUESTED (current: "
                            + cur + ")",
                    HttpStatus.CONFLICT, LewReviewErrorCode.INVALID_STATUS_TRANSITION);
        }

        // 2) Phase 1 종료 가드 (LEW가 검토를 끝냈는지 재확인) — 상태 충돌이므로 409
        assertKvaConfirmed(application);
        assertNoPendingDocumentRequests(applicationSeq);

        // 상태 전이 — 도메인 메서드 사용 (reviewComment 클리어 포함)
        application.approveForPayment();
        log.info("LEW requested payment: applicationSeq={}, lewUserSeq={}, prevStatus={}",
                applicationSeq, lewUserSeq, cur);

        // 신청자에게 결제 요청 이메일 발송 — ADMIN 흐름과 동일. 실패가 트랜잭션을 깨뜨리지 않도록 방어.
        notifyPaymentRequested(application);

        return ApplicationResponse.from(application);
    }

    // ── 내부 유틸 ──────────────────────

    private Application loadApplication(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    private void assertAssignedLew(Application application, Long lewUserSeq) {
        User assigned = application.getAssignedLew();
        if (assigned == null || !assigned.getUserSeq().equals(lewUserSeq)) {
            throw new BusinessException(
                    "You are not assigned to this application",
                    HttpStatus.FORBIDDEN, LewReviewErrorCode.APPLICATION_NOT_ASSIGNED);
        }
    }

    /** 가드: {@link Application#getKvaStatus()} 가 CONFIRMED 이어야 requestPayment 가능 (상태 충돌 → 409). */
    private void assertKvaConfirmed(Application application) {
        if (application.getKvaStatus() != KvaStatus.CONFIRMED) {
            throw new BusinessException(
                    "kVA must be confirmed first (current kvaStatus: "
                            + application.getKvaStatus() + ")",
                    HttpStatus.CONFLICT, LewReviewErrorCode.KVA_NOT_CONFIRMED);
        }
    }

    /** 가드: 미해결 DocumentRequest(REQUESTED/UPLOADED) 가 없어야 requestPayment 가능. */
    private void assertNoPendingDocumentRequests(Long applicationSeq) {
        long pending = documentRequestRepository.countByApplicationAndStatusIn(
                applicationSeq, DOCUMENT_PENDING_STATUSES);
        if (pending > 0) {
            throw new BusinessException(
                    "There are " + pending + " pending document request(s) — resolve them first",
                    HttpStatus.CONFLICT, LewReviewErrorCode.DOCUMENT_REQUESTS_PENDING);
        }
    }

    /**
     * LEW가 결제 요청을 트리거하면 신청자에게 결제 요청 이메일 발송.
     * 메일 발송 실패는 swallow 하여 상태 전이 트랜잭션을 롤백하지 않는다.
     */
    private void notifyPaymentRequested(Application application) {
        try {
            User applicant = application.getUser();
            if (applicant == null || applicant.getEmail() == null) {
                log.warn("결제 요청 메일 발송 스킵 — 신청자 정보 없음: applicationId={}",
                        application.getApplicationSeq());
                return;
            }
            emailService.sendPaymentRequestEmail(
                    applicant.getEmail(),
                    (applicant.getFirstName() != null ? applicant.getFirstName() : "") + " "
                            + (applicant.getLastName() != null ? applicant.getLastName() : ""),
                    application.getApplicationSeq(),
                    application.getAddress(),
                    application.getQuoteAmount());
        } catch (RuntimeException ex) {
            log.warn("결제 요청 메일 발송 실패 (LEW trigger): applicationId={}, err={}",
                    application.getApplicationSeq(), ex.getMessage());
        }
    }
}
