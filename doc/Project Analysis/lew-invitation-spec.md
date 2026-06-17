# 관리자(Admin) LEW 초대 기능 — 정식 스펙

- 문서 버전: v1.3 (2026-06-17) — v1.2 + 남은 열린 질문 전부 권장안으로 확정(D-4~D-9, D-PN6~D-PN8). 열린 질문 0개.
- 작성: product-manager 에이전트
- 발주자: 제품 오너 (ringo@contigo.im)
- 상태: **전 결정 확정 — 구현 계획 단계 준비 완료** — 본 문서를 developer 핸드오프 정본으로 사용. (D-3: 이번 작업은 스펙 문서까지만, 구현 미착수)
- 관련 코드: `AccountSetupTokenService`, `AccountSetupController/Service`, `AdminUserController`, `ConciergeService`(C1 생성 패턴 정본), `ConciergeManagerService.resendSetupEmail`(재발송 패턴 정본), `UserController/UserService`+`ProfilePage`(PayNow 관리), `UserConsentLog`(이력 로그 선례), `DatabaseMigrationRunner`(멱등 컬럼/테이블 마이그)
- 관련 원칙: `CLAUDE.md §설계 원칙`(설정 우선/JIT)

---

## 요구사항 요약

관리자가 신뢰하는 LEW를 **이메일 초대**로 가입시킨다. admin은 이메일·이름만 입력해 초대를 발송하고, LEW는 메일의 링크를 클릭해 셋업 화면에서 **비밀번호 설정 + PDPA 동의 + 면허번호/등급 입력 + 본인 PayNow 수취정보 입력**을 마치면 즉시 **자동 승인(APPROVED)** 상태의 LEW로 활성화되어 바로 접속·활동한다. 가입 후에는 **본인 ProfilePage에서 PayNow를 조회·변경**할 수 있으며, **변경 시마다 이력(old→new, 변경자, 시각)을 보존**한다. 신규 셋업 인프라는 **컨시어지 계정 셋업 인프라(AccountSetupToken + 공개 셋업 엔드포인트 + 셋업 화면)** 를 LEW 버전으로 확장한다. (PayNow 변경이력 1개 테이블을 제외하면 신규 테이블 없음.)

---

## 0. 코드 근거 현황 (착수 전 확인된 사실)

