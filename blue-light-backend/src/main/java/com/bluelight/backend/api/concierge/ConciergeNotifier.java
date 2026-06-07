package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concierge 신청 관련 알림 오케스트레이터 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * <ul>
 *   <li>N1  : 신청자에게 접수 확인 + 계정 설정 링크 (신규 C1 / PENDING C3)</li>
 *   <li>N1-Alt: 신청자에게 접수 확인 + 이미 활성 계정 연결 (C2)</li>
 *   <li>N2  : Admin/Concierge Manager 모두에게 신규 접수 알림 (이메일 + 인앱)</li>
 * </ul>
 * <p>
 * 알림 발송은 반드시 트랜잭션 커밋 이후({@code afterCommit})에 일어나도록 보장한다 —
 * 롤백된 상태가 수신자에게 통보되는 사고 차단 (9584c6c 교훈).
 * 개별 발송 실패는 catch-and-log로 격리하여 비즈니스 트랜잭션 무결성을 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConciergeNotifier {

    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final SystemSettingRepository systemSettingRepository;

    @Value("${concierge.account-setup.base-url}")
    private String setupBaseUrl;

    private static final DateTimeFormatter EXPIRES_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'SGT'");
    private static final ZoneId SG_ZONE = ZoneId.of("Asia/Singapore");

    /**
     * 신청 제출 성공 시 호출 — afterCommit 훅 등록.
     *
     * @param conciergeRequestSeq  저장된 ConciergeRequest의 seq
     * @param applicantEmail       신청자 이메일 (정규화된 소문자)
     * @param applicantFullName    신청자 이름
     * @param publicCode           공개 코드 (C-YYYY-NNNN)
     * @param setupTokenUuid       AccountSetup 토큰 UUID (C2 케이스는 null)
     * @param expiresAt            토큰 만료 시각 (C2는 null)
     * @param resolverCase         C1/C2/C3 중 하나
     */
    public void notifySubmitted(Long conciergeRequestSeq,
                                 String applicantEmail,
                                 String applicantFullName,
                                 String publicCode,
                                 String setupTokenUuid,
                                 LocalDateTime expiresAt,
                                 ConciergeCaseResolver.Case resolverCase) {
        // 신청자 접수 확인 (A-31) — #A 옵션1: 단일 템플릿. C2(기존 활성계정)는 setupUrl/expiresAtDisplay 빈 값.
        User applicant = userRepository.findByEmail(applicantEmail).orElse(null);
        if (applicant != null) {
            String setupUrl = setupTokenUuid != null ? setupBaseUrl + "/setup-account/" + setupTokenUuid : "";
            String expStr = expiresAt != null ? expiresAt.atZone(SG_ZONE).format(EXPIRES_FMT) : "";
            Map<String, String> a31 = new LinkedHashMap<>();
            a31.put("applicantName", applicantFullName);
            a31.put("publicCode", publicCode);
            a31.put("setupUrl", setupUrl);
            a31.put("expiresAtDisplay", expStr);
            a31.put("ctaUrl", !setupUrl.isEmpty() ? setupUrl : "/dashboard");
            eventPublisher.publishEvent(new NotificationDispatchEvent(
                    "CONCIERGE_REQUEST_SUBMITTED", applicant.getUserSeq(),
                    "CONCIERGE_REQUEST", conciergeRequestSeq, "A-31", a31));
        }

        // 스태프 알림 — 매니저(C-01) / 어드민(M-03) 분리. 권한자 전원(ACTIVE) 대상.
        List<User> staff;
        try {
            staff = userRepository.findByRoleInAndStatus(
                    List.of(UserRole.ADMIN, UserRole.CONCIERGE_MANAGER), UserStatus.ACTIVE);
        } catch (Exception e) {
            log.warn("Concierge staff lookup failed (suppressed): err={}", e.getMessage());
            return;
        }
        for (User s : staff) {
            boolean isManager = s.getRole() == UserRole.CONCIERGE_MANAGER;
            Map<String, String> sp = new LinkedHashMap<>();
            sp.put("publicCode", publicCode);
            sp.put("applicantName", applicantFullName);
            sp.put("applicantEmail", applicantEmail);
            sp.put("managerName", s.getFullName());
            sp.put("ctaUrl", isManager ? "/concierge-manager/requests" : "/admin/applications");
            eventPublisher.publishEvent(new NotificationDispatchEvent(
                    "CONCIERGE_REQUEST_SUBMITTED", s.getUserSeq(),
                    "CONCIERGE_REQUEST", conciergeRequestSeq,
                    isManager ? "C-01" : "M-03", sp));
        }
    }

    // ─── Quote 이메일 (Phase 1.5) ─────────────────────────────

    /**
     * 견적 이메일 발송 — 매니저가 통화 후 수수료 + 일정 + 메모를 기록하면 호출된다.
     * afterCommit 훅으로 발송하여 트랜잭션 롤백 시 메일 미발송 보장.
     */
    public void notifyQuoteSent(Long conciergeRequestSeq,
                                 String applicantEmail,
                                 String applicantName,
                                 String publicCode,
                                 BigDecimal quotedAmount,
                                 LocalDateTime callScheduledAt,
                                 String managerNote,
                                 String verificationPhrase) {
        // A-33 (CONCIERGE_QUOTE_SENT) — 오케스트레이터 경로. 채널(E+I)·locale·옵트인은 orchestrator 결정.
        // PayNow 설정은 설정 우선 원칙대로 system_settings 에서 조회.
        User applicant = userRepository.findByEmail(applicantEmail).orElse(null);
        if (applicant == null) return;
        String paynowUen = readSetting("payment_paynow_uen");
        String paynowName = readSetting("payment_paynow_name");

        Map<String, String> p = new LinkedHashMap<>();
        p.put("applicantName", applicantName);
        p.put("publicCode", publicCode);
        p.put("quotedAmount", quotedAmount != null ? quotedAmount.toPlainString() : "");
        p.put("verificationPhrase", verificationPhrase != null ? verificationPhrase : "");
        p.put("paynowUen", paynowUen != null ? paynowUen : "");
        p.put("paynowAccountName", paynowName != null ? paynowName : "");
        p.put("paynowReference", publicCode); // 수취 식별용 — 컨시어지 공개 코드 사용
        if (managerNote != null && !managerNote.isBlank()) {
            p.put("managerNote", managerNote);
        }
        if (callScheduledAt != null) {
            p.put("callScheduledAt",
                    callScheduledAt.atZone(SG_ZONE).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'SGT'")));
        }
        eventPublisher.publishEvent(new NotificationDispatchEvent(
                "CONCIERGE_QUOTE_SENT",
                applicant.getUserSeq(),
                "CONCIERGE_REQUEST", conciergeRequestSeq,
                "A-33", p));
    }

    private String readSetting(String key) {
        try {
            return systemSettingRepository.findById(key)
                .map(s -> s.getSettingValue())
                .orElse(null);
        } catch (Exception e) {
            log.warn("SystemSetting lookup failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

}
