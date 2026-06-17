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
 * LEW PayNow 변경 이력 (D-PN3 변경이력 필수, D-PN8 전용 테이블).
 * <p>
 * 정산 민감정보의 변경(old→new)을 시계열로 보존해 감사 추적성을 확보한다.
 * {@link UserConsentLog} 선례를 따라 BaseEntity 를 상속하지 않으며, soft delete 미적용,
 * 모든 필드 {@code @Column(updatable=false)} 로 불변(append-only) 보장.
 * <p>
 * 최초 입력은 {@code oldType/oldValue == null}, 이후 변경은 직전 값을 old 로 기록한다.
 */
@Entity
@Table(name = "lew_paynow_change_logs", indexes = {
    @Index(name = "idx_paynow_log_user", columnList = "user_seq, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LewPaynowChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "paynow_change_log_seq")
    private Long paynowChangeLogSeq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_seq", nullable = false, updatable = false)
    private User user;

    /** 변경 전 유형 (최초 입력 시 null). */
    @Enumerated(EnumType.STRING)
    @Column(name = "old_type", length = 20, updatable = false)
    private PaynowType oldType;

    /** 변경 전 값 (최초 입력 시 null). */
    @Column(name = "old_value", length = 20, updatable = false)
    private String oldValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_type", nullable = false, length = 20, updatable = false)
    private PaynowType newType;

    @Column(name = "new_value", nullable = false, length = 20, updatable = false)
    private String newValue;

    /** 변경을 수행한 사용자 seq (본인 또는 대리 처리자). */
    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_context", nullable = false, length = 40, updatable = false)
    private PaynowChangeSourceContext sourceContext;

    /** 변경 요청 IP (IPv6 max 45자). */
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
    public LewPaynowChangeLog(User user, PaynowType oldType, String oldValue,
                              PaynowType newType, String newValue, Long changedBy,
                              PaynowChangeSourceContext sourceContext,
                              String ipAddress, String userAgent) {
        this.user = user;
        this.oldType = oldType;
        this.oldValue = oldValue;
        this.newType = newType;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.sourceContext = sourceContext;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }
}
