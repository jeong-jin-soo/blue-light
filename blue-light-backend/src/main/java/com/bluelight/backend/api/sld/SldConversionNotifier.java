package com.bluelight.backend.api.sld;

import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SLD self-upload → LEW 작성 전환(결제 후) 시 알림 디스패치.
 *
 * <ul>
 *   <li>신청자 통보(A-58, SLD_FEE_ADDED_APPLICANT) — "LEW가 SLD를 작성, 추가 요금 $X".</li>
 *   <li>ADMIN 정산 요청(A-59, SLD_FEE_SETTLEMENT_PENDING_ADMIN) — "SLD 보충요금 $X 정산 대기".</li>
 * </ul>
 *
 * <p>전체 try-safe — 알림 실패가 전환 트랜잭션을 롤백시키지 않는다. orchestrator 는 AFTER_COMMIT 단계.
 * (sld-lew-conversion-fee-spec.md §11)</p>
 */
@Slf4j
public final class SldConversionNotifier {

    private static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private SldConversionNotifier() {}

    /** 결제 후 전환 시 신청자 통보 + ADMIN 정산 요청. */
    public static void dispatchPostPayment(ApplicationEventPublisher publisher,
                                           UserRepository userRepository,
                                           Application application,
                                           BigDecimal sldFee) {
        Long applicationSeq = application.getApplicationSeq();
        String feeStr = sldFee == null ? "" : sldFee.toPlainString();
        User applicant = application.getUser();
        String applicantName = safeName(applicant);

        // 1) 신청자 통보 (A-58)
        try {
            if (applicant != null && applicant.getUserSeq() != null) {
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("applicantName", applicantName);
                payload.put("publicCode", String.valueOf(applicationSeq));
                payload.put("sldFee", feeStr);
                payload.put("ctaUrl", "/applications/" + applicationSeq);
                publisher.publishEvent(new NotificationDispatchEvent(
                        "SLD_FEE_ADDED_APPLICANT", applicant.getUserSeq(),
                        REFERENCE_TYPE_APPLICATION, applicationSeq, "A-58", payload));
            }
        } catch (RuntimeException ex) {
            log.warn("SLD fee applicant notification failed: appSeq={}, err={}", applicationSeq, ex.getMessage());
        }

        // 2) ADMIN 정산 요청 (A-59) — 활성 ADMIN/SYSTEM_ADMIN 전원, 1인당 1 이벤트.
        try {
            List<User> admins = userRepository.findByRoleInAndStatus(
                    List.of(UserRole.ADMIN, UserRole.SYSTEM_ADMIN), UserStatus.ACTIVE);
            for (User admin : admins) {
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("applicantName", applicantName);
                payload.put("publicCode", String.valueOf(applicationSeq));
                payload.put("sldFee", feeStr);
                payload.put("ctaUrl", "/admin/applications/" + applicationSeq);
                try {
                    publisher.publishEvent(new NotificationDispatchEvent(
                            "SLD_FEE_SETTLEMENT_PENDING_ADMIN", admin.getUserSeq(),
                            REFERENCE_TYPE_APPLICATION, applicationSeq, "A-59", payload));
                } catch (RuntimeException ex) {
                    log.warn("SLD fee admin notification failed (single): adminSeq={}, err={}",
                            admin.getUserSeq(), ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("SLD fee admin notification failed: appSeq={}, err={}", applicationSeq, ex.getMessage());
        }
    }

    private static String safeName(User user) {
        if (user == null) return "Applicant";
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return name.isEmpty() ? "Applicant" : name;
    }
}
