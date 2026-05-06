package com.bluelight.backend.domain.user;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 동의 감사 로그 (★ Kaki Concierge v1.3, PRD §3.11)
 * <p>
 * PDPA 7년 증적 보존 요건 — soft delete 미적용, 모든 필드 {@code @Column(updatable=false)}로 불변 보장.
 * BaseEntity를 상속하지 않으며 독자적인 createdAt을 {@link #onCreate()}에서 기록한다.
 * <p>
 * 동의 부여(GRANTED)와 철회(WITHDRAWN)를 모두 기록하여 시계열로 조회 가능.
 */
@Entity
@Table(name = "user_consent_logs", indexes = {
    @Index(name = "idx_consent_log_user_type", columnList = "user_seq, consent_type, created_at"),
    @Index(name = "idx_consent_log_created", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_log_seq")
    private Long consentLogSeq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_seq", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 40, updatable = false)
    private ConsentType consentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20, updatable = false)
    private ConsentAction action;

    /**
     * 동의 시점의 약관 버전 (예: TermsVersion.CURRENT 스냅샷).
     * PDPA/SIGNUP 등 버전이 없는 동의는 null 가능.
     */
    @Column(name = "document_version", length = 30, updatable = false)
    private String documentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_context", nullable = false, length = 40, updatable = false)
    private ConsentSourceContext sourceContext;

    /**
     * 동의 요청 IP (IPv6 max 45자)
     */
    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 500, updatable = false)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    @Builder
    public UserConsentLog(User user, ConsentType consentType, ConsentAction action,
                          String documentVersion, ConsentSourceContext sourceContext,
                          String ipAddress, String userAgent) {
        this.user = user;
        this.consentType = consentType;
        this.action = action;
        this.documentVersion = documentVersion;
        this.sourceContext = sourceContext;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }
}
