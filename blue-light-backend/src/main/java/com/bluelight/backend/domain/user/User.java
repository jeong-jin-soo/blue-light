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

    // ============================================================
    // ★ Kaki Concierge v1.4/v1.5 확장 컬럼 (Phase 1 PR#1)
    // ============================================================

    /**
     * 계정 활성화 상태 (v1.3 signupCompleted boolean 대체)
     * - 기본값은 ACTIVE (기존 유저 backfill 및 DIRECT_SIGNUP 대응)
     * - 컨시어지 자동 생성 계정은 PENDING_ACTIVATION으로 시작
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * 최초 활성화 시점 (컴플라이언스 증적).
     * - PENDING_ACTIVATION → ACTIVE 전이 시점에 한 번만 기록 ({@link #activate()}가 null 가드)
     * - 도메인 메서드 레벨에서 불변 보장 (activatedAt != null이면 재세팅 금지)
     * - JPA updatable 제약은 걸 수 없음: 엔티티 INSERT 시점엔 null이고 이후 UPDATE로
     *   값을 채우는 플로우이므로 updatable=false를 걸면 DB 반영이 막힘.
     */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /**
     * 첫 로그인 성공 시점 (분석/대시보드용)
     */
    @Column(name = "first_logged_in_at")
    private LocalDateTime firstLoggedInAt;

    /**
     * 가입 경로 (DIRECT_SIGNUP / CONCIERGE_REQUEST / ADMIN_INVITE)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "signup_source", nullable = false, length = 30)
    private SignupSource signupSource = SignupSource.DIRECT_SIGNUP;

    /**
     * 회원가입 명시 동의 시점 (v1.3 5종 동의 중 하나)
     */
    @Column(name = "signup_consent_at")
    private LocalDateTime signupConsentAt;

    /**
     * 동의한 약관 버전 (TermsVersion.CURRENT 스냅샷)
     */
    @Column(name = "terms_version", length = 30)
    private String termsVersion;

    /**
     * 마케팅 수신 동의 여부 (선택 동의)
     */
    @Column(name = "marketing_opt_in", nullable = false)
    private Boolean marketingOptIn = false;

    /**
     * 마케팅 수신 동의 시점
     */
    @Column(name = "marketing_opt_in_at")
    private LocalDateTime marketingOptInAt;

    @Builder
    public User(String email, String password, String firstName, String lastName, String phone,
                UserRole role, ApprovalStatus approvedStatus, String lewLicenceNo,
                LewGrade lewGrade,
                String companyName, String uen, String designation,
                String correspondenceAddress, String correspondencePostalCode,
                Boolean emailVerified, String emailVerificationToken,
                LocalDateTime pdpaConsentAt,
                UserStatus status, SignupSource signupSource,
                LocalDateTime signupConsentAt, String termsVersion,
                Boolean marketingOptIn) {
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
        // ★ Concierge v1.4/v1.5 — 기본값 처리
        this.status = status != null ? status : UserStatus.ACTIVE;
        this.signupSource = signupSource != null ? signupSource : SignupSource.DIRECT_SIGNUP;
        this.signupConsentAt = signupConsentAt;
        this.termsVersion = termsVersion;
        this.marketingOptIn = marketingOptIn != null ? marketingOptIn : false;
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
     * Phase 2 PR#3 (JIT): 회사 정보 3필드만 업데이트.
     * 신청 제출 경로에서 단일 트랜잭션으로 호출된다.
     */
    public void updateCompanyInfo(String companyName, String uen, String designation) {
        this.companyName = companyName;
        this.uen = uen;
        this.designation = designation;
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
     * - email도 익명화: 원본 이메일을 PII로 폐기하고, 동일 이메일 재가입을 허용
     *   (UNIQUE 제약 uk_users_email은 deleted_at을 포함하지 않으므로 원본을 남기면 재가입 시 충돌)
     */
    public void anonymize() {
        this.email = "deleted-" + this.userSeq + "@deleted.licensekaki.sg";
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
        // ★ Concierge v1.3 — PDPA 삭제 시 마케팅 기록도 초기화
        this.marketingOptIn = false;
        this.marketingOptInAt = null;
    }

    // ============================================================
    // ★ Kaki Concierge v1.4/v1.5 도메인 메서드 (Phase 1 PR#1)
    // ============================================================

    /**
     * 첫 로그인 성공 시 호출: PENDING_ACTIVATION → ACTIVE 전이
     * <p>
     * - 멱등성: 이미 ACTIVE면 아무 일도 하지 않고 반환
     * - activatedAt은 updatable=false로 한 번만 기록됨 (이중 가드로 null 체크)
     * - firstLoggedInAt도 함께 기록 (분석용)
     *
     * @throws IllegalStateException PENDING_ACTIVATION/ACTIVE 외 상태에서 호출 시
     */
    public void activate() {
        if (this.status == UserStatus.ACTIVE) {
            return; // 멱등
        }
        if (this.status != UserStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("Cannot activate from status: " + this.status);
        }
        this.status = UserStatus.ACTIVE;
        if (this.activatedAt == null) {
            this.activatedAt = LocalDateTime.now();
        }
        if (this.firstLoggedInAt == null) {
            this.firstLoggedInAt = LocalDateTime.now();
        }
    }

    /**
     * 관리자 정지 (정책 위반, 의심 활동 등)
     * <p>
     * DELETED 상태에서는 호출 불가.
     * reason은 별도 감사 로그(AuditLog)에 기록되며 엔티티 자체에는 저장하지 않는다.
     *
     * @throws IllegalStateException DELETED 계정 정지 시도 시
     */
    public void suspend(String reason) {
        if (this.status == UserStatus.DELETED) {
            throw new IllegalStateException("Cannot suspend deleted user");
        }
        this.status = UserStatus.SUSPENDED;
    }

    /**
     * 관리자 정지 해제: SUSPENDED → ACTIVE
     *
     * @throws IllegalStateException SUSPENDED 외 상태에서 호출 시
     */
    public void unsuspend() {
        if (this.status != UserStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot unsuspend from: " + this.status);
        }
        this.status = UserStatus.ACTIVE;
    }

    /**
     * Soft delete + status=DELETED 원자 세팅 (PRD §3.4b-2)
     * <p>
     * BaseEntity.softDelete()가 deleted_at을 기록하는 것과 일관되도록
     * 여기서는 status=DELETED만 세팅하고, BaseEntity의 softDelete()도 호출한다.
     * 기존 @SQLDelete 동작(@Hibernate가 DELETE 쿼리 가로채 UPDATE로 전환)과는 별개로,
     * 애플리케이션 레벨에서 명시적 삭제 시 두 필드를 원자적으로 업데이트한다.
     */
    @Override
    public void softDelete() {
        this.status = UserStatus.DELETED;
        super.softDelete();
    }

    /**
     * 회원가입 동의 기록 (Concierge 통합 플로우에서 호출)
     *
     * @param at          동의 시점
     * @param termsVersion 동의한 약관 버전 (TermsVersion.CURRENT 스냅샷)
     * @param source      가입 경로 (주로 CONCIERGE_REQUEST)
     */
    public void recordSignupConsent(LocalDateTime at, String termsVersion, SignupSource source) {
        this.signupConsentAt = at;
        this.termsVersion = termsVersion;
        this.signupSource = source;
    }

    /**
     * 마케팅 수신 동의
     */
    public void optInMarketing(LocalDateTime at) {
        this.marketingOptIn = true;
        this.marketingOptInAt = at;
    }

    /**
     * 마케팅 수신 거부 (동의 철회)
     * - marketingOptInAt은 이력 보존 목적으로 그대로 두고 플래그만 내린다.
     */
    public void optOutMarketing() {
        this.marketingOptIn = false;
    }
}
