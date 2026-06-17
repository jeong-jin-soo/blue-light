# LEW 초대 가입 + PayNow 수집 — 파일 단위 구현 계획

- 작성: developer 에이전트 (2026-06-17)
- 정본 스펙: `doc/Project Analysis/lew-invitation-spec.md` v1.3 (전 결정 확정, 열린 질문 0개)
- 본 문서는 스펙을 **실제 코드에 매핑**한 PR별 구현 계획이다. 구현 코드는 포함하지 않는다.
- 브랜치: **`feature/lew-invitation`** (이미 생성됨). 이 브랜치에 PR을 순차 커밋한다. 한국어 커밋 메시지.
- ⚠️ **배포는 사용자 허락 후에만** (절대 규칙). 개발/운영 서버 자동 배포 금지.
- 모든 경로는 백엔드 기준 `blue-light-backend/src/main/java/com/bluelight/backend/`, 프론트 기준 `blue-light-frontend/src/`.

---

## 0. 사실 검증 결과 (Read로 확인한 실제 시그니처/경로)

스펙 §0 표를 실제 코드와 대조해 **모두 일치함**을 확인했다. 추가로 발견한 정정 사항:

| 항목 | 스펙 표기 | 실제 코드 (검증됨) | 영향 |
|---|---|---|---|
| 프로필 수정 메서드 | `UserService.updateMyProfile` | 실제는 **`UserService.updateProfile(userSeq, request)`** (`UserService.java:61`). 컨트롤러 `UserController.updateMyProfile`가 `userService.updateProfile` 호출 (`UserController.java:45-53`) | PR-6에서 메서드명 정정 |
| 프로필 화면 LEW 필드 위치 | `ProfilePage.tsx:275-298` | 확인됨 — `(profile?.role === 'LEW' || authUser?.role === 'LEW')` 가드 블록 (`ProfilePage.tsx:275`). 등급은 **`<Select>`** 사용(SignupPage는 버튼) | PR-6 PayNow 입력란을 이 블록에 추가 |
| ProfilePage 경로 | 스펙 §4.2 `pages/applicant/ProfilePage.tsx` | 확인됨 — `pages/applicant/ProfilePage.tsx` | — |
| AdminUserResponse 필드 | — | `userSeq/email/firstName/lastName/phone/role/approvedStatus/lewLicenceNo/lewGrade/companyName/uen/designation/correspondenceAddress/correspondencePostalCode/createdAt` + `from(User)` 빌더 (`AdminUserResponse.java:16-52`). **status(PENDING_ACTIVATION) 필드 없음** | AC-1/§3.1 "상태=초대됨" 표시 위해 `status` 노출 필요 — 확인 필요로 PR-1/PR-5에 명시 |
| AccountSetupStatusResponse | — | `getStatus`가 `maskedEmail + expiresAt`만 빌드 (`AccountSetupService.java:48-52`); 프론트 타입은 `accountSetupApi.ts:11` | PR-3에서 `requiresLewDetails` 추가 |
| AccountSetupService.complete 잠금 카운트 | D-9: 입력검증 오류 미카운트 | 확인됨 — 현 `complete`는 어떤 검증 실패에도 `recordFailure` 미호출 (`AccountSetupService.java:67-132`). LEW 분기 추가 시에도 동일 정책 유지 | PR-3 회귀 안전 |
| signup LEW 분기 | AuthService 면허/등급 검증 위치 | 확인됨 — `AuthService.java:112-144` (selectedRole==LEW 블록, `existsByLewLicenceNo` at :131). User 빌더 :155-173 | PR-PN3 PayNow 검증을 이 블록에 추가 |
| 임시 비번 해시 패턴 | `ConciergeService.java:196-198` | 확인됨 — `"!PLACEHOLDER!" + UUID.randomUUID()` → `passwordEncoder.encode(...)`, `status=PENDING_ACTIVATION`, `recordSignupConsent(now, termsVersion, source)` (`ConciergeService.java:195-217`) | PR-1 초대 User 생성에 동일 패턴 |
| resend 패턴 | `ConciergeManagerService.resendSetupEmail` | 확인됨 — PENDING_ACTIVATION 가드(409 NOT_PENDING) + `tokenService.issue` + audit + afterCommit `safeSend` (`ConciergeManagerService.java:280-324`) | PR-1 resend-invite 모방 |
| 이메일 IF | `sendAccountSetupLinkEmail(to, fullName, setupUrl, expiresAtDisplay)` | 확인됨 — `EmailService.java:255` (IF) / `SmtpEmailService.java:1454` / `LogOnlyEmailService.java:258` | PR-2 `sendLewInvitationEmail` 대칭 추가 |
| addColumnIfMissing | `DatabaseMigrationRunner.java:363-372` | 확인됨 — `columnExists` 가드 후 `ALTER TABLE ... ADD COLUMN`. users 컬럼 추가 사례 다수 (:340-343) | PR-PN1 paynow 컬럼 추가 |
| schema.sql CREATE 패턴 | `user_consent_logs` 선례 | 확인됨 — `schema.sql:1048-1062` (FK + INDEX, `ENGINE=InnoDB CHARSET=utf8mb4`) | PR-PN1 `lew_paynow_change_logs` CREATE 모델 |
| User LEW 컬럼 위치 | — | `lew_licence_no` `User.java:107`, `lewGrade` :115, `signupSource` :206. 빌더 :267-298 | PR-PN1 paynow 컬럼/빌더 인자 추가 위치 |
| 등급 옵션 enum 값 | GRADE_7/8/9 | SignupPage 버튼 `SignupPage.tsx:232-235`, ProfilePage Select `ProfilePage.tsx:291-294` | PR-4 셋업 화면은 버튼 패턴 재사용 |
| audit 액션 | `LEW_INVITATION_SENT`/`LEW_PAYNOW_VIEWED` 미존재 | 확인됨 — `AuditAction.java`에 `LEW_APPROVED`(48)/`ACCOUNT_SETUP_TOKEN_ISSUED`(111)/`ACCOUNT_ACTIVATED`(114)만. 신규 2종 미존재 | PR-1/PR-PN2 추가 |
| enum ADMIN_INVITE | 정의됨 | `SignupSource.ADMIN_INVITE`(`SignupSource.java:15`), `ConsentSourceContext.ADMIN_INVITE`(`ConsentSourceContext.java:17`) 둘 다 존재 — 활성화만 | PR-1/PR-3 그대로 사용 |

