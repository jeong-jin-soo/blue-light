package com.bluelight.backend.api.document;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.document.DocumentRequest;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Phase 3 PR#4 — 서류 요청 워크플로 알림 오케스트레이터.
 *
 * <p>인앱 알림과 이메일 발송을 한 곳에서 관리한다. 발송 트리거는 반드시 트랜잭션
 * 커밋 이후({@code afterCommit})에 일어나도록 보장하여, 롤백된 상태가 수신자에게
 * 통보되는 사고를 차단한다 (B-2 §3.4 / P3-R6).
 *
 * <p>개별 호출 실패는 catch-and-log 처리하며, 알림/이메일 오류가 비즈니스
 * 트랜잭션을 롤백시키지 않는다 (SmtpEmailService 의 기존 계약과 동일).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRequestNotifier {

    private final NotificationService notificationService;
    private final EmailService emailService;

    /**
     * 배치 요청 생성 → 신청자에게 인앱 + 이메일 (AC-N1).
     */
    public void notifyCreated(Application application, List<DocumentRequest> created) {
        if (application == null || created == null || created.isEmpty()) return;
        User applicant = application.getUser();
        if (applicant == null) return;

        final Long applicantSeq = applicant.getUserSeq();
        final Long appSeq = application.getApplicationSeq();
        final String applicantName = applicant.getFullName();
        final String applicantEmail = applicant.getEmail();
        final int count = created.size();
        final List<String> labels = created.stream()
                .map(dr -> dr.getCustomLabel() != null && !dr.getCustomLabel().isBlank()
                        ? dr.getCustomLabel()
                        : dr.getDocumentTypeCode())
                .toList();

        afterCommit(() -> {
            createInAppSafe(applicantSeq, NotificationType.DOCUMENT_REQUEST_CREATED,
                    "LEW가 서류를 요청했습니다",
                    "신청 #" + appSeq + " — " + count + "건의 서류 요청이 도착했습니다.",
                    "APPLICATION", appSeq);
            if (hasEmail(applicantEmail)) {
                try {
                    emailService.sendDocumentRequestCreatedEmail(
                            applicantEmail, applicantName, appSeq, count, labels);
                } catch (Exception e) {
                    log.warn("sendDocumentRequestCreatedEmail failed (suppressed): appSeq={}, err={}",
                            appSeq, e.getMessage());
                }
            }
        });
    }

    /**
     * 신청자 업로드 → 할당 LEW 에게 인앱 + 이메일 (AC-N2).
     * LEW 미할당 시 조용히 skip.
     */
    public void notifyFulfilled(DocumentRequest request) {
        if (request == null) return;
        Application application = request.getApplication();
        if (application == null || application.getAssignedLew() == null) return;

        User lew = application.getAssignedLew();
        final Long lewSeq = lew.getUserSeq();
        final String lewEmail = lew.getEmail();
        final String lewName = lew.getFullName();
        final Long appSeq = application.getApplicationSeq();
        final Long drId = request.getId();
        final String code = request.getDocumentTypeCode();
        final String label = documentLabel(request);

        afterCommit(() -> {
            createInAppSafe(lewSeq, NotificationType.DOCUMENT_REQUEST_FULFILLED,
                    "신청자가 서류를 업로드했습니다",
                    "신청 #" + appSeq + " — " + code + " 검토가 필요합니다.",
                    "DOCUMENT_REQUEST", drId);
            if (hasEmail(lewEmail)) {
                try {
                    emailService.sendDocumentRequestFulfilledEmail(lewEmail, lewName, appSeq, label);
                } catch (Exception e) {
                    log.warn("sendDocumentRequestFulfilledEmail failed (suppressed): drId={}, err={}",
                            drId, e.getMessage());
                }
            }
        });
    }

    /**
     * LEW 승인 → 신청자에게 인앱 + 이메일 (AC-N3).
     */
    public void notifyApproved(DocumentRequest request) {
        if (request == null) return;
        Application application = request.getApplication();
        if (application == null) return;
        User applicant = application.getUser();
        if (applicant == null) return;

        final Long applicantSeq = applicant.getUserSeq();
        final String applicantName = applicant.getFullName();
        final String applicantEmail = applicant.getEmail();
        final Long appSeq = application.getApplicationSeq();
        final Long drId = request.getId();
        final String code = request.getDocumentTypeCode();
        final String label = documentLabel(request);

        afterCommit(() -> {
            createInAppSafe(applicantSeq, NotificationType.DOCUMENT_REQUEST_APPROVED,
                    "서류가 승인되었습니다",
                    "신청 #" + appSeq + " — " + code + " 승인 완료.",
                    "DOCUMENT_REQUEST", drId);
            if (hasEmail(applicantEmail)) {
                try {
                    emailService.sendDocumentRequestApprovedEmail(applicantEmail, applicantName, appSeq, label);
                } catch (Exception e) {
                    log.warn("sendDocumentRequestApprovedEmail failed (suppressed): drId={}, err={}",
                            drId, e.getMessage());
                }
            }
        });
    }

    /**
     * LEW 반려 → 신청자에게 인앱 + 이메일 (사유 포함, AC-N3).
     */
    public void notifyRejected(DocumentRequest request) {
        if (request == null) return;
        Application application = request.getApplication();
        if (application == null) return;
        User applicant = application.getUser();
        if (applicant == null) return;

        final Long applicantSeq = applicant.getUserSeq();
        final String applicantName = applicant.getFullName();
        final String applicantEmail = applicant.getEmail();
        final Long appSeq = application.getApplicationSeq();
        final Long drId = request.getId();
        final String code = request.getDocumentTypeCode();
        final String label = documentLabel(request);
        final String reason = request.getRejectionReason();

        afterCommit(() -> {
            createInAppSafe(applicantSeq, NotificationType.DOCUMENT_REQUEST_REJECTED,
                    "서류가 반려되었습니다",
                    "신청 #" + appSeq + " — " + code + " 반려. 재업로드가 필요합니다.",
                    "DOCUMENT_REQUEST", drId);
            if (hasEmail(applicantEmail)) {
                try {
                    emailService.sendDocumentRequestRejectedEmail(
                            applicantEmail, applicantName, appSeq, label, reason);
                } catch (Exception e) {
                    log.warn("sendDocumentRequestRejectedEmail failed (suppressed): drId={}, err={}",
                            drId, e.getMessage());
                }
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────────────────────────────

    /**
     * afterCommit 훅에 등록하되, 활성 트랜잭션이 없으면 즉시 실행한다.
     * (테스트/예외 경로에서 TX 없이 호출될 수 있으므로 견고성 확보.)
     */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void createInAppSafe(Long recipientSeq, NotificationType type,
                                  String title, String message,
                                  String referenceType, Long referenceId) {
        if (recipientSeq == null) return;
        try {
            notificationService.createNotification(recipientSeq, type, title, message, referenceType, referenceId);
        } catch (Exception e) {
            log.warn("In-app notification failed (suppressed): type={}, recipient={}, err={}",
                    type, recipientSeq, e.getMessage());
        }
    }

    private static boolean hasEmail(String email) {
        return email != null && !email.isBlank();
    }

    private static String documentLabel(DocumentRequest request) {
        String custom = request.getCustomLabel();
        if (custom != null && !custom.isBlank()) return custom;
        return request.getDocumentTypeCode();
    }
}
