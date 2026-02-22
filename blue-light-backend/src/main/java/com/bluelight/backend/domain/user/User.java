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
     * 이름 (First Name)
     */
    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    /**
     * 성 (Last Name)
     */
    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

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
     * LEW 면허번호 (LEW만 사용)
     */
    @Column(name = "lew_licence_no", length = 50)
    private String lewLicenceNo;

    /**
     * LEW 등급 (GRADE_7, GRADE_8, GRADE_9 — LEW만 사용)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "lew_grade", length = 20)
    private LewGrade lewGrade;

    /**
     * 회사명 (EMA 라이선스에 인쇄됨)
     */
    @Column(name = "company_name", length = 100)
    private String companyName;

    /**
     * UEN (Unique Entity Number, 싱가포르 사업자등록번호)
     * - 사업체 신청 시 필수, 개인 신청 시 null
     */
    @Column(name = "uen", length = 20)
    private String uen;

    /**
     * 직위 (Director, Manager 등)
     */
    @Column(name = "designation", length = 50)
    private String designation;

    /**
     * 통신 주소 (EMA 통지서 수신 주소, 설치 현장 주소와 별개)
     */
    @Column(name = "correspondence_address", length = 255)
    private String correspondenceAddress;

    /**
     * 통신 주소 우편번호
     */
    @Column(name = "correspondence_postal_code", length = 10)
    private String correspondencePostalCode;

    /**
     * 이메일 인증 여부
     */
    @Column(name = "email_verified")
    private Boolean emailVerified = false;

    /**
     * 이메일 인증 토큰
     */
    @Column(name = "email_verification_token", length = 255)
    private String emailVerificationToken;

    /**
     * PDPA 동의 일시
     */
    @Column(name = "pdpa_consent_at")
    private LocalDateTime pdpaConsentAt;

    /**
     * 프로필 서명 이미지 경로
     */
    @Column(name = "signature_url", length = 255)
    private String signatureUrl;

    @Builder
    public User(String email, String password, String firstName, String lastName, String phone,
                UserRole role, ApprovalStatus approvedStatus, String lewLicenceNo,
                LewGrade lewGrade,
                String companyName, String uen, String designation,
                String correspondenceAddress, String correspondencePostalCode,
                Boolean emailVerified, String emailVerificationToken,
                LocalDateTime pdpaConsentAt) {
        this.email = email;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.role = role != null ? role : UserRole.APPLICANT;
        this.approvedStatus = approvedStatus;
        this.lewLicenceNo = lewLicenceNo;
        this.lewGrade = lewGrade;
        this.companyName = companyName;
        this.uen = uen;
        this.designation = designation;
        this.correspondenceAddress = correspondenceAddress;
        this.correspondencePostalCode = correspondencePostalCode;
        this.emailVerified = emailVerified != null ? emailVerified : false;
        this.emailVerificationToken = emailVerificationToken;
        this.pdpaConsentAt = pdpaConsentAt;
    }

    /**
     * Full name 헬퍼 (firstName + lastName)
     */
    public String getFullName() {
        if (firstName == null && lastName == null) return "";
        if (firstName == null) return lastName;
        if (lastName == null || lastName.isEmpty()) return firstName;
        return firstName + " " + lastName;
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
    public void updateProfile(String firstName, String lastName, String phone) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
    }

    /**
     * 프로필 정보 수정 (LEW 면허번호 포함)
     */
    public void updateProfile(String firstName, String lastName, String phone, String lewLicenceNo) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.lewLicenceNo = lewLicenceNo;
    }

    /**
     * 프로필 정보 수정 (회사 정보 + LEW 등급 포함)
     */
    public void updateProfile(String firstName, String lastName, String phone, String lewLicenceNo,
                              LewGrade lewGrade,
                              String companyName, String uen, String designation,
                              String correspondenceAddress, String correspondencePostalCode) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.lewLicenceNo = lewLicenceNo;
        this.lewGrade = lewGrade;
        this.companyName = companyName;
        this.uen = uen;
        this.designation = designation;
        this.correspondenceAddress = correspondenceAddress;
        this.correspondencePostalCode = correspondencePostalCode;
    }

    /**
     * 역할 변경 (approvedStatus, lewGrade 연동)
     */
    public void changeRole(UserRole role) {
        this.role = role;
        if (role == UserRole.LEW) {
            this.approvedStatus = ApprovalStatus.PENDING;
        } else {
            this.approvedStatus = null;
            this.lewGrade = null;
        }
    }

    /**
     * 해당 LEW가 주어진 kVA를 처리할 수 있는지 확인
     */
    public boolean canHandleKva(int kva) {
        if (this.role != UserRole.LEW) return false;
        if (this.lewGrade == null) return false;
        return this.lewGrade.canHandle(kva);
    }

    /**
     * 이메일 인증 여부 확인
     */
    public boolean isEmailVerified() {
        return Boolean.TRUE.equals(this.emailVerified);
    }

    /**
     * 이메일 인증 완료 처리
     */
    public void verifyEmail() {
        this.emailVerified = true;
        this.emailVerificationToken = null;
    }

    /**
     * 이메일 인증 토큰 설정
     */
    public void setEmailVerificationToken(String token) {
        this.emailVerificationToken = token;
        this.emailVerified = false;
    }

    /**
     * 프로필 서명 등록/업데이트
     */
    public void updateSignatureUrl(String signatureUrl) {
        this.signatureUrl = signatureUrl;
    }

    /**
     * 프로필 서명 삭제
     */
    public void removeSignatureUrl() {
        this.signatureUrl = null;
    }

    /**
     * PDPA 동의 철회
     * - pdpaConsentAt을 null로 설정
     */
    public void withdrawPdpaConsent() {
        this.pdpaConsentAt = null;
    }

    /**
     * PDPA 동의 여부 확인
     */
    public boolean hasPdpaConsent() {
        return this.pdpaConsentAt != null;
    }

    /**
     * PDPA 계정 삭제: 개인정보 익명화 (soft delete + 데이터 마스킹)
     * - 법적 보존 의무가 있는 신청 기록은 유지하되, 개인 식별 정보는 마스킹
     */
    public void anonymize() {
        this.firstName = "Deleted";
        this.lastName = "User";
        this.phone = null;
        this.lewLicenceNo = null;
        this.lewGrade = null;
        this.companyName = null;
        this.uen = null;
        this.designation = null;
        this.correspondenceAddress = null;
        this.correspondencePostalCode = null;
        this.signatureUrl = null;
        this.emailVerificationToken = null;
        this.password = "DELETED";
    }
}