> **확인 필요 (Read로 단정 못 한 부분)**:
> - **`AuditCategory`에 `ADMIN` 값 존재 여부** — `LEW_PAYNOW_VIEWED` category=ADMIN, `LEW_INVITATION_SENT` category=ADMIN 지정 전 enum 확인. (기존 `changeRole`이 `AuditCategory.ADMIN` 사용 → `AdminUserController.java:82` — 존재 추정, 구현 시 재확인)
> - **`@Auditable` 어노테이션 정확한 속성**(action/category/entityType) — `AdminUserController.java:82,141,173`에서 `@Auditable(action=..., category=..., entityType="User")` 형태 확인됨. reveal 엔드포인트에 동일 적용.
> - **`AdminUserResponse`에 계정 status(PENDING_ACTIVATION/ACTIVE) 필드 노출 여부** — 현재 미노출. "초대됨/활성" 배지(§3.1, PR-5)를 위해 `UserStatus status`를 응답에 추가할지 결정 필요. 대안: `approvedStatus` + `status` 조합으로 프론트가 배지 계산.
> - **`UserResponse`(프로필 GET 응답) DTO 필드 목록** — PayNow 전체값 노출(§6.5) 위치. `UserResponse.from(user)` 위치 확인 필요(PR-6).
> - **프론트 `User` 타입 / `UpdateProfileRequest` 타입 위치**(`src/types`) — PR-6에서 paynow 필드 추가.

---

## 1. 전체 PR 순서 그래프 (의존관계)

스펙 §8 PR 분해와 정합. 두 독립 루트(`PR-1`, `PR-PN1`)에서 시작한다.

```
            ┌─────────────────────────────────────────────────────────┐
   PR-1 ───►│ PR-2 (초대 이메일)                                       │
 (admin     │   └─► PR-3 (셋업 LEW 확장) ───► PR-4 (프론트 셋업 화면)  │
  invite    │                                                          │
  백엔드)   └─► PR-5 (프론트 admin UI: 초대 버튼/모달/재발송/배지)     │
   │                                                                    
   └──────────────► (PR-3 도 PR-PN1 의존)                              

   PR-PN1 ──┬─► PR-3   (셋업에서 PayNow 검증/세팅/이력)
 (PayNow    ├─► PR-6   (프로필 PayNow 관리)
  데이터/   ├─► PR-PN2 (admin reveal + 마스킹 + 열람감사)
  검증/     └─► PR-PN3 (자가가입 PayNow 수집)
  이력)
```

**권장 머지 순서** (스펙 §8): `(PR-1 ∥ PR-PN1)` → `PR-2` → `PR-3` → `(PR-4 ∥ PR-5 ∥ PR-6 ∥ PR-PN2 ∥ PR-PN3)`.

- `PR-1`과 `PR-PN1`은 서로 독립 → 병행 착수 가능.
- `PR-3`은 `PR-1`(토큰 소스/엔드포인트)과 `PR-PN1`(PaynowValidator/changePaynow/LewPaynowChangeLog) **둘 다** 선행 필요.
- `PR-4`는 `PR-3`(서버 응답 `requiresLewDetails` + 셋업 바디) 선행.
- `PR-5`는 `PR-1`(invite/resend 엔드포인트)만 선행. PayNow 마스킹/reveal UI는 `PR-PN2`로 분리.

---

## 2. 공통 설계 결정 (전 PR 적용)

1. **설정 우선 원칙 예외 (D-6)**: 등급 옵션은 `LewGrade` enum 3종(GRADE_7/8/9) 법적 고정값 → 하드코딩 허용. 신규 코드에 `// 설정 우선 원칙 예외: LewGrade는 법적 자격 등급(3종 고정)` 주석 필수. `role_metadata`/`priceApi` 미참조.
2. **PayNow ≠ system_settings PayNow** (스펙 §1.4): per-LEW 개인 데이터로 `users` 테이블 컬럼에 저장. `system_settings` 절대 읽기/쓰기 금지(R-PN4).
3. **PayNow 검증 단일 소스** (R-PN5): 백엔드 `PaynowValidator` + 프론트 `constants/paynow.ts`가 **동일 정규식** 공유. MOBILE `^[89]\d{7}$`(8자리), COMPANY_UEN 10자 UEN 형식.
4. **DTO Request/Response 분리**: `InviteLewRequest`(요청) / `AdminUserResponse`·`PaynowRevealResponse`(응답) 분리.
5. **ENUM → VARCHAR**: `PaynowType`, `LewPaynowChangeLog.SourceContext` 등 신규 enum은 `@Enumerated(EnumType.STRING)` + VARCHAR 컬럼. DB 마이그레이션 불필요(코드 레벨만).
6. **append-only 이력 = BaseEntity 미상속**: `LewPaynowChangeLog`는 `UserConsentLog` 선례대로 BaseEntity·soft delete 미적용, 전 컬럼 `@Column(updatable=false)`, `@PrePersist createdAt`.
7. **멱등 마이그레이션**: 컬럼은 `addColumnIfMissing`, 테이블은 `schema.sql` `CREATE TABLE IF NOT EXISTS` + `DatabaseMigrationRunner` 부팅 동기화. 운영 수동 SQL 불필요.
8. **한국어 커밋 메시지**, `@Auditable` 패턴 준수.

---

## 3. PR별 상세 계획

### PR-1 — 토큰 소스 + admin invite 백엔드

**1. 목표 / 스펙 매핑**: D-1(자동승인 전제 준비), D-2(이메일·이름만), D-4(이메일 중복 정책), D-7(재발송), R-3/R-4/R-5. AC-1, AC-2, AC-3, AC-4, AC-10. (이메일 실제 발송은 PR-2 — 본 PR은 LogOnly로 동작.)

**2. 신규 파일**
- `api/admin/dto/InviteLewRequest.java` — 초대 요청 DTO.
  ```java
  @Getter @NoArgsConstructor
  public class InviteLewRequest {
    @NotBlank @Email private String email;
    @NotBlank @Size(max=50) private String firstName;
    @NotBlank @Size(max=50) private String lastName;
  }
  ```
