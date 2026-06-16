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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

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
     * 역할 구분 (APPLICANT, LEW, ADMIN) — primary role.
     * <p>
     * ★ Concierge 강화 + 별도 수금 PR-1 (D1=B): 다중 역할은 {@link #roles} 1:N 정규화 테이블에서 관리한다.
     * primary {@code role} 컬럼은 호환성을 위해 유지된다 (기존 코드/조회/UI 가 의존).
     * 추가 역할은 {@link #addRole}/{@link #removeRole} 로 관리되며, primary role 은 항상 {@link #roles}
     * 에 포함된다 ({@link #ensurePrimaryInRoles} 가 보증).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role = UserRole.APPLICANT;

    /**
     * 다중 역할 (★ D1=B 정규화 1:N).
     * <p>
     * {@code user_roles} 테이블에 (user_seq, role) 1:N 으로 저장. primary {@link #role} 은 항상
     * 본 집합에 포함되도록 도메인 메서드가 보증한다.
     * <p>
     * EAGER 로드는 Spring Security 의 {@code GrantedAuthority} 매핑이 lazy 트랜잭션 밖에서도
     * 안전하게 수행되도록 하기 위함이다 (LazyInitializationException 회피).
     * 단, 한 사용자당 row 가 보통 1~3개 수준이라 EAGER 로드 비용이 무시 가능하다.
     */
    @ElementCollection(targetClass = UserRole.class, fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_seq", nullable = false),
        foreignKey = @ForeignKey(name = "fk_user_roles_user")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 40)
    private Set<UserRole> roles = new HashSet<>();

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

    // ============================================================
    // ★ WhatsApp 알림 인프라 (PR-0A, 2026-05-11)
    // ----------------------------------------------------------------
    // phone_e164 가 WhatsApp/SMS 발송 정본 (E.164 정규화). 기존 phone 컬럼은 표시용 원본.
    // 옵트인/옵트아웃은 채널×용도(transactional/marketing) 가 ConsentType 으로 분리되며
    // (PR-0D 별건 + Phase 1), 본 필드는 단순 ON/OFF 토글 + 변경 시각만 보관한다.
    // ============================================================

    /** WhatsApp/SMS 발송 정본 — E.164 정규화(+65...). null 이면 발송 불가. */
    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    /** 전화번호 OTP 검증 완료 여부 — 잘못된 번호로 PII 노출 방지 가드. */
    @Column(name = "phone_verified", nullable = false)
    private Boolean phoneVerified = false;

    @Column(name = "phone_verified_at")
    private LocalDateTime phoneVerifiedAt;

    /** WhatsApp 알림 수신 옵트인 토글. STOP 응답 또는 사용자 설정으로 토글된다. */
    @Column(name = "whatsapp_opt_in", nullable = false)
    private Boolean whatsappOptIn = false;

    @Column(name = "whatsapp_opt_in_at")
    private LocalDateTime whatsappOptInAt;

    @Column(name = "whatsapp_opt_out_at")
    private LocalDateTime whatsappOptOutAt;

    /** 알림 메시지 언어 — ISO 639-1 (en / ko / zh-Hans 등). 기본 en. */
    @Column(name = "preferred_language", nullable = false, length = 10)
    private String preferredLanguage = "en";

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
        // primary role 은 항상 roles 집합에 포함되도록 빌더에서 즉시 동기화.
        this.roles = new HashSet<>();
        this.roles.add(this.role);
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
        // ★ WhatsApp 컬럼은 가입 시점에 받지 않는다 — 옵트인 흐름에서 별도 메서드로 세팅.
        //   Phase 1 SignupPage 가 phone 자체를 제거한 JIT 원칙(2026-04-17)을 존중.
        this.phoneVerified = false;
        this.whatsappOptIn = false;
        this.preferredLanguage = "en";
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
        // 상태 머신 가드(#7): PENDING/REJECTED(번복)에서만 승인. 이미 APPROVED면 거부(중복 승인→중복 알림 방지).
        if (this.approvedStatus == ApprovalStatus.APPROVED) {
            throw new IllegalStateException("LEW is already approved");
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
        // 상태 머신 가드(#7): PENDING에서만 거절. 이미 REJECTED(중복) 또는 APPROVED(권한 회수는 별도 동작)는 거부.
        if (this.approvedStatus != ApprovalStatus.PENDING) {
            throw new IllegalStateException("LEW can only be rejected from PENDING (current=" + this.approvedStatus + ")");
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
     * Primary 역할 변경 (approvedStatus, lewGrade 연동).
     * <p>
     * primary {@link #role} 만 교체하며, 기존 secondary 역할({@link #roles})은 그대로 유지된다.
     * 신규 primary 는 즉시 {@link #roles} 집합에도 포함된다.
     * <p>
     * 본 메서드 호출만으로는 이전 primary role 이 secondary 로 강등되지 않는다 — secondary 로 남길지
     * 여부는 호출자(Admin 화면)가 명시적으로 {@link #removeRole}/{@link #addRole} 로 결정.
     */
    public void changeRole(UserRole role) {
        if (role == null) {
            throw new IllegalArgumentException("primary role must not be null");
        }
        if (role == UserRole.LEW) {
            // LEW 승격은 면허번호·등급이 반드시 함께 등록돼야 하므로 changeRoleToLew 를 사용한다.
            throw new IllegalArgumentException("Use changeRoleToLew(licenceNo, grade) to promote to LEW");
        }
        this.role = role;
        if (this.roles == null) this.roles = new HashSet<>();
        this.roles.add(role);
        // LEW 자격 해제: 승인상태·등급·면허번호 모두 정리
        this.approvedStatus = null;
        this.lewGrade = null;
        this.lewLicenceNo = null;
    }

    /**
     * APPLICANT 등 비-LEW 사용자를 LEW 로 승격하면서 면허번호·등급을 함께 등록한다.
     * <p>
     * 신규 LEW 는 {@link ApprovalStatus#PENDING} 으로 설정되어 ADMIN 승인 후에야 활동 가능하다.
     * 면허번호/등급이 비어 있으면 거부한다(등급 null LEW 가 배정 단계에서 막히는 무결성 구멍 방지).
     */
    public void changeRoleToLew(String lewLicenceNo, LewGrade lewGrade) {
        if (lewLicenceNo == null || lewLicenceNo.isBlank()) {
            throw new IllegalArgumentException("LEW licence number is required");
        }
        if (lewGrade == null) {
            throw new IllegalArgumentException("LEW grade is required");
        }
        this.role = UserRole.LEW;
        if (this.roles == null) this.roles = new HashSet<>();
        this.roles.add(UserRole.LEW);
        this.approvedStatus = ApprovalStatus.PENDING;
        this.lewLicenceNo = lewLicenceNo.trim();
        this.lewGrade = lewGrade;
    }

    // ============================================================
    // ★ 다중 역할 (D1=B 정규화) — Concierge 강화 + 별도 수금 PR-1
    // ============================================================

    /**
     * 사용자가 특정 역할을 보유하고 있는지 확인한다 (primary 또는 secondary).
     * <p>
     * Spring Security {@code @PreAuthorize("hasRole(...)")} 와 직접 동치는 아니지만,
     * 도메인 권한 체크에서 일관된 진입점 역할을 한다. JWT/Authorities 매핑은 별도로
     * {@link #effectiveRoles()} 를 사용한다.
     */
    public boolean hasRole(UserRole role) {
        if (role == null) return false;
        if (this.role == role) return true;
        return this.roles != null && this.roles.contains(role);
    }

    /**
     * 보조 역할 추가 (멱등). primary role 과 동일해도 안전하게 무시된다.
     * <p>
     * 본 메서드는 {@link #role}(primary) 을 변경하지 않는다. primary 변경은
     * {@link #changeRole(UserRole)} 사용.
     */
    public void addRole(UserRole role) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (this.roles == null) this.roles = new HashSet<>();
        this.roles.add(role);
    }

    /**
     * 보조 역할 제거. primary role 제거는 거부한다 — primary 를 바꾸려면
     * {@link #changeRole(UserRole)} 을 사용해야 한다.
     *
     * @throws IllegalStateException primary role 제거 시도 시
     */
    public void removeRole(UserRole role) {
        if (role == null) return;
        if (role == this.role) {
            throw new IllegalStateException(
                "Cannot remove primary role: " + role + ". Use changeRole() to switch primary first.");
        }
        if (this.roles != null) {
            this.roles.remove(role);
        }
    }

    /**
     * 사용자가 보유한 모든 역할의 합집합 (primary + secondary, 불변 EnumSet).
     * <p>
     * Spring Security {@code GrantedAuthority} 매핑 + JWT {@code roles} claim 발급에 사용된다.
     * 반환값은 호출자가 변경할 수 없도록 unmodifiable 로 감싸 노출한다.
     */
    public Set<UserRole> effectiveRoles() {
        EnumSet<UserRole> out = EnumSet.noneOf(UserRole.class);
        if (this.role != null) out.add(this.role);
        if (this.roles != null) out.addAll(this.roles);
        return Collections.unmodifiableSet(out);
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
        // ★ PR-0A — WhatsApp 채널 정보 일괄 초기화 (옵트아웃 시각은 감사용으로 now() 기록)
        this.phoneE164 = null;
        this.phoneVerified = false;
        this.phoneVerifiedAt = null;
        this.whatsappOptIn = false;
        this.whatsappOptInAt = null;
        this.whatsappOptOutAt = LocalDateTime.now();
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

    // ============================================================
    // ★ WhatsApp 알림 인프라 도메인 메서드 (PR-0A)
    // ============================================================

    /**
     * 전화번호 OTP 검증 성공 시 호출. 검증된 E.164 번호를 정본으로 기록한다.
     * <p>호출 측이 E.164 정규화 + 형식 검증을 마친 값을 넘겨야 한다.</p>
     */
    public void verifyPhone(String e164, LocalDateTime at) {
        if (e164 == null || e164.isBlank()) {
            throw new IllegalArgumentException("phoneE164 must not be blank");
        }
        this.phoneE164 = e164;
        this.phoneVerified = true;
        this.phoneVerifiedAt = at;
    }

    /**
     * 전화번호 변경 시 검증 무효화 (재OTP 요구). phoneE164 컬럼은 호출자가 별도 갱신.
     */
    public void clearPhoneVerification() {
        this.phoneVerified = false;
        this.phoneVerifiedAt = null;
        // 옵트인 상태는 그대로 두되, 실제 발송은 isWhatsappReachable() 가드가 차단.
    }

    /**
     * WhatsApp 알림 수신 옵트인.
     * <p>채널×용도(transactional/marketing) 분리는 ConsentType 레이어가 담당하며,
     * 본 메서드는 단순 채널 토글이다. 옵트아웃 이력 시각은 보존만 한다.</p>
     */
    public void optInWhatsapp(LocalDateTime at) {
        this.whatsappOptIn = true;
        this.whatsappOptInAt = at;
    }

    /**
     * WhatsApp 알림 수신 거부 (사용자 설정 또는 STOP 응답 수신).
     */
    public void optOutWhatsapp(LocalDateTime at) {
        this.whatsappOptIn = false;
        this.whatsappOptOutAt = at;
    }

    /**
     * WhatsApp 발송 가능 여부 — 적재 직전 가드.
     * <p>요건: ① E.164 번호 보유 ② OTP 검증 완료 ③ 옵트인 ON ④ 익명화/삭제 상태 아님.</p>
     */
    public boolean isWhatsappReachable() {
        if (this.status == UserStatus.DELETED) return false;
        if (this.phoneE164 == null || this.phoneE164.isBlank()) return false;
        if (!Boolean.TRUE.equals(this.phoneVerified)) return false;
        return Boolean.TRUE.equals(this.whatsappOptIn);
    }

    /**
     * 알림 메시지 언어 변경.
     */
    public void updatePreferredLanguage(String locale) {
        if (locale == null || locale.isBlank()) {
            throw new IllegalArgumentException("locale must not be blank");
        }
        this.preferredLanguage = locale;
    }
}
