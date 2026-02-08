package com.bluelight.backend.domain.user;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 사용자 정보 Entity
 * - 건물주(APPLICANT), LEW, 관리자(ADMIN)를 포함
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE users SET deleted_at = NOW() WHERE user_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_seq")
    private Long userSeq;

    /**
     * 로그인 이메일 (Unique)
     */
    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    /**
     * 암호화된 비밀번호
     */
    @Column(name = "password", nullable = false, length = 255)
    private String password;

    /**
     * 사용자 이름
     */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /**
     * 연락처
     */
    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * 역할 구분 (APPLICANT, LEW, ADMIN)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role = UserRole.APPLICANT;

    /**
     * LEW 승인 상태 (LEW만 사용, APPLICANT/ADMIN은 null)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approved_status", length = 20)
    private ApprovalStatus approvedStatus;

    /**
     * PDPA 동의 일시
     */
    @Column(name = "pdpa_consent_at")
    private LocalDateTime pdpaConsentAt;

    @Builder
    public User(String email, String password, String name, String phone,
                UserRole role, ApprovalStatus approvedStatus, LocalDateTime pdpaConsentAt) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.role = role != null ? role : UserRole.APPLICANT;
        this.approvedStatus = approvedStatus;
        this.pdpaConsentAt = pdpaConsentAt;
    }

    /**
     * LEW 승인 여부 확인
     * - LEW가 아닌 역할은 항상 true
     * - LEW는 APPROVED 상태일 때만 true
     */
    public boolean isApproved() {
        if (this.role != UserRole.LEW) return true;
        return this.approvedStatus == ApprovalStatus.APPROVED;
    }

    /**
     * LEW 승인 처리
     */
    public void approve() {
        if (this.role != UserRole.LEW) {
            throw new IllegalStateException("Only LEW users can be approved");
        }
        this.approvedStatus = ApprovalStatus.APPROVED;
    }

    /**
     * LEW 거절 처리
     */
    public void reject() {
        if (this.role != UserRole.LEW) {
            throw new IllegalStateException("Only LEW users can be rejected");
        }
        this.approvedStatus = ApprovalStatus.REJECTED;
    }

    /**
     * 비밀번호 변경
     */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /**
     * 프로필 정보 수정
     */
    public void updateProfile(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    /**
     * 역할 변경 (approvedStatus 연동)
     */
    public void changeRole(UserRole role) {
        this.role = role;
        if (role == UserRole.LEW) {
            this.approvedStatus = ApprovalStatus.PENDING;
        } else {
            this.approvedStatus = null;
        }
    }
}