- `api/admin/AdminLewInviteService.java` — 초대/재발송 비즈니스 로직 (`@Service`, `@Transactional`). `ConciergeService.resolveOrCreateApplicant`(생성) + `ConciergeManagerService.resendSetupEmail`(재발송) 패턴 합성.
  ```java
  // 책임: D-4 이메일 중복 정책 → User 생성(role=LEW, PENDING_ACTIVATION, approvedStatus=PENDING,
  //       signupSource=ADMIN_INVITE, lewLicenceNo=null, lewGrade=null, 임시 비번 해시)
  //       → tokenService.issue(user, LEW_INVITATION, req) → audit LEW_INVITATION_SENT
  //       → afterCommit 이메일(PR-2까지 LogOnly sendAccountSetupLinkEmail 임시 사용)
  AdminUserResponse invite(InviteLewRequest req, Long actorSeq, HttpServletRequest http);
  AdminUserResponse resendInvite(Long userId, Long actorSeq, HttpServletRequest http);
  ```

**3. 수정 파일**
- `domain/user/AccountSetupTokenSource.java` (현재 :11-14, 2개 값) — `LEW_INVITATION` 추가 → 3개 값.
- `domain/audit/AuditAction.java` — `LEW_INVITATION_SENT` 추가 (category 지정은 호출부에서; 기존 `ACCOUNT_SETUP_TOKEN_ISSUED`(:111) 인접).
- `api/admin/AdminUserController.java` (`@PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")` 클래스 레벨 :34 상속) — 2개 매핑 추가:
  - `POST /api/admin/users/invite-lew` → `AdminLewInviteService.invite` 위임, `@Auditable(action=LEW_INVITATION_SENT, category=ADMIN, entityType="User")`, 201 반환.
  - `POST /api/admin/users/{id}/resend-invite` → `resendInvite` 위임, `@Auditable(action=ACCOUNT_SETUP_TOKEN_ISSUED, ...)`, 202 반환.
  - `eventPublisher`/`userRepository`는 이미 주입됨(:37-38). `AdminLewInviteService` 추가 주입.
- (확인 필요) `api/admin/dto/AdminUserResponse.java` — "초대됨/활성" 배지용 `status` 필드 노출 검토. 추가 시 `from(User)`에 `.status(user.getStatus()...)` 한 줄. (PR-5에서도 사용)

**D-4 이메일 중복 정책 구현** (AdminLewInviteService.invite):
- `userRepository.findByEmail(email)` 조회 →
  - 존재 + role==LEW → 409 `EMAIL_ALREADY_LEW`
  - 존재 + status==PENDING_ACTIVATION → 409 `EMAIL_PENDING_ACTIVATION`
  - 존재 + 그 외 role → 409 `EMAIL_EXISTS_USE_CHANGE_ROLE`
  - 없음 → 신규 생성.
- (확인 필요) `findByEmail` 시그니처 — 스펙 §0이 `findByEmail`/`existsByEmail` 존재 확인. `Optional<User>` 반환 가정.

**4. DB 변경**: 없음. enum 2종(`AccountSetupTokenSource.LEW_INVITATION`, `AuditAction.LEW_INVITATION_SENT`)은 VARCHAR 저장 → 마이그레이션 불필요. `SignupSource.ADMIN_INVITE`/`ConsentSourceContext.ADMIN_INVITE` 이미 존재.

**5. 테스트**
- 신규 `AdminLewInviteServiceTest`(또는 `AdminLewInviteControllerTest`):
  - 정상 초대 → User(role=LEW, status=PENDING_ACTIVATION, approvedStatus=PENDING, signupSource=ADMIN_INVITE, lewLicenceNo=null) 생성 + LEW_INVITATION 토큰 1개 + audit (AC-1, AC-3).
  - 비-admin 403 (AC-2) — `@WebMvcTest`/`@SpringBootTest` + security.
  - 이메일 중복 3케이스별 409 (AC-4): 기존 LEW / PENDING_ACTIVATION / 기타 role.
  - resend: PENDING_ACTIVATION만 허용, ACTIVE면 409 NOT_PENDING (AC-10); O-17 기존 토큰 revoke 확인.
- 기존 영향: `AccountSetupTokenSource` enum 값 추가 → 기존 switch/매핑 없으면 무영향. `AccountSetupTokenServiceTest` 회귀 확인.

**6. 로컬 검증**: `./gradlew test` (구현 단계에서 실행, 본 계획 단계는 명령 기재만).

**7. 의존성/DoD**: 선행 없음. DoD = 위 테스트 그린 + invite/resend 엔드포인트가 LogOnly 이메일로 동작 + audit 기록.

**추정 규모**: 신규 2 + 수정 3~4 = **약 5~6 파일**.

---

### PR-2 — 초대 이메일

**1. 목표 / 스펙 매핑**: §3.1 afterCommit 발송, AC-3(발송 실패 swallow). 본문 카피(48h 만료 + 단일 CTA + 반피싱 푸터).

**2. 신규 파일**: 없음 (기존 3개 이메일 클래스에 메서드 추가).

**3. 수정 파일**
- `api/email/EmailService.java` (IF, :255 `sendAccountSetupLinkEmail` 인접) — 신규 IF 메서드:
  ```java
  void sendLewInvitationEmail(String to, String fullName, String setupUrl, String expiresAtDisplay);
  ```
- `api/email/SmtpEmailService.java` (:1454 패턴 대칭) — 구현. 본문: LEW 초대 안내, 48h 만료, 단일 CTA 버튼(`setupUrl` 절대 URL), 반피싱 푸터("본인이 요청하지 않았다면 무시").
- `api/email/LogOnlyEmailService.java` (:258 패턴 대칭) — 로그 출력 구현.
- `api/admin/AdminLewInviteService.java`(PR-1 신규) — afterCommit `safeSend`를 `sendAccountSetupLinkEmail` → `sendLewInvitationEmail`로 교체. `setupUrl = setupBaseUrl + "/setup-account/" + token.getTokenUuid()` (`ConciergeManagerService.java:310` 패턴), `expStr`는 `token.getExpiresAt().atZone(SG_ZONE).format(EXPIRES_FMT)` 패턴.

