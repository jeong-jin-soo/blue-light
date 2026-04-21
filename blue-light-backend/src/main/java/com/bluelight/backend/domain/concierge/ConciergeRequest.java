package com.bluelight.backend.domain.concierge;

import com.bluelight.backend.domain.common.BaseEntity;
import com.bluelight.backend.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 컨시어지 신청 엔티티 (★ Kaki Concierge v1.5, PRD §3.1)
 * <p>
 * Visitor가 랜딩페이지에서 제출한 화이트글러브 대행 서비스 신청.
 * 신청 시점에 {@code submitter*} 스냅샷을 저장하고,
 * 동일 트랜잭션으로 생성된 {@link User}(APPLICANT)를 {@link #applicantUser}로 연결한다.
 * <p>
 * 상태 전이는 반드시 도메인 메서드로만 수행하며, {@link ConciergeRequestStatus#canTransitionTo}
 * 가드를 통과해야 한다.
 */
@Entity
@Table(name = "concierge_requests", indexes = {
    @Index(name = "idx_concierge_status", columnList = "status"),
    @Index(name = "idx_concierge_assigned", columnList = "assigned_manager_seq, status"),
    @Index(name = "idx_concierge_submitter_email", columnList = "submitter_email"),
    @Index(name = "idx_concierge_created", columnList = "created_at"),
    @Index(name = "idx_concierge_applicant_user", columnList = "applicant_user_seq")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE concierge_requests SET deleted_at = NOW(6) WHERE concierge_request_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class ConciergeRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "concierge_request_seq")
    private Long conciergeRequestSeq;

    /**
     * 신청자에게 노출할 공개 식별자 (C-YYYY-NNNN 포맷).
     * 생성 로직은 Service 레이어에서 구현한다 (Stage 2 범위 외).
     */
    @Column(name = "public_code", nullable = false, unique = true, length = 20)
    private String publicCode;

    /**
     * 신청 시점 원본 이름 스냅샷.
     * User 프로필이 수정돼도 신청 접수 시점의 정보는 그대로 보존.
     */
    @Column(name = "submitter_name", nullable = false, length = 100)
    private String submitterName;

    /**
     * 신청 시점 원본 이메일 스냅샷 (unique 제약 없음 — 재신청 허용).
     */
    @Column(name = "submitter_email", nullable = false, length = 100)
    private String submitterEmail;

    @Column(name = "submitter_phone", nullable = false, length = 20)
    private String submitterPhone;

    @Column(name = "memo", length = 2000)
    private String memo;

    /**
     * Concierge 자동 가입 또는 기존 APPLICANT 계정 연결.
     * 신청 폼 제출과 동일 트랜잭션에서 세팅되므로 {@code nullable=false}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_user_seq", nullable = false)
    private User applicantUser;

    /**
     * 담당 Concierge Manager (초기 SUBMITTED는 미배정 → nullable).
     * ASSIGNED 전이 시점에 세팅된다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_manager_seq")
    private User assignedManager;

    /**
     * 대리 생성된 Application의 FK (PR#5에서 연결).
     * 순환 의존 회피 및 Application 도메인 독립성을 위해 Long만 보관.
     */
    @Column(name = "application_seq")
    private Long applicationSeq;

    /**
     * 결제 FK (Phase 2부터 사용).
     */
    @Column(name = "payment_seq")
    private Long paymentSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ConciergeRequestStatus status;

    // ============================================================
    // 동의 스냅샷 (신청 시점에 기록, UserConsentLog에도 병행 기록)
    // updatable=false — 동의 시점은 컴플라이언스 증적으로 불변 보장
    // ============================================================

    @Column(name = "pdpa_consent_at", nullable = false, updatable = false)
    private LocalDateTime pdpaConsentAt;

    @Column(name = "terms_consent_at", nullable = false, updatable = false)
    private LocalDateTime termsConsentAt;

    @Column(name = "signup_consent_at", nullable = false, updatable = false)
    private LocalDateTime signupConsentAt;

    /**
     * 대행 위임 동의 (Concierge 고유 동의 — User 엔티티에는 저장할 곳이 없어 여기에 보관)
     */
    @Column(name = "delegation_consent_at", nullable = false, updatable = false)
    private LocalDateTime delegationConsentAt;

    @Column(name = "marketing_opt_in", nullable = false)
    private Boolean marketingOptIn = false;

    // ============================================================
    // 상태 전이 타임스탬프 (SLA/리포트용)
    // ============================================================

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    /**
     * 첫 연락 시점 — 24h SLA 종료 기준 (CONTACTING 전이 시)
     */
    @Column(name = "first_contact_at")
    private LocalDateTime firstContactAt;

    @Column(name = "application_created_at")
    private LocalDateTime applicationCreatedAt;

    @Column(name = "loa_requested_at")
    private LocalDateTime loaRequestedAt;

    @Column(name = "loa_signed_at")
    private LocalDateTime loaSignedAt;

    @Column(name = "licence_paid_at")
    private LocalDateTime licencePaidAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    // ============================================================
    // Quote Workflow (Phase 1.5) — 통화 후 견적 이메일 발송 플로우
    // ============================================================

    /** 매니저가 통화 후 신청자와 합의한 후속 일정 (미팅 · 방문 등) */
    @Column(name = "call_scheduled_at")
    private LocalDateTime callScheduledAt;

    /** 컨시어지 서비스 수수료 견적 (SGD). 통화 후 매니저가 확정. */
    @Column(name = "quoted_amount", precision = 10, scale = 2)
    private BigDecimal quotedAmount;

    /** 견적 이메일 발송 시점 — 발송 이후에만 non-null */
    @Column(name = "quote_sent_at")
    private LocalDateTime quoteSentAt;

    /**
     * 피싱 방지용 검증 문구 (4단어 랜덤 조합).
     * 신청 생성 시 세팅되며 통화 + 이메일에 병기되어, 사칭 메일과 정상 메일을 구분할 수 있게 함.
     * updatable=false — 신청 건별 고정 값.
     */
    @Column(name = "verification_phrase", length = 60, updatable = false)
    private String verificationPhrase;

    /**
     * 낙관적 락 — 재배정 race 방지 (Stage 3/4 Manager 재배정 API에서 사용)
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Builder
    public ConciergeRequest(String publicCode, String submitterName, String submitterEmail,
                            String submitterPhone, String memo, User applicantUser,
                            LocalDateTime pdpaConsentAt, LocalDateTime termsConsentAt,
                            LocalDateTime signupConsentAt, LocalDateTime delegationConsentAt,
                            Boolean marketingOptIn, String verificationPhrase) {
        this.publicCode = publicCode;
        this.submitterName = submitterName;
        this.submitterEmail = submitterEmail;
        this.submitterPhone = submitterPhone;
        this.memo = memo;
        this.applicantUser = applicantUser;
        this.pdpaConsentAt = pdpaConsentAt;
        this.termsConsentAt = termsConsentAt;
        this.signupConsentAt = signupConsentAt;
        this.delegationConsentAt = delegationConsentAt;
        this.marketingOptIn = marketingOptIn != null ? marketingOptIn : false;
        this.verificationPhrase = verificationPhrase;
        this.status = ConciergeRequestStatus.SUBMITTED;
    }

    // ============================================================
    // 도메인 메서드 (상태 전이 가드 포함)
    // ============================================================

    /**
     * SUBMITTED → ASSIGNED: 담당 Manager 배정
     */
    public void assignManager(User manager) {
        transitionTo(ConciergeRequestStatus.ASSIGNED);
        this.assignedManager = manager;
        this.assignedAt = LocalDateTime.now();
    }

    /**
     * ASSIGNED → CONTACTING: 첫 연락 노트 기록 시점 (24h SLA 종료)
     * firstContactAt은 최초 1회만 세팅 (멱등 재호출 시 보존)
     */
    public void markContacted() {
        transitionTo(ConciergeRequestStatus.CONTACTING);
        if (this.firstContactAt == null) {
            this.firstContactAt = LocalDateTime.now();
        }
    }

    /**
     * CONTACTING → QUOTE_SENT: 통화 완료 + 견적 + 일정을 기록하고 상태 전이.
     * 이메일 발송 자체는 호출자(서비스 레이어)가 afterCommit 훅으로 실행하며,
     * 성공 시 {@link #markQuoteEmailSent()}로 타임스탬프를 기록한다.
     * <p>
     * 멱등성: 같은 값으로 재호출 시 transitionTo(self)가 허용되므로 안전.
     *
     * @param quotedAmount     서비스 수수료 견적 (SGD, 양수)
     * @param callScheduledAt  통화에서 합의한 후속 일정 (nullable)
     */
    public void recordQuote(BigDecimal quotedAmount, LocalDateTime callScheduledAt) {
        if (quotedAmount == null || quotedAmount.signum() <= 0) {
            throw new IllegalArgumentException("quotedAmount must be positive");
        }
        transitionTo(ConciergeRequestStatus.QUOTE_SENT);
        this.quotedAmount = quotedAmount;
        this.callScheduledAt = callScheduledAt;
    }

    /**
     * 견적 이메일 발송 성공 시 호출 — quoteSentAt 타임스탬프만 기록 (상태 전이 없음).
     * 재발송 시에도 최초 발송 시점을 보존하고 싶다면 null 체크를 추가하되,
     * 현 구현은 최신 발송 시점으로 덮어쓴다(감사 로그에서 원본 시점 추적 가능).
     */
    public void markQuoteEmailSent() {
        this.quoteSentAt = LocalDateTime.now();
    }

    /**
     * QUOTE_SENT → APPLICATION_CREATED (또는 CONTACTING → APPLICATION_CREATED, 기존 경로 유지)
     */
    public void linkApplication(Long applicationSeq) {
        transitionTo(ConciergeRequestStatus.APPLICATION_CREATED);
        this.applicationSeq = applicationSeq;
        this.applicationCreatedAt = LocalDateTime.now();
    }

    /**
     * APPLICATION_CREATED → AWAITING_APPLICANT_LOA_SIGN: LOA 서명 요청 시점
     */
    public void requestLoaSign() {
        transitionTo(ConciergeRequestStatus.AWAITING_APPLICANT_LOA_SIGN);
        if (this.loaRequestedAt == null) {
            this.loaRequestedAt = LocalDateTime.now();
        }
    }

    /**
     * AWAITING_APPLICANT_LOA_SIGN → AWAITING_LICENCE_PAYMENT: LOA 서명 완료
     */
    public void markLoaSigned() {
        transitionTo(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
        if (this.loaSignedAt == null) {
            this.loaSignedAt = LocalDateTime.now();
        }
    }

    /**
     * AWAITING_LICENCE_PAYMENT → IN_PROGRESS: 라이선스료 결제 완료
     */
    public void markLicencePaid() {
        transitionTo(ConciergeRequestStatus.IN_PROGRESS);
        if (this.licencePaidAt == null) {
            this.licencePaidAt = LocalDateTime.now();
        }
    }

    /**
     * IN_PROGRESS → COMPLETED: 면허 발급 완료 (Application.COMPLETED 동기화)
     */
    public void markCompleted() {
        transitionTo(ConciergeRequestStatus.COMPLETED);
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 임의 상태(terminal 제외) → CANCELLED: 취소 처리
     *
     * @throws IllegalStateException 이미 terminal(COMPLETED/CANCELLED) 상태에서 호출 시
     */
    public void cancel(String reason) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException("Cannot cancel terminal request: " + this.status);
        }
        transitionTo(ConciergeRequestStatus.CANCELLED);
        this.cancelledAt = LocalDateTime.now();
        this.cancellationReason = reason;
    }

    /**
     * 결제 연결 (Phase 2)
     */
    public void linkPayment(Long paymentSeq) {
        this.paymentSeq = paymentSeq;
    }

    /**
     * 상태 전이 가드 (PRD §5.2 전이표 준수)
     */
    private void transitionTo(ConciergeRequestStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new IllegalStateException(
                "Invalid concierge request transition: " + this.status + " → " + next);
        }
        this.status = next;
    }

    /**
     * 24h SLA 위반 여부 판정.
     * - firstContactAt이 세팅됐으면 연락 완료 → SLA 준수
     * - terminal 상태면 SLA 판정 대상 아님
     * - 접수 24h 경과 후에도 firstContactAt null이면 SLA 위반
     */
    public boolean isSlaBreached() {
        if (this.firstContactAt != null) {
            return false;
        }
        if (this.status.isTerminal()) {
            return false;
        }
        return this.getCreatedAt().plusHours(24).isBefore(LocalDateTime.now());
    }
}