| 항목 | 현황 | 근거 (file:line) |
|---|---|---|
| 셋업 토큰 | `AccountSetupToken` 엔티티 — 48h TTL(`TOKEN_TTL_HOURS=48`), 5회 실패 잠금(H-3), 유저당 활성 토큰 1개(O-17), IP/UA 로깅 | `AccountSetupTokenService.java:35,45-70,84-97` |
| 토큰 소스 enum | `AccountSetupTokenSource` = `CONCIERGE_ACCOUNT_SETUP`, `LOGIN_ACTIVATION` — **`LEW_INVITATION` 미존재** | `AccountSetupTokenSource.java:11-14` |
| 공개 셋업 GET | `GET /api/public/account-setup/{token}` → maskedEmail + expiresAt | `AccountSetupController.java:35-38`, `AccountSetupService.java:44-52` |
| 공개 셋업 POST | `POST /api/public/account-setup/{token}` → 비번 설정 + `activate()` + `verifyEmail()` + markUsed + 자동 로그인 JWT | `AccountSetupController.java:50-56`, `AccountSetupService.java:67-132` |
| 셋업 POST 바디 | `AccountSetupCompleteRequest` = password + passwordConfirm **만** (면허/등급 없음) | `AccountSetupCompleteRequest.java:18-26` |
| 셋업 화면(프론트) | `AccountSetupPage.tsx` — 비번 강도 표시 + 비번/확인 입력 + 자동 로그인 후 `roleHomePath` 리다이렉트(LEW→`/lew/dashboard`) | `AccountSetupPage.tsx:31-38,126-168` |
| 셋업 라우트 | `/setup-account/:token` 등록됨 | `router/index.tsx:162` |
| 셋업 이메일 | `sendAccountSetupLinkEmail(to, fullName, setupUrl, expiresAtDisplay)` — Smtp/LogOnly 양 구현 | `EmailService.java:255`, `SmtpEmailService.java:1454`, `LogOnlyEmailService.java:258` |
| 셋업 URL 조립 | `setupBaseUrl + "/setup-account/" + token.getTokenUuid()` (afterCommit 발송) | `ConciergeManagerService.java:297-323` |
| User 생성 패턴 | C1: `status=PENDING_ACTIVATION`, 임시 비번 해시(`!PLACEHOLDER!`+UUID), `recordSignupConsent(...)` | `ConciergeService.java:195-225` |
| 등급/승인 도메인 | `User.changeRoleToLew(licenceNo, grade)` → role=LEW + `approvedStatus=PENDING`; `approve()`는 APPROVED 외에서만 호출 가능(#7 가드) | `User.java:332-341,444-457` |
| 면허 중복가드 | `existsByLewLicenceNo`(signup), `existsByLewLicenceNoAndUserSeqNot`(changeRole) | `UserRepository.java:33,38`, `AuthService.java:131`, `AdminUserController.java:121` |
| 등급 옵션(프론트) | SignupPage가 `{GRADE_7 '≤45 kVA', GRADE_8 '≤500 kVA', GRADE_9 '≤400 kV'}` **하드코딩** 버튼 사용 | `SignupPage.tsx:233-242` |
| 등급 도메인(백엔드) | `LewGrade` = GRADE_7(45)/GRADE_8(500)/GRADE_9(9999) | `LewGrade.java:9-13` |
| signupSource enum | `DIRECT_SIGNUP`/`CONCIERGE_REQUEST`/**`ADMIN_INVITE`(Phase 3 예약, 정의됨)** | `SignupSource.java:12-16` |
| consent context enum | `DIRECT_SIGNUP`/`CONCIERGE_REQUEST`/`PROFILE_UPDATE`/**`ADMIN_INVITE`(정의됨)** | `ConsentSourceContext.java:13-18` |
| 프로필 조회/수정 | `GET /api/users/me`, `PUT /api/users/me`(`UserController.updateMyProfile` + `UpdateProfileRequest`). 프론트 `userApi.updateProfile`→`PUT /users/me` | `UserController.java:31-48`, `UpdateProfileRequest.java:13-46`, `userApi.ts:15-18` |
| 프로필 화면 LEW 필드 | `ProfilePage`가 role==LEW일 때 면허번호+등급 입력란을 조건부 렌더(이미 존재). **PayNow 입력란은 없음** | `ProfilePage.tsx:275-298` |
| 컬럼 추가 마이그레이션 | `addColumnIfMissing(conn, table, column, ddl)` — `columnExists` 가드로 멱등 `ALTER TABLE ... ADD COLUMN`. enum 컬럼은 VARCHAR | `DatabaseMigrationRunner.java:352-372` |
| 불변 이력 로그 선례 | `UserConsentLog` — 독립 엔티티(BaseEntity 미상속), soft delete 미적용, 전 컬럼 `@Column(updatable=false)`, `@ManyToOne User` + `@PrePersist createdAt`. PDPA 증적 보존 | `UserConsentLog.java:31-98` |
| 계정 상태 enum | `PENDING_ACTIVATION`/`ACTIVE`/`SUSPENDED`/`DELETED` | `UserStatus.java:15-20` |
| Admin User API | `AdminUserController` `@PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")`, `@Auditable` 패턴, approve/reject 존재 | `AdminUserController.java:34,141-167` |
| Admin User API(프론트) | `adminUserApi.ts` getUsers/changeUserRole/approveLew/rejectLew | `adminUserApi.ts:6-31` |
| Audit | `ACCOUNT_ACTIVATED`, `ACCOUNT_SETUP_TOKEN_ISSUED`, `LEW_APPROVED` 등 존재. **`LEW_INVITATION_SENT` 미존재** | `AuditAction.java:48,111,114` |
| 이메일 존재 확인 | `existsByEmail`, `findByEmail` 존재 | `UserRepository.java:22,27` |

> **핵심 결론**: 신규 테이블 0개. 컨시어지가 만든 `AccountSetupToken` + 공개 셋업 + 셋업 화면 인프라를 그대로 재사용하며, ① `AccountSetupTokenSource.LEW_INVITATION` 추가 ② 셋업 POST 바디에 면허/등급 필드 추가 ③ admin invite 엔드포인트 신설 ④ 셋업 화면에 면허/등급 입력 UI 추가 — 이 4개가 핵심 신규 작업이다.

---

## 1. 배경 · 목표 · 비목표

### 1.1 배경
- 현재 LEW가 플랫폼에 들어오는 경로는 ① 자가가입(`AuthService.signup`, `isLewRegistrationOpen()` 게이트 + admin `/approve`) ② admin이 기존 APPLICANT를 `changeRole`로 LEW 승격(면허/등급 admin 입력) 두 가지다.
- 운영상 admin이 **신뢰하는 LEW를 직접 데려오는** 경로가 없다. 자가가입은 LEW가 먼저 알아서 가입해야 하고, changeRole은 대상이 이미 APPLICANT 계정을 가지고 있어야 한다.
- 컨시어지 계정 셋업 인프라(토큰·공개 셋업·셋업 화면·이메일)가 이미 검증되어 있으므로 이를 LEW 초대로 확장하는 것이 가장 저비용이다.

### 1.2 목표
1. admin/SYSTEM_ADMIN이 **이메일·이름만**으로 LEW 초대를 발송한다.
2. 초대받은 사람이 링크를 통해 **비번 + PDPA 동의 + 면허번호/등급 + 본인 PayNow 수취정보**를 입력하고 셋업을 완료하면 **즉시 APPROVED LEW**로 활성화되어 자동 로그인된다.
3. LEW가 **본인 ProfilePage에서 PayNow를 조회·변경**할 수 있고, 변경할 때마다 **이력(old→new, 변경자, 시각)이 보존**되어 사후 감사 추적이 가능하다.
4. PayNow 변경이력 1개 테이블 외 신규 테이블 없이 기존 인프라를 재사용한다.
5. admin 사용자 목록에서 초대 발송·재발송과 상태(초대됨/활성)를 확인할 수 있다.

### 1.3 비목표 (Out of Scope)
- 디지털 면허 검증(EMA ELISE 등 외부 조회로 면허번호 진위 확인) — 본 스펙은 **사후 검토 + SUSPEND** 완화책만 둔다 (§7).
- 대량 초대(CSV 업로드/벌크) — 1건씩 초대. (확장 후보로만 기재)
- admin이 초대 시 면허/등급을 미리 채워주는 옵션 — **D-2로 명시적으로 제외**.
- APPLICANT/기타 역할 초대 — 본 스펙은 **LEW 초대 전용**. (컨시어지가 APPLICANT 자동생성을 이미 담당)
- 초대 만료 후 자동 정리 배치(만료된 PENDING_ACTIVATION 계정 청소) — 별도 운영 작업.
- PayNow 수취정보를 이용한 **실제 정산/지급 실행**(플랫폼→LEW 송금) — 본 스펙은 **수집·관리·이력**까지만. 지급 워크플로는 별도 트랙.

### 1.4 ⚠️ PayNow 개념 구분 (혼동 절대 금지)

본 스펙의 LEW PayNow는 **LEW가 플랫폼으로부터 정산/대금을 받기 위한 "본인 수취 계좌"** 다. 이는 CLAUDE.md "설정 우선" 원칙에서 다루는 `system_settings` PayNow와 **완전히 다른 대상**이다.

| 구분 | system_settings PayNow (기존) | LEW PayNow (본 스펙 신규) |
|---|---|---|
| 자금 방향 | **신청자 → 플랫폼** (수금) | **플랫폼 → LEW** (지급) |
| 소유 주체 | 플랫폼 단일 계좌 (global) | **per-LEW 개인 데이터** |
| 저장 위치 | `system_settings`(payment_paynow_uen, payment_paynow_name) | **`users` 테이블의 per-row 컬럼**(또는 LEW 프로필) |
| 소비 방식 | 관리자 설정값을 API로 로드(SSOT) | **사용자별 개인 데이터 — system_settings 소비 대상 아님** |
| 사용처 | 컨시어지 견적 이메일 등 결제 안내 | (향후) 플랫폼이 LEW에 지급 시 |

> **결론**: LEW PayNow는 **system_settings에 저장하지 않으며 system_settings를 소비하지도 않는다.** per-LEW 개인 데이터로 `User` 엔티티에 직접 저장한다. "설정 우선" 원칙은 여기에 적용되지 않는다(개인 데이터이지 admin 전역 설정이 아님). 단, **PayNow 자리수/형식 검증 규칙**은 법적·고정 형식이므로 한 곳(공유 상수/검증 유틸)에 정의해 프론트·백엔드가 공유한다(§7-R 참고).

---

## 2. 확정 결정 및 추가 결정 후보

### 2.1 확정 결정 (절대 변경 금지)

| ID | 결정 | 의미 |
|---|---|---|
| **D-1** | **자동 승인** | 초대받은 LEW는 셋업 완료 시점에 `approvedStatus=APPROVED`로 활성화된다. 별도 admin `/approve` 단계 없음. (admin의 초대 행위 자체를 검증으로 간주) |
| **D-2** | **LEW 자가 면허/등급 입력** | admin은 초대 시 면허/등급을 입력하지 **않는다**. 이메일·이름만 입력. 면허번호(`lewLicenceNo`)·등급(`lewGrade`)은 LEW가 **셋업 화면에서 직접 입력**한다. |
| **D-3** | **스펙까지만** | 이번 작업은 본 문서까지. 구현은 별도 발주. (PR 분해는 §8에 포함하되 착수하지 않음) |
| **D-PN1** | **PayNow 본인 수취정보 수집** | LEW가 본인정보(면허/등급) 입력 시 **본인 PayNow 결제정보를 함께 입력**한다. 두 결제수단 중 **택1**: ① Company UEN PayNow(10자) ② Mobile PayNow(8자리 숫자). |
| **D-PN2** | **택1 단일쌍 저장** | 둘을 동시에 보관하지 않는다. `paynowType`(enum: `COMPANY_UEN`/`MOBILE`) + `paynowValue`(VARCHAR) **단일쌍**으로 `users` 테이블에 저장(per-LEW 개인 데이터, §1.4). |
| **D-PN3** | **변경 이력 보존(필수)** | PayNow 값이 바뀔 때마다 **변경 이력(old type/value → new type/value, 변경자, 시각)** 을 남겨 감사 추적이 가능해야 한다. |
| **D-PN4** | **수집·관리 지점** | 가입(셋업) 시 입력 + 가입 후 본인 ProfilePage에서 조회·변경. |
| **D-PN5** | **접근 권한 + 열람 감사**(구 D-PN-Q4) | PayNow 접근은 마스킹 단순문제가 아니라 **접근권한 + 열람감사** 문제다. ⚠️ 초안의 "admin 부분 마스킹" 단순권장은 모순이었다 — **admin/SYSTEM_ADMIN이 곧 지급 주체**라 송금하려면 전체 값이 반드시 필요하다. 확정안: **① 접근권한** — LEW 본인 전체 노출 / ADMIN·SYSTEM_ADMIN 전체 값 접근 가능 / 그 외(신청자·다른 LEW) 접근 불가(PDPA). **② admin 화면 표시** — 기본 마스킹(끝 4자리만, 예 `****1983` / UEN 동일) + '보기(reveal)' 클릭 시 전체 노출 + **열람 감사 기록**(누가·언제·어느 LEW PayNow). reveal는 별도 엔드포인트(`GET /api/admin/users/{id}/paynow/reveal`, `@PreAuthorize hasAnyRole('ADMIN','SYSTEM_ADMIN')`, `@Auditable(LEW_PAYNOW_VIEWED, ADMIN)`)로 분리해 목록 응답엔 마스킹값만 싣는다(과다노출·로그유출 방지). → §6.6 / §7-R 참고. |
| **D-4** | **신규 이메일에만 초대** | admin 입력 이메일이 이미 존재하면 **거부**(케이스별 409: 이미 LEW면 `EMAIL_ALREADY_LEW`, 다른 역할이면 `EMAIL_EXISTS_USE_CHANGE_ROLE`(→ changeRole 안내), PENDING_ACTIVATION이면 `EMAIL_PENDING_ACTIVATION`). 초대는 신규 이메일에만 발급. 기존 계정 LEW 승격은 기존 검증 경로(`changeRole`)로. (이유: D-1 자동승인 + 무분별 LEW 승격의 권한 상승 차단.) |
| **D-5** | **셋업 단일 화면 1-step** | `AccountSetupPage` 단일 폼에 비번 + PDPA + 면허/등급 + PayNow를 모두 받는다(step 분리 안 함). 토큰 1회성이라 부분 제출 상태관리 비용 회피(JIT). |
| **D-6** | **등급 옵션 하드코딩(법적 고정값 예외)** | 등급 옵션은 `LewGrade` enum 3종(GRADE_7/8/9) 고정값이라 "설정 우선 원칙"의 **법적 고정값 예외**로 하드코딩 버튼 재사용(SignupPage 패턴 `SignupPage.tsx:233-242`). kVA 한도 라벨은 `LewGrade.maxKva`/정적 라벨로 통일(가격 tier 비참조). 코드 주석 `// 설정 우선 원칙 예외: LewGrade는 법적 자격 등급(3종 고정)` 필수. `role_metadata` 외부화는 비채택(테이블 없음). |
| **D-7** | **PENDING_ACTIVATION만 재발송** | 초대 계정 재발송 허용 — `ConciergeManagerService.resendSetupEmail` 패턴(`tokenService.issue`가 O-17로 기존 토큰 revoke + 새 토큰). **대상이 PENDING_ACTIVATION일 때만** 가능(ACTIVE면 409 NOT_PENDING). |
| **D-8** | **PDPA 동의는 셋업 시 본인이** | 셋업 화면에서 LEW 본인이 동의 체크 → 셋업 POST에 플래그 → 서버가 `recordSignupConsent(now, TermsVersion.CURRENT, ADMIN_INVITE)` + `UserConsentLog`(sourceContext=`ADMIN_INVITE`) 기록. admin invite 시점엔 동의 미기록. (PDPA 동의는 본인 행위.) |
| **D-9** | **입력 검증 오류는 전용 카운터 10회 후 잠금** *(2026-06-17 갱신: 기존 "미카운트"→10회 제한)* | 비번/토큰 자체 오류는 기존 H-3 **5회** 잠금(`failed_attempts`). 면허/등급/PayNow/PDPA **입력 검증 오류는 별개 카운터(`input_validation_failures`)로 10회까지 허용 후 토큰 잠금** — 오타로 쉽게 잠기지 않되 무제한 시도 방지. 증분은 검증 실패 롤백에도 보존되도록 `recordInputValidationFailure`를 REQUIRES_NEW로 처리. 단 **면허 중복(409)은 충돌이라 미카운트**(토큰 살려 재시도 가능). 구현: `AccountSetupToken.recordInputValidationFailure`, `AccountSetupService.complete`. (스펙 본문 §6.2/E-5/E-11 등 "미카운트" 서술은 이 결정으로 대체.) |
| **D-PN6** (구 D-PN-Q1) | **모든 LEW 생성 경로에서 PayNow 수집** | 초대 셋업 + **자가가입 `SignupPage`(role=LEW)** + 프로필 관리 — 모든 경로에서 수집해 데이터 일관성 확보. 자가가입 경로(`AuthService.signup` + `SignupPage`)에도 PayNow 입력·검증·이력 추가. |
| **D-PN7** (구 D-PN-Q2) | **가입 시 PayNow 필수** | 셋업·자가가입 시 택1(MOBILE/COMPANY_UEN) 중 하나는 **반드시 입력**. 정산정보 없는 LEW의 활동 개시 방지. |
| **D-PN8** (구 D-PN-Q3) | **전용 `LewPaynowChangeLog` 테이블(방식 a)** | 변경이력은 `UserConsentLog` 선례를 따른 전용 엔티티(독립, soft delete 미적용, 전 컬럼 `@Column(updatable=false)`, `@ManyToOne User`, `@PrePersist createdAt`). **대안 b(`AuditAction.LEW_PAYNOW_UPDATED` 범용 AuditLog)는 폐기.** → §5.2 참고. |

> D-1 + D-2의 조합은 "admin이 면허번호를 한 번도 검증하지 않고 LEW 계정이 활성화된다"는 긴장을 낳는다. 이 리스크와 완화책은 §7에서 다룬다. **결정 자체는 뒤집지 않는다.**

### 2.2 결정 현황 — **전 항목 확정 (2026-06-17 사용자 승인, 열린 질문 0개)**

모든 추가 결정 후보(D-4~D-9, D-PN6~D-PN8)가 각 권장안으로 **확정**되어 §2.1로 이동했다. 이 스펙에는 **미결 열린 질문이 없으며**, 본문(동선·데이터·API·PR·수용기준·테스트)은 확정값 기준으로 기술되어 있다. 구현 계획 단계로 진행 가능.

| 구 ID | 확정 ID | 확정값(요약) |
|---|---|---|
| D-4 | D-4 | 신규 이메일에만 초대(중복 409), 기존 계정은 changeRole |
| D-5 | D-5 | 셋업 단일 화면 1-step |
| D-6 | D-6 | 등급 옵션 하드코딩(법적 고정값 예외) |
| D-7 | D-7 | PENDING_ACTIVATION만 재발송 |
| D-8 | D-8 | PDPA 동의는 셋업 시 본인이 |
| D-9 | D-9 | 입력 검증 오류는 토큰 잠금 미카운트 |
| D-PN-Q1 | **D-PN6** | 모든 LEW 생성 경로(셋업+자가가입+프로필)에서 PayNow 수집 |
| D-PN-Q2 | **D-PN7** | 가입 시 PayNow 필수 |
| D-PN-Q3 | **D-PN8** | 전용 `LewPaynowChangeLog` 테이블(방식 b 폐기) |
| D-PN-Q4 | D-PN5 | 접근권한 + 기본 마스킹 + reveal 열람 감사 (v1.2에서 확정) |

---

## 3. 사용자 동선

### 3.1 정상 동선 (Happy Path)

```
[ADMIN]                          [SYSTEM/EMAIL]               [초대받은 LEW]
  │                                                                  
  ├─ 사용자 목록 → "LEW 초대" 버튼                                   
  ├─ 모달: 이메일 + 이름(First/Last) 입력 ──┐                        
  │                                          │ POST /api/admin/users/invite-lew
  │                                          ▼                        
  │                          User 생성(role=LEW, status=PENDING_ACTIVATION,
  │                          approvedStatus=PENDING, signupSource=ADMIN_INVITE,
  │                          lewLicenceNo=null, lewGrade=null, 임시 비번 해시)
  │                          AccountSetupToken.issue(LEW_INVITATION) 발급(48h)
  │                          Audit: LEW_INVITATION_SENT
  │                                          │ afterCommit
  │                                          ├─ sendLewInvitationEmail(...) ──▶ 초대 메일 수신
  │  ◀─ 201 + AdminUserResponse(상태=초대됨)                          │
  │                                                                   ├─ 링크 클릭 /setup-account/{token}
  │                                          GET /api/public/account-setup/{token}
  │                                          ◀── maskedEmail + expiresAt
  │                                                                   ├─ 셋업 폼:
  │                                                                   │   비번 + 비번확인
  │                                                                   │   PDPA 동의 체크
  │                                                                   │   면허번호 입력
  │                                                                   │   등급 버튼(G7/8/9) 선택
  │                                                                   │   PayNow: 수단(UEN/Mobile) 택1 + 값 입력
  │                                          POST /api/public/account-setup/{token}
  │                                          { password, passwordConfirm,
  │                                            lewLicenceNo, lewGrade, pdpaConsent:true,
  │                                            paynowType, paynowValue }
  │                                          ▼
  │                          토큰 검증 → 비번정책 → 면허 중복가드 → PayNow 형식검증
  │                          → user.changePassword / 면허·등급·PayNow 세팅 / approvedStatus=APPROVED
  │                          → activate()(PENDING_ACTIVATION→ACTIVE) / verifyEmail()
  │                          → recordSignupConsent(ADMIN_INVITE) + UserConsentLog
  │                          → token.markUsed / Audit: ACCOUNT_ACTIVATED
  │                          → 자동 로그인 JWT(approved=true)
  │                                          ◀── TokenResponse(role=LEW, approved=true)
  │                                                                   └─ /lew/dashboard 즉시 진입·활동
```

### 3.2 단계별 상세

1. **초대 발송 (admin)**: `AdminUserListPage`에 "LEW 초대" 버튼 → 모달에서 email/firstName/lastName 입력 → `POST /api/admin/users/invite-lew`.
   - 서버는 D-4 정책으로 이메일 중복 검사 → 신규 이메일이면 LEW 계정을 `PENDING_ACTIVATION`로 생성(면허/등급 null, `approvedStatus=PENDING`, `signupSource=ADMIN_INVITE`, 임시 비번 해시 = `!PLACEHOLDER!`+UUID — `ConciergeService.java:196-198` 패턴).
   - `AccountSetupTokenService.issue(user, LEW_INVITATION, httpRequest)`로 토큰 발급.
   - `@Auditable(LEW_INVITATION_SENT)` 또는 명시 audit 기록.
   - afterCommit으로 `sendLewInvitationEmail` 발송(실패는 swallow + 경고 로그 — `ConciergeManagerService.safeSend` 패턴).
2. **이메일 수신 (LEW)**: 본문에 만료 안내(48h) + `setupBaseUrl + /setup-account/{uuid}` 절대 URL(`PASSWORD_RESET_BASE_URL` 기준 환경별).
3. **링크 진입 (LEW)**: `AccountSetupPage`가 `GET /api/public/account-setup/{token}`로 상태 조회 → maskedEmail 표시. (이 GET 응답에 "초대 종류=LEW"임을 알려 면허/등급/PayNow 입력란을 조건부 렌더할 필요 → §6.3 응답 확장)
4. **셋업 완료 (LEW)**: 비번 + PDPA 동의 + 면허번호 + 등급 + PayNow(수단 택1 + 값) 입력 → `POST`. 성공 시 자동 로그인 → `/lew/dashboard`.
5. **가입 후 관리 (LEW)**: 본인 `ProfilePage`에서 PayNow 조회·변경(`PUT /api/users/me`). 값이 바뀌면 서버가 변경 이력을 기록(D-PN3, §6.5).

### 3.3 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E-1 | **토큰 만료(48h 초과)** | `validate`가 410 `TOKEN_EXPIRED`. 셋업 화면이 "만료됨, 새 링크 요청" 안내. admin이 재발송(D-7). |
| E-2 | **재발송** | admin "재발송" → `issue`가 O-17로 기존 토큰 revoke + 새 토큰. 구 링크 진입 시 410 `TOKEN_REVOKED`("최신 메일 확인" 안내, 이미 화면에 구현됨 `AccountSetupPage.tsx:77-80`). |
| E-3 | **이미 가입된 이메일** | invite-lew가 D-4 정책으로 409(`EMAIL_ALREADY_LEW`/`EMAIL_EXISTS_USE_CHANGE_ROLE`/`EMAIL_PENDING_ACTIVATION`). admin 모달이 코드별 메시지 표시. |
| E-4 | **중복 면허번호(셋업 제출 시)** | 셋업 POST에서 `existsByLewLicenceNo(trim)` true → 409 `DUPLICATE_LEW_LICENCE_NO`. 셋업 화면이 "이미 등록된 면허번호" 인라인 에러. **계정은 PENDING_ACTIVATION로 남아 LEW가 정정 후 재제출 가능**(토큰은 markUsed 전이므로 살아있음). |
| E-5 | **토큰 잠금(5회 실패)** | `recordFailure` 누적 5회 → `TOKEN_LOCKED`(410). 화면이 "잠김, 새 링크 요청" 안내(구현됨). admin 재발송 필요. ※ "실패"의 정의는 §6.2 참고(비번 정책/면허 중복은 사용자 정정 가능 오류 — 잠금 카운트에 포함할지 D-9로 확인 필요, 권장: **토큰 자체 유효성 오류만** 카운트, 입력 검증 오류는 미카운트하여 면허 오타로 계정이 잠기는 것 방지). |
| E-6 | **PDPA 미동의 제출** | 서버가 400 `PDPA_CONSENT_REQUIRED`. 화면은 동의 전 제출 버튼 비활성. |
| E-7 | **면허/등급 누락 제출** | 400 `LEW_LICENCE_NO_REQUIRED` / `LEW_GRADE_REQUIRED`(기존 코드 메시지 재사용). |
| E-8 | **잘못된 등급 문자열** | `EnumParser.parse(LewGrade)` 실패 → `INVALID_LEW_GRADE`(400). |
| E-9 | **이미 ACTIVE 계정에 재발송 시도** | D-7로 거부(409, 예: `NOT_PENDING`). |
| E-10 | **셋업 도중 admin이 SUSPEND** | 셋업 POST 시 `user.status` 가드(권장: ACTIVE/PENDING_ACTIVATION 외 상태면 거부). |
| E-11 | **PayNow 형식 오류** | MOBILE이 8자리 숫자(`^[89]\d{7}$`) 아님 또는 COMPANY_UEN이 10자 형식 아님 → 400 `INVALID_PAYNOW_VALUE`(type별 메시지). 셋업/프로필 화면 인라인 에러. **검증 오류는 토큰 잠금 미카운트**(D-9). |
| E-12 | **PayNow type 누락/불일치** | `paynowType` 미지정 또는 enum 외 값 → 400 `PAYNOW_TYPE_REQUIRED`/`INVALID_PAYNOW_TYPE`. type과 value 불일치(예: MOBILE인데 10자) → `INVALID_PAYNOW_VALUE`. |
| E-13 | **프로필에서 PayNow 변경** | 값/타입이 바뀌면 `LewPaynowChangeLog`에 old→new + 변경자(self) + 시각 기록. 동일 값 재저장은 no-op(이력 미기록). |

---

## 4. 재사용 자산 + 신규 구현 목록

### 4.1 재사용 (수정 없이 그대로)
- `AccountSetupToken` 엔티티 + `AccountSetupTokenService.issue/validate/markUsed/recordFailure` — 토큰 라이프사이클 전부.
- 공개 셋업 라우트 `/api/public/account-setup/**`(permitAll, `TokenLogMaskingFilter` 마스킹) — `AccountSetupController.java:20-22`.
- `AccountSetupPage` 골격(verifying/form/submitting/done/invalid 상태기계, 비번 강도, 자동 로그인 + `roleHomePath`).
- `User.changeRoleToLew` / `approve` / `activate` / `verifyEmail` / `recordSignupConsent` 도메인 메서드.
- 면허 중복가드 `existsByLewLicenceNo`(`UserRepository.java:33`).
- `setupBaseUrl + /setup-account/{uuid}` URL 조립 + afterCommit 발송 패턴(`ConciergeManagerService.java:297-323`).
- enum: `SignupSource.ADMIN_INVITE`, `ConsentSourceContext.ADMIN_INVITE`(이미 정의됨 — 활성화만).
- 프로필 조회/수정: `GET /api/users/me`, `PUT /api/users/me`(`UpdateProfileRequest`) — PayNow 필드 추가만(엔드포인트 신설 불필요).
- 컬럼 추가 멱등 마이그레이션: `DatabaseMigrationRunner.addColumnIfMissing`(`DatabaseMigrationRunner.java:363-372`).
- 불변 이력 로그 선례: `UserConsentLog` 패턴(`UserConsentLog.java`) — `LewPaynowChangeLog`가 그대로 모방.

### 4.2 신규/수정 구현

| 영역 | 작업 | 파일 |
|---|---|---|
| Enum | `AccountSetupTokenSource`에 `LEW_INVITATION` 추가 | `domain/user/AccountSetupTokenSource.java` |
| Audit | `AuditAction`에 `LEW_INVITATION_SENT` 추가 | `domain/audit/AuditAction.java` |
| DTO(요청) | `InviteLewRequest`(email, firstName, lastName) 신규 + `@Valid` | `api/admin/dto/InviteLewRequest.java` (신규) |
| DTO(응답) | 초대 결과는 기존 `AdminUserResponse.from(user)` 재사용 (status=PENDING_ACTIVATION 노출) | `api/admin/dto/AdminUserResponse.java` |
| 서비스 | LEW 초대 서비스 로직(이메일 중복 정책 D-4 + User 생성 + 토큰 발급 + audit + afterCommit 메일) | `api/admin/AdminLewInviteService.java` (신규, 또는 AdminUserController에 직접) |
| 컨트롤러 | `POST /api/admin/users/invite-lew` + (D-7) `POST /api/admin/users/{id}/resend-invite` | `api/admin/AdminUserController.java` |
| 셋업 DTO | `AccountSetupCompleteRequest`에 `lewLicenceNo`, `lewGrade`, `pdpaConsent`, **`paynowType`, `paynowValue`** 추가 (LEW 초대 토큰일 때만 필수 — Service 분기 검증) | `api/auth/dto/AccountSetupCompleteRequest.java` |
| 셋업 응답 | `AccountSetupStatusResponse`에 `requiresLewDetails`(boolean) 추가 — 토큰 source가 LEW_INVITATION이면 true (프론트가 면허/등급/PayNow 입력란 조건부 렌더) | `api/auth/dto/AccountSetupStatusResponse.java` |
| 셋업 서비스 | `complete`에 LEW_INVITATION 분기: 면허/등급 검증 + 중복가드 + **PayNow 형식검증** + 면허·등급·**PayNow** 세팅 + `approvedStatus=APPROVED`(D-1) + consent 기록(D-8). 기존 컨시어지 분기 무영향 | `api/auth/AccountSetupService.java` |
| 이메일 IF | `sendLewInvitationEmail(to, fullName, setupUrl, expiresAtDisplay)` 추가 | `api/email/EmailService.java` + `SmtpEmailService` + `LogOnlyEmailService` |
| **PayNow 도메인** | `User`에 `paynowType`(enum `PaynowType`: COMPANY_UEN/MOBILE) + `paynowValue`(VARCHAR) 컬럼 + `changePaynow(type, value)` 도메인 메서드(기존 `changeRoleToLew` 패턴) | `domain/user/User.java`, `domain/user/PaynowType.java`(신규) |
| **PayNow 검증** | 공유 검증 유틸 — MOBILE `^[89]\d{7}$`(8자리), COMPANY_UEN 10자 UEN 일반형식. 백엔드 단일 정의 + 프론트 동일 규칙 공유 | `domain/user/PaynowValidator.java`(신규) + 프론트 `constants/paynow.ts`(신규) |
| **PayNow 이력(D-PN8)** | `LewPaynowChangeLog` 엔티티(`UserConsentLog` 패턴) + Repository + 변경 시 기록 서비스 로직 | `domain/user/LewPaynowChangeLog.java`(신규), `LewPaynowChangeLogRepository.java`(신규) |
| **PayNow 컬럼 마이그** | `addColumnIfMissing(users, paynow_type, ...)` + `addColumnIfMissing(users, paynow_value, ...)` + `lew_paynow_change_logs` CREATE TABLE IF NOT EXISTS(schema.sql + sync) | `config/DatabaseMigrationRunner.java`, `schema.sql` |
| **프로필 DTO/서비스** | `UpdateProfileRequest`에 `paynowType`/`paynowValue` 추가 + `UserService.updateMyProfile`에서 변경 감지 시 이력 기록(LEW만) | `api/user/dto/UpdateProfileRequest.java`, `api/user/UserService.java` |
| **PayNow 마스킹 유틸(D-PN5)** | 끝 4자리만 노출(`****1983`) 마스킹 헬퍼 — `AdminUserResponse.from(user)`가 평문 대신 `paynowValueMasked`만 싣는다 | `domain/user/PaynowMasker.java`(신규) 또는 `AdminUserResponse` 내부 |
| **PayNow reveal 엔드포인트(D-PN5)** | `GET /api/admin/users/{id}/paynow/reveal` — 전체 평문 반환 + `@Auditable(LEW_PAYNOW_VIEWED, ADMIN)` 열람 감사. 클래스 `@PreAuthorize hasAnyRole('ADMIN','SYSTEM_ADMIN')` 상속 | `api/admin/AdminUserController.java`, `api/admin/dto/PaynowRevealResponse.java`(신규) |
| **Audit 액션(D-PN5)** | `AuditAction.LEW_PAYNOW_VIEWED` 추가(category=ADMIN). (D-PN8로 변경이력은 전용 테이블 → `LEW_PAYNOW_UPDATED` audit 액션은 불필요) | `domain/audit/AuditAction.java` |
| 프론트 셋업 | `AccountSetupPage`에 면허번호 입력 + 등급 버튼 그룹 + PDPA 동의 + **PayNow 수단 선택(UEN/Mobile) + 값 입력** 조건부 렌더(`requiresLewDetails`) | `pages/auth/AccountSetupPage.tsx`, `api/accountSetupApi.ts` |
| 프론트 프로필 | `ProfilePage` LEW 섹션(이미 면허/등급 렌더)에 **PayNow 수단 선택 + 값 입력**(본인이라 전체 표시) 추가 + 검증 | `pages/applicant/ProfilePage.tsx`, `api/userApi.ts`, `types` |
| **자가가입 PayNow(D-PN6)** | 백엔드: `SignupRequest`에 `paynowType`/`paynowValue` 추가 + `AuthService.signup`의 role=LEW 분기에 PayNow 필수검증(`PaynowValidator`)·세팅·최초 이력 기록(sourceContext=SIGNUP). 프론트: `SignupPage`(role=LEW 선택 시) PayNow 입력란(공유 검증) | `api/auth/dto/SignupRequest.java`, `api/auth/AuthService.java`, `pages/auth/SignupPage.tsx` |
| 프론트 admin | "LEW 초대" 버튼 + 모달(이메일/이름) + 재발송 액션 + 상태 배지(초대됨/활성) + **PayNow 마스킹 표시 + '보기(reveal)' 버튼**(클릭→reveal API→전체값, 감사기록)(D-PN5) | `pages/admin/AdminUserListPage.tsx`, `api/adminUserApi.ts` |

---

## 5. 데이터 / 스키마 영향

### 5.1 초대 흐름 (PayNow 제외)
- **신규 테이블: 없음.** `users`, `account_setup_token`, `user_consent_logs` 모두 기존 테이블 재사용.
- **신규 컬럼: 없음.** 초대 LEW가 사용하는 모든 컬럼(`role`, `status`, `approved_status`, `lew_licence_no`, `lew_grade`, `signup_source`, `signup_consent_at`, `terms_version`)이 이미 존재(`User.java`).
- **Enum 값 추가(코드 레벨, DB는 VARCHAR라 마이그레이션 불필요 — CLAUDE.md Key Decisions "DB ENUM → VARCHAR")**:
  - `AccountSetupTokenSource.LEW_INVITATION`
  - `AuditAction.LEW_INVITATION_SENT`
  - (이미 존재: `SignupSource.ADMIN_INVITE`, `ConsentSourceContext.ADMIN_INVITE`)
- **DB 마이그레이션 SQL: 불필요.** enum이 VARCHAR로 저장되므로 `ALTER TABLE` 없음.

### 5.2 PayNow 데이터 모델 (신규)

**(1) `users` 컬럼 2종 추가** — per-LEW 개인 데이터(§1.4). `User.java` 기존 컬럼 추가 패턴(예: `lew_licence_no`, `status`) 그대로.

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `paynow_type` | VARCHAR(20) NULL | `PaynowType` enum: `COMPANY_UEN` / `MOBILE` (ENUM→VARCHAR 정책) |
| `paynow_value` | VARCHAR(20) NULL | 택1 단일값. MOBILE=8자리, COMPANY_UEN=10자 (D-PN2) |

- 마이그레이션: `DatabaseMigrationRunner.addColumnIfMissing(conn, "users", "paynow_type", "ALTER TABLE users ADD COLUMN paynow_type VARCHAR(20) NULL")` + 동일 패턴 `paynow_value`. (멱등, `columnExists` 가드)
- nullable: 가입 시 필수(D-PN7 확정)라도 기존 LEW backfill·비-LEW row 때문에 컬럼은 NULL 허용. 필수 강제는 **애플리케이션 검증**으로(LEW 셋업/자가가입/프로필 시점).

**(2) `lew_paynow_change_logs` 테이블 신규** — `UserConsentLog` 패턴(D-PN8 확정). 불변·soft delete 미적용, 전 컬럼 `@Column(updatable=false)`, `@PrePersist createdAt`.

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `paynow_change_log_seq` | BIGINT PK AI | |
| `user_seq` | BIGINT NOT NULL (FK→users, updatable=false) | 대상 LEW |
| `old_type` | VARCHAR(20) NULL | 변경 전 type (최초 입력 시 null) |
| `old_value` | VARCHAR(20) NULL | 변경 전 value |
| `new_type` | VARCHAR(20) NOT NULL | 변경 후 type |
| `new_value` | VARCHAR(20) NOT NULL | 변경 후 value |
| `changed_by` | BIGINT NOT NULL | 변경 수행자 userSeq(보통 본인; admin 대리 변경 대비) |
| `source_context` | VARCHAR(40) NOT NULL | `ACCOUNT_SETUP` / `SIGNUP`(자가가입, D-PN6) / `PROFILE_UPDATE` (신규 enum) |
| `ip_address` | VARCHAR(45) NULL | |
| `user_agent` | VARCHAR(500) NULL | |
| `created_at` | DATETIME(6) NOT NULL | `@PrePersist` |

- 마이그레이션: `schema.sql`에 `CREATE TABLE IF NOT EXISTS lew_paynow_change_logs (...)` 추가 → `DatabaseMigrationRunner.syncCreateTablesFromSchemaSql`가 부팅 시 멱등 생성(수동 SQL 불필요).
- 인덱스: `(user_seq, created_at)` — 사용자별 시계열 조회.
- **BaseEntity 미상속**: `UserConsentLog`와 동일하게 audit 4필드(updatedBy 등)·soft delete 불필요한 append-only 로그이므로 독립 엔티티로 둔다.

### 5.3 enum/audit 추가 (DB 마이그레이션 불필요 — VARCHAR)
- `PaynowType`(COMPANY_UEN/MOBILE) — 신규 enum.
- `LewPaynowChangeLog.SourceContext`(ACCOUNT_SETUP / SIGNUP / PROFILE_UPDATE) — 신규 enum. (D-PN6로 자가가입 경로 SIGNUP 추가)
- **`AuditAction.LEW_PAYNOW_VIEWED`(category=ADMIN)** — admin reveal 열람 감사(D-PN5, 확정).
- (D-PN8로 변경이력은 전용 `LewPaynowChangeLog` 테이블이 정본 → `LEW_PAYNOW_UPDATED` audit 액션은 **불필요**.)

---

## 6. API 명세

### 6.1 ADMIN — LEW 초대 발송

```
POST /api/admin/users/invite-lew
권한: hasAnyRole('ADMIN','SYSTEM_ADMIN')  (@PreAuthorize 클래스 레벨 상속)
Audit: LEW_INVITATION_SENT (category=ADMIN, entityType=User)

Request (InviteLewRequest):
{
  "email": "lew@example.com",        // @NotBlank @Email
  "firstName": "Tan",                // @NotBlank
  "lastName": "Ah Kow"               // @NotBlank
}

Response 201 (AdminUserResponse): 생성된 LEW 계정 (status=PENDING_ACTIVATION, approvedStatus=PENDING, lewLicenceNo=null)

Errors:
  409 EMAIL_ALREADY_LEW                 // 이미 LEW 계정
  409 EMAIL_EXISTS_USE_CHANGE_ROLE      // 다른 역할 기존 계정 (changeRole 안내)
  409 EMAIL_PENDING_ACTIVATION          // 컨시어지 미활성 계정 — 별도 처리
  400 INVALID_EMAIL / VALIDATION        // 형식 오류
```

> D-4 확정: 위 3개 케이스별 409 코드를 사용한다(단일 `EMAIL_EXISTS` 단순화는 비채택). 초대는 신규 이메일에만 발급.

### 6.2 ADMIN — 초대 재발송 (D-7)

```
POST /api/admin/users/{id}/resend-invite
권한: hasAnyRole('ADMIN','SYSTEM_ADMIN')
Audit: ACCOUNT_SETUP_TOKEN_ISSUED (기존 재사용)

Response 202 (AdminUserResponse)
Errors:
  404 USER_NOT_FOUND
  409 NOT_PENDING        // 대상이 PENDING_ACTIVATION 아님(이미 ACTIVE 등)
  400 NOT_LEW_INVITE     // signupSource != ADMIN_INVITE (오용 방지, 선택)
```

### 6.3 공개 셋업 — 상태 조회 (확장)

```
GET /api/public/account-setup/{token}
Response (AccountSetupStatusResponse):
{
  "maskedEmail": "l***@example.com",
  "expiresAt": "2026-06-19T12:00:00",
  "requiresLewDetails": true          // ★ 신규: source==LEW_INVITATION 이면 true (면허/등급/PayNow 입력란 렌더)
}
```

### 6.4 공개 셋업 — 완료 (확장)

```
POST /api/public/account-setup/{token}
Request (AccountSetupCompleteRequest):
{
  "password": "...",                  // @NotBlank @Size(8,72)
  "passwordConfirm": "...",
  "lewLicenceNo": "EW0001234",        // ★ 신규: LEW_INVITATION 토큰일 때 필수
  "lewGrade": "GRADE_9",              // ★ 신규: LEW_INVITATION 토큰일 때 필수
  "pdpaConsent": true,                // ★ 신규: LEW_INVITATION 토큰일 때 true 필수 (D-8)
  "paynowType": "MOBILE",             // ★ 신규: COMPANY_UEN | MOBILE (D-PN2, 필수 — D-PN7 확정)
  "paynowValue": "97771983"           // ★ 신규: type별 형식 검증 (MOBILE 8자리 / COMPANY_UEN 10자)
}

성공 시 서버 처리(LEW_INVITATION 분기):
  1) 토큰 validate (만료/잠금/사용/무효 → 410)
  2) password == passwordConfirm (400 PASSWORD_MISMATCH)
  3) validatePasswordPolicy (400 PASSWORD_POLICY_VIOLATION)
  4) pdpaConsent != true → 400 PDPA_CONSENT_REQUIRED
  5) lewLicenceNo blank → 400 LEW_LICENCE_NO_REQUIRED
  6) lewGrade blank/invalid → 400 LEW_GRADE_REQUIRED / INVALID_LEW_GRADE
  7) existsByLewLicenceNo(trim) → 409 DUPLICATE_LEW_LICENCE_NO   (계정은 PENDING_ACTIVATION 유지, 토큰 미사용)
  8) paynowType blank/invalid → 400 PAYNOW_TYPE_REQUIRED / INVALID_PAYNOW_TYPE
     PaynowValidator(type, value) 실패 → 400 INVALID_PAYNOW_VALUE   (계정은 PENDING_ACTIVATION 유지, 토큰 미사용)
  9) user.changePassword(encode) ; lewLicenceNo/lewGrade 세팅 ; user.changePaynow(type, value) ; approvedStatus=APPROVED (D-1)
  10) activate() (PENDING_ACTIVATION→ACTIVE) ; verifyEmail()
  11) recordSignupConsent(now, TermsVersion.CURRENT, ADMIN_INVITE) + UserConsentLog(sourceContext=ADMIN_INVITE)
  12) LewPaynowChangeLog 기록(old=null → new, changedBy=self, sourceContext=ACCOUNT_SETUP)   ← 최초 입력도 이력
  13) token.markUsed ; Audit ACCOUNT_ACTIVATED
  14) JWT 발급(approved=true) → TokenResponse(role=LEW)

Response 200 (TokenResponse): 자동 로그인 토큰 (approved=true, role=LEW)
```

> **검증 오류 vs 토큰 잠금(E-5/D-9)**: 4~8번의 입력 검증 오류는 `recordFailure`(잠금 카운트)를 **호출하지 않는다** — 면허/PayNow 오타로 토큰이 잠기는 것을 방지(권장, D-9로 확인). 토큰 자체의 무효(만료/이미 사용)만 카운트 대상. 기존 코드도 입력 검증 실패 시 `recordFailure`를 호출하지 않으므로(`AccountSetupService.complete`) 이 정책과 일관됨.

> **컨시어지 분기 무영향 보장**: source가 `CONCIERGE_ACCOUNT_SETUP`/`LOGIN_ACTIVATION`이면 4~12번의 LEW 전용 처리를 건너뛰고 기존 로직(비번만 + APPLICANT/기존 role 유지)을 그대로 탄다. 면허/등급/pdpaConsent/paynow 필드는 무시.

### 6.5 프로필 — PayNow 조회/변경 (기존 엔드포인트 확장)

```
GET /api/users/me  (기존)
Response (UserResponse): ... + paynowType, paynowValue (★ 본인이므로 전체값 노출 — D-PN5 ① LEW 본인 전체 노출)

PUT /api/users/me  (기존, UpdateProfileRequest 확장)
Request: 기존 필드 + { "paynowType": "COMPANY_UEN", "paynowValue": "201837490N" }

서버 처리(UserService.updateMyProfile):
  - role==LEW 이고 paynow 필드가 들어온 경우에만 처리 (비-LEW는 무시)
  - PaynowValidator(type, value) → 실패 시 400 INVALID_PAYNOW_VALUE / PAYNOW_TYPE_REQUIRED
  - 기존값과 (type,value) 동일 → no-op (이력 미기록)
  - 변경됨 → user.changePaynow(type, value) + LewPaynowChangeLog 기록
      (old=기존 type/value, new=신규, changedBy=self, sourceContext=PROFILE_UPDATE, ip/ua)
Response 200 (UserResponse)
```

> 프론트(`userApi.updateProfile` → `PUT /users/me`)·`ProfilePage`는 이미 존재 — PayNow 입력란만 LEW 섹션(`ProfilePage.tsx:275-298`)에 추가. 신규 엔드포인트 불필요. 본인 화면이므로 마스킹 없음.

### 6.6 ADMIN — PayNow 마스킹 목록 + reveal 열람 (D-PN5)

```
GET /api/admin/users  (기존 목록, AdminUserResponse 확장)
Response (AdminUserResponse): 기존 필드 + paynowType + paynowValueMasked (예 "****1983")
  ★ 평문 paynowValue 는 절대 싣지 않는다 — 과다노출·access-log 유출 방지.
  ★ AdminUserResponse.from(User) 가 PaynowMasker 로 마스킹값만 채운다.
  (비-LEW row 는 paynowType/paynowValueMasked = null)

GET /api/admin/users/{id}/paynow/reveal  (★ 신규)
권한: hasAnyRole('ADMIN','SYSTEM_ADMIN')  (@PreAuthorize 클래스 레벨 상속)
Audit: LEW_PAYNOW_VIEWED (category=ADMIN, entityType=User)  ← @Auditable, 열람 감사(누가·언제·어느 LEW)

Response 200 (PaynowRevealResponse):
{
  "userSeq": 42,
  "paynowType": "MOBILE",
  "paynowValue": "97771983"     // 전체 평문 (지급 주체가 송금에 사용)
}
Errors:
  404 USER_NOT_FOUND
  409 NOT_LEW / PAYNOW_NOT_SET   // 대상이 LEW 아님 또는 PayNow 미설정
```

> **설계 의도(D-PN5)**: admin이 곧 지급 주체라 전체 값이 **반드시** 필요하므로 마스킹으로 가리지 않는다 — 대신 ① 기본은 마스킹 노출 ② 전체값은 별도 reveal 엔드포인트로만 ③ reveal 시 `LEW_PAYNOW_VIEWED` 감사 기록. 그 외 역할(신청자·다른 LEW)은 reveal 엔드포인트 권한 밖이며 목록 응답에도 타인 PayNow가 실리지 않는다(PDPA).

### 6.7 자가가입 — PayNow 수집 (기존 엔드포인트 확장, D-PN6)

```
POST /api/auth/signup  (기존, SignupRequest 확장)
Request: 기존 필드 + role=LEW 일 때 { "paynowType": "MOBILE", "paynowValue": "97771983" } 필수 (D-PN6/D-PN7)

서버 처리(AuthService.signup, role==LEW 분기 — 기존 면허/등급 검증 옆에 추가):
  - paynowType blank/invalid → 400 PAYNOW_TYPE_REQUIRED / INVALID_PAYNOW_TYPE
  - PaynowValidator(type, value) 실패 → 400 INVALID_PAYNOW_VALUE
  - user.changePaynow(type, value) + LewPaynowChangeLog 기록(old=null→new, changedBy=self, sourceContext=SIGNUP, ip/ua)
  - (기존 동작) LEW 자가가입은 approvedStatus=PENDING 으로 시작 → 기존 admin /approve 경로 유지 (초대와 달리 자동승인 아님)
```

> D-PN6 확정으로 **모든 LEW 생성 경로(초대 셋업·자가가입·프로필)** 가 동일한 `PaynowValidator` + `LewPaynowChangeLog`를 공유한다. 자가가입은 본 발주(초대) 범위 밖이었으나 데이터 일관성을 위해 확정 포함됐다. (자가가입의 승인 흐름은 기존대로 — PayNow만 추가.)

---

## 7. 리스크 & 완화책

### 7.1 핵심 긴장: D-1(자동승인) + D-2(LEW 자가 면허입력)

> **리스크 R-CORE**: admin은 이메일·이름만 입력하고(D-2), 셋업 완료 시 자동 APPROVED가 되므로(D-1), **admin이 면허번호를 한 번도 검증하지 않은 채 LEW 계정이 활성화**된다. 기존 면허 중복가드(`existsByLewLicenceNo`)는 셋업 제출 시점에 동작하므로 **중복 면허는 차단**되지만, **존재하지 않는 면허/오타 면허/타인 면허 사칭**은 시스템이 사전 검증하지 못하고 **사후 발견**된다.

| ID | 완화책 | 비고 |
|---|---|---|
| R-1 | **셋업 시 면허 중복가드 적용**(`existsByLewLicenceNo`) | 한 실물 LEW = 한 계정 불변식 유지. 중복은 막힘(E-4). 필수. |
| R-2 | **admin 사용자 목록에서 사후 검토** + 의심 시 `suspend(reason)`(SUSPENDED) | `User.suspend`(`User.java:651`) 이미 존재. admin UI에 정지 액션 노출(이미 있으면 재사용). |
| R-3 | **초대 대상 이메일 사전 신뢰** — invite는 admin만 발송 가능(권한 가드)하므로 신뢰된 채널 | admin의 초대 행위 = 검증으로 간주하는 D-1의 전제. |
| R-4 | **Audit 추적성** — `LEW_INVITATION_SENT`(누가 누구를 초대) + `ACCOUNT_ACTIVATED`(언제 면허·등급 입력) | 사후 추적·책임 소재 명확. |
| R-5 | **D-4 정책으로 기존 계정 LEW 자동승격 차단** — 신규 이메일에만 초대 발급 | 권한 상승 표면 최소화. |
| R-6 | (향후) **EMA 면허 외부 검증** 도입 시 셋업 단계 또는 사후 배치로 진위 확인 | 비목표(§1.3). 별도 트랙. |

> **결론**: D-1/D-2는 유지하되, R-1(중복가드)·R-2(사후 SUSPEND)·R-4(감사)·R-5(D-4)를 **필수 완화책**으로 구현에 포함한다. 잔존 리스크(오타/사칭 면허)는 "사후 발견 + 정지"로 수용한다.

### 7.2 기타 리스크

| ID | 리스크 | 완화 |
|---|---|---|
| R-7 | 셋업 폼에 면허/등급/PayNow 추가로 컨시어지 셋업 회귀 | `requiresLewDetails`/source 분기로 컨시어지 경로는 LEW 입력란 미렌더 + 서버 미검증. 회귀 테스트 §10. |
| R-8 | 만료된 PENDING_ACTIVATION 초대 계정 누적 | 운영 정리 배치(비목표). users.email UNIQUE라 동일 이메일 재초대는 D-4로 차단되므로 청소 전엔 재초대 불가 → 만료 정리 배치 필요성 기록. |
| R-9 | 설정 우선 원칙(등급 옵션 하드코딩) | D-6: LewGrade는 법적 고정 3종 → 예외. 코드 주석으로 사유 명시. |

### 7.3 PayNow 정산정보 리스크

> **리스크 R-PN**: PayNow는 **플랫폼이 LEW에게 대금을 지급하는 수취 계좌**(§1.4)다. LEW가 **잘못된 번호/UEN을 입력**하면 향후 지급이 **엉뚱한 계좌로 송금**될 수 있다. D-PN1/2(LEW 자가입력)이므로 플랫폼이 진위를 사전 검증하지 못한다.

| ID | 완화책 | 비고 |
|---|---|---|
| R-PN1 | **형식 검증**(MOBILE 8자리 `^[89]\d{7}$`, COMPANY_UEN 10자 UEN 형식) — 백엔드 단일 정의(`PaynowValidator`) + 프론트 동일 규칙 공유 | 명백한 오타·자리수 오류 차단. 단 "유효형식이지만 타인 계좌"는 못 막음. |
| R-PN2 | **변경 이력 보존(D-PN3, `LewPaynowChangeLog`)** — old→new/변경자/시각 불변 기록 | 오지급 사후 추적·분쟁 대응. 정산 책임 소재 명확. **필수.** |
| R-PN3 | **지급 시점 재확인**(향후 지급 워크플로) — 송금 전 LEW에게 수취정보 확인 단계 | 비목표(별도 트랙)지만 오지급 최종 방어선으로 기록. |
| R-PN4 | **system_settings 혼동 금지**(§1.4) — per-LEW 개인 데이터, 전역 설정 아님 | 잘못 통합하면 한 LEW PayNow가 플랫폼 수금계좌로 노출되는 치명 버그. |
| R-PN5 | **검증 규칙 단일 소스** — 자리수/정규식을 백·프론트가 한 정의 공유(중복 정의 시 드리프트로 한쪽만 통과) | 설계 원칙 "단일 진실원" 정신 준수(개인 데이터라 system_settings는 아니나 규칙은 공유). |
| R-PN6 | **정산정보 상시 노출 → 기본 마스킹 + reveal 열람 감사(D-PN5)** | admin이 지급 주체라 전체값이 필요하나, 목록/상세에 평문을 항상 싣지 않는다. 기본 마스킹(끝 4자리) + 전체값은 reveal 엔드포인트로만 + `LEW_PAYNOW_VIEWED` 감사. 평문이 access-log·화면 캐시·과다권한에 상시 노출되는 것을 차단하면서도 지급 업무는 가능. |
| R-PN7 | **타인 PayNow 접근 차단(D-PN5 ①, PDPA)** | reveal·목록 모두 ADMIN/SYSTEM_ADMIN·본인으로 권한 제한. 신청자·다른 LEW는 타인 정산정보 접근 불가. |

---

## 8. PR 분해 (의존 순서)

> D-3에 따라 **구현은 착수하지 않는다.** 아래는 핸드오프용 분해.

| PR | 제목 | 내용 | 의존 |
|---|---|---|---|
| **PR-1** | 토큰 소스 + admin invite 백엔드 | `AccountSetupTokenSource.LEW_INVITATION`, `AuditAction.LEW_INVITATION_SENT`, `InviteLewRequest` DTO, `POST /api/admin/users/invite-lew`(User 생성 + 토큰 발급 + audit, 이메일은 PR-2까지 LogOnly), D-4 이메일 중복 정책, D-7 resend-invite | — |
| **PR-2** | 초대 이메일 | `sendLewInvitationEmail` IF + Smtp/LogOnly 구현, afterCommit 발송 연결, 본문 카피(48h 만료 + 단일 CTA + 반피싱 푸터, `notification-copy-templates` 톤) | PR-1 |
| **PR-PN1** | PayNow 데이터모델 + 검증 + 이력 | `PaynowType` enum, `User.paynow_type/paynow_value` 컬럼 + `changePaynow` + 마이그(`addColumnIfMissing`), `PaynowValidator`(공유 규칙), `LewPaynowChangeLog` 엔티티/Repo + `schema.sql` CREATE TABLE | — (독립; PR-3/6 전 선행) |
| **PR-3** | 셋업 완료 LEW 확장 | `AccountSetupCompleteRequest`(면허/등급/pdpaConsent/**paynowType·paynowValue**) + `AccountSetupStatusResponse.requiresLewDetails` + `AccountSetupService.complete` LEW 분기(D-1 자동승인 + R-1 중복가드 + D-8 consent + **PayNow 검증/세팅/이력**) | PR-1, PR-PN1 |
| **PR-4** | 프론트 셋업 화면 | `AccountSetupPage` 면허번호 + 등급 버튼(D-6) + PDPA 동의 + **PayNow 수단 선택/값 입력**(공유 검증 `constants/paynow.ts`) + `requiresLewDetails` 조건부 렌더, `accountSetupApi` 바디 확장, 에러 매핑(중복면허/동의누락/PayNow) | PR-3 |
| **PR-PN2** | admin PayNow reveal + 마스킹 + 열람감사(D-PN5) | `AuditAction.LEW_PAYNOW_VIEWED`, `PaynowMasker`, `AdminUserResponse`에 `paynowValueMasked`(평문 미노출), `GET /api/admin/users/{id}/paynow/reveal`(`@Auditable`) + `PaynowRevealResponse`, 프론트 `AdminUserListPage` 마스킹 표시 + '보기' 버튼 → reveal API | PR-PN1 |
| **PR-5** | 프론트 admin UI(초대) | `AdminUserListPage` "LEW 초대" 버튼 + 모달(이메일/이름) + 재발송 + 상태 배지(초대됨/활성) + 정지(R-2) 노출, `adminUserApi.inviteLew/resendInvite`. (PayNow 마스킹·reveal은 PR-PN2) | PR-1 |
| **PR-6** | 프로필 PayNow 관리 | `UpdateProfileRequest`(paynow) + `UserService.updateMyProfile` 변경감지·이력기록 + `ProfilePage` LEW 섹션 PayNow 입력란(본인 전체표시) + `userApi`/`types` | PR-PN1 |
| **PR-PN3** | 자가가입 PayNow 수집(D-PN6/D-PN7) | `SignupRequest`(paynowType/paynowValue) + `AuthService.signup` role=LEW 분기 PayNow 필수검증·세팅·최초이력(sourceContext=SIGNUP) + `SignupPage`(role=LEW) PayNow 입력란(공유 검증) | PR-PN1 |

권장 머지 순서: (PR-1, PR-PN1 병행) → PR-2 → PR-3 → (PR-4 ∥ PR-5 ∥ PR-6 ∥ PR-PN2 ∥ PR-PN3).

---

## 9. 수용 기준 (Acceptance Criteria)

- [ ] **AC-1**: admin/SYSTEM_ADMIN이 email+firstName+lastName만으로 `POST /api/admin/users/invite-lew`를 호출하면 role=LEW, status=PENDING_ACTIVATION, approvedStatus=PENDING, signupSource=ADMIN_INVITE, lewLicenceNo=null, lewGrade=null인 User가 생성되고 `LEW_INVITATION_SENT` audit이 남는다.
- [ ] **AC-2**: 비-admin(APPLICANT/LEW)은 invite-lew 호출 시 403.
- [ ] **AC-3**: 초대 직후 `LEW_INVITATION` source 토큰이 1개 발급되고(48h), 초대 이메일이 afterCommit으로 발송된다(발송 실패는 트랜잭션 롤백 없이 swallow).
- [ ] **AC-4 (D-4)**: 이미 존재하는 이메일로 초대 시 409(케이스별 코드)로 거부되고 User가 새로 생성되지 않는다.
- [ ] **AC-5**: `GET /api/public/account-setup/{token}`이 LEW_INVITATION 토큰에 대해 `requiresLewDetails=true`를 반환한다(컨시어지 토큰은 false 또는 미설정).
- [ ] **AC-6 (D-1)**: 셋업 POST에서 비번/PDPA/면허/등급/PayNow를 올바르게 제출하면 approvedStatus=APPROVED, status=ACTIVE, emailVerified=true, paynowType/paynowValue 저장이 되고 approved=true인 JWT가 발급되어 `/lew/dashboard`로 자동 진입한다. **별도 admin /approve 호출 없이** LEW로 활동 가능.
- [ ] **AC-7 (R-1)**: 이미 등록된 면허번호로 셋업 제출 시 409 DUPLICATE_LEW_LICENCE_NO, 계정은 PENDING_ACTIVATION 유지, 토큰은 사용 처리되지 않아 정정 재제출이 가능하다.
- [ ] **AC-8 (D-8)**: 셋업 완료 시 `signup_consent_at`, `terms_version`이 기록되고 `UserConsentLog`에 sourceContext=ADMIN_INVITE 행이 남는다. pdpaConsent=false 제출은 400 PDPA_CONSENT_REQUIRED.
- [ ] **AC-9**: 면허/등급 누락 또는 잘못된 등급 제출 시 각각 400 LEW_LICENCE_NO_REQUIRED / LEW_GRADE_REQUIRED / INVALID_LEW_GRADE.
- [ ] **AC-10 (D-7)**: PENDING_ACTIVATION 초대 계정에 admin이 resend-invite 시 기존 토큰이 revoke되고 새 토큰·새 메일이 발급된다. ACTIVE 계정은 409 NOT_PENDING.
- [ ] **AC-11 (R-7)**: 컨시어지 셋업(CONCIERGE_ACCOUNT_SETUP/LOGIN_ACTIVATION) 경로는 면허/등급/PayNow 입력 없이 기존과 동일하게 동작한다(회귀 없음).
- [ ] **AC-12**: 만료/잠금/사용/무효 토큰은 410 + 기존 세부 코드로 화면이 안내한다(기존 동작 유지).
- [ ] **AC-13 (D-PN1/2, R-PN1)**: MOBILE은 8자리(`^[89]\d{7}$`)만, COMPANY_UEN은 10자 형식만 통과. 위반 시 400 INVALID_PAYNOW_VALUE. paynowType 누락/오류 시 400 PAYNOW_TYPE_REQUIRED/INVALID_PAYNOW_TYPE. 백·프론트 검증 규칙이 동일하다(R-PN5).
- [ ] **AC-14 (D-PN3, R-PN2)**: 셋업 시 최초 입력도 `LewPaynowChangeLog`에 (old=null→new, changedBy=self, sourceContext=ACCOUNT_SETUP)로 기록된다.
- [ ] **AC-15 (D-PN4)**: 본인 ProfilePage에서 PayNow를 변경하면 `PUT /api/users/me`가 새 값을 저장하고 `LewPaynowChangeLog`에 (old→new, sourceContext=PROFILE_UPDATE) 행이 추가된다. **동일 값 재저장은 이력이 추가되지 않는다.**
- [ ] **AC-16 (§1.4)**: LEW PayNow는 `users` 테이블에 per-row로 저장되며 `system_settings`를 읽거나 쓰지 않는다(전역 결제계좌와 분리). 비-LEW 사용자에게는 PayNow 필드가 적용되지 않는다.
- [ ] **AC-17 (E-4 패턴)**: PayNow 형식 오류로 셋업 실패 시 계정은 PENDING_ACTIVATION 유지·토큰 미사용으로 정정 재제출이 가능하다(잠금 미카운트, D-9).
- [ ] **AC-18 (D-PN5 ①, R-PN6)**: 본인 ProfilePage(`GET /api/users/me`)는 PayNow 전체값을 노출한다. admin 사용자 목록(`AdminUserResponse`)은 **마스킹값(`paynowValueMasked`)만** 내려주고 평문 `paynowValue`는 포함하지 않는다.
- [ ] **AC-19 (D-PN5 ②)**: ADMIN/SYSTEM_ADMIN이 `GET /api/admin/users/{id}/paynow/reveal` 호출 시 전체 평문이 반환되고 `LEW_PAYNOW_VIEWED` 감사 로그(actor=adminSeq, target=LEW userSeq)가 남는다.
- [ ] **AC-20 (D-PN5 ①, R-PN7)**: 비권한자(APPLICANT·다른 LEW)는 reveal 엔드포인트 호출 시 403. 대상이 LEW 아님/PayNow 미설정이면 409 NOT_LEW/PAYNOW_NOT_SET.
- [ ] **AC-21 (D-PN6/D-PN7)**: 자가가입(`POST /api/auth/signup`, role=LEW)에서 PayNow 누락 시 400(PAYNOW_TYPE_REQUIRED/INVALID_PAYNOW_VALUE), 정상 입력 시 저장 + `LewPaynowChangeLog`(old=null→new, sourceContext=SIGNUP) 기록. (자가가입의 승인 흐름은 기존대로 PENDING→admin /approve.)

---

## 10. 테스트 계획

### 10.1 백엔드 단위/통합 (기존 패턴 참고)
- `AccountSetupTokenServiceTest` 패턴: LEW_INVITATION source 발급/검증/O-17 revoke/만료/잠금.
- `AuthServiceSignupDuplicateLicenceTest` 패턴: 셋업 완료 시 `existsByLewLicenceNo` 중복가드(AC-7) — 정상/중복/정정 재제출.
- `AccountSetupServiceTest` 확장:
  - LEW_INVITATION + 정상 입력 → APPROVED/ACTIVE/JWT approved=true (AC-6).
  - PDPA 미동의 → 400 (AC-8).
  - 면허/등급 누락·오류 → 400 (AC-9).
  - 컨시어지 토큰 → LEW 필드 무시, 기존 동작 (AC-11).
  - 입력 검증 오류 시 `recordFailure` 미호출 검증(E-5/D-9 정책).
  - **PayNow**: MOBILE 8자리/COMPANY_UEN 10자 통과·위반(AC-13), 최초 입력 시 `LewPaynowChangeLog` 기록(AC-14), 형식오류 시 PENDING 유지·토큰 미사용(AC-17).
- `PaynowValidatorTest`: MOBILE `^[89]\d{7}$` 경계(7자리/9자리/8x/9x 시작/비숫자), COMPANY_UEN 10자/9자/11자/비형식.
- `UserService.updateMyProfile` 테스트: PayNow 변경 시 `LewPaynowChangeLog`(old→new, PROFILE_UPDATE) 기록(AC-15), **동일 값 재저장 no-op**(이력 미추가), 비-LEW는 무시(AC-16).
- `AuthService.signup` 테스트(D-PN6/D-PN7): role=LEW 자가가입 PayNow 누락 시 400, 정상 시 저장 + `LewPaynowChangeLog`(sourceContext=SIGNUP) 기록(AC-21); 기존 `AuthServiceSignupDuplicateLicenceTest`와 동일 패턴.
- `PaynowMaskerTest`: 끝 4자리 노출 규칙(8자리·10자, 4자 이하 경계).
- `AdminUserResponse.from` 테스트: 평문 `paynowValue` 미포함, `paynowValueMasked`만 채움; 비-LEW row는 null (AC-18).
- PayNow reveal 통합 테스트: ADMIN/SYSTEM_ADMIN 전체값 + `LEW_PAYNOW_VIEWED` 감사 기록(AC-19); APPLICANT·다른 LEW 403(AC-20); 비-LEW/미설정 409.
- `AdminUserController` 통합(또는 신규 `AdminLewInviteControllerTest`):
  - 권한(ADMIN/SYSTEM_ADMIN 201, 그 외 403) (AC-1, AC-2).
  - 이메일 중복 케이스별 409 (AC-4).
  - resend-invite PENDING만 허용 (AC-10).
  - audit `LEW_INVITATION_SENT` 기록 검증.
- 마이그레이션: 부팅 테스트(schema/data.sql 로드)로 `users.paynow_*` 컬럼 + `lew_paynow_change_logs` 테이블 생성 확인.
- 이메일: LogOnlyEmailService로 `sendLewInvitationEmail` 호출/인자 검증.

### 10.2 프론트
- `AccountSetupPage`: `requiresLewDetails=true`일 때 면허/등급/PDPA/**PayNow** 렌더 + 제출 비활성 조건(동의·면허·등급·**PayNow** 미입력/형식오류) + 중복면허·PayNow 409/400 인라인 에러.
- `ProfilePage`: LEW PayNow 수단 선택/값 입력(본인 전체표시) + 형식 검증 + 저장 → 토스트. 비-LEW는 PayNow 미표시.
- `SignupPage`(D-PN6): role=LEW 선택 시 PayNow 입력란 표시 + 필수/형식 검증; role=APPLICANT면 미표시.
- `AdminUserListPage`: 초대 모달 제출 → 목록에 "초대됨" 배지, 재발송 액션. **PayNow는 마스킹 표시 + '보기' 버튼 클릭 → reveal API 전체값 노출**(D-PN5).
- 회귀: 컨시어지 셋업(`requiresLewDetails` 없음)에서 면허/PayNow 입력란 미표시.
- 공유 검증: 프론트 `constants/paynow.ts` 규칙이 백엔드 `PaynowValidator`와 동일(R-PN5) — 동일 입력에 동일 판정.

### 10.3 수동 검증 (배포 전)
- 로컬에서 admin 초대 → LogOnly 로그의 setupUrl 복사 → 셋업(면허+등급+PayNow) 완료 → `/lew/dashboard` 진입 → 신청서 배정/검토 가능 확인.
- 중복 면허번호 / PayNow 형식오류로 재현 → 409/400 + 정정 재제출.
- 프로필에서 PayNow 변경 → DB `lew_paynow_change_logs`에 old→new 행 추가 확인.

---

## 11. 핸드오프 체크리스트

- [x] **모든 결정 확정됨 (2026-06-17 사용자 승인, 열린 질문 0개)** — D-4~D-9, D-PN1~D-PN8 전부 §2.1 확정. 구현 계획 단계 진행 가능.
- [ ] **D-PN6 확정 반영 확인**: PayNow는 초대 셋업 + **자가가입(`AuthService.signup`/`SignupPage`)** + 프로필 3경로 모두에서 수집(§4.2 자가가입 행, §6.7, PR-PN3, AC-21).
- [ ] **PayNow ≠ system_settings PayNow**(§1.4) — per-LEW 개인 데이터로 `users`에 저장, 전역 결제계좌와 절대 혼동 금지(R-PN4).
- [ ] PayNow 검증 규칙은 백·프론트 단일 소스 공유(R-PN5).
- [ ] **PayNow 노출은 기본 마스킹 + reveal 열람감사(D-PN5)** — 목록 응답에 평문 미노출, 전체값은 `/paynow/reveal`(`LEW_PAYNOW_VIEWED`)로만. admin이 지급 주체라 전체값 접근은 보장하되 감사로 추적(R-PN6/R-PN7).
- [ ] enum/audit/PayNow 컬럼 추가는 DB 마이그레이션이 `DatabaseMigrationRunner` 멱등 처리(수동 SQL 불필요) — `addColumnIfMissing` + `schema.sql` CREATE TABLE IF NOT EXISTS.
- [ ] 한국어 커밋 메시지, DTO Request/Response 분리, `@Auditable` 패턴 준수. (PayNow 이력 로그는 `UserConsentLog` 선례대로 BaseEntity·soft delete 미적용 append-only.)
- [ ] R-1/R-2/R-4/R-5 + R-PN1/R-PN2/R-PN6/R-PN7 완화책을 구현에 반드시 포함.
- [ ] 컨시어지 셋업 회귀 테스트(AC-11) 통과 후 배포.