**4. DB 변경**: 없음.

**5. 테스트**
- `LogOnlyEmailService`로 `sendLewInvitationEmail` 호출/인자(setupUrl, expiresAt) 검증 — 초대 서비스 통합 테스트에서 mock/spy.
- 발송 예외 시 트랜잭션 롤백 없이 swallow 확인(safeSend try/catch, `ConciergeManagerService.java:326-` 패턴 모방) (AC-3).

**6. 로컬 검증**: `./gradlew test`. 로컬 LogOnly 로그에서 setupUrl 육안 확인(§10.3 수동).

**7. 의존성/DoD**: PR-1 선행. DoD = 3개 이메일 구현 추가 + 초대 서비스가 신규 메서드 호출 + 테스트 그린.

**추정 규모**: 수정 **4 파일**.

---

### PR-PN1 — PayNow 데이터모델 + 검증 + 이력

**1. 목표 / 스펙 매핑**: D-PN1/D-PN2(택1 단일쌍), D-PN3(이력), D-PN8(전용 테이블), R-PN1/R-PN2/R-PN5. 스펙 §5.2. (이 PR은 도메인 토대 — 소비는 PR-3/6/PN2/PN3.)

**2. 신규 파일**
- `domain/user/PaynowType.java` — `enum PaynowType { COMPANY_UEN, MOBILE }`.
- `domain/user/PaynowValidator.java` — 공유 검증 유틸 (`@Component` 또는 static util).
  ```java
  // MOBILE: ^[89]\d{7}$ (8자리), COMPANY_UEN: 10자 UEN 일반형식
  // 실패 시 BusinessException 400: PAYNOW_TYPE_REQUIRED / INVALID_PAYNOW_TYPE / INVALID_PAYNOW_VALUE
  void validate(PaynowType type, String value);   // 또는 boolean isValid(type, value)
  ```
- `domain/user/LewPaynowChangeLog.java` — `UserConsentLog`(`UserConsentLog.java:31-99`) 패턴 모방. BaseEntity 미상속, 전 컬럼 `@Column(updatable=false)`, `@ManyToOne(LAZY) User`, `@PrePersist onCreate()`, `@Builder`.
  - 필드(스펙 §5.2 표): `paynowChangeLogSeq`(PK), `user`(@ManyToOne), `oldType`(enum nullable), `oldValue`, `newType`(enum NOT NULL), `newValue`, `changedBy`(Long), `sourceContext`(enum), `ipAddress`, `userAgent`, `createdAt`.
  - `@Table(name="lew_paynow_change_logs", indexes=@Index(columnList="user_seq, created_at"))`.
- `domain/user/LewPaynowChangeLog.SourceContext` (내부 enum 또는 별도 파일) — `ACCOUNT_SETUP / SIGNUP / PROFILE_UPDATE`.
- `domain/user/LewPaynowChangeLogRepository.java` — `JpaRepository<LewPaynowChangeLog, Long>` + `findByUser_UserSeqOrderByCreatedAtDesc(Long)`.

**3. 수정 파일**
- `domain/user/User.java`:
  - 컬럼 2종 추가 (lew_licence_no :107 / lewGrade :115 인접):
    ```java
    @Enumerated(EnumType.STRING) @Column(name="paynow_type", length=20) private PaynowType paynowType;
    @Column(name="paynow_value", length=20) private String paynowValue;
    ```
  - 빌더(:267-298)에 `paynowType`/`paynowValue` 인자 추가 (선택 — 셋업/자가가입은 changePaynow로 세팅하므로 빌더 불요할 수 있음. **확인 필요**: 빌더 vs 도메인 메서드 일관성).
  - 도메인 메서드 `changePaynow(PaynowType type, String value)` 추가 (`changeRoleToLew` :444 패턴 — blank 가드):
    ```java
    public void changePaynow(PaynowType type, String value) {
      if (type == null) throw new IllegalArgumentException("paynowType is required");
      if (value == null || value.isBlank()) throw new IllegalArgumentException("paynowValue is required");
      this.paynowType = type; this.paynowValue = value.trim();
    }
    ```
- `config/DatabaseMigrationRunner.java` (:340-343 users 컬럼 추가 블록 인접):
  ```java
  addColumnIfMissing(conn, "users", "paynow_type",  "ALTER TABLE users ADD COLUMN paynow_type VARCHAR(20) NULL");
  addColumnIfMissing(conn, "users", "paynow_value", "ALTER TABLE users ADD COLUMN paynow_value VARCHAR(20) NULL");
  ```
- `src/main/resources/schema.sql` — `users` CREATE에 `paynow_type VARCHAR(20)`, `paynow_value VARCHAR(20)` 컬럼 추가 + 신규 테이블 CREATE (`user_consent_logs` :1048 패턴):
  ```sql
  CREATE TABLE IF NOT EXISTS lew_paynow_change_logs (
    paynow_change_log_seq  BIGINT       NOT NULL AUTO_INCREMENT,
    user_seq               BIGINT       NOT NULL,
    old_type               VARCHAR(20),
    old_value              VARCHAR(20),
    new_type               VARCHAR(20)  NOT NULL,
    new_value              VARCHAR(20)  NOT NULL,
    changed_by             BIGINT       NOT NULL,
    source_context         VARCHAR(40)  NOT NULL,
    ip_address             VARCHAR(45),
    user_agent             VARCHAR(500),
    created_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (paynow_change_log_seq),
    CONSTRAINT fk_paynow_log_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    INDEX idx_paynow_log_user (user_seq, created_at)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  ```
  > schema.sql 하단 "운영 DB 적용 가이드" 주석 블록에 `users.paynow_*` 컬럼은 `DatabaseMigrationRunner`가 멱등 추가함을 명시(수동 SQL 불필요). 테이블은 부팅 시 `IF NOT EXISTS` 자동 생성.

**4. DB 변경**: users 컬럼 2종(멱등 ALTER, NULL 허용 — 기존 row/비-LEW backfill 안전) + 신규 테이블 1개(IF NOT EXISTS, 안전). **모두 비가역 위험 없음**(추가만, DROP 없음). 운영은 부팅 자동 멱등.

