package com.bluelight.backend.domain.application;

import com.bluelight.backend.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 신청자 동의/선언(declaration) 감사 로그 — append-only.
 *
 * <p>EMA ELISE 제출 흐름에서 신청자가 약관/선언에 동의하는 시점마다 한 건씩
 * 기록된다. 규제 대응(ELISE 약관, PDPA, 전자서명 유효성)을 위해 서명 스냅샷,
 * 문서 버전, IP/User-Agent를 함께 보존한다.</p>
 *
 * <p>불변(append-only) 이므로 soft delete 를 두지 않는다 — 변조 방지를 위해
 * {@link Immutable} 을 적용하고, {@code declared_at} 은 {@link PrePersist} 에서
 * 자동 세팅한다.</p>
 */
@Entity
@Table(name = "application_declaration_logs",
        indexes = {
                @Index(name = "idx_decl_log_application", columnList = "application_seq"),
                @Index(name = "idx_decl_log_user", columnList = "user_seq")
        })
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationDeclarationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "declaration_log_seq")
    private Long declarationLogSeq;

    /** 대상 신청 (FK). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

    /** 동의/선언을 수행한 사용자 (FK). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_seq", nullable = false)
    private User user;

    /** 동의 유형 — 예: ELISE_TERMS, PDPA_CONSENT, E_SIGNATURE 등. */
    @Column(name = "consent_type", nullable = false, length = 60)
    private String consentType;

    /** 동의 대상 문서 버전 (정책/약관/LOA 버전 문자열). */
    @Column(name = "document_version", length = 30)
    private String documentVersion;

    /**
     * 동의 시점의 폼 스냅샷 해시 (SHA-256 hex = 64자).
     * 이후 신청서 값이 변경되더라도 "어느 상태에 대해 동의했는지" 를 재구성할 수 있다.
     */
    @Column(name = "form_snapshot_hash", length = 64)
    private String formSnapshotHash;

    /** 원격 IP (IPv6 포함, 최대 45자). */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** 브라우저 User-Agent (최대 500자). */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** 동의 시각 — @PrePersist 에서 자동 설정, 불변. */
    @Column(name = "declared_at", nullable = false, updatable = false)
    private LocalDateTime declaredAt;

    @Builder
    public ApplicationDeclarationLog(Application application,
                                     User user,
                                     String consentType,
                                     String documentVersion,
                                     String formSnapshotHash,
                                     String ipAddress,
                                     String userAgent,
                                     LocalDateTime declaredAt) {
        this.application = application;
        this.user = user;
        this.consentType = consentType;
        this.documentVersion = documentVersion;
        this.formSnapshotHash = formSnapshotHash;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.declaredAt = declaredAt; // null 이면 @PrePersist에서 now() 로 채움
    }

    @PrePersist
    void onCreate() {
        if (this.declaredAt == null) {
            this.declaredAt = LocalDateTime.now();
        }
    }
}