**5. 테스트**
- `PaynowValidatorTest`: MOBILE `^[89]\d{7}$` 경계(7자리/9자리/`7xxxxxxx`(8 시작 아님)/비숫자), COMPANY_UEN 10자/9자/11자/비형식 (AC-13).
- `User.changePaynow` 단위 테스트: 정상 세팅, type null / value blank → IllegalArgumentException.
- 부팅 테스트(schema/data.sql 로드)로 `users.paynow_type/paynow_value` 컬럼 + `lew_paynow_change_logs` 테이블 생성 확인.
- `LewPaynowChangeLog` 영속화: `@PrePersist createdAt` 채워짐, 전 컬럼 불변.

**6. 로컬 검증**: `./gradlew test` (부팅이 schema 검증 = 마이그레이션 자동 확인).

**7. 의존성/DoD**: 선행 없음(독립). DoD = 컬럼/테이블/enum/validator/엔티티/repo + 테스트 그린. 소비처 없어도 컴파일·부팅 통과.

**추정 규모**: 신규 4~5 + 수정 3 = **약 7~8 파일**.

---

### PR-3 — 셋업 완료 LEW 확장

**1. 목표 / 스펙 매핑**: D-1(자동승인 APPROVED), D-5(단일 폼), D-8(PDPA 본인 동의), D-9(검증오류 잠금 미카운트), R-1(중복가드), R-7(컨시어지 회귀). + PayNow 검증/세팅/이력(D-PN3/D-PN7). AC-5~AC-9, AC-11, AC-12, AC-13, AC-14, AC-17. 스펙 §6.3/§6.4.

**2. 신규 파일**: 없음 (DTO 확장 + Service 분기).

**3. 수정 파일**
- `api/auth/dto/AccountSetupStatusResponse.java` — `requiresLewDetails`(boolean) 추가.
- `api/auth/AccountSetupService.java`:
  - `getStatus`(:44-52) — `requiresLewDetails = token.getSource() == LEW_INVITATION` 채움.
  - `complete`(:67-132) — **LEW_INVITATION 분기** 추가 (source가 LEW_INVITATION일 때만; 컨시어지/LOGIN_ACTIVATION 경로는 기존 그대로 → R-7/AC-11):
    1. (기존) 토큰 validate, password==confirm, validatePasswordPolicy.
    2. **신규**: `pdpaConsent != true` → 400 `PDPA_CONSENT_REQUIRED`.
    3. **신규**: `lewLicenceNo` blank → 400 `LEW_LICENCE_NO_REQUIRED`; `lewGrade` blank/invalid → 400 `LEW_GRADE_REQUIRED`/`INVALID_LEW_GRADE` (`EnumParser.parse(LewGrade)`).
    4. **신규**: `existsByLewLicenceNo(trim)` → 409 `DUPLICATE_LEW_LICENCE_NO` (계정 PENDING 유지, **token.markUsed 호출 전이라 토큰 살아있음** → AC-7/AC-17).
    5. **신규(PayNow)**: `paynowType` blank/invalid → 400; `PaynowValidator.validate(type,value)` 실패 → 400 `INVALID_PAYNOW_VALUE`.
    6. (기존) `user.changePassword` + 면허/등급 세팅 + **`user.changePaynow(type,value)`** + `approvedStatus=APPROVED`(**D-1** — 면허/등급은 직접 set, `changeRoleToLew`는 PENDING으로 만들므로 사용 안 함; 또는 신규 도메인 메서드 `activateAsInvitedLew(licenceNo, grade)` 검토).
    7. (기존) `activate()` + `verifyEmail()`.
    8. **신규**: `recordSignupConsent(now, TermsVersion.CURRENT, ADMIN_INVITE)` + `UserConsentLog`(sourceContext=ADMIN_INVITE) (D-8).
    9. **신규**: `LewPaynowChangeLog` 기록(old=null→new, changedBy=self, sourceContext=ACCOUNT_SETUP) (AC-14).
    10. (기존) `token.markUsed` + audit `ACCOUNT_ACTIVATED` + JWT(approved=true).
  - **D-9 보장**: 2~5번 입력 검증 실패 시 `recordFailure` 미호출 (현 코드도 미호출 → 일관). 토큰 자체 무효만 `validate`에서 410.
  - **의존성 주입 추가**: `UserRepository`(existsByLewLicenceNo), `PaynowValidator`, `LewPaynowChangeLogRepository`, consent 기록용 repo/메서드.
- `api/auth/dto/AccountSetupCompleteRequest.java` (:18-26, 현재 password/passwordConfirm만) — 필드 추가:
  ```java
  private String lewLicenceNo;   // LEW_INVITATION 토큰일 때만 Service 검증
  private String lewGrade;
  private Boolean pdpaConsent;
  private String paynowType;
  private String paynowValue;
  ```
  > DTO-level `@NotBlank` 미부여(컨시어지 토큰은 이 필드 불요) — **Service 분기 검증**(스펙 §4.2 "Service 분기 검증" 명시).

**4. DB 변경**: 없음 (PR-PN1에서 완료). enum 소비만.

**5. 테스트**
- `AccountSetupServiceTest` 확장:
  - LEW_INVITATION + 정상 → APPROVED/ACTIVE/emailVerified/paynow 저장/JWT approved=true (AC-6).
  - PDPA 미동의 → 400 (AC-8); 면허/등급 누락·오류 → 400 (AC-9); 중복 면허 → 409, PENDING 유지·토큰 미사용 (AC-7).
  - PayNow: 정상/형식위반(AC-13), 최초 입력 LewPaynowChangeLog 기록(AC-14), 형식오류 시 PENDING 유지·토큰 미사용(AC-17).
  - **컨시어지 토큰 → LEW 필드 무시, 기존 동작 동일** (AC-11) — 회귀 핵심.
  - getStatus: LEW_INVITATION → requiresLewDetails=true, 컨시어지 → false (AC-5).
  - 입력 검증 오류 시 `recordFailure` 미호출 검증(D-9).
- 기존 `AccountSetupServiceTest`(컨시어지 시나리오) 회귀 그린 필수.

**6. 로컬 검증**: `./gradlew test`.

**7. 의존성/DoD**: PR-1 + PR-PN1 선행. DoD = LEW 분기 동작 + 컨시어지 회귀 그린 + AC-5~17 커버.

**추정 규모**: 수정 **3 파일** (+ Service 의존성 주입).

---

### PR-4 — 프론트 셋업 화면

**1. 목표 / 스펙 매핑**: D-5(단일 폼), D-6(등급 버튼 하드코딩 예외), §3.2 step4, AC-5 소비. 프론트 회귀(컨시어지 셋업 미렌더).

**2. 신규 파일**
- `constants/paynow.ts` — 백엔드 `PaynowValidator`와 **동일 규칙** 공유 소스 (R-PN5):
  ```ts
  // 설정 우선 원칙 예외 아님 — 법적/고정 형식 규칙. 백엔드 PaynowValidator와 동일 정규식.
  export const PAYNOW_MOBILE_RE = /^[89]\d{7}$/;
  export const PAYNOW_UEN_RE = /^.{10}$/;  // 10자 UEN (확인 필요: 백엔드와 정확히 일치시킬 것)
  export type PaynowType = 'COMPANY_UEN' | 'MOBILE';
  export function validatePaynow(type: PaynowType, value: string): string | null; // 에러 메시지 or null
  ```

**3. 수정 파일**
- `api/accountSetupApi.ts`:
  - `AccountSetupStatusResponse` 인터페이스(:11)에 `requiresLewDetails?: boolean` 추가.
  - `AccountSetupCompletePayload`에 `lewLicenceNo/lewGrade/pdpaConsent/paynowType/paynowValue` 추가.
- `pages/auth/AccountSetupPage.tsx`:
  - `status.requiresLewDetails`일 때만 조건부 렌더: 면허번호 입력 + 등급 버튼 그룹(`SignupPage.tsx:232-235` 버튼 패턴 재사용, `// 설정 우선 원칙 예외` 주석) + PDPA 동의 체크박스 + PayNow 수단 선택(UEN/Mobile 토글) + 값 입력.
  - 제출 버튼 비활성 조건: 동의 미체크 / 면허·등급 미입력 / PayNow 형식오류(`constants/paynow.ts`).
  - 에러 매핑: 409 `DUPLICATE_LEW_LICENCE_NO` → 면허 인라인, 400 `PDPA_CONSENT_REQUIRED`/`INVALID_PAYNOW_VALUE`/`PAYNOW_TYPE_REQUIRED` → 해당 필드 인라인.
  - 컨시어지(`requiresLewDetails` false/없음) → 기존 비번만 폼 유지(회귀).
  - 성공 시 기존 `roleHomePath(role)` 자동 로그인 흐름(:144) 유지.

**4. DB 변경**: 없음.

**5. 테스트**(프론트)
- `AccountSetupPage`: requiresLewDetails=true → 면허/등급/PDPA/PayNow 렌더 + 제출 비활성 조건 + 409/400 인라인. requiresLewDetails 없음 → 면허/PayNow 미표시(회귀).
- `constants/paynow.ts` 검증이 백엔드 `PaynowValidator`와 동일 판정(동일 입력 케이스).

**6. 로컬 검증**: `npm run build` (tsc -b + vite). (E2E는 §10.3 수동.)

**7. 의존성/DoD**: PR-3 선행. DoD = 셋업 화면 LEW 입력 동작 + 컨시어지 회귀 미렌더 + `npm run build` 성공.

**추정 규모**: 신규 1 + 수정 2 = **약 3 파일**.

---

### PR-5 — 프론트 admin UI (초대)

**1. 목표 / 스펙 매핑**: §3.1 초대 모달, D-7 재발송, R-2(SUSPEND 노출), AC-1 UI. (PayNow 마스킹/reveal은 PR-PN2.)

**2. 신규 파일**: 초대 모달 컴포넌트 (예: `pages/admin/InviteLewModal.tsx` 또는 `AdminUserListPage` 인라인 — 기존 모달 패턴 따름. **확인 필요**: AdminUserListPage 내 모달 구조).

**3. 수정 파일**
- `api/adminUserApi.ts` (:6-31 기존 getUsers/changeUserRole/approveLew/rejectLew) — 추가:
  ```ts
  export const inviteLew = (data: { email; firstName; lastName }) => axiosClient.post('/admin/users/invite-lew', data);
  export const resendInvite = (id: number) => axiosClient.post(`/admin/users/${id}/resend-invite`);
  ```
- `pages/admin/AdminUserListPage.tsx`:
  - "LEW 초대" 버튼 + 모달(email/firstName/lastName 입력) → `inviteLew` → 목록 refetch.
  - 이메일 중복 409 코드별 메시지(EMAIL_ALREADY_LEW/EMAIL_EXISTS_USE_CHANGE_ROLE/EMAIL_PENDING_ACTIVATION) 표시.
  - 상태 배지: status==PENDING_ACTIVATION → "초대됨", ACTIVE+LEW APPROVED → "활성". (확인 필요: AdminUserResponse에 status 노출 여부 — PR-1 결정 따름.)
  - PENDING_ACTIVATION 행에 "재발송" 액션 → `resendInvite`.
  - 정지(suspend) 액션 노출 검토(R-2) — 기존 admin 정지 UI 있으면 재사용, 없으면 별도(범위 확인 필요, 스펙 R-2는 "이미 있으면 재사용").

**4. DB 변경**: 없음.

**5. 테스트**(프론트): 초대 모달 제출 → "초대됨" 배지 + 재발송 액션 노출. (정지는 기존 동작.)

**6. 로컬 검증**: `npm run build`.

**7. 의존성/DoD**: PR-1 선행. DoD = 초대/재발송 UI 동작 + `npm run build` 성공.

**추정 규모**: 신규 0~1 + 수정 2 = **약 2~3 파일**.

---

### PR-PN2 — admin PayNow reveal + 마스킹 + 열람감사 (D-PN5)

**1. 목표 / 스펙 매핑**: D-PN5(접근권한+마스킹+reveal 열람감사), R-PN6/R-PN7. AC-18, AC-19, AC-20. 스펙 §6.6.

**2. 신규 파일**
- `domain/user/PaynowMasker.java` (또는 AdminUserResponse 내부 static) — 끝 4자리만 노출(`****1983`), 4자 이하 경계 처리.
- `api/admin/dto/PaynowRevealResponse.java` — `{ userSeq, paynowType, paynowValue(평문) }`.

**3. 수정 파일**
- `domain/audit/AuditAction.java` — `LEW_PAYNOW_VIEWED` 추가 (category=ADMIN, 호출부 지정).
- `api/admin/dto/AdminUserResponse.java`(:33-52 `from`) — `paynowType` + `paynowValueMasked`(마스킹) 추가. **평문 `paynowValue` 절대 미포함**(AC-18). 비-LEW row는 null.
- `api/admin/AdminUserController.java` — `GET /api/admin/users/{id}/paynow/reveal` 추가:
  - `@Auditable(action=LEW_PAYNOW_VIEWED, category=ADMIN, entityType="User")`, 클래스 `@PreAuthorize` 상속(ADMIN/SYSTEM_ADMIN).
  - 대상 LEW 아님 → 409 `NOT_LEW`; PayNow 미설정 → 409 `PAYNOW_NOT_SET`; 없음 → 404.
  - `PaynowRevealResponse` 전체 평문 반환 (AC-19). 비권한자는 403(클래스 가드, AC-20).
- 프론트 `api/adminUserApi.ts` — `revealPaynow = (id) => axiosClient.get(`/admin/users/${id}/paynow/reveal`)`.
- 프론트 `pages/admin/AdminUserListPage.tsx` — PayNow 마스킹 표시 + '보기' 버튼 → `revealPaynow` → 전체값 인라인 노출(감사 기록됨).
- 프론트 `User` 타입(`src/types`) — `paynowType`/`paynowValueMasked`(목록) 추가. (확인 필요: 타입 위치.)

**4. DB 변경**: 없음 (`LEW_PAYNOW_VIEWED` VARCHAR).

**5. 테스트**
- `PaynowMaskerTest`: 끝 4자리(8자리/10자/4자 이하 경계) (AC-18 보조).
- `AdminUserResponse.from`: 평문 미포함, masked만, 비-LEW null (AC-18).
- reveal 통합: ADMIN/SYSTEM_ADMIN 전체값 + `LEW_PAYNOW_VIEWED` 감사(actor=admin, target=LEW) (AC-19); APPLICANT/다른 LEW 403 (AC-20); 비-LEW/미설정 409.

**6. 로컬 검증**: `./gradlew test` + `npm run build`.

**7. 의존성/DoD**: PR-PN1 선행. DoD = 목록 마스킹 + reveal 평문 + 열람감사 + AC-18~20.

**추정 규모**: 신규 2 + 수정 5 = **약 7 파일**.

---

### PR-6 — 프로필 PayNow 관리

**1. 목표 / 스펙 매핑**: D-PN4(가입 후 본인 변경), D-PN5 ①(본인 전체 노출), D-PN3(이력). AC-15, AC-16, AC-18(본인 전체). 스펙 §6.5.

**2. 신규 파일**: 없음(`constants/paynow.ts`는 PR-4 신규 재사용. PR-4보다 PR-6이 먼저 머지되면 여기서 신규).

**3. 수정 파일**
- `api/user/dto/UpdateProfileRequest.java`(:13-46) — `paynowType`/`paynowValue` 추가(`@Size(max=20)`).
- `api/user/UserService.java`(**`updateProfile` :61** — 스펙의 `updateMyProfile`는 오기, 실제 메서드명):
  - role==LEW 이고 paynow 필드 존재 시에만 처리(비-LEW 무시 → AC-16).
  - `PaynowValidator.validate` → 실패 400.
  - 기존 (type,value) 동일 → no-op(이력 미기록, AC-15 동일값 재저장).
  - 변경됨 → `user.changePaynow(type,value)` + `LewPaynowChangeLog`(old→new, changedBy=self, sourceContext=PROFILE_UPDATE, ip/ua) 기록.
  - 의존성 주입: `PaynowValidator`, `LewPaynowChangeLogRepository`. (`auditLogService` 이미 주입 :86.)
  - **HttpServletRequest 필요**(ip/ua): `updateProfile` 시그니처에 추가하거나 컨트롤러에서 전달 → `UserController.updateMyProfile`(:45-53) 수정 동반. (확인 필요: 시그니처 변경 영향.)
- `api/user/dto/UserResponse.java`(GET /me 응답) — `paynowType`/`paynowValue`(**본인이라 전체값** — D-PN5 ①) 추가. (확인 필요: `UserResponse.from` 위치/필드.)
- 프론트 `api/userApi.ts`(:14-17 updateProfile) — 변경 불필요(바디만 확장). `types`의 `UpdateProfileRequest`/`User`에 paynow 추가.
- 프론트 `pages/applicant/ProfilePage.tsx`(:275-298 LEW 블록) — PayNow 수단 선택(UEN/Mobile) + 값 입력(본인 전체표시) + `constants/paynow.ts` 검증 + 저장 토스트. 비-LEW 미표시.

**4. DB 변경**: 없음 (PR-PN1 완료).

**5. 테스트**
- `UserService.updateProfile`(PayNow): 변경 시 `LewPaynowChangeLog`(PROFILE_UPDATE) 기록(AC-15), 동일값 no-op(이력 미추가), 비-LEW 무시(AC-16).
- 프론트 `ProfilePage`: LEW PayNow 입력/검증/저장, 비-LEW 미표시.

**6. 로컬 검증**: `./gradlew test` + `npm run build`.

**7. 의존성/DoD**: PR-PN1 선행. DoD = 본인 조회/변경 + 이력 + 동일값 no-op + AC-15/16.

**추정 규모**: 수정 **6~7 파일** (백 4 + 프론트 2~3).

---

### PR-PN3 — 자가가입 PayNow 수집 (D-PN6/D-PN7)

**1. 목표 / 스펙 매핑**: D-PN6(모든 LEW 경로), D-PN7(가입 시 필수). AC-21. 스펙 §6.7. (자가가입 승인 흐름은 **기존대로** PENDING→admin /approve — PayNow만 추가.)

**2. 신규 파일**: 없음.

**3. 수정 파일**
- `api/auth/dto/SignupRequest.java`(:20-57, LEW 면허/등급 필드 :49-56 인접) — `paynowType`/`paynowValue`(`@Size(max=20)`) 추가.
- `api/auth/AuthService.java`(role==LEW 분기 :112-144) — 면허/등급 검증 뒤에 PayNow 추가:
  - `paynowType` blank/invalid → 400; `PaynowValidator.validate` 실패 → 400 `INVALID_PAYNOW_VALUE`.
  - User 빌더(:155-173)에 paynow 세팅 또는 save 후 `changePaynow`.
  - save 후 `LewPaynowChangeLog`(old=null→new, changedBy=self, sourceContext=SIGNUP, ip/ua) 기록 — `recordSignupConsent`(:182-183) 인접에 추가. ip/ua 이미 추출됨(:180-181).
  - 의존성 주입: `PaynowValidator`, `LewPaynowChangeLogRepository`.
- 프론트 `pages/auth/SignupPage.tsx`(등급 버튼 :232-235 인접) — role==LEW 선택 시 PayNow 입력란(수단+값, `constants/paynow.ts` 검증). role==APPLICANT 미표시.
- 프론트 `api/authApi`(signup) + `types` — paynow 필드 추가.

**4. DB 변경**: 없음 (PR-PN1 완료).

**5. 테스트**
- `AuthService.signup`(role=LEW): PayNow 누락 400, 정상 저장 + `LewPaynowChangeLog`(SIGNUP) 기록(AC-21). 기존 `AuthServiceSignupDuplicateLicenceTest` 패턴.
- 프론트 `SignupPage`: role=LEW PayNow 필수/형식, role=APPLICANT 미표시.

**6. 로컬 검증**: `./gradlew test` + `npm run build`.

**7. 의존성/DoD**: PR-PN1 선행. DoD = 자가가입 LEW PayNow 필수 + 이력 + 기존 승인 흐름 무변 + AC-21.

**추정 규모**: 수정 **4~5 파일**.

---

## 4. 리스크 / 롤백 주의

| 항목 | 평가 | 비고 |
|---|---|---|
| **컬럼 추가** (`users.paynow_*`) | 안전 (비가역 위험 없음) | NULL 허용 → 기존 row/비-LEW backfill 무영향. `addColumnIfMissing` 멱등. |
| **테이블 추가** (`lew_paynow_change_logs`) | 안전 | `CREATE TABLE IF NOT EXISTS` 부팅 자동. DROP 없음. |
| **enum 추가** (`LEW_INVITATION`/`LEW_PAYNOW_VIEWED`/`PaynowType`/SourceContext) | 안전 | ENUM→VARCHAR 정책 → DB 마이그레이션 불필요. 기존 switch 매핑 영향 없음(신규 값). |
| **운영 마이그레이션** | 부팅 시 자동 멱등 | `DatabaseMigrationRunner`가 컬럼/테이블 동기화 → **수동 SQL 불필요**. schema.sql 주석에 명시. |
| **컨시어지 셋업 회귀** (R-7) | PR-3 핵심 위험 | source 분기로 LEW 입력 미렌더·미검증. AC-11 회귀 테스트 통과 전 배포 금지. |
| **DROP/비가역 변경** | **없음** | 본 작업은 전부 추가형(컬럼/테이블/enum/필드). 롤백 시 코드 revert로 충분, DB는 미사용 컬럼/테이블로 잔존(무해). |
| **AdminUserResponse 평문 노출** | PR-PN2 보안 위험 | 목록 응답에 평문 `paynowValue` 절대 미포함 — masked만. reveal 엔드포인트로만 평문 + 감사. |

**롤백 전략**: 각 PR은 추가형이라 코드 revert만으로 롤백 가능. DB 컬럼/테이블은 잔존해도 무해(다음 부팅에서 `IF NOT EXISTS`/`columnExists`로 재추가 안 함). 배포는 사용자 허락 후에만.

---

## 5. 테스트 / 검증 매트릭스 (요약)

- **백엔드 전체**: `./gradlew test` — 부팅이 schema/data.sql 로드 = 마이그레이션 자동 검증. 신규 테스트(PR별 §5) + 기존 회귀(`AccountSetupServiceTest` 컨시어지, `AuthServiceSignupDuplicateLicenceTest`, `AccountSetupTokenServiceTest`).
- **프론트 전체**: `npm run build` (tsc -b + vite) — 타입/빌드.
- **수동 (배포 전, §10.3)**: admin 초대 → LogOnly setupUrl → 셋업(면허+등급+PayNow) → /lew/dashboard → 배정/검토. 중복면허/PayNow 형식오류 재현. 프로필 PayNow 변경 → `lew_paynow_change_logs` old→new 행 확인.
- ⚠️ 본 계획 단계에서는 `./gradlew`/`npm` **미실행** (읽기·계획만). 명령은 구현 단계 기재용.

---

## 6. 핸드오프 체크리스트 (스펙 §11 대응)

- [ ] PayNow ≠ system_settings PayNow — per-LEW `users` 컬럼, 전역 결제계좌와 혼동 금지(R-PN4).
- [ ] PayNow 검증 규칙 백·프론트 단일 소스(`PaynowValidator` ↔ `constants/paynow.ts`, R-PN5) — 정규식 정확 일치.
- [ ] PayNow 노출: 목록 마스킹 + reveal 열람감사(`LEW_PAYNOW_VIEWED`), 평문 목록 미포함(D-PN5).
- [ ] enum/audit/컬럼/테이블은 `DatabaseMigrationRunner` 멱등 자동(수동 SQL 불필요).
- [ ] 한국어 커밋, DTO Request/Response 분리, `@Auditable` 패턴, append-only 이력 BaseEntity 미상속.
- [ ] R-1/R-2/R-4/R-5 + R-PN1/R-PN2/R-PN6/R-PN7 완화책 포함.
- [ ] 컨시어지 셋업 회귀(AC-11) 통과 후 배포(허락 후).
- [ ] **확인 필요 항목 해소**(§0 하단): AuditCategory.ADMIN, AdminUserResponse.status, UserResponse 필드, 프론트 types 위치, UEN 정규식 백/프론트 일치.
