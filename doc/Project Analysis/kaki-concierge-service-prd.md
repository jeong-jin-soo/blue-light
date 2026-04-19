# Kaki Concierge Service — PRD

**작성일**: 2026-04-19
**최종 수정**: 2026-04-19 (v1.5 — 보안 리뷰 반영 + 오픈 이슈 일괄 승인)
**작성자**: Product Manager (LicenseKaki)
**상태**: Approved v1.5 — Phase 1 착수 승인. 옵션 A 폐기, 옵션 B 단독. H-1~H-5 반영 완료
**대상 독자**: Backend/Frontend 개발자, Admin 운영팀, 법무·보안 검토
**전제**: 기존 LicenseKaki 플랫폼(MVP Phase 1~16, Phase 3 LEW 서류 워크플로 머지 완료) 위에 얹는 확장 기능
**근거 문서**: `doc/Project Analysis/kaki-concierge-security-review.md` (Phase 1 사전 보안 검토, 2026-04-19)

---

## Changelog

### v1.4 → v1.5 (2026-04-19, 본 개정 — 보안 리뷰 기반 일괄 승인)

| 항목 | 변경 내용 | 근거 |
|------|----------|------|
| **옵션 A 완전 폐기 (H-5 / O-21)** | 이메일에 평문 임시 비밀번호 포함 경로 전면 삭제. `POST /api/auth/login/force-change-password` 엔드포인트 제거, §6.4-1b "옵션 A 대안 템플릿" 섹션 제거, 로그인 활성화는 **옵션 B 단독** | 보안 리뷰 H-5 (PDPA §24 + OWASP ASVS V2.1.6 위반) |
| **AC-29 재작성 (H-1)** | **비밀번호 검증 선행 → 성공한 경우에만 `status` 분기**. 미존재 이메일에도 **dummy BCrypt.verify** 실행(타이밍 동등성). `DELETED`/미존재 모두 `INVALID_CREDENTIALS` 응답 | 보안 리뷰 H-1 (CWE-204 이메일 enumeration) |
| **AC-31 수정** | H-1 방어 로직에 맞춰 "로그인 성공 + PENDING_ACTIVATION인 경우에만 활성화 플로우 CTA 노출" | 보안 리뷰 H-1 |
| **AccountSetupToken 잠금 (H-3)** | `failedAttempts`, `lockedAt` 컬럼 추가. **5회 실패 시 자동 잠금** + Manager 재발송으로 복구. **AC-28b 신설** | 보안 리뷰 H-3 (LoaSigningToken AC-18과 비대칭 해소) |
| **활성 토큰 단일 유지 (O-17)** | AccountSetupToken은 유저당 유효 1개만 허용. 신규 발급 시 기존 토큰 즉시 invalidate(`revokedAt`) | O-17 해결 |
| **SecurityConfig 매처 분리 (H-4)** | `/api/admin/**`은 ADMIN 전용. **LOA 경로 A 업로드 API는 CONCIERGE_MANAGER+ADMIN만, LEW 제외**. §12에 SecurityConfigTest 매트릭스 검증 필수. **AC-15b 신설** | 보안 리뷰 H-4 (LOA 위조 경로 차단) |
| **§4.4 이메일 유출 방지 재작성 (M-1 / O-23)** | Bucket4j 대신 기존 `GenericRateLimiter` 재사용. 5케이스 공통 코드 경로 + dummy BCrypt.verify + `@Async` + `MessageDigest.isEqual`. **p95 < 200ms 타이밍 통합 테스트 CI 필수** | 보안 리뷰 M-1 (O-23 해결) |
| **PENDING_ACTIVATION 자동 정리 (O-22)** | **90일 미접속 → SUSPENDED**, **180일 → soft-delete+DELETED**. Phase 3에 `ConciergePendingCleanupScheduler` 구현 | O-22 해결 |
| **경로 A 7일 이의 제기 창구 (O-15)** | N5-UploadConfirm 이메일에 "7일 내 이의 없으면 묵시적 동의 간주" 명시. **AC-22b 신설** | O-15 조건부 해결 (법무 배포 전 재검토) |
| **약관 버전 관리 (O-19)** | Phase 1~2: Java 상수(`TermsVersion.CURRENT = "2026-04-19"`) + `user_consent_logs.version` 기록 / Phase 3: `terms_documents` DB CMS로 전환 | O-19 해결 |
| **LOA 경로 A 법적 무결성 (O-1)** | **Phase 1 착수 승인 + 배포 전 싱가포르 법무 자문 필수**. ETA 3·4 / PDPA §22A / EMA 가이드라인 | O-1 조건부 승인 |
| **Payment 권한 분기 (M-5)** | `Payment.referenceType`별 권한 검증 분기 명시 — `APPLICATION`/`CONCIERGE_REQUEST`/`SLD_ORDER` 각각 다른 OwnershipValidator 적용. Phase 2 필수 | 보안 리뷰 M-5 |
| **오픈 이슈 대폭 정리 (§10)** | O-15/O-17/O-19/O-21/O-22/O-23 해결 완료로 이동. O-1은 조건부 승인. 잔여: O-3(Phase 4), O-13(Phase 2), O-14(Phase 2), O-18(법무) | 보안 리뷰 §5 총평 |

### v1.3 → v1.4 (2026-04-19)

| 항목 | 변경 내용 |
|------|----------|
| **계정 활성화 정책 재정의** | v1.3의 `signupCompleted` boolean 플래그 → **`UserStatus` enum 명시적 도입** (`PENDING_ACTIVATION` / `ACTIVE` / `SUSPENDED` / `DELETED`). 컨시어지 자동 생성 계정은 `PENDING_ACTIVATION`으로 시작, **최초 로그인 성공 시 `ACTIVE`로 전환** |
| **User 엔티티 (§3.4b)** | `signupCompleted` 제거(마이그레이션으로 `status`에 흡수) + `status`, `activatedAt`(`updatable=false`), `firstLoggedInAt` 컬럼 신규 |
| **로그인 활성화 플로우 (§4, §5.1c)** | 2가지 옵션 제시 — **옵션 B 권장**(로그인 시도 시 이메일 인증 링크 재발송 → 비밀번호 설정 → ACTIVE 전환), 옵션 A(임시 비밀번호)는 대안으로 병기 |
| **API (§4)** | `POST /api/auth/login` 확장(`ACCOUNT_PENDING_ACTIVATION` 응답), `POST /api/auth/login/request-activation` 신규, Account Setup 성공 시 `status=ACTIVE` 전환 로직 |
| **상태 머신 (§5.1c 신규)** | **User.status 상태 머신 다이어그램** 추가: `PENDING_ACTIVATION → ACTIVE → SUSPENDED/DELETED` |
| **알림 (§6, §6.4-1)** | **N1 본문 전면 재설계** — "계정이 비활성 상태" 명시 + "최초 로그인 시 활성화" 안내 + 로그인 페이지 CTA. N1-R 문구 재정의("계정 설정 미완료" → "계정 미활성화 리마인더"). **N-Activation** 신규(로그인 시도 시 발송) |
| **기존 회원 분기 (§7.7)** | C3 케이스를 "기존 APPLICANT + `status=PENDING_ACTIVATION`"으로 재정의. 중복 계정 생성 방지 |
| **수용 기준 (§9)** | AC-30 ~ AC-35 신규 6종 |
| **오픈 이슈 (§10)** | O-21(옵션 A vs B 최종 선택), O-22(PENDING_ACTIVATION 장기 미접속 GC 정책), O-23(옵션 B 이메일 존재 노출 방지) 신규 |
| **개발 단계 (§11)** | Phase 1 PR#1-Enhanced에 `UserStatus` + PENDING_ACTIVATION 초기값, PR#3-Enhanced에 옵션 B 로그인 활성화 플로우 포함. 옵션 A는 Phase 2 선택 과제 |

### v1.2 → v1.3 (2026-04-19)

| 항목 | 변경 내용 |
|------|----------|
| **통합 가입 플로우** | **컨시어지 신청 = 즉시 회원가입**으로 전환. 기존 "Manager contacting 단계에서 계정 생성" → **폼 제출 시 한 트랜잭션으로 User + ConciergeRequest 생성 + 계정 설정 링크 이메일 발송** |
| **동의 수집 5종** | 기존 2개(PDPA + 자동계정) → **필수 4개(PDPA / ToS / 회원가입 / 대행위임) + 선택 1개(마케팅)** |
| **User 엔티티 확장 (§3)** | `signupSource`, `signupCompleted`, `signupConsentAt`, `termsVersion`, `marketingOptIn`, `marketingOptInAt` 컬럼 6종 추가 |
| **ConciergeRequest 확장 (§3)** | `delegationConsentAt` 컬럼 추가 (대행 위임 동의 시점) |
| **신규 엔티티 (§3.11)** | **`UserConsentLog`** — 동의/철회 이력 전수 보존(감사용), 약관 버전·IP·UA 기록 |
| **기존 회원 이메일 처리 (§7.7)** | 이메일 매칭 케이스별 분기표 신규(미가입/APPLICANT/LEW·ADMIN/soft-deleted) |
| **Account Setup 페이지 (§2.9)** | `/setup-account/{token}` 신규 — 비밀번호 설정 + 이메일 자동 인증 |
| **API 확장 (§4)** | `/api/public/concierge/request` DTO 확장, `check-email`(Phase 2), Account Setup 2종 엔드포인트 신규 |
| **알림 (§6)** | N1 강화(접수확인 + 계정설정 링크 통합), **N1-R**(계정 설정 리마인더) 신규 |
| **상태 머신 (§5)** | `AWAITING_ACCOUNT_SETUP` 보조 플래그 추가 — Manager 대시보드에서 "계정 설정 대기" 별도 집계 |
| **수용 기준 (§9)** | AC-22 ~ AC-27 신규 6종 |
| **오픈 이슈 (§10)** | O-18 (soft-deleted 계정 재신청), O-19 (약관 버전 관리 체계), O-20 (마케팅 동의 철회 방법) 신규 |
| **개발 단계 (§11)** | Phase 1 PR#1-Enhanced에 통합 가입 플로우 편입, Phase 2에 실시간 이메일 체크 UX, Phase 3에 약관 버전 CMS |

### v1.1 → v1.2 (2026-04-19)

| 항목 | 변경 내용 |
|------|----------|
| **LOA 서명 수집 방식 확장** | 기존 "신청자 본인 로그인 후 직접 서명" 단일 경로 → **3-경로 모델**로 확장: ① 원칙 경로(직접 서명) ② **경로 A** Manager 대리 업로드 ③ **경로 B** 원격 서명 링크/QR (로그인 불필요) |
| 데이터 모델 (§3) | `Application`에 LOA 서명 출처 추적 컬럼 4종 추가: `loa_signature_source`, `loa_signature_uploaded_by`, `loa_signature_uploaded_at`, `loa_signature_source_memo` |
| 신규 엔티티 (§3.10) | **`LoaSigningToken`** — 1회성 원격 서명 토큰(UUID, 48h 만료, IP/UA 로그, rate limit) |
| API (§4) | Manager 2종 + Public 토큰 기반 3종 신규 엔드포인트 추가 |
| 화면 (§2.5) | 요청 상세 페이지에 **"LOA 서명 수집" 3-탭 섹션** 추가 (직접 요청 / 파일 업로드 / 원격 링크) |
| 알림 (§6) | **N5-Alt** (원격 링크 발송), **N5-UploadConfirm** (대리 업로드 후 본인 확인 메일) 신설 |
| On-Behalf-Of (§7.2.1) | LOA 전자서명 행을 **3-경로 분리 표**로 재기술 (법적 무결성 등급 명시) |
| 오픈 이슈 (§10) | O-1 재정의(3-경로 각각 법무 검토), O-13/O-14 신규(OTP 공급자, QR 라이브러리) |
| 개발 단계 (§11) | Phase 1 = 경로 A만 / Phase 2 = 경로 B(원격 링크+QR) / Phase 3 = OTP·감사 대시보드 |

### v1.0 → v1.1

| 항목 | 변경 내용 |
|------|----------|
| SLA | "최대한 빨리" → **"접수 후 24시간 이내 최초 연락"** 확정 (O-4 해결) |
| Manager 캐파시티 | 가드레일 20건 제안 → **현 시점 제한 없음**, Phase 4에서 운영 지표 기반 재검토 (O-3 재정의) |
| 결제 엔티티 | Payment 재사용 확정 + **`reference_type`/`reference_seq` 컬럼 추가**로 APPLICATION/SLD_ORDER/CONCIERGE_REQUEST 분기 (O-6 해결) |
| On-Behalf-Of | 서명·인증 단계별 대행 가능/불가 전수 조사 결과를 §7에 반영 |
| 상태 머신 | `AWAITING_APPLICANT_LOA_SIGN`, `AWAITING_LICENCE_PAYMENT` 상태 신설 (§5) |
| 알림 매트릭스 | **LOA 서명 요청** 알림 + **24h SLA 경고** 알림을 명시적으로 분리 (§6) |
| Manager 프로세스 | "Manager 대행 / Applicant 본인 수행" 책임 분장표 추가 (§7.5) |

---

## 0. 요구사항 요약

LicenseKaki 랜딩페이지에 **"화이트글러브 대행 서비스(Kaki Concierge)" 신청 진입점**을 추가한다. 방문자는 서비스 소개를 읽고 즉시 신청 폼(이름·이메일·모바일·메모 + **5종 동의**)을 제출하며(결제는 Phase 2에서 통합), **폼 제출 시점에 한 트랜잭션으로 APPLICANT 계정 + ConciergeRequest가 생성**되고 확인+계정설정 통합 이메일(N1)이 즉시 발송된다. Concierge Manager + Admin에게도 인앱·이메일 알림이 동시 발송된다. 신청자는 이메일 내 "계정 설정 링크"를 클릭해 비밀번호를 설정하면 즉시 로그인 가능하며, 이후 Manager가 해당 계정의 소유로 라이선스 신청을 대리 작성·제출까지 진행한다. 대리 작성 이력은 전부 감사 로그로 추적되며, **LOA 전자서명은 반드시 실소유자(신청자)가 수행**하는 것을 원칙으로 한다(3-경로 모델은 §7.2.1-LOA 참조).

**v1.3 핵심 원칙**: Kaki Concierge Service를 신청한 사람은 해당 신청 정보로 자동 회원가입된다. 회원가입이 신청과 **같은 시점**에 이루어지며, 필수 동의 4종(PDPA / ToS / 회원가입 / 대행위임)을 명시적으로 수집하여 감사 로그(`UserConsentLog`)에 보존한다.

**v1.4 핵심 원칙**: 컨시어지로 자동 생성된 계정은 **신청 시점에는 비활성(`PENDING_ACTIVATION`) 상태**로 저장되며, **신청자가 실제로 로그인에 성공할 때 비로소 `ACTIVE`로 전환**된다. 신청자는 N1 이메일에서 "계정이 비활성 상태"임을 고지받고, 로그인 페이지에서 이메일을 입력해 **일회성 인증 링크**(옵션 B)를 받은 뒤 비밀번호를 설정하여 최초 로그인을 완료한다. 기존에 "이메일 인증 + 비밀번호 설정 완료"를 나타내던 v1.3의 `signupCompleted` boolean은 **명시적 enum `UserStatus`로 대체**되어 suspend/delete 같은 추가 상태와 일관되게 관리된다.

**v1.5 핵심 원칙 (신규)**: 보안 리뷰 High 5건(H-1 ~ H-5)을 전면 반영하여 **Phase 1 착수 가능 상태로 승인**한다.
- **옵션 A(이메일 평문 임시 비밀번호) 완전 폐기** — PDPA §24 + OWASP ASVS V2.1.6 위반 소지. 로그인 활성화는 **옵션 B(이메일 인증 링크) 단독**.
- **이메일 enumeration 방어 강화** — 로그인 API는 **비밀번호 검증 선행**, 미존재 이메일에도 dummy BCrypt.verify를 수행해 타이밍 동등성 확보. 상태별 분기는 비밀번호 검증 통과 후에만 이루어진다.
- **SecurityConfig 매처 분리** — `/api/admin/**`에 LEW가 접근 가능했던 구조를 제거. LOA 경로 A 업로드 API는 CONCIERGE_MANAGER + ADMIN으로만 제한되어 LEW가 타인의 LOA 서명을 위조할 수 없다.
- **AccountSetupToken 대칭화** — LoaSigningToken AC-18과 같은 수준으로 5회 실패 잠금 + 활성 토큰 1개 유지.
- **PDPA "잊혀질 권리" 자동 반영** — PENDING_ACTIVATION 계정은 90일/180일 두 단계로 자동 정리된다.

---

## 1. 기능 정의

### 1.1 User Stories

| ID | 역할 | 스토리 |
|----|------|--------|
| US-1 | Visitor | 랜딩페이지에서 "대행 서비스" 배너를 발견하고, 내 상황(바빠서 직접 처리 어려움)에 맞는 서비스인지 한눈에 이해하고 싶다. |
| US-2 | Visitor | 이름·이메일·전화·메모와 동의 체크만으로 신청과 동시에 자동 회원가입되어 즉시 진행 상황을 확인할 수 있기를 원한다. |
| US-2b | Visitor | 신청 시 어떤 동의가 필요한지(PDPA / 서비스 약관 / 회원가입 / 대행 위임 / 마케팅) 개별 체크박스로 명확히 보고 싶다. |
| US-3 | Visitor | 제출 직후 "접수 확인 + 계정 설정 링크"가 담긴 이메일 1통으로 안심하고 싶다. |
| US-3b | Applicant (자동 가입자) | 이메일의 계정 설정 링크를 클릭해 비밀번호를 설정하면 즉시 로그인하여 진행 상황을 확인할 수 있기를 원한다. |
| US-4 | Concierge Manager | 새 요청이 접수되면 실시간으로 인앱·이메일 알림을 받아 SLA 안에 연락을 시작할 수 있어야 한다. |
| US-5 | Concierge Manager | 신청자 대신 플랫폼 내에서 라이선스 신청서를 작성·제출하되, 신청자 계정 소유로 귀속시키고 싶다. |
| US-6 | Concierge Manager | 내가 담당하는 요청의 진행 상태(접수 / 연락 중 / 신청서 생성 / 처리 중 / 완료)를 대시보드에서 관리하고 싶다. |
| US-7 | Applicant (Concierge 경유 가입자) | 내 이메일로 생성된 계정에 로그인하여 대리 작성된 신청서의 진행 상황을 확인하고 LOA에 **본인 서명**을 하고 싶다. |
| US-8 | Admin | 전체 Concierge 요청의 처리 현황, 담당자 배정, SLA 준수율을 모니터링하고 필요 시 재배정하고 싶다. |
| US-9 | Admin / Auditor | 대리 작성된 모든 행위(누가, 언제, 어떤 신청서에)를 감사 로그로 추적하고 PDPA 감사 시 증빙으로 제출할 수 있어야 한다. |

### 1.2 핵심 유스케이스

- **UC-1 대행 신청 접수 + 통합 가입 (★ v1.3 개정)**: Visitor가 랜딩페이지 CTA 클릭 → 모달에서 정보 입력 + 5종 동의 체크 → 결제(Phase 2부터) → 서버가 **한 트랜잭션으로** `User`(APPLICANT, `signupSource=CONCIERGE_REQUEST`, `signupCompleted=false`, 임시 해시) + `ConciergeRequest`(`userSeq` 즉시 연결) + `UserConsentLog` 4~5건 생성 → N1 통합 이메일(접수확인 + 계정설정 링크) + 알림 발송.
- **UC-1b 계정 설정 (★ v1.3 신규)**: Applicant가 N1 이메일의 계정 설정 링크(`/setup-account/{token}`) 클릭 → 비밀번호 설정(복잡도 검증) → `signupCompleted=true` + `emailVerified=true` 전환 → 자동 로그인 → 대시보드 이동.
- **UC-2 담당자 배정**: 기본 정책은 *자동 라운드로빈 1차 + Admin 수동 재배정*. 접수 즉시 `assignedManager`가 세팅되고 해당 Manager에게 추가 알림. Manager 대시보드에는 신청자의 `signupCompleted=false`일 경우 "계정 설정 대기" 배지 표시.
- **UC-3 연락 및 정보 수집**: Manager가 전화·이메일로 연락. 상태를 `CONTACTING`으로 전환, 연락 기록(노트)을 추가.
- **UC-4 대리 신청서 작성**: Manager가 Concierge 요청 상세에서 "Create Application on behalf" 버튼 → 기존 신청 플로우를 **대상 신청자 계정의 스코프로 실행**하여 `Application` 레코드 생성 (owner = applicant, created_by = manager).
- **UC-5 신청자 인수인계**: Application이 생성되면 신청자에게 "귀하의 신청서가 준비되었습니다. 로그인 후 LOA에 서명해주세요" 메일 발송 → 신청자 본인 로그인 → LOA 전자서명 수행.
- **UC-6 완료 처리**: Application 상태가 `COMPLETED`로 전이되면 `ConciergeRequest.status = COMPLETED` 자동 동기화.

### 1.3 Scope

**In Scope**
- 랜딩페이지 CTA + 상세 모달 + 신청 폼 (+ **★ v1.3: 5종 동의 체크박스**)
- `ConciergeRequest` 엔티티 및 상태 머신
- `CONCIERGE_MANAGER` UserRole 신설, 기존 `/admin/**` 권한 구조에 편입
- 대리 신청서 생성 기능 (on-behalf-of)
- **★ v1.3: 신청 시점 즉시 APPLICANT 계정 생성 + 동의 스냅샷 저장 + 계정 설정 링크 이메일 발송**
- **★ v1.3: Account Setup 페이지 (`/setup-account/{token}`)** — 비밀번호 설정 + 이메일 자동 인증
- **★ v1.3: `UserConsentLog` 엔티티** — 동의/철회 이력 감사 보존
- 이메일 + 인앱 알림 (afterCommit 훅)
- 감사 로그 (`CONCIERGE_REQUEST_*`, `APPLICATION_CREATED_ON_BEHALF` 신규 액션 + **★ v1.3: `USER_CONSENT_RECORDED`, `ACCOUNT_SETUP_COMPLETED`**)
- Concierge Manager 대시보드 + 요청 상세 페이지 (**★ v1.3: "계정 설정 대기" 집계 + 재발송 버튼**)
- Admin 전체 대시보드 섹션

**Out of Scope (차기 단계 또는 외부)**
- Concierge Manager 간 내부 채팅/핸드오프
- 통화 녹음·자동 전사
- 고객 만족도 설문(NPS)
- Concierge Manager 성과 리포트/정산 계산
- 신청자 본인 인증 강화(NRIC, Singpass 연동) — 기존 이메일 인증 재사용
- 외부 PG사 전환(현 플랫폼은 수동 결제 확인 구조; Phase 2에서 별도 검토)

---

## 2. 화면 설계 (Wireframe Level)

### 2.1 랜딩페이지 변경점

위치: `blue-light-frontend/src/pages/LandingPage.tsx`. 기존 Hero Section(B) 직하 또는 Features Section(C) 직후에 **Concierge 섹션** 신규 추가.

```
┌──────────────────────────────────────────────────────────────────────┐
│  [Hero — 기존]                                                        │
│  Electrical Installation Licences, simplified.                       │
│  [ Get Started ]  [ Learn More ↓ ]                                   │
└──────────────────────────────────────────────────────────────────────┘

  ── 신규 Concierge Section (slate-50 배경, 좌우 2-col 그리드) ──
┌──────────────────────────────────────────────────────────────────────┐
│  badge: "White-Glove Service"                                         │
│  H2: Too busy to handle licensing yourself?                          │
│  Let our team take over.                                             │
│                                                                      │
│  ▢ "LicenseKaki offers a White-Glove Licensing Service, where our   │
│     team personally manages your entire electrical licensing        │
│     process—from submission to approval."                            │
│                                                                      │
│  bullets:                                                            │
│   ✓ Dedicated Concierge Manager assigned to you                      │
│   ✓ We collect info, prepare docs, and submit on your behalf        │
│   ✓ You only sign the final LOA (required by Singapore law)          │
│                                                                      │
│  [ Request Concierge Service → ]   ← 신규 CTA (primary, lg)          │
│  fine print: Service fee starts from S$--- · [see pricing]          │
└──────────────────────────────────────────────────────────────────────┘
```

**CTA 동작**: 클릭 시 `/concierge/request` 모달 페이지 오픈 (라우터 모달 또는 별도 풀페이지 — 모바일에서 풀페이지가 컨버전 유리).

### 2.2 Concierge 신청 모달/페이지 (`/concierge/request`, ★ v1.3 재설계)

```
┌──────────────────────────────────────────────────────────────────────┐
│  ×                                                                    │
│  ┌─ 좌측(스크롤 가능, 서비스 설명) ─┬─ 우측(sticky, 신청 폼) ─┐     │
│  │                                  │                                │
│  │  H2 Kaki Concierge Service       │  H3 Request this service       │
│  │  "Our team personally manages    │                                │
│  │   your entire electrical         │  Service Fee: S$ 500.00 (가정)│
│  │   licensing process…"            │  (결제 방식: PayNow / 카드)    │
│  │                                  │                                │
│  │  What we do:                     │  ─── 입력 폼 ───               │
│  │   1. Initial consultation call   │  [ Full Name *        ]        │
│  │   2. Document collection         │  [ Email *            ]        │
│  │   3. Application drafting        │  [ Mobile (+65) *     ]        │
│  │   4. LEW coordination            │  [ Memo (optional)    ]        │
│  │   5. Payment & issuance handling │                                │
│  │                                  │  ─── Consent (v1.3) ───        │
│  │  SLA: First contact within       │   ▢ Agree to ALL (편의)        │
│  │   24 hours                       │   ─────────────                │
│  │                                  │   ▢ 1. PDPA — 개인정보 수집·   │
│  │  Your account                    │      이용 동의 [약관 링크] *   │
│  │   We'll create a LicenseKaki     │   ▢ 2. ToS — 서비스 이용약관   │
│  │   account for you automatically. │      v{termsVersion} 동의 *    │
│  │   You'll receive a setup link    │   ▢ 3. Sign-up — 자동 회원가입 │
│  │   via email.                     │      동의 (LicenseKaki 계정)  *│
│  │                                  │   ▢ 4. Delegation — Concierge  │
│  │  Not included:                   │      대행 위임 동의 *           │
│  │   - EMA fees                     │   ─────────────                │
│  │   - LOA signing (you must sign)  │   ▢ 5. Marketing — 마케팅 수신 │
│  │                                  │      (선택)                    │
│  │  FAQ (expandable) ………            │                                │
│  │                                  │  [ Pay & Submit ]  (Phase 2)   │
│  │                                  │  [ Submit Request ] (Phase 1)  │
│  │                                  │  ↑ 필수 4개 모두 체크 시만 활성│
│  │                                  │                                │
│  │                                  │  fine: We'll email you a       │
│  │                                  │  setup link. Contact within    │
│  │                                  │  24 hours.                     │
│  └──────────────────────────────────┴────────────────────────────────┘
```

**프론트 검증**:
- Email: RFC 5322 형식 + (Phase 2) 실시간 `check-email` 호출로 기존 회원 여부 사전 체크
- Mobile: 싱가포르 번호 (E.164: `+65` prefix 허용, 8자리 로컬 허용)
- Memo: 500자 제한, XSS-safe 렌더링 (기존 B-2 XSS 방어 준용)
- **★ v1.3: 동의 체크박스 5개**
  - 필수 4개: ① PDPA ② ToS ③ 회원가입 ④ 대행 위임
  - 선택 1개: ⑤ 마케팅 수신
  - 각 약관은 **별도 링크(새 창)** 로 전문 열람 가능
  - "전체 동의" 체크박스는 편의 기능(선택 1개 포함 모두 체크, 사용자가 개별로 해제 가능)
  - 제출 버튼은 필수 4개가 모두 `true`일 때만 활성화
- **★ v1.3: 실시간 이메일 중복/권한 체크 (Phase 2 UX 개선)**
  - 이메일 입력 blur 시 `GET /api/public/concierge/check-email?email=...` 호출
  - 응답: `{exists, role, accountStatus}` → UI에 안내 문구 표시
    - `exists=false` → "We'll create a new account for you."
    - `role=APPLICANT` → "You already have a LicenseKaki account. We'll link this request to it."
    - `role=LEW|ADMIN|SYSTEM_ADMIN|SLD_MANAGER|CONCIERGE_MANAGER` → "This email belongs to a staff account. Please contact support." + 제출 비활성
  - Phase 1에서는 서버 검증만으로도 동작(UX는 Phase 2)

**동의 저장 필드 맵핑**:

| 동의 항목 | 필수 | 저장 필드 (User) | 감사 로그 (UserConsentLog) |
|----------|------|-----------------|--------------------------|
| PDPA | ✅ | `pdpaConsentAt` | `consentType=PDPA`, `documentVersion` |
| ToS | ✅ | `termsAgreedAt`, `termsVersion` | `consentType=TERMS`, `documentVersion` |
| 회원가입 | ✅ | `signupConsentAt`, `signupSource=CONCIERGE_REQUEST` | `consentType=SIGNUP`, `sourceContext=CONCIERGE_REQUEST` |
| 대행 위임 | ✅ | (ConciergeRequest.`delegationConsentAt`) | `consentType=DELEGATION` |
| 마케팅 | ⬜ | `marketingOptIn`, `marketingOptInAt` | `consentType=MARKETING` (opt-in인 경우만) |

### 2.3 제출 완료 화면 (`/concierge/request/success`, ★ v1.3 강화)

```
  ┌──────────────────────────────────────────────────┐
  │  ✓  Request received + Account created            │
  │                                                   │
  │  Thanks, {firstName}. Your Concierge request     │
  │  #C-2026-0001 has been received.                 │
  │                                                   │
  │  ┌─ Your account ─────────────────────────────┐  │
  │  │ We've created a LicenseKaki account for    │  │
  │  │ you at {email}. Please check your inbox    │  │
  │  │ and click the setup link to set your       │  │
  │  │ password.                                   │  │
  │  │                                             │  │
  │  │ ⚠  The setup link expires in 48 hours.     │  │
  │  └────────────────────────────────────────────┘  │
  │                                                   │
  │  Next steps:                                     │
  │  1. Check your inbox for the setup email.       │
  │  2. Set your password (link valid 48h).         │
  │  3. A Concierge Manager will call you within    │
  │     24 hours at {phone}.                        │
  │                                                   │
  │  Didn't get the email?                           │
  │  [ Resend setup email ]   [ Back to Home ]       │
  └──────────────────────────────────────────────────┘
```

**동작**:
- "Resend setup email" 버튼은 rate-limited (5분당 1회, 이메일별)
- **기존 APPLICANT 이메일로 신청한 경우**에는 문구가 "We've linked this request to your existing LicenseKaki account. Please log in to track progress." + `[ Log in ]` CTA로 대체

### 2.4 Concierge Manager 대시보드 (`/concierge-manager/dashboard`)

기존 AdminDashboardPage 패턴 재사용. 사이드바 1개 섹션 추가.

```
┌─ Sidebar ─┬─ Main ───────────────────────────────────────────────┐
│ Dashboard │  KPI 카드 4개                                          │
│ Requests  │  [ 접수 오늘 ]  [ 진행중 ]  [ 내 담당 ]  [ SLA 위반 ] │
│ Calendar  │                                                        │
│ Profile   │  ─ Filter bar ─                                        │
│           │  [status ▾] [assignee ▾] [date range] [search]          │
│           │                                                        │
│           │  ─ Table ─                                              │
│           │  # | 접수일 | 신청자 | 전화 | 상태 | 담당자 | 경과    │
│           │  --+-------+-------+------+------+--------+--------    │
│           │  C-2026-0010 | 10분전 | Tan WM | +65 9… | SUBMITTED  │
│           │                         → 내 담당으로 받기              │
└──────────┴──────────────────────────────────────────────────────┘
```

### 2.5 Concierge 요청 상세 (`/concierge-manager/requests/:id`)

```
┌──────────────────────────────────────────────────────────────────┐
│ ← Back     Request #C-2026-0010                                   │
│                                                                   │
│ ┌─ 좌측 1/3 (요청자 정보) ──┬─ 우측 2/3 (타임라인 + 액션) ───┐  │
│ │ Name:   Tan Wei Ming       │ Status: CONTACTING ▾           │  │
│ │ Email:  tan@example.sg     │ Assignee: @me (reassign)      │  │
│ │ Mobile: +65 9123 4567 📞   │                                │  │
│ │ Memo:   "Shophouse at …"   │ ── Timeline ──                 │  │
│ │                            │ • 10:02  Submitted  (system)   │  │
│ │ ── 결제 상태 ──            │ • 10:03  Auto-assigned to me   │  │
│ │ Paid S$500 · txn_xxx       │ • 10:30  Contacted via phone   │  │
│ │ [ Refund ]                 │          note: "Site visit…"   │  │
│ │                            │ • (next) Create application    │  │
│ │ ── 계정 ──                 │                                │  │
│ │ tan@example.sg             │ ── Action Bar ──               │  │
│ │ Account: Created (pwd unset)│ [ Add contact note ]          │  │
│ │ [ Resend setup email ]     │ [ Create Application on       │  │
│ │                            │   behalf of applicant → ]      │  │
│ │ ── Linked Application ──   │ [ Mark as Cancelled ]         │  │
│ │ (아직 없음 / #A-2026-2131) │                                │  │
│ │ [ Open Application → ]     │                                │  │
│ └────────────────────────────┴────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.6 On-Behalf-Of Application 작성 플로우

"Create Application on behalf" 버튼 → 기존 `/applicant/applications/new` 플로우와 **동일한 UI**를 재사용하되:
- 상단에 **노란 배너**: `You are filling this application on behalf of Tan Wei Ming (tan@example.sg)`
- "신청자 동의" 체크박스 추가: "I confirm the applicant has authorized me to submit on their behalf and the information provided is accurate"
- 제출 시 ownership = 대상 APPLICANT, created_by/updated_by = Manager
- LOA 단계는 **불가**: LOA 서명은 신청자 본인 로그인 후 또는 §2.7의 보조 경로(A/B)로 수집됨(배너로 안내)

### 2.7 LOA 서명 수집 섹션 (★ v1.2 신규)

**위치**: Concierge 요청 상세 페이지(`/concierge-manager/requests/:id`) 우측 액션 영역. Application이 생성되고 LOA PDF가 생성된 시점부터 활성화. 상태가 `AWAITING_APPLICANT_LOA_SIGN`인 동안에만 표시.

**탭 구조 (3-탭)**:

```
┌─ LOA Signature Collection ─────────────────────────────────────────┐
│                                                                    │
│  [ ① Direct sign request ] [ ② Upload signature ] [ ③ Remote link]│
│  ─────────────────────────────────────────────────────────────────│
│                                                                    │
│  ── Tab 1: Direct sign request (default, 원칙 경로) ──             │
│  Status: Pending applicant signature since 2026-04-19 14:32        │
│  [ Resend LOA signing email to tan@example.sg ]                    │
│  Last reminder sent: 2026-04-19 18:00 (in 24h auto-reminder cycle) │
│                                                                    │
│  ── Tab 2: Upload signature (경로 A) ──                             │
│  Use this when the applicant sent a handwritten signature image    │
│  via email/WhatsApp/in-person.                                     │
│                                                                    │
│  Signature file *      [ Choose file ]  PNG/JPG, max 2MB          │
│  Source *              ( ) Email  ( ) WhatsApp/SMS                 │
│                        ( ) In-person  ( ) Other                    │
│  Memo (optional)       [ e.g. "Received via email on 2026-04-19" ] │
│                                                                    │
│  [✓] I confirm I received this signature directly from the         │
│      applicant and have not altered it.   ← 필수                   │
│                                                                    │
│  [ Embed signature & finalize LOA → ]                              │
│                                                                    │
│  ── Tab 3: Remote signing link (경로 B, Phase 2) ──                │
│  Generate a single-use link the applicant can open without         │
│  logging in. Token expires in 48 hours.                            │
│                                                                    │
│  Delivery method *     ( ) Email   ( ) SMS   ( ) QR only           │
│  Recipient             [ tan@example.sg ] (auto-filled)            │
│                                                                    │
│  [ Generate & send link ]                                          │
│                                                                    │
│  ── After generation ──                                            │
│  Token: a3f1-... (expires 2026-04-21 14:32)                        │
│  URL:   https://licensekaki.sg/sign/a3f1-...   [ Copy ]            │
│  ┌──────────────────┐                                               │
│  │  ▓▓▓ QR PNG ▓▓▓ │   ← 대면 상담 시 모바일 스캔 유도             │
│  │  ▓▓▓▓▓▓▓▓▓▓▓▓▓ │                                                │
│  └──────────────────┘                                               │
│  Sent to: tan@example.sg via Email at 14:35                        │
│  [ Revoke token ]  [ Resend ]                                      │
└────────────────────────────────────────────────────────────────────┘
```

**탭별 동작 요약**:

| 탭 | 사용 시점 | 핵심 동작 | 필수 입력 |
|----|----------|----------|----------|
| ① Direct sign request | 신청자가 본인 로그인 가능 | N5 이메일 재발송 트리거만 | (없음) |
| ② Upload signature | 신청자가 자필 서명 파일을 외부 채널로 보낸 경우 | 파일 업로드 → `embedSignatureIntoPdf()` 재사용 → `loa_signature_source=MANAGER_UPLOAD` | 파일, source, 확인 체크 |
| ③ Remote link (Phase 2) | 신청자가 로그인 어렵지만 모바일/이메일 응답 가능 | 1회성 토큰 생성 + 링크/QR 전달 | deliveryMethod |

**프론트 검증**:
- 탭 ②: 파일 확장자 PNG/JPG, 크기 ≤2MB, 확인 체크박스 미체크 시 제출 버튼 비활성
- 탭 ③: deliveryMethod=Email인 경우 recipient 자동 채움(편집 가능), SMS인 경우 모바일 번호 검증, QR only는 recipient 입력 불요
- 모든 탭: 현재 `loa_signature_source` 값이 이미 세팅되어 있다면(이미 서명 완료) "Already signed via {source} at {ts}" 읽기 전용 표시

### 2.8 원격 서명 페이지 (`/sign/{token}`, ★ v1.2 신규, Phase 2)

신청자가 이메일/SMS/QR로 받은 링크를 클릭하여 진입. **로그인 불필요**, 토큰만으로 인증.

```
┌──────────────────────────────────────────────────────────────────┐
│                  LicenseKaki Remote LOA Signing                   │
│                                                                    │
│  Step 1 / 3 — Verify your identity                                │
│                                                                    │
│  Please confirm you are the applicant:                            │
│  Name on file:    Tan W*** M***                                    │
│  Email last 4:    [ ____ ]   ← e.g. e.sg → "e.sg"                  │
│                                                                    │
│  (Phase 3+) Or enter the 6-digit code we sent to +65 9*** 4567:   │
│  [ _ _ _ _ _ _ ]                                                   │
│                                                                    │
│  [ Verify and continue → ]                                         │
└──────────────────────────────────────────────────────────────────┘

  ──── Step 2 ────
┌──────────────────────────────────────────────────────────────────┐
│  Review your Letter of Authorization (LOA)                        │
│  ┌──────────── PDF preview ────────────┐                          │
│  │  Embedded LOA PDF (signed-pending)   │                          │
│  └──────────────────────────────────────┘                          │
│  [ Download PDF ]                                                  │
│  [✓] I have read and agree to the terms above                      │
│  [ Continue → ]                                                    │
└──────────────────────────────────────────────────────────────────┘

  ──── Step 3 ────
┌──────────────────────────────────────────────────────────────────┐
│  Please draw your signature below                                 │
│  ┌────────────────────────────────────────┐                       │
│  │            [ SignaturePad area ]         │                      │
│  └────────────────────────────────────────┘                       │
│  [ Clear ]      [ Submit signature → ]                             │
│                                                                    │
│  By submitting, you confirm: signing voluntarily, IP and device   │
│  details will be logged for audit, this signature has the same    │
│  legal weight as a handwritten signature under Singapore ETA.     │
└──────────────────────────────────────────────────────────────────┘
```

**보안 표시**:
- HTTPS lock 아이콘과 함께 도메인 확인 안내
- Token expiry countdown (예: "This link expires in 47h 32m")
- 5회 본인확인 실패 시 "Token locked. Please contact your Concierge Manager." 표시 (재발급 안내)

### 2.9 Account Setup 페이지 (`/setup-account/{token}`, ★ v1.3 신규)

**목적**: 컨시어지 신청으로 자동 생성된 APPLICANT 계정의 비밀번호 설정 + 이메일 자동 인증.

```
┌──────────────────────────────────────────────────────────────────┐
│                LicenseKaki — Set up your account                  │
│                                                                    │
│  Hi {maskedName},                                                  │
│  You received this link because you requested Kaki Concierge      │
│  Service on {submitDate}.                                          │
│                                                                    │
│  Account email: {email}  (verified via this link)                 │
│  Token expires: in 47h 32m                                        │
│                                                                    │
│  ─── Set your password ───                                        │
│  New password *           [ ________________ ]                     │
│  Confirm password *       [ ________________ ]                     │
│                                                                    │
│  Password must:                                                    │
│   ✓ At least 8 characters                                         │
│   ✓ Include upper + lower case                                    │
│   ✓ Include a number                                              │
│   ✓ Include a symbol (!@#$...)                                    │
│                                                                    │
│  [ Set password & sign in → ]                                     │
│                                                                    │
│  Having trouble? Contact your Concierge Manager or reply to       │
│  the setup email.                                                 │
└──────────────────────────────────────────────────────────────────┘
```

**동작 흐름**:
1. 페이지 진입 시 `GET /api/public/account-setup/{token}` 호출 → 토큰 유효성 + 마스킹된 사용자 정보 반환
2. 유효하지 않은 토큰 → 410 Gone → "This link has expired. Ask your Concierge Manager to resend." 화면
3. 비밀번호 입력 → 프론트 복잡도 검증 → `POST /api/public/account-setup/{token}` 제출
4. 서버: 토큰 재검증 → `passwordHash` 업데이트 → `emailVerified=true`, `signupCompleted=true` → 감사 로그 `ACCOUNT_SETUP_COMPLETED` → 자동 로그인(JWT 발급)
5. 성공 시 `/applicant/applications` 또는 `/concierge/requests/{publicCode}` 진행 상황 페이지로 리다이렉트

**재사용 정책**:
- 본 토큰은 기존 `PasswordResetToken` 인프라를 **재활용하되**, 생성 경로(`source=CONCIERGE_ACCOUNT_SETUP`)를 구분하여 로그에 식별 가능하게 기록.
- 기존 비밀번호 재설정과 달리 **이메일 인증도 함께 처리**하므로 `emailVerified=true` 전환 로직 포함.

**만료·재발송 정책**:
- 기본 48시간 만료
- Manager가 요청 상세 페이지에서 "Resend setup email" 클릭 시 토큰 재생성(기존 토큰 invalidate)
- 신청자 본인이 success 페이지의 "Resend setup email"로도 재발송 가능(rate limit 5분당 1회)

**보안**:
- 토큰은 URL path에만 노출 (Referer 유출 방지, 기존 토큰 인프라 정책 준용)
- 5회 잘못된 비밀번호 입력 시 토큰 잠금(rate-limit, O-16 패턴 재사용)

---

## 3. 데이터 모델

### 3.1 신규 엔티티: `ConciergeRequest`

```java
@Entity
@Table(name = "concierge_requests")
@SQLDelete(sql = "UPDATE concierge_requests SET deleted_at = NOW() WHERE concierge_request_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class ConciergeRequest extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "concierge_request_seq")
    private Long conciergeRequestSeq;

    // 공개 식별자 (URL 노출용, C-YYYY-NNNN 포맷)
    @Column(name = "public_code", nullable = false, unique = true, length = 20)
    private String publicCode;

    // 제출 폼 필드
    @Column(name = "submitter_name", nullable = false, length = 100)
    private String submitterName;
    @Column(name = "submitter_email", nullable = false, length = 100)
    private String submitterEmail;    // not unique — 같은 이메일 재신청 허용
    @Column(name = "submitter_phone", nullable = false, length = 20)
    private String submitterPhone;
    @Column(name = "memo", length = 1000)
    private String memo;

    // 상태 머신
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ConciergeRequestStatus status;

    // 연결 관계 (모두 nullable — 접수 시에는 없을 수 있음)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicant_user_seq")
    private User applicantUser;           // 자동 생성된 APPLICANT 계정

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_manager_seq")
    private User assignedManager;         // CONCIERGE_MANAGER 소지자

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq")
    private Application application;       // 대리 작성 완료 후 FK

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_seq")
    private Payment payment;              // Phase 2 결제 통합 후

    // 동의 스냅샷 (ConciergeRequest 자체 스냅샷 — User 엔티티에도 병행 기록)
    @Column(name = "pdpa_consent_at", nullable = false)
    private LocalDateTime pdpaConsentAt;
    @Column(name = "account_auto_create_consent_at", nullable = false)
    private LocalDateTime accountAutoCreateConsentAt;

    // ★ v1.3: 대행 위임 동의 시점 (Concierge 고유 동의 — User 엔티티에 저장할 수 없어 여기에 보관)
    @Column(name = "delegation_consent_at", nullable = false)
    private LocalDateTime delegationConsentAt;

    // SLA 추적
    @Column(name = "first_contact_at")
    private LocalDateTime firstContactAt;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ... 도메인 메서드 (assignTo, markContacted, linkApplication, cancel, complete)
}
```

**인덱스 제안**:
- `idx_concierge_status_assigned` on (`status`, `assigned_manager_seq`) — 대시보드 필터링
- `idx_concierge_submitter_email` on (`submitter_email`) — 중복/재접수 조회
- `idx_concierge_created_at_desc` on (`created_at`) — 최근순 리스팅
- `uk_concierge_public_code` on (`public_code`)

### 3.2 상태 Enum: `ConciergeRequestStatus`

```java
public enum ConciergeRequestStatus {
    SUBMITTED,                      // 폼 제출 완료 (24h SLA 카운트 시작)
    ASSIGNED,                       // Manager 배정됨
    CONTACTING,                     // 연락 시작 (firstContactAt 기록)
    APPLICATION_CREATED,            // 대리 작성 Application 생성됨
    AWAITING_APPLICANT_LOA_SIGN,    // ★ 신청자 LOA 서명 대기 (본인 액션 필수 지점 1)
    AWAITING_LICENCE_PAYMENT,       // ★ 라이선스 수수료 결제 대기 (본인 액션 필수 지점 2)
    IN_PROGRESS,                    // Application이 실제 라이선스 프로세스 진행 중
    COMPLETED,                      // 라이선스 발급 완료 (Application.COMPLETED에 동기화)
    CANCELLED                       // 취소/환불
}
```

**AWAITING_* 상태의 의미**: 프로세스가 Manager 손을 떠나 **신청자 본인 개입을 기다리는 구간**임을 대시보드/리마인더 스케줄러가 명확히 인지하도록 별도 상태로 분리. 이 상태에서는 Manager가 "재촉 이메일 발송" 외에 할 수 있는 행동이 없음을 UI에서도 명시.

### 3.3 부가 엔티티: `ConciergeNote` (연락 기록)

```java
@Entity
@Table(name = "concierge_notes")
public class ConciergeNote extends BaseEntity {
    @Id @GeneratedValue private Long noteSeq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concierge_request_seq", nullable = false)
    private ConciergeRequest conciergeRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_seq", nullable = false)
    private User author;                 // CONCIERGE_MANAGER

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20)
    private NoteChannel channel;         // PHONE / EMAIL / WHATSAPP / IN_PERSON / OTHER

    @Column(name = "content", nullable = false, length = 2000)
    private String content;              // XSS-safe 렌더링
}
```

### 3.4 `UserRole` 확장

```java
public enum UserRole {
    APPLICANT,
    LEW,
    ADMIN,
    SYSTEM_ADMIN,
    SLD_MANAGER,
    CONCIERGE_MANAGER    // 신규
}
```

### 3.4b `User` 엔티티 확장 (★ v1.3 → v1.4 재정의)

컨시어지 신청 경로로 자동 생성된 계정을 구분하고 동의 이력을 보존하기 위해 컬럼을 추가한다. **v1.4에서는 `signupCompleted` boolean을 명시적 `UserStatus` enum으로 대체**하여 suspend/delete 등 추가 상태를 일관되게 관리한다.

```java
// domain/user/User.java (수정)

// 가입 경로 식별
@Enumerated(EnumType.STRING)
@Column(name = "signup_source", nullable = false, length = 30)
private SignupSource signupSource = SignupSource.DIRECT_SIGNUP;

// ★ v1.4: 계정 상태 (v1.3의 signupCompleted boolean 대체)
@Enumerated(EnumType.STRING)
@Column(name = "status", nullable = false, length = 30)
private UserStatus status = UserStatus.PENDING_ACTIVATION;

// ★ v1.4: 최초 활성화 시점 — 불변 (컴플라이언스 증적)
@Column(name = "activated_at", updatable = false)
private LocalDateTime activatedAt;

// ★ v1.4: 첫 로그인 성공 시점 — 분석/대시보드용
@Column(name = "first_logged_in_at")
private LocalDateTime firstLoggedInAt;

// v1.3 동의 스냅샷 (UserConsentLog에도 병행 기록)
@Column(name = "signup_consent_at")
private LocalDateTime signupConsentAt;

@Column(name = "terms_version", length = 20)
private String termsVersion;

@Column(name = "marketing_opt_in", nullable = false)
private boolean marketingOptIn = false;

@Column(name = "marketing_opt_in_at")
private LocalDateTime marketingOptInAt;

// 기존 필드 유지
// pdpaConsentAt (기존)
// termsAgreedAt (기존 또는 신규)
// emailVerified (기존 — v1.4에서도 유지: UserStatus.ACTIVE 전환과는 별도로 이메일 인증 성공 여부 기록)
// deletedAt (기존 soft delete)
```

```java
public enum SignupSource {
    DIRECT_SIGNUP,        // 기존 일반 가입
    CONCIERGE_REQUEST,    // v1.3: 컨시어지 신청으로 자동 생성
    ADMIN_INVITE          // Admin이 수동 초대로 생성 (향후 확장 여지)
}

// ★ v1.4 신규
public enum UserStatus {
    PENDING_ACTIVATION,   // 자동 생성되었으나 아직 최초 로그인 전 — 로그인 불가
    ACTIVE,               // 정상 활성화 — 모든 기능 접근 가능
    SUSPENDED,            // Admin이 일시 정지 — 로그인 차단, 데이터는 보존
    DELETED               // 사용자 탈퇴 또는 Admin 삭제 — soft delete와 병행 (§3.4b-2 참조)
}
```

**`UserStatus` 상태별 동작**:

| status | 로그인 | 설명 | 전이 트리거 |
|--------|--------|------|------------|
| `PENDING_ACTIVATION` (CONCIERGE_REQUEST 계정 초기값) | ❌ 불가 (분기 응답) | 자동 생성 후 최초 로그인 대기 | 최초 로그인 성공(비밀번호 설정 + 이메일 인증) → `ACTIVE` |
| `ACTIVE` (DIRECT_SIGNUP 계정 초기값 + 전환 완료) | ✅ 가능 | 정상 APPLICANT로 모든 기능 접근 | Admin 조치 → `SUSPENDED` / 탈퇴 → `DELETED` |
| `SUSPENDED` | ❌ 차단 | Admin이 일시 정지(정책 위반, 의심 활동 등) | Admin 해제 → `ACTIVE` |
| `DELETED` | ❌ 차단 | 사용자 탈퇴 또는 Admin 삭제 | (terminal, 복구는 별도 워크플로) |

- **로그인 API(`POST /api/auth/login`) 분기 (★ v1.5 재작성, H-1 반영)**:
  - **1단계**: 이메일 조회 → 존재하지 않으면 `DUMMY_BCRYPT_HASH`로 dummy verify 수행 후 401 `INVALID_CREDENTIALS`
  - **2단계**: 이메일이 존재하면 실제 `passwordHash`로 BCrypt.verify → **실패 시** 401 `INVALID_CREDENTIALS` (status 분기 없음)
  - **3단계 (비밀번호 검증 성공 후에만 실행되는 status 분기)**:
    - `ACTIVE` → 200 + JWT 발급
    - `PENDING_ACTIVATION` → 401 `ACCOUNT_PENDING_ACTIVATION` + `activationFlow=EMAIL_LINK`
    - `SUSPENDED` → 403 `ACCOUNT_SUSPENDED`
    - `DELETED` → **401 `INVALID_CREDENTIALS`** (v1.4의 404 `ACCOUNT_NOT_FOUND`는 존재 감춤을 완전히 달성하지 못하므로 v1.5에서 INVALID_CREDENTIALS로 통일)
  - 전체 구현 의사코드는 §4.4 참조
- **Manager 대시보드**: v1.3 "계정 설정 대기" 배지 의미론 유지하되, 근거를 `status=PENDING_ACTIVATION`으로 교체. 집계 쿼리의 조건도 동일하게 변경.

### 3.4b-2 `UserStatus.DELETED` vs `deleted_at` 관계 (★ v1.4)

기존 LicenseKaki는 `@SQLDelete + @SQLRestriction` 기반 **soft delete**를 사용한다(`deleted_at IS NOT NULL`이면 기본 쿼리에서 자동 제외). v1.4에서 `UserStatus.DELETED`를 도입하지만 **기존 soft delete 메커니즘을 대체하지 않고 병행 추적**한다.

**원칙**:
- `deleted_at`은 **물리적 삭제 보류 마커**(데이터 보관 + 기본 쿼리 제외)로 그대로 유지
- `status=DELETED`는 **상태 머신의 terminal 상태**로서 "사용자 의사 또는 Admin 판단에 의한 종결"을 의미
- **soft delete 시 반드시 `status=DELETED`도 함께 세팅**(도메인 메서드 `User.softDelete()`에서 두 값을 원자적으로 업데이트)
- 조회 관점에서:
  - 일반 쿼리는 `deleted_at IS NULL`이 기본 적용되어 DELETED 계정이 보이지 않음 (기존 동작 유지)
  - 감사/복구 목적으로 명시적으로 `@SQLRestriction`을 우회하는 Repository 메서드(`findByEmailIncludingDeleted`)에서는 `status=DELETED + deleted_at IS NOT NULL`이 함께 매칭됨

**PENDING_ACTIVATION 자동 정리 정책 (★ v1.5 확정, O-22 해결)**:

| 경과 시간 (activatedAt=NULL 기준) | 자동 조치 | 구현 단계 |
|----------------------------------|----------|----------|
| 90일 미접속 | `status=PENDING_ACTIVATION → SUSPENDED` + 감사 로그 `ACCOUNT_AUTO_SUSPENDED_INACTIVE` + 이메일 알림 | Phase 3 (`ConciergePendingCleanupScheduler`) |
| 180일 미접속 | `status=SUSPENDED → DELETED` + `deleted_at=NOW()` + 감사 로그 `ACCOUNT_DELETED` | Phase 3 (동일 스케줄러) |

- Phase 1~2에서는 자동 정리 미구현 (데이터 축적만 허용, Admin이 수동 SUSPENDED/DELETED 가능)
- 정리 기준 시점은 `GREATEST(created_at, last_setup_token_issued_at)` — Manager가 재발송한 경우 카운트 리셋
- 스케줄러는 일 1회 (예: 매일 03:00 Asia/Singapore) 실행, 한 번에 최대 1000건씩 배치 처리

**Flyway 마이그레이션 (v1.3 → v1.4 전환)**:

```sql
-- V_NN__user_replace_signup_completed_with_status.sql

-- 1. status, activated_at, first_logged_in_at 컬럼 추가 (우선 nullable, 이후 NOT NULL 전환)
ALTER TABLE users
  ADD COLUMN status VARCHAR(30) NULL,
  ADD COLUMN activated_at DATETIME NULL,
  ADD COLUMN first_logged_in_at DATETIME NULL;

-- 2. 기존 데이터 backfill
--    - DIRECT_SIGNUP 또는 signup_completed=true → ACTIVE
--    - signup_completed=false (v1.3에서 CONCIERGE_REQUEST로 만들어졌으나 미설정) → PENDING_ACTIVATION
--    - deleted_at IS NOT NULL → DELETED (soft-deleted 계정도 status 일관성 확보)
UPDATE users
SET status = CASE
    WHEN deleted_at IS NOT NULL THEN 'DELETED'
    WHEN signup_completed = TRUE THEN 'ACTIVE'
    ELSE 'PENDING_ACTIVATION'
  END,
  activated_at = CASE
    WHEN signup_completed = TRUE THEN COALESCE(created_at, NOW())
    ELSE NULL
  END
WHERE status IS NULL;

-- 3. NOT NULL 제약 전환
ALTER TABLE users MODIFY COLUMN status VARCHAR(30) NOT NULL;

-- 4. 인덱스 (대시보드 필터링 + 장기 미활성화 정리 스케줄러용)
CREATE INDEX idx_user_status ON users (status);
CREATE INDEX idx_user_status_created ON users (status, created_at);

-- 5. (신규 배포 이후) signup_completed 컬럼 제거 — 별도 릴리스로 분리 권장
-- ALTER TABLE users DROP COLUMN signup_completed;
```

**`signup_completed` 컬럼 제거 시점 (2단계 릴리스 권장)**:

| 릴리스 | 작업 | 이유 |
|--------|------|------|
| R1 (v1.4 배포) | `status` 추가 + backfill + 애플리케이션 코드 `status` 기준으로 분기. `signup_completed` 컬럼은 **아직 유지**하되 애플리케이션은 읽기/쓰기 모두 하지 않음 | 롤백 안전성: 문제 발생 시 이전 버전으로 되돌려도 `signup_completed` 값이 그대로 있음 |
| R2 (v1.4 안정 후) | `DROP COLUMN signup_completed` | R1 이후 충분한 관측 기간(최소 2주 권장) 확보 후 제거 |

**검증 쿼리** (backfill 후 0건이어야 함):
```sql
SELECT COUNT(*) FROM users WHERE status IS NULL;
SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND activated_at IS NULL;
SELECT COUNT(*) FROM users WHERE status = 'PENDING_ACTIVATION' AND activated_at IS NOT NULL;
```

### 3.4c `SignupSource` enum 및 Application 링크 지표

Application 통계/대시보드에서 컨시어지 경유 가입 비율을 확인할 수 있도록 User.signupSource를 노출. 별도 FK/중간 테이블 없이 User 컬럼만으로 집계 가능.

### 3.5 `Application` 연결 방식

**결론**: 기존 `Application.user`(소유자)를 유지하고, **별도 FK 없이** `ConciergeRequest.application`(1:1) 단방향으로 연결한다.

**근거**:
- `Application`은 소유자 기준(user)으로 이미 권한/조회가 이뤄짐 — Concierge 경유 여부는 조회 로직에 영향을 주지 않아야 함
- 감사 로그와 BaseEntity.createdBy(Manager)로 대리 작성 사실을 추적 가능
- 신청자가 로그인하면 자기 계정의 Application이 자연스럽게 보임

**추가 컬럼 (Application)**:
```java
// via_concierge_request_seq: 이 Application이 Concierge 경유로 생성되었는지 플래그
@Column(name = "via_concierge_request_seq", updatable = false)
private Long viaConciergeRequestSeq;  // 읽기 전용 FK 힌트 (불변)
```
- 대시보드/감사 시 "Concierge를 통해 생성된 신청서"를 쉽게 필터링하기 위함.
- `updatable = false`로 최초 생성 시에만 기록, 이후 변경 금지.

**★ LOA 서명 출처 추적 컬럼 4종 (v1.2 신규)**:

```java
// 어떤 경로로 서명이 수집되었는지
@Enumerated(EnumType.STRING)
@Column(name = "loa_signature_source", length = 30)
private LoaSignatureSource loaSignatureSource;  // null이면 미서명

// Manager 대리 업로드 경로(A)에서 업로드한 사용자
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "loa_signature_uploaded_by")
private User loaSignatureUploadedBy;             // 직접 서명/원격 링크일 경우 null

@Column(name = "loa_signature_uploaded_at")
private LocalDateTime loaSignatureUploadedAt;

@Column(name = "loa_signature_source_memo", length = 500)
private String loaSignatureSourceMemo;            // Manager 업로드 시 수령 채널/메모
```

```java
public enum LoaSignatureSource {
    APPLICANT_DIRECT,    // 신청자 본인 로그인 후 SignaturePad 서명 (원칙)
    MANAGER_UPLOAD,      // Manager가 신청자 제공 서명 파일 대리 업로드 (경로 A)
    REMOTE_LINK          // 1회성 토큰 링크/QR로 신청자 비로그인 서명 (경로 B)
}
```

**불변성 정책**:
- 기존 LOA 스냅샷 4개 컬럼(`@Column(updatable=false)`: 신청자 신원·LEW 정보)은 v1.2에서도 **불변 유지**. 서명 출처는 신원 정보와 무관한 메타데이터이므로 `updatable=false`를 적용하지 않는다(예: 잘못된 source가 기록된 경우 Admin이 정정 가능해야 함).
- 단, `loa_signature_url`(서명 PNG의 storage URL)은 일단 세팅되면 변경 금지(기존 정책 유지). 즉 "어떤 서명을 적용했는가"는 불변이며 "어떻게 받았는가"의 라벨만 가변.

**감사 로그 보강**:
- Manager 업로드(A): `LOA_SIGNATURE_UPLOADED_BY_MANAGER` 액션, metadata={source, memo, fileSize, originalFilename}
- 원격 링크(B): `LOA_SIGNATURE_VIA_REMOTE_LINK` 액션, metadata={tokenSeq, ip, userAgent}
- 직접 서명: 기존 `LOA_SIGNED` 액션 유지

### 3.6 `NotificationType` 확장

```java
// 기존에 CONCIERGE_* 추가
CONCIERGE_REQUEST_SUBMITTED,            // Admin/Manager 수신
CONCIERGE_REQUEST_ASSIGNED,             // Manager 수신
CONCIERGE_APPLICATION_CREATED,          // Applicant 수신 (LOA 서명 요청)
CONCIERGE_REQUEST_COMPLETED,            // Applicant 수신
CONCIERGE_REQUEST_CANCELLED,            // Applicant + Manager 수신
// v1.2 신규
LOA_REMOTE_SIGN_LINK_SENT,              // Applicant 수신 — 원격 서명 링크 발송
LOA_SIGNATURE_UPLOAD_CONFIRMATION,      // Applicant 수신 — Manager가 대리 업로드한 서명 확인 메일
// v1.3 신규 — 통합 가입 플로우
CONCIERGE_REQUEST_RECEIVED_WITH_SETUP,  // Applicant 수신 — 접수확인 + 계정 설정 링크 (N1, v1.4에서 명칭 유지하되 본문 재설계)
ACCOUNT_SETUP_REMINDER,                 // Applicant 수신 — 미설정/미활성화 리마인더 (N1-R)
CONCIERGE_EXISTING_ACCOUNT_LINKED,      // Applicant 수신 — 기존 계정에 요청 연결 안내
// v1.4 신규 — 활성화 플로우
ACCOUNT_ACTIVATION_LINK_SENT            // Applicant 수신 — 로그인 시도 시 활성화 인증 링크 재발송 (N-Activation)
```

### 3.7 `AuditAction` 확장

```java
CONCIERGE_REQUEST_SUBMITTED,
CONCIERGE_REQUEST_ASSIGNED,
CONCIERGE_REQUEST_REASSIGNED,
CONCIERGE_REQUEST_CANCELLED,
CONCIERGE_NOTE_ADDED,
CONCIERGE_ACCOUNT_AUTO_CREATED,
APPLICATION_CREATED_ON_BEHALF,          // 대리 작성 추적 전용
// v1.2 신규
LOA_SIGNATURE_UPLOADED_BY_MANAGER,      // 경로 A
LOA_REMOTE_SIGN_TOKEN_ISSUED,           // 경로 B 토큰 생성
LOA_REMOTE_SIGN_TOKEN_REVOKED,          // 경로 B 토큰 폐기
LOA_REMOTE_SIGN_VERIFY_FAILED,          // 경로 B 본인확인 실패 (rate limit 추적)
LOA_SIGNATURE_VIA_REMOTE_LINK,          // 경로 B 서명 완료
// v1.3 신규 — 통합 가입 플로우
USER_CONSENT_RECORDED,                  // 동의 항목 기록 (consentType별 1건씩)
USER_CONSENT_WITHDRAWN,                 // 동의 철회
CONCIERGE_EXISTING_USER_LINKED,         // 기존 APPLICANT 계정에 새 ConciergeRequest 연결
CONCIERGE_STAFF_EMAIL_BLOCKED,          // LEW/ADMIN 이메일로 신청 시도 차단
ACCOUNT_SETUP_TOKEN_ISSUED,             // 계정 설정 토큰 발급 (자동 + 재발송)
ACCOUNT_SETUP_TOKEN_RESENT,             // Manager/신청자가 재발송 트리거
ACCOUNT_SETUP_COMPLETED,                // 비밀번호 설정 + 이메일 인증 완료
// v1.4 신규 — 계정 상태 전이
ACCOUNT_ACTIVATED,                      // status PENDING_ACTIVATION → ACTIVE 전이 (최초 로그인 성공)
ACCOUNT_ACTIVATION_REQUEST_SENT,        // /auth/login/request-activation 성공 발송 (내부 로그 전용, 외부 응답은 유출 금지)
ACCOUNT_ACTIVATION_REQUEST_NO_MATCH,    // /auth/login/request-activation 호출했으나 발송 대상 아님 (enum scan 탐지용)
ACCOUNT_SUSPENDED,                      // status ACTIVE → SUSPENDED (Admin 조치)
ACCOUNT_UNSUSPENDED,                    // status SUSPENDED → ACTIVE (Admin 해제)
ACCOUNT_AUTO_SUSPENDED_INACTIVE,        // PENDING_ACTIVATION 180일 미활성 → 자동 SUSPENDED (O-22)
ACCOUNT_DELETED                         // status → DELETED (탈퇴 또는 Admin 삭제)
```

### 3.10 신규 엔티티: `LoaSigningToken` (★ v1.2, 경로 B 전용)

원격 서명 링크/QR 경로에서 사용하는 **1회성 서명 토큰**을 관리한다.

```java
@Entity
@Table(name = "loa_signing_tokens",
    indexes = {
        @Index(name = "uk_loa_token_uuid", columnList = "token_uuid", unique = true),
        @Index(name = "idx_loa_token_application", columnList = "application_seq"),
        @Index(name = "idx_loa_token_expires", columnList = "expires_at")
    })
@SQLDelete(sql = "UPDATE loa_signing_tokens SET deleted_at = NOW() WHERE token_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class LoaSigningToken extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_seq")
    private Long tokenSeq;

    // URL path에 노출되는 UUID (보안: URL path만 사용, query string ❌)
    @Column(name = "token_uuid", nullable = false, unique = true, length = 36, updatable = false)
    private String tokenUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_seq", nullable = false, updatable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concierge_request_seq", nullable = false, updatable = false)
    private ConciergeRequest conciergeRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_seq", nullable = false, updatable = false)
    private User createdByUser;       // 토큰 발급한 Manager

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, length = 20)
    private DeliveryMethod deliveryMethod;   // EMAIL / SMS / QR_ONLY

    @Column(name = "delivery_target", length = 100)
    private String deliveryTarget;            // 발송 대상(이메일/모바일) — QR_ONLY는 null

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;          // 기본 createdAt + 48h

    @Column(name = "used_at")
    private LocalDateTime usedAt;             // 사용 완료 시점 — 1회성

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;               // 본인확인 실패 횟수

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;           // 5회 실패 시 잠금

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;          // Manager가 수동 폐기

    // 서명 시점 감사 정보
    @Column(name = "signed_from_ip", length = 45)
    private String signedFromIp;              // IPv4/IPv6
    @Column(name = "signed_user_agent", length = 500)
    private String signedUserAgent;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum DeliveryMethod { EMAIL, SMS, QR_ONLY }

    // 도메인 메서드
    public boolean isUsable() {
        return usedAt == null
            && revokedAt == null
            && lockedAt == null
            && expiresAt.isAfter(LocalDateTime.now());
    }

    public void recordFailedAttempt() {
        this.failedAttempts++;
        if (this.failedAttempts >= 5) this.lockedAt = LocalDateTime.now();
    }

    public void markUsed(String ip, String ua) {
        if (!isUsable()) throw new IllegalStateException("TOKEN_NOT_USABLE");
        this.usedAt = LocalDateTime.now();
        this.signedFromIp = ip;
        this.signedUserAgent = ua;
    }

    public void revoke() {
        if (usedAt != null) throw new IllegalStateException("TOKEN_ALREADY_USED");
        this.revokedAt = LocalDateTime.now();
    }
}
```

**불변식 (테스트로 보장)**:
1. `tokenUuid`는 UUIDv4, DB 유니크 인덱스로 보장.
2. `usedAt != null`이면 같은 토큰으로 재인증/재서명 불가.
3. `failedAttempts >= 5` → `lockedAt` 자동 세팅, Manager 재발급 필요.
4. `expiresAt < NOW()`이면 `isUsable() = false`. 만료 토큰은 별도 cleanup 잡으로 30일 후 soft delete.
5. 같은 Application에 대해 미사용 활성 토큰이 이미 존재하면 새 발급 시 기존 토큰 자동 revoke (정책 옵션 — 운영 검토 필요, AC-19 참조).

**Flyway 마이그레이션**: `V_NN__loa_signing_tokens.sql` 신규.

**Rate limit 보강**:
- 같은 IP에서 1시간 내 10회 이상 토큰 진입(`GET /api/public/loa-sign/{token}`)은 WAF/Bucket4j로 차단 (스캐닝 방지).
- 토큰 발급 자체도 Manager당 1시간 50회 상한.

### 3.11 신규 엔티티: `UserConsentLog` (★ v1.3)

동의/철회 이력을 **전수 보존**하여 약관 개정·분쟁 시 "이 사람이 언제 어떤 버전의 약관에 동의했는가"를 증명하는 감사 테이블. **Soft delete 적용하지 않음**(PDPA 감사 증적 무결성 우선).

```java
@Entity
@Table(name = "user_consent_logs",
    indexes = {
        @Index(name = "idx_consent_user", columnList = "user_seq"),
        @Index(name = "idx_consent_type_user", columnList = "consent_type, user_seq"),
        @Index(name = "idx_consent_source", columnList = "source_context"),
        @Index(name = "idx_consent_consented_at", columnList = "consented_at")
    })
public class UserConsentLog extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_seq")
    private Long logSeq;

    // 동의 주체 (soft-deleted 사용자도 조회 가능하도록 FK만 유지, @ManyToOne은 lazy)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_seq", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 30, updatable = false)
    private ConsentType consentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_action", nullable = false, length = 20, updatable = false)
    private ConsentAction consentAction;

    // 동의한 시점의 약관/문서 버전 스냅샷
    @Column(name = "document_version", length = 20, updatable = false)
    private String documentVersion;

    // 동의 발생 맥락
    @Enumerated(EnumType.STRING)
    @Column(name = "source_context", nullable = false, length = 40, updatable = false)
    private ConsentSourceContext sourceContext;

    // 참조 엔티티 (선택) — 예: ConciergeRequest.seq
    @Column(name = "reference_type", length = 30, updatable = false)
    private String referenceType;

    @Column(name = "reference_seq", updatable = false)
    private Long referenceSeq;

    // 수집 환경 메타 (감사용)
    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 500, updatable = false)
    private String userAgent;

    @Column(name = "consented_at", nullable = false, updatable = false)
    private LocalDateTime consentedAt;

    public enum ConsentType {
        PDPA,            // 개인정보 수집·이용 동의
        TERMS,           // 서비스 이용약관
        SIGNUP,          // 회원가입 동의 (자동 생성)
        DELEGATION,      // Concierge 대행 위임
        MARKETING        // 마케팅 수신
    }

    public enum ConsentAction {
        AGREED,
        WITHDRAWN
    }

    public enum ConsentSourceContext {
        DIRECT_SIGNUP,          // 기존 /api/auth/register 가입 플로우
        CONCIERGE_REQUEST,      // ★ v1.3: 컨시어지 신청 폼
        PROFILE_UPDATE,         // 프로필 페이지에서 동의 변경
        MARKETING_UNSUBSCRIBE,  // 이메일 수신거부 링크
        ADMIN_INVITE            // 향후 확장
    }
}
```

**불변식 (테스트로 보장)**:
1. `consentType`, `consentAction`, `documentVersion`, `ipAddress`, `userAgent`, `consentedAt`, `referenceType`, `referenceSeq` 모두 `@Column(updatable=false)` — **한 번 기록되면 수정 불가**.
2. soft delete 없음 (`@SQLDelete`, `@SQLRestriction` 적용하지 않음) — 사용자가 anonymize 되더라도 동의 기록은 유지되어야 함(PDPA 감사 요건).
3. 마케팅 동의가 opt-out(`AGREED`만 기록)에서 opt-in(`AGREED`)으로 토글될 때는 **별도 row 2건**(`WITHDRAWN` 한 번 + `AGREED` 한 번)이 기록됨. 동일 row를 덮어쓰지 않음.
4. ConciergeRequest 제출 시 필수 4종(PDPA/TERMS/SIGNUP/DELEGATION) + 선택 1종(MARKETING — opt-in인 경우) = 최대 5건이 한 트랜잭션에 기록됨.

**조회 패턴**:
- "사용자 X의 PDPA 동의 현재 상태" → `findTopByUserAndConsentTypeOrderByConsentedAtDesc(user, PDPA)` → 최신 row의 action으로 판단
- "약관 v2.0 개정 후 재동의 필요한 사용자 목록" → `termsVersion != '2.0'` AND `consentType=TERMS` AND `consentAction=AGREED`인 최신 row 필터
- "특정 ConciergeRequest에 연결된 모든 동의" → `referenceType='CONCIERGE_REQUEST' AND referenceSeq=?`

**Flyway 마이그레이션**: `V_NN__user_consent_logs.sql` — 신규 테이블 생성. **기존 User의 PDPA 동의는 backfill**:

```sql
-- 기존 User 테이블의 pdpaConsentAt을 UserConsentLog에 backfill (감사 증적 보존)
INSERT INTO user_consent_logs
  (user_seq, consent_type, consent_action, document_version, source_context,
   ip_address, user_agent, consented_at, created_at, updated_at)
SELECT user_seq, 'PDPA', 'AGREED', '1.0', 'DIRECT_SIGNUP',
       NULL, 'backfill-v1.3', pdpa_consent_at, NOW(), NOW()
FROM users
WHERE pdpa_consent_at IS NOT NULL;
```

**유의**: backfill 데이터는 `ip_address=NULL`, `user_agent='backfill-v1.3'`으로 원본 데이터 부재를 명시. 법적 분쟁 시 backfill 항목은 "신청 당시 스냅샷이 아님"을 구분할 수 있게 표시.

---

### 3.12 신규 엔티티: `AccountSetupToken` (★ v1.5, H-3 + O-17 반영)

v1.3에서 "기존 `PasswordResetToken` 인프라 재활용" 방침이었으나, **v1.5에서 시도 제한 + 활성 토큰 단일 유지 요건이 추가되면서 독립 엔티티로 분리**한다. LoaSigningToken(§3.10)과 대칭 구조이되 활성화 전용 의미론을 갖는다.

```java
@Entity
@Table(name = "account_setup_tokens",
    indexes = {
        @Index(name = "uk_account_setup_token_uuid", columnList = "token_uuid", unique = true),
        @Index(name = "idx_account_setup_token_user", columnList = "user_seq"),
        @Index(name = "idx_account_setup_token_expires", columnList = "expires_at")
    })
@SQLDelete(sql = "UPDATE account_setup_tokens SET deleted_at = NOW() WHERE token_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class AccountSetupToken extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_seq")
    private Long tokenSeq;

    // URL path에 노출되는 UUID (LoaSigningToken과 동일 패턴)
    @Column(name = "token_uuid", nullable = false, unique = true, length = 36, updatable = false)
    private String tokenUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_seq", nullable = false, updatable = false)
    private User user;

    // 발급 맥락 (로깅·분석용)
    @Enumerated(EnumType.STRING)
    @Column(name = "issue_source", nullable = false, length = 40, updatable = false)
    private IssueSource issueSource;    // CONCIERGE_REQUEST / LOGIN_REQUEST_ACTIVATION / MANAGER_RESEND / SELF_RESEND

    // 발급 주체 (Manager 재발송의 경우 Manager.userSeq, 그 외 null)
    @Column(name = "issued_by_user_seq", updatable = false)
    private Long issuedByUserSeq;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;    // 기본 createdAt + 48h

    @Column(name = "used_at")
    private LocalDateTime usedAt;       // 사용 완료 시점 — 1회성

    // ★ v1.5 (H-3): 실패 시도 제한
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;     // 5회 실패 시 잠금

    // ★ v1.5 (O-17): 활성 토큰 단일 유지를 위한 명시적 revoke
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum IssueSource {
        CONCIERGE_REQUEST,            // 컨시어지 신청 시 자동 발급 (N1 이메일)
        LOGIN_REQUEST_ACTIVATION,     // 로그인 페이지 "활성화 링크 요청" 클릭 (N-Activation)
        MANAGER_RESEND,               // Manager 대시보드 재발송 버튼
        SELF_RESEND                   // success 페이지 본인 재발송
    }

    public boolean isUsable() {
        return usedAt == null
            && revokedAt == null
            && lockedAt == null
            && expiresAt.isAfter(LocalDateTime.now());
    }

    public void recordFailedAttempt() {
        this.failedAttempts++;
        if (this.failedAttempts >= 5) {
            this.lockedAt = LocalDateTime.now();
        }
    }

    public void markUsed() {
        if (!isUsable()) throw new IllegalStateException("TOKEN_NOT_USABLE");
        this.usedAt = LocalDateTime.now();
    }

    public void revoke() {
        if (usedAt != null) return;  // 이미 사용 완료된 토큰은 무시
        this.revokedAt = LocalDateTime.now();
    }
}
```

**불변식 (테스트로 보장)**:
1. **한 User에 대해 `isUsable() = true`인 토큰은 최대 1개** — 새 토큰 발급 시 서비스 레이어가 기존 유효 토큰을 `revoke()` 처리한 후 INSERT (O-17)
2. `failedAttempts >= 5` → `lockedAt` 자동 세팅, 이후 모든 요청 410 `TOKEN_LOCKED` (H-3, AC-28b)
3. `usedAt != null`이면 같은 토큰으로 재사용 불가
4. `expiresAt`, `tokenUuid`, `issueSource`, `issuedByUserSeq` 모두 `@Column(updatable=false)`

**Flyway 마이그레이션**: `V_NN__account_setup_tokens.sql` (Phase 1 PR#1-Enhanced 포함). 기존 컨시어지 신청 테스트 데이터가 없으므로 backfill 불필요.

**레거시 호환**: v1.3~v1.4 설계에서 언급된 "기존 `PasswordResetToken` 인프라 재활용"은 **폐기**되며, 비밀번호 재설정과 계정 활성화가 유사하지만 **시도 제한·잠금·단일성 요구사항이 다르므로 엔티티를 분리**한다. 비밀번호 재설정은 기존 `PasswordResetToken`을 그대로 사용한다.

---

### 3.8 결제 연동 (Payment 엔티티 재사용 — 확정)

**결정**: O-6 확정 — 기존 `Payment` 엔티티를 재사용하되, 어떤 도메인의 결제인지 구분하기 위한 **다형 참조(polymorphic reference) 컬럼**을 추가한다.

#### 3.8.1 Payment 스키마 변경

```java
// domain/payment/Payment.java (수정)
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {
    @Id @GeneratedValue private Long paymentSeq;

    // 기존: Application FK (nullable로 변경)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq", nullable = true)  // ← was: nullable=false
    private Application application;

    // 신규: 다형 참조 컬럼 2종 —— 어떤 도메인의 결제인지 식별
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 30)
    private PaymentReferenceType referenceType;

    @Column(name = "reference_seq", nullable = false)
    private Long referenceSeq;

    // ... 기존 금액/상태/txnId 등 컬럼 유지
}

public enum PaymentReferenceType {
    APPLICATION,         // 라이선스 신청 수수료 (기존 데이터)
    SLD_ORDER,           // SLD 주문 결제 (기존 SldOrder)
    CONCIERGE_REQUEST    // Concierge 서비스 요금 (신규)
}
```

**네이밍 근거**: `reference_type` + `reference_seq`는 레일즈/JPA에서 흔한 polymorphic association 네이밍 규약. 기존 `application` FK는 legacy 조회 편의를 위해 유지(단, nullable).

#### 3.8.2 데이터 마이그레이션 (Flyway)

```sql
-- V_NN__payment_add_reference_type.sql

-- 1. 컬럼 추가 (우선 nullable로)
ALTER TABLE payments
  ADD COLUMN reference_type VARCHAR(30),
  ADD COLUMN reference_seq BIGINT;

-- 2. 기존 데이터 backfill — 모든 기존 Payment는 Application 결제
UPDATE payments
SET reference_type = 'APPLICATION',
    reference_seq  = application_seq
WHERE reference_type IS NULL
  AND application_seq IS NOT NULL;

-- 3. NOT NULL 제약 전환
ALTER TABLE payments
  MODIFY COLUMN reference_type VARCHAR(30) NOT NULL,
  MODIFY COLUMN reference_seq BIGINT NOT NULL;

-- 4. application_seq FK nullable 허용
ALTER TABLE payments
  MODIFY COLUMN application_seq BIGINT NULL;

-- 5. 복합 인덱스 (대시보드/리포트용)
CREATE INDEX idx_payment_reference ON payments (reference_type, reference_seq);
```

**검증**: 마이그레이션 후 `SELECT COUNT(*) FROM payments WHERE reference_type IS NULL` = 0 이어야 한다.

#### 3.8.3 도메인 접근 패턴

- 조회: `paymentRepository.findByReferenceTypeAndReferenceSeq(CONCIERGE_REQUEST, conciergeRequestSeq)`
- 생성: 각 도메인 서비스에서 `Payment.of(referenceType, referenceSeq, amount, …)` 팩토리 메서드 사용
- 레거시 호환: 기존 `paymentRepository.findByApplication(app)` 쿼리는 `reference_type = APPLICATION` 필터와 함께 동작

#### 3.8.4 ConciergeRequest ↔ Payment 연결

```java
// ConciergeRequest에는 payment_seq FK를 유지 (편의상 직결)
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "payment_seq")
private Payment payment;     // Phase 2 이후 세팅
```

**이중 관리 주의**: `ConciergeRequest.payment_seq`와 `Payment.reference_seq`가 같은 정보를 가리킨다. 저장 시 두 값이 일치하도록 서비스 레이어에서 강제해야 하며, 테스트로 불변식 검증 필요 (`AC-10` 참조).

#### 3.8.5 Phase별 적용

- **Phase 1**: 스키마 마이그레이션까지만 포함. `ConciergeRequest.payment` = null, 운영자가 별도 채널(예: PayNow)로 요금 수납 후 Admin UI에서 수동으로 "결제 완료 표시"
- **Phase 2**: PG 연동 + `Payment(CONCIERGE_REQUEST, …)` 자동 생성. 환불 시 `Payment.refund()` 호출 + `ConciergeRequest.cancel()` 연쇄

---

## 4. API 설계

### 4.1 엔드포인트 매트릭스

| Method | Path | 역할 | 설명 |
|--------|------|------|------|
| **Public** | | | |
| POST | `/api/public/concierge/request` | Anonymous | **★ v1.3 통합 플로우**: User 자동 생성 + ConciergeRequest + 동의 로그 + 계정설정 토큰 발급까지 한 트랜잭션 (기존 `/api/concierge/requests` 경로 deprecate) |
| GET | `/api/public/concierge/check-email?email=...` | Anonymous | **★ v1.3 (Phase 2)**: 기존 회원 여부 + role 체크. 응답 `{exists, role, accountStatus, canSubmit}`. Rate-limit 적용 |
| GET | `/api/concierge/pricing` | Anonymous | 서비스 가격 조회 |
| GET | `/api/concierge/requests/public/{publicCode}` | Anonymous | 신청자 본인 이메일+publicCode로 진행상태 조회(옵션, Phase 2) |
| **★ v1.3: Account Setup** | | | |
| GET | `/api/public/account-setup/{token}` | Anonymous | 토큰 유효성 검증 + 마스킹된 사용자 정보 반환. **★ v1.5 (H-3)**: `lockedAt IS NOT NULL`이면 410 `TOKEN_LOCKED` 반환 + "Manager에게 재발송 요청 안내" |
| POST | `/api/public/account-setup/{token}` | Anonymous | 비밀번호 설정 + `emailVerified=true` + **★ v1.4: `status=ACTIVE` + `activatedAt=NOW()` + `firstLoggedInAt=NOW()`** + 자동 로그인 JWT 발급. **★ v1.5 (H-3)**: 복잡도·확인 검증 실패 시 `failedAttempts++`, 5회 누적 시 `lockedAt=NOW()` 세팅 + 이후 요청 모두 410 `TOKEN_LOCKED` |
| POST | `/api/public/account-setup/{token}/resend` | Anonymous | 신청자 본인이 만료된 토큰 재발송 요청 (rate-limit 5분/1회). **★ v1.5 (O-17)**: 기존 유효 토큰이 있으면 즉시 `revokedAt=NOW()`로 invalidate 후 신규 발급 |
| **★ v1.4 → v1.5: 로그인 활성화 플로우 (옵션 B 단독)** | | | |
| POST | `/api/auth/login` | Anonymous | **★ v1.5 재작성 (H-1)**: **비밀번호 검증 선행** → 실패 시 무조건 401 `INVALID_CREDENTIALS` (dummy BCrypt.verify로 타이밍 동등성). 성공 후에만 `status` 분기: `ACTIVE` → JWT / `PENDING_ACTIVATION` → 401 `ACCOUNT_PENDING_ACTIVATION` + `activationFlow=EMAIL_LINK` / `SUSPENDED` → 403 / `DELETED` → 401 `INVALID_CREDENTIALS` |
| POST | `/api/auth/login/request-activation` | Anonymous | **★ v1.5 (옵션 B, 유일)**: 이메일 입력 → `PENDING_ACTIVATION` 계정이면 일회성 인증 링크 재발송(AccountSetupToken 재발급, 기존 유효 토큰 자동 revoke). **항상 200 + 고정 메시지** 반환(존재 여부 유출 방지). Rate-limit: 기존 `GenericRateLimiter` 재사용(IP당 시간당 20회) + 이메일당 5분/1회 내부 카운터 |
| ~~POST~~ | ~~`/api/auth/login/force-change-password`~~ | ~~Anonymous~~ | **★ v1.5에서 폐기 (H-5)** — 옵션 A 전면 삭제에 따른 제거. 구현하지 않음 |
| **Concierge Manager** | | | |
| GET | `/api/concierge-manager/requests` | CONCIERGE_MANAGER, ADMIN | 요청 목록 (필터: status, assignee, date) |
| GET | `/api/concierge-manager/requests/{id}` | CONCIERGE_MANAGER(본인 담당), ADMIN | 요청 상세 |
| PATCH | `/api/concierge-manager/requests/{id}/status` | CONCIERGE_MANAGER(본인 담당), ADMIN | 상태 전이 |
| POST | `/api/concierge-manager/requests/{id}/notes` | CONCIERGE_MANAGER(본인 담당), ADMIN | 연락 기록 추가 |
| POST | `/api/concierge-manager/requests/{id}/applications` | CONCIERGE_MANAGER(본인 담당), ADMIN | 대리 Application 생성 (on-behalf-of) |
| POST | `/api/concierge-manager/requests/{id}/resend-setup-email` | CONCIERGE_MANAGER, ADMIN | **★ v1.3**: 신청자 계정 설정 메일 재발송 (기존 토큰 invalidate + 새 토큰 발급). 감사 로그 `ACCOUNT_SETUP_TOKEN_RESENT` |
| PATCH | `/api/concierge-manager/requests/{id}/cancel` | CONCIERGE_MANAGER(본인 담당), ADMIN | 취소 처리 |
| **Admin** | | | |
| GET | `/api/admin/concierge/requests` | ADMIN | 전체 요청 목록 |
| POST | `/api/admin/concierge/requests/{id}/assign` | ADMIN | 담당 Manager 배정/재배정 |
| GET | `/api/admin/concierge/metrics` | ADMIN | SLA/처리율 지표 |
| POST | `/api/admin/concierge/requests/{id}/refund` | ADMIN | 환불 트리거 (Phase 2) |
| **LOA 서명 수집 (★ v1.2 신규)** | | | |
| POST | `/api/concierge-manager/applications/{id}/loa/upload-signature` | CONCIERGE_MANAGER, ADMIN | **★ v1.5 (H-4)**: 경로 A — Manager 대리 서명 업로드 (multipart). **기존 `/api/admin/...` 경로에서 이관** — `/api/admin/**`이 LEW에게도 허용되는 구조였으므로 LOA 위조 경로 차단을 위해 분리. SecurityConfig에 `hasAnyRole('CONCIERGE_MANAGER','ADMIN')` 명시. AC-15b 검증 |
| POST | `/api/admin/applications/{id}/loa/request-remote-sign` | CONCIERGE_MANAGER, ADMIN | **경로 B**: 원격 서명 토큰 발급 + 발송 (Phase 2) |
| POST | `/api/admin/applications/{id}/loa/revoke-remote-sign/{tokenSeq}` | CONCIERGE_MANAGER, ADMIN | **경로 B**: 토큰 폐기 (Phase 2) |
| GET | `/api/admin/applications/{id}/loa/remote-sign-tokens` | CONCIERGE_MANAGER, ADMIN | **경로 B**: 발급된 토큰 이력 조회 (Phase 2) |
| **Public — 토큰 기반 (로그인 불필요, ★ v1.2, Phase 2)** | | | |
| GET | `/api/public/loa-sign/{token}` | Anonymous | 토큰 유효성 + LOA 메타정보 + 마스킹된 신청자 정보 응답 |
| POST | `/api/public/loa-sign/{token}/verify` | Anonymous | 본인확인 (이메일 last-4 또는 OTP). 5회 실패 시 토큰 잠김 |
| GET | `/api/public/loa-sign/{token}/pdf` | Anonymous + verified 세션 | LOA PDF 미리보기 다운로드 |
| POST | `/api/public/loa-sign/{token}/submit` | Anonymous + verified 세션 | 서명 PNG 제출 → `embedSignatureIntoPdf()` → 서명 완료 |

### 4.2 권한 매트릭스

| 리소스 | Visitor | APPLICANT | CONCIERGE_MANAGER | ADMIN |
|--------|---------|-----------|-------------------|-------|
| 신청 생성 | ✅ | ✅ | ✅ | ✅ |
| 자기 요청 조회 (publicCode) | ✅(옵션) | ✅ | — | — |
| 전체 목록 | — | — | — | ✅ |
| 자기 담당 목록 | — | — | ✅ | ✅ |
| 상태 전이 | — | — | ✅(본인 담당) | ✅ |
| 재배정 | — | — | — | ✅ |
| 대리 Application 생성 | — | — | ✅(본인 담당) | ✅ |
| 환불 | — | — | — | ✅ |
| LOA 직접 서명 (본인 로그인) | — | ✅(본인 application) | — | — |
| LOA 서명 파일 대리 업로드 (경로 A) | — | — | ✅(본인 담당) | ✅ |
| LOA 원격 서명 토큰 발급 (경로 B) | — | — | ✅(본인 담당) | ✅ |
| LOA 원격 서명 페이지 진입/제출 (경로 B) | ✅(유효 토큰 보유 시) | — | — | — |
| **★ v1.3: Account Setup 진입/완료** | ✅(유효 토큰 보유 시) | — | — | — |
| **★ v1.3: Account Setup 재발송 (본인)** | ✅(토큰/이메일 있음) | ✅ | — | — |
| **★ v1.3: Account Setup 재발송 (Manager)** | — | — | ✅(본인 담당) | ✅ |
| **★ v1.3: check-email 호출** | ✅(rate-limited) | ✅ | — | — |

**보안 기본 원칙**:
- 기존 `OwnershipValidator` 패턴 준용 — Manager는 본인 담당(`assigned_manager_seq = currentUser`)만 변경 가능, Admin은 bypass.
- `POST /api/concierge/requests`는 rate limit (IP + 이메일) — 스팸 방지. 5req/hour/email 권장.
- 모든 입력은 기존 B-2 XSS 방어 서버·클라이언트 이중 적용.
- **★ v1.2 추가 보안**:
  - `/api/public/loa-sign/{token}/**`은 **HTTPS 강제** + 토큰을 **URL path만** 사용(쿼리스트링 ❌, Referer 헤더 유출 방지)
  - 본인확인 5회 실패 시 토큰 자동 잠김(`failed_attempts >= 5` → `lockedAt`), Manager 재발급 필요
  - 토큰 발급 시 `LOA_REMOTE_SIGN_TOKEN_ISSUED` 감사 로그(actor=Manager), 서명 시 `LOA_SIGNATURE_VIA_REMOTE_LINK`(actor=null, metadata={ip, ua, tokenSeq})
  - 경로 A 파일 업로드는 기존 `FileEncryptionService` 적용 + MIME 검증(PNG/JPG only) + 크기 ≤2MB

### 4.3 주요 DTO 스켈레톤

```java
// ★ v1.3: Public submit — 통합 가입 + 요청 생성
public record ConciergeRequestCreateRequest(
    @NotBlank @Size(max=100) String fullName,
    @NotBlank @Email @Size(max=100) String email,
    @NotBlank @Pattern(regexp="^(\\+65)?[0-9]{8}$") String mobileNumber,
    @Size(max=1000) String memo,
    // 필수 동의 4종 — 모두 true여야 함
    @AssertTrue(message="PDPA_CONSENT_REQUIRED") boolean pdpaConsent,
    @AssertTrue(message="TERMS_CONSENT_REQUIRED") boolean termsAgreed,
    @AssertTrue(message="SIGNUP_CONSENT_REQUIRED") boolean signupConsent,
    @AssertTrue(message="DELEGATION_CONSENT_REQUIRED") boolean delegationConsent,
    // 선택 동의 1종
    boolean marketingOptIn,
    // 동의한 약관 버전 스냅샷 (서버에서 현재 활성 버전과 비교 검증)
    @NotBlank @Size(max=20) String termsVersion
) {}

public record ConciergeRequestCreateResponse(
    Long conciergeRequestSeq,
    String publicCode,
    ConciergeRequestStatus status,
    // ★ v1.3: 가입 결과 정보
    UserAccountStatus userAccountStatus,   // NEW_CREATED / EXISTING_LINKED / SETUP_EMAIL_SENT
    Long userSeq,
    String maskedEmail,
    boolean setupEmailSent,
    LocalDateTime setupTokenExpiresAt
) {}

public enum UserAccountStatus {
    NEW_CREATED,        // 신규 User 생성 + 계정 설정 링크 발송
    EXISTING_LINKED,    // 기존 APPLICANT 계정에 연결 + 로그인 안내 메일
    SETUP_EMAIL_SENT    // 재신청(기존에 signupCompleted=false인 경우) 토큰 재발송
}

// ★ v1.3: 실시간 이메일 체크 (Phase 2)
public record CheckEmailResponse(
    boolean exists,
    UserRole existingRole,          // exists=true일 때만
    SignupStatus accountStatus,     // NEW / SETUP_PENDING / ACTIVE / STAFF_BLOCKED
    boolean canSubmit,              // staff 계정이면 false
    String message                  // UI 표시용 안내 문구
) {}

public enum SignupStatus {
    NEW,              // 미가입 이메일 — 신규 생성 예정
    SETUP_PENDING,    // APPLICANT + signupCompleted=false — 재발송 예정
    ACTIVE,           // APPLICANT + signupCompleted=true — 기존 계정 연결 예정
    STAFF_BLOCKED     // LEW/ADMIN/SYSTEM_ADMIN/SLD_MANAGER/CONCIERGE_MANAGER
}

// ★ v1.3: Account Setup — GET 응답
public record AccountSetupSessionResponse(
    String token,
    String maskedName,              // "Tan W*** M***"
    String maskedEmail,             // "t**@e******.sg"
    Long applicationCount,          // 이 계정에 연결된 Application 수 (UX 안내용)
    Long conciergeRequestCount,
    LocalDateTime expiresAt,
    boolean valid,
    String invalidReason            // EXPIRED / USED / REVOKED
) {}

// ★ v1.3: Account Setup — POST 요청
public record AccountSetupSubmitRequest(
    @NotBlank @Size(min=8, max=100) String newPassword,
    @NotBlank @Size(min=8, max=100) String confirmPassword
) {
    // 서비스 레이어에서 복잡도 검증:
    //   - 8자 이상, upper+lower+digit+symbol 각 1개 이상
    //   - newPassword == confirmPassword
}

public record AccountSetupSubmitResponse(
    String jwtAccessToken,          // 설정 완료 즉시 자동 로그인
    String refreshToken,
    UserBrief user,                  // user.status 포함 (v1.4 ACTIVE로 전환된 직후)
    String redirectPath             // 연결된 ConciergeRequest/Application이 있으면 해당 경로
) {}

// ★ v1.4: 로그인 응답 확장 (PENDING_ACTIVATION 분기 처리)
public record LoginResponse(
    // 성공 케이스
    String jwtAccessToken,
    String refreshToken,
    UserBrief user,
    // 분기 케이스 (성공 응답은 아니지만 2xx로 반환하지 않음 — 401 body에 동반)
    String errorCode,                // ACCOUNT_PENDING_ACTIVATION / ACCOUNT_SUSPENDED / INVALID_CREDENTIALS ...
    ActivationFlow activationFlow    // EMAIL_LINK(옵션 B) / TEMPORARY_PASSWORD(옵션 A, Phase 2)
) {}

public enum ActivationFlow {
    EMAIL_LINK,            // 옵션 B — 로그인 페이지에서 "인증 링크 요청" 버튼 노출
    TEMPORARY_PASSWORD     // 옵션 A — 이메일로 받은 임시 비밀번호 사용 지시
}

// ★ v1.4: 활성화 인증 링크 재발송 요청 (옵션 B)
public record LoginRequestActivationRequest(
    @NotBlank @Email @Size(max=100) String email
) {}

// ★ v1.4: 활성화 인증 링크 재발송 응답 — 이메일 존재 여부 유출 방지 (§4.4)
public record LoginRequestActivationResponse(
    // 항상 고정 메시지 (이메일 존재하든 안 하든, status가 뭐든 동일 응답)
    String message  // "If this email is registered and eligible for activation, we've sent an activation link."
) {}

public record ConciergeRequestResponse(
    Long id, String publicCode, String submitterName, String submitterEmail,
    String submitterPhone, String memo, ConciergeRequestStatus status,
    UserBrief assignedManager, UserBrief applicant, Long applicationSeq,
    PaymentBrief payment, LocalDateTime createdAt, LocalDateTime firstContactAt
) {}

// Create application on behalf (Manager)
public record CreateApplicationOnBehalfRequest(
    // 기존 ApplicationCreateRequest 필드 + 대리 작성 확인
    @AssertTrue boolean authorizedByApplicant,
    // ... address, postalCode, selectedKva 등 기존 필드
) {}

// ★ v1.2: 경로 A — Manager 대리 서명 업로드
public record LoaSignatureUploadRequest(
    // signature 파일은 multipart 파트로 별도 수신
    @NotNull LoaSignatureSource source,            // MANAGER_UPLOAD 고정 (validation)
    @NotBlank @Size(max=50) String receivedVia,    // EMAIL / WHATSAPP / IN_PERSON / OTHER
    @Size(max=500) String memo,
    @AssertTrue boolean managerConfirmation        // "정당하게 수령했음을 확인" 체크
) {}

// ★ v1.2: 경로 B — 원격 서명 토큰 발급 요청
public record RemoteSignRequestPayload(
    @NotNull DeliveryMethod deliveryMethod,        // EMAIL / SMS / QR_ONLY
    @Size(max=100) String recipientOverride,        // null이면 application.user의 기본 연락처 사용
    Integer expiryHoursOverride                     // null이면 기본 48h, max 168h(7일)
) {}

public record RemoteSignTokenResponse(
    Long tokenSeq,
    String tokenUuid,
    String signingUrl,                              // https://licensekaki.sg/sign/{uuid}
    String qrPngBase64,                             // base64-encoded PNG (data:image/png;base64,...)
    LocalDateTime expiresAt,
    DeliveryMethod deliveryMethod,
    String deliveryTarget,
    LocalDateTime deliveredAt                        // null이면 발송 실패
) {}

// ★ v1.2: Public — 토큰 진입 응답 (마스킹 적용)
public record PublicLoaSignSessionResponse(
    String tokenUuid,
    String maskedApplicantName,                     // "Tan W*** M***"
    String maskedApplicantEmail,                    // "t**@e******.sg"
    String maskedApplicantMobile,                   // "+65 9*** 4567"
    Long applicationSeq,
    String applicationPublicCode,
    LocalDateTime expiresAt,
    boolean verified,                                // 본인확인 완료 여부
    int remainingVerifyAttempts                      // 5 - failedAttempts
) {}

// ★ v1.2: Public — 본인확인 요청
public record PublicLoaSignVerifyRequest(
    @NotBlank @Size(min=4, max=4) String emailLast4,  // 또는 OTP 6자리 (Phase 3)
    @Size(min=6, max=6) String otpCode
) {}

// ★ v1.2: Public — 서명 제출
public record PublicLoaSignSubmitRequest(
    @NotBlank String signaturePngBase64,             // data:image/png;base64,...
    @AssertTrue boolean voluntaryConsent             // "자발적으로 서명함을 확인"
) {}
```

### 4.4 이메일 존재 여부 유출 방지 (★ v1.5 전면 재작성, H-1 + M-1 + O-23 반영)

**위협 모델**: 로그인 페이지의 "활성화 링크 요청" API 또는 로그인 API가 이메일 존재 여부를 **응답 본문 / HTTP 상태 / 응답 시간 / 에러 코드 / 헤더**로 노출하면 공격자가 **이메일 enumeration 공격**(CWE-204)으로 회원 목록을 수집할 수 있다. PDPA §13 간접 식별정보 보호, NIST SP 800-63B §5.2.2 (Verifier Impersonation Resistance), OWASP ASVS V2.2.1이 모두 이를 요구한다.

**적용 대상 엔드포인트**:
- `POST /api/auth/login` (★ v1.5 H-1 반영 — 가장 광범위하게 탐지되는 경로)
- `POST /api/auth/login/request-activation` (★ v1.4~v1.5)
- `POST /api/auth/password-reset/request` (기존 — 동일 원칙 재확인)
- `GET /api/public/concierge/check-email` (v1.3) — **예외**: 본 엔드포인트는 UX 편의 목적(신청 폼 실시간 체크)으로 설계된 것으로 rate-limit + IP 차단 + CAPTCHA 조합으로 보호하되 **응답 자체는 정보를 공개함**. 따라서 이 항목은 위협 평가에서 제외되며, `request-activation`과는 스코프가 다르므로 혼동하지 말 것

**구현 원칙 (★ v1.5 constant-time 패딩 전략)**:

| 항목 | 규칙 |
|------|------|
| **공통 코드 경로** | `ACTIVE` / `PENDING_ACTIVATION` / `SUSPENDED` / `DELETED` / 이메일 미존재 **5케이스 모두 동일한 메서드를 통과**. 조기 `return`이나 분기 없음 |
| **Dummy BCrypt.verify** | 이메일이 존재하지 않아도 사전 생성된 `DUMMY_BCRYPT_HASH`(cost=10)에 대해 `passwordEncoder.matches(...)`를 **반드시 호출**. BCrypt는 O(N) 시간 복잡도가 비밀번호 길이가 아닌 cost factor에만 의존하므로 타이밍 차이의 주된 근원을 제거 |
| **Dummy hash 선택 기준** | `BCrypt.hashpw("__dummy__", "$2a$10$...")`로 애플리케이션 시작 시 1회 생성. **BCrypt.hashpw 자체는 요청 처리 경로에서 호출 금지** — 생성 부하가 크므로 |
| **응답 본문** | 이메일 존재 여부와 무관하게 **동일한 고정 메시지**. `request-activation`: `"If this email is registered and eligible for activation, we've sent an activation link."` / `login`: `"Invalid email or password."` |
| **HTTP 상태 코드** | `request-activation`: 항상 200 / `login` 실패: 항상 401 `INVALID_CREDENTIALS`. 이메일 미존재 ≠ 401 이외 코드 |
| **이메일 발송** | `@Async + afterCommit`으로 격리. 발송 자체가 응답 타이밍에 영향 주지 않음. 실제 발송 대상이 없더라도(= 이메일 미존재) **응답 경로의 시간은 같아야 하므로** 발송 로직 스킵만 다를 뿐 그 외 경로(감사 로그 기록 포함)는 동일하게 수행 |
| **로그 기록** | 내부 감사 로그에는 실제 결과 기록(`ACCOUNT_ACTIVATION_REQUEST_SENT` vs `ACCOUNT_ACTIVATION_REQUEST_NO_MATCH`) — 외부 응답으로는 유출 금지 |
| **Rate-limit** | **기존 `GenericRateLimiter` 재사용** (Bucket4j 미도입). IP당 시간당 20회 + 내부 카운터로 이메일당 5분/1회. 이메일 기반 한도 초과 시 429 반환하지 않고 **고정 메시지로 수렴**(공격자가 히트 여부로 존재 추정 방지). IP 한도 초과만 429로 응답 |
| **응답 비교** | 문자열 비교는 `MessageDigest.isEqual(expected.getBytes(), actual.getBytes())`로 상수 시간 비교(응답 템플릿 비교에 한함) |

**구현 의사코드 (Java / Spring Boot)**:

```java
@Service
public class LoginActivationService {

    // 애플리케이션 시작 시 1회 생성되는 상수 hash (cost=10)
    private static final String DUMMY_BCRYPT_HASH =
        "$2a$10$CwTycUXWue0Thq9StjUM0uJ8wu7yQNEKX3PO2f3n1xpP6dLy9HnyC";

    private static final String FIXED_MESSAGE =
        "If this email is registered and eligible for activation, we've sent an activation link.";

    @Autowired private UserRepository userRepository;
    @Autowired private AccountSetupTokenService tokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GenericRateLimiter rateLimiter;
    @Autowired private AuditService auditService;

    public LoginRequestActivationResponse requestActivation(
            String email, String ip, String userAgent) {

        // 1) IP rate-limit만 외부 429 반환 (이메일 기반은 고정 메시지로 흡수)
        rateLimiter.checkOrThrow("login-activation-ip:" + ip, 20, Duration.ofHours(1));

        // 2) 공통 경로: 이메일 조회 (case-insensitive 정규화)
        String normalizedEmail = email.toLowerCase(Locale.ROOT).trim();
        Optional<User> userOpt = userRepository.findByEmailIgnoringDeleted(normalizedEmail);

        // 3) 공통 경로: dummy BCrypt.verify로 타이밍 흡수
        //    (login 엔드포인트에서는 사용자가 입력한 비밀번호로 검증하지만
        //     request-activation은 비밀번호 입력이 없으므로 dummy만 호출하여 경로 일치)
        String hashToCheck = userOpt.map(User::getPassword).orElse(DUMMY_BCRYPT_HASH);
        boolean _discard = passwordEncoder.matches(DUMMY_BCRYPT_HASH_SEED, hashToCheck);
        // _discard 결과는 사용하지 않음. BCrypt 시간만 소모.

        // 4) 이메일당 분 단위 한도 (내부 카운터, 초과해도 고정 메시지)
        boolean emailRateExceeded = !rateLimiter.tryAcquire(
            "login-activation-email:" + normalizedEmail, 1, Duration.ofMinutes(5));

        // 5) 실제 발송 여부 결정 (외부 응답에는 영향 없음)
        if (userOpt.isPresent()
            && userOpt.get().getStatus() == UserStatus.PENDING_ACTIVATION
            && !emailRateExceeded) {

            User user = userOpt.get();
            AccountSetupToken newToken = tokenService.issueOrReissue(
                user, IssueSource.LOGIN_REQUEST_ACTIVATION);

            auditService.record(ACCOUNT_ACTIVATION_REQUEST_SENT,
                user.getUserSeq(), Map.of("ip", ip, "userAgent", userAgent));

            // afterCommit + @Async — 응답 타이밍에 영향 없음
            registerAfterCommit(() -> emailService.sendAccountActivationLink(user, newToken));
        } else {
            auditService.record(ACCOUNT_ACTIVATION_REQUEST_NO_MATCH,
                null, Map.of("emailHash", sha256(normalizedEmail), "ip", ip));
        }

        // 6) 상수 시간 비교로 응답 구성 (실질적으로는 템플릿 고정이므로 장식 목적)
        byte[] expected = FIXED_MESSAGE.getBytes(StandardCharsets.UTF_8);
        return new LoginRequestActivationResponse(new String(expected, StandardCharsets.UTF_8));
    }
}
```

**`POST /api/auth/login` 의사코드 (H-1 반영)**:

```java
@Service
public class AuthService {

    public LoginResponse login(LoginRequest req) {
        Optional<User> userOpt = userRepository.findByEmailIncludingInactive(
            req.email().toLowerCase(Locale.ROOT).trim());

        // 1) 이메일이 없어도 dummy hash로 BCrypt 연산 수행 (타이밍 일정성)
        String hashToCheck = userOpt.map(User::getPassword).orElse(DUMMY_BCRYPT_HASH);
        boolean passwordOk = passwordEncoder.matches(req.password(), hashToCheck);

        // 2) 미존재 또는 비밀번호 불일치 → 동일 응답
        if (userOpt.isEmpty() || !passwordOk) {
            throw new BusinessException("INVALID_CREDENTIALS",
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password.");
        }

        User user = userOpt.get();

        // 3) 비밀번호 검증 성공 후에만 status 분기
        return switch (user.getStatus()) {
            case ACTIVE -> issueJwt(user);
            case PENDING_ACTIVATION -> throw new BusinessException(
                "ACCOUNT_PENDING_ACTIVATION",
                HttpStatus.UNAUTHORIZED,
                Map.of("activationFlow", "EMAIL_LINK"));
            case SUSPENDED -> throw new BusinessException(
                "ACCOUNT_SUSPENDED",
                HttpStatus.FORBIDDEN);
            case DELETED -> throw new BusinessException(
                "INVALID_CREDENTIALS",  // 존재 감춤 — v1.4의 404와 달라짐
                HttpStatus.UNAUTHORIZED,
                "Invalid email or password.");
        };
    }
}
```

**테스트 전략 (★ v1.5 CI 필수)**:

| 테스트 | 기준 | CI 동작 |
|--------|------|--------|
| 응답 본문 동일성 (단위) | 5케이스 모두 같은 JSON 반환 | 실패 시 빌드 차단 |
| HTTP 상태 코드 동일성 (단위) | `login`: 모든 실패 케이스 401, `request-activation`: 모든 케이스 200 | 실패 시 빌드 차단 |
| **타이밍 동등성 통합 테스트** | 5케이스 각 100회 호출, `p95(각 케이스) - p50(baseline) < 200ms` | **CI 실패 시 빌드 차단** |
| 로그 구분 | 감사 로그는 실제 결과를 구분하여 기록했는지 | 단위 테스트 |
| Dummy hash 호출 | 이메일 미존재 케이스에서도 `passwordEncoder.matches` 호출됐는지 (Mockito.verify) | 단위 테스트 |

**타이밍 테스트의 현실성 (O-23 해결)**:
- JVM warm-up 필수: 본 테스트 전 **최소 2000회 warm-up 요청**으로 JIT 최적화 수렴
- GC 튐 제외: p99가 아닌 **p95 기준**으로 완화 (p99는 G1GC 특성상 흔들림)
- 테스트 환경: Spring Boot 내장 Tomcat + H2 인메모리 DB로 외부 의존성 제거
- 실패 시 디버그: 각 케이스의 `p50/p95/p99`를 JSON 리포트로 출력하여 어떤 단계가 비대칭인지 분석

---

## 5. 상태 머신

### 5.1 전이 다이어그램

```
SUBMITTED ──(auto/manual assign)──► ASSIGNED ──(first note)──► CONTACTING
                                                                    │
                                                    ┌───────────────┘
                                                    ▼
                                   APPLICATION_CREATED ──(LOA 생성 완료)──► AWAITING_APPLICANT_LOA_SIGN
                                                                                  │
                                                                                  │ (신청자 본인 LOA 전자서명)
                                                                                  ▼
                                                                      AWAITING_LICENCE_PAYMENT
                                                                                  │
                                                                                  │ (신청자 본인 라이선스료 결제)
                                                                                  ▼
                                                                            IN_PROGRESS
                                                                                  │
                                                                                  │ (Application.COMPLETED)
                                                                                  ▼
                                                                             COMPLETED

* 임의 상태 → CANCELLED (Manager/Admin 수동, COMPLETED 제외)
```

### 5.1b 병행 상태: User.status (★ v1.3 → v1.4 재정의)

ConciergeRequest 본 상태 머신과 **독립적으로** 신청자 계정 상태를 추적한다. 두 상태는 **동시에 진행**되며, Manager 대시보드에서 각각 별도로 집계된다. v1.4에서 `signupCompleted` boolean을 `UserStatus` enum으로 대체하면서 의미론을 확장했다(suspend/delete 병행 관리).

**활성화는 Manager 업무를 차단하지 않는다**: Manager는 `PENDING_ACTIVATION` 상태의 신청자에 대해서도 Application 생성까지 진행할 수 있다. 단 **LOA 서명 API**는 여전히 Applicant JWT 인증을 요구하므로 신청자가 활성화를 완료하지 않으면 서명 단계에서 막힌다.

**ConciergeRequest.status × User.status 상관관계 (★ v1.4 재정의)**:

| ConciergeRequest.status | User.status | 정상 여부 | Manager 대시보드 표시 |
|------------------------|-------------|----------|--------------------|
| SUBMITTED / ASSIGNED / CONTACTING | PENDING_ACTIVATION | ✅ 정상 (초기 단계) | "PENDING_ACTIVATION" 배지 + "활성화 링크 재발송" 버튼 |
| SUBMITTED / ASSIGNED / CONTACTING | ACTIVE | ✅ 정상 (빠른 활성화) | 일반 표시 |
| APPLICATION_CREATED | PENDING_ACTIVATION | ⚠️ 경고 | "PENDING_ACTIVATION" 배지 강조 — LOA 서명이 블록되므로 Manager가 연락 권장 |
| AWAITING_APPLICANT_LOA_SIGN | PENDING_ACTIVATION | ❌ 블록 | "활성화 필수" 빨간 배지 — LOA 서명 API는 로그인 세션 필수 |
| AWAITING_APPLICANT_LOA_SIGN | ACTIVE | ✅ 정상 | 일반 |
| COMPLETED | ACTIVE | ✅ 정상 | — |
| COMPLETED | PENDING_ACTIVATION | ⚠️ 비정상 | Admin 알림 대상 — 조사 필요(이론상 불가능, 데이터 정합성 위반) |
| 임의 | SUSPENDED | ❌ 차단 | Admin 조사 대상 |
| 임의 | DELETED | — | ConciergeRequest도 cancel 처리 권장 |

**집계 쿼리 예시 (v1.4)**:
- "활성화 대기 건수" = `SELECT COUNT(*) FROM concierge_requests cr JOIN users u ON cr.applicant_user_seq = u.user_seq WHERE u.status = 'PENDING_ACTIVATION' AND cr.status NOT IN ('COMPLETED', 'CANCELLED') AND u.deleted_at IS NULL`
- Manager 대시보드 KPI 카드: **"PENDING_ACTIVATION N건"** (v1.3의 "계정 설정 대기" 카드를 의미론 교체)
- Manager 요청 목록 필터: **"활성화 대기만 보기"** 토글 추가

### 5.1c User 상태 머신 (★ v1.4 신규)

```
       ┌──────────────────────────────────────────────────┐
       │                                                  │
   [DIRECT_SIGNUP 가입]                                    │
       │                                                  │
       ▼                                                  │
   ACTIVE ◄─────(Admin 해제)──── SUSPENDED               │
     │  ▲                          ▲                      │
     │  └──(Admin 해제)──┐        │                      │
     │                    │        │                      │
     │ (Admin 조치)       │ (Admin 조치)                  │
     │                    │                               │
     └──► SUSPENDED       │                               │
     │                    │                               │
     │ (사용자 탈퇴/Admin 삭제)                           │
     ▼                                                    │
   DELETED  (+ deleted_at 세팅, terminal)                 │
                                                           │
   ┌───────────────────────────────────────────────────┐  │
   │ CONCIERGE_REQUEST 자동 생성                        │  │
   │                                                    │  │
   │ [신청 폼 제출]                                     │  │
   │     │                                              │  │
   │     ▼                                              │  │
   │ PENDING_ACTIVATION                                 │  │
   │     │                                              │  │
   │     │ (최초 로그인 성공: 이메일 인증 + 비번 설정) │  │
   │     ▼                                              │  │
   │   ACTIVE ──────────────────────────────────────┬──┘
   │     │                                            │
   │     └──► SUSPENDED / DELETED (상단과 동일)     │
   └─────────────────────────────────────────────────┘
```

**전이 트리거 표**:

| From → To | 트리거 | 주체 | Side Effect |
|-----------|--------|------|------------|
| (init) → `PENDING_ACTIVATION` | 컨시어지 신청 폼 제출 + `signupSource=CONCIERGE_REQUEST` | System | User 생성, AccountSetupToken 발급, N1 발송 |
| (init) → `ACTIVE` | 일반 `/api/auth/register` 직접 가입 (`signupSource=DIRECT_SIGNUP`) | Applicant | User 생성 (기존 플로우) |
| `PENDING_ACTIVATION` → `ACTIVE` | **최초 로그인 성공** — Account Setup 완료(`POST /api/public/account-setup/{token}` 성공) 또는 옵션 A 채택 시 임시 비밀번호 로그인 후 강제 변경 완료 | Applicant | `activatedAt=NOW()`, `firstLoggedInAt=NOW()`, `emailVerified=true`, 감사 로그 `ACCOUNT_ACTIVATED` |
| `ACTIVE` → `SUSPENDED` | Admin이 정지 조치 | Admin | `suspendedAt`, `suspensionReason` 기록, 감사 로그 `ACCOUNT_SUSPENDED` (§3.4b에는 명시 안 했지만 Phase 2에서 필요 시 추가) |
| `SUSPENDED` → `ACTIVE` | Admin이 정지 해제 | Admin | 감사 로그 `ACCOUNT_UNSUSPENDED` |
| `ACTIVE`/`SUSPENDED` → `DELETED` | 사용자 탈퇴 또는 Admin 삭제 | Applicant/Admin | `deletedAt=NOW()`, 감사 로그 `ACCOUNT_DELETED` |
| `PENDING_ACTIVATION` → `SUSPENDED` (정리) | 180일 이상 미활성화 (스케줄러, O-22) | System | 감사 로그 `ACCOUNT_AUTO_SUSPENDED_INACTIVE` |

**불변식**:
1. `activatedAt`은 `@Column(updatable=false)` — 한 번 세팅되면 변경 불가(컴플라이언스 증적)
2. `firstLoggedInAt`은 `updatable=true` — 이론상 첫 로그인 시 1회만 세팅되지만, 데이터 정정 여지를 위해 enforce는 서비스 레이어에서만
3. `PENDING_ACTIVATION`에서 `SUSPENDED`로 직접 전이는 **System 스케줄러만** 가능 (Admin 수동은 `ACTIVE → SUSPENDED`만)
4. `DELETED`는 terminal 상태 — 복구는 별도 워크플로에서 Admin이 명시적으로 상태 복원
5. 전이는 반드시 도메인 메서드(`User.activate()`, `User.suspend(reason)`, `User.softDelete()`)로만 — 서비스 레이어 직접 `setStatus(...)` 호출 금지(테스트로 보장)

### 5.2 전이표

| From → To | 트리거 | 주체 | Side Effect |
|-----------|--------|------|------------|
| (init) → `SUBMITTED` | `POST /api/public/concierge/request` | Visitor | **★ v1.3 통합 가입 트랜잭션**: ① 이메일 기존 회원 체크(§7.7 분기) ② 신규 User 생성 또는 기존 User 연결 ③ `UserConsentLog` 4~5건 기록 ④ ConciergeRequest 생성(`delegationConsentAt`, `applicantUser` 세팅) ⑤ 감사 로그 `CONCIERGE_REQUEST_SUBMITTED`, `CONCIERGE_ACCOUNT_AUTO_CREATED` 또는 `CONCIERGE_EXISTING_USER_LINKED`, `USER_CONSENT_RECORDED`×N, `ACCOUNT_SETUP_TOKEN_ISSUED` ⑥ Concierge 서비스요금 즉시 결제(Phase 2) ⑦ **N1 통합 이메일**(접수확인 + 계정설정 링크) 발송 (기존 APPLICANT 연결 시 `CONCIERGE_EXISTING_ACCOUNT_LINKED` 메일) ⑧ Admin+Manager 인앱+이메일 (N2) ⑨ **24h SLA 카운트다운 시작** |
| `SUBMITTED` → `ASSIGNED` | 자동 라운드로빈 또는 Admin 수동 | System / Admin | ① 감사 로그 ② Manager 인앱+이메일 ("24시간 이내 연락 SLA") |
| `ASSIGNED` → `CONTACTING` | Manager가 첫 노트 추가 (`firstContactAt=NOW()`) | Manager | 감사 로그, **SLA 카운트 종료** |
| `CONTACTING` → `APPLICATION_CREATED` | Manager가 `POST .../applications` 성공 (대리 작성) | Manager | ① `application_seq` 세팅 ② 감사 로그 `APPLICATION_CREATED_ON_BEHALF` |
| `APPLICATION_CREATED` → `AWAITING_APPLICANT_LOA_SIGN` | Manager가 `POST /api/admin/applications/{id}/loa/generate` 호출 성공 | Manager | ① LOA PDF 생성(미서명) ② **Applicant에게 "LOA 서명 요청" 이메일+인앱** (N5) ③ 48h 내 미서명 시 리마인더 |
| `AWAITING_APPLICANT_LOA_SIGN` → `AWAITING_LICENCE_PAYMENT` | **Applicant 본인**이 `POST /api/applications/{id}/loa/sign` 호출 | Applicant | ① `loa_signature_url` 세팅 ② Application `PENDING_REVIEW` → 기존 리뷰 플로우 진행 ③ LEW 검토 완료 후 PENDING_PAYMENT 도달 시 Applicant에게 "라이선스료 결제" 알림 |
| `AWAITING_LICENCE_PAYMENT` → `IN_PROGRESS` | Application이 `PAID`/`IN_PROGRESS`로 전이 | System (Application 이벤트 리스너) | ConciergeRequest 상태 동기화 |
| `IN_PROGRESS` → `COMPLETED` | Application이 `COMPLETED`로 전이 | System | ① `completedAt=NOW()` ② Applicant 완료 이메일 (N7) |
| 임의(COMPLETED 제외) → `CANCELLED` | Manager/Admin 수동 | Manager/Admin | ① `cancelledAt`, 사유 기록 ② Applicant+Manager 이메일 (N8) ③ Phase 2: 환불 트리거 |

### 5.3 전이 제약

- `COMPLETED` 또는 `CANCELLED` 후 다른 상태로 전이 금지 (terminal).
- `APPLICATION_CREATED` 이상에서는 `assigned_manager` 변경은 허용하되(재배정), 상태 역행 금지.
- `CONTACTING` 없이 `APPLICATION_CREATED`로 직행 금지 (최소 1개 노트 필수) — SLA 추적 무결성 보장.
- `AWAITING_APPLICANT_LOA_SIGN` 상태에서 Manager가 서명 API를 호출하면 `LOA_SIGNATURE_REQUIRES_APPLICANT` 403 반환 (§7, AC-6).
- `AWAITING_APPLICANT_LOA_SIGN` → `AWAITING_LICENCE_PAYMENT` 전이는 반드시 Applicant JWT로만 발생.

### 5.4 리마인더 스케줄러 (신규)

| 조건 | 발송 대상 | 문구 | 주기 |
|------|----------|------|------|
| `SUBMITTED`/`ASSIGNED` 상태로 24h 경과 + `firstContactAt IS NULL` | 담당 Manager + Admin | "SLA 위반: 24h 경과, 미연락" | 즉시 1회 |
| `AWAITING_APPLICANT_LOA_SIGN` 진입 후 24h 경과 | Applicant + 담당 Manager | "LOA 서명이 아직 완료되지 않았습니다. 로그인 후 서명해주세요." | 24h·48h·72h |
| `AWAITING_LICENCE_PAYMENT` 진입 후 48h 경과 | Applicant + 담당 Manager | "라이선스 수수료 결제가 필요합니다." | 48h·96h |

**구현**: Spring `@Scheduled` cron (매시간), `findByStatusAndUpdatedAtBefore(...)` 쿼리 기반. 중복 발송 방지를 위해 `reminder_sent_at` 컬럼 추가 고려 또는 감사로그로 추적.

### 5.5 전이 메서드 설계 (도메인 불변식 엔티티에 캡슐화)

```java
public class ConciergeRequest {
    public void assignTo(User manager) {
        assertRole(manager, UserRole.CONCIERGE_MANAGER);
        if (status == COMPLETED || status == CANCELLED)
            throw new IllegalStateException(...);
        this.assignedManager = manager;
        if (status == SUBMITTED) this.status = ASSIGNED;
    }

    public void markContacted(LocalDateTime when) {
        if (status != ASSIGNED && status != SUBMITTED && status != CONTACTING)
            throw new IllegalStateException(...);
        if (firstContactAt == null) this.firstContactAt = when;
        this.status = CONTACTING;
    }

    public void linkApplication(Application app) {
        if (status != CONTACTING) throw new IllegalStateException(...);
        this.application = app;
        this.status = APPLICATION_CREATED;
    }

    public void markLoaGenerated() {
        if (status != APPLICATION_CREATED) throw new IllegalStateException(...);
        this.status = AWAITING_APPLICANT_LOA_SIGN;
    }

    public void markLoaSigned() {
        if (status != AWAITING_APPLICANT_LOA_SIGN) throw new IllegalStateException(...);
        this.status = AWAITING_LICENCE_PAYMENT;
    }

    public void markLicencePaid() {
        if (status != AWAITING_LICENCE_PAYMENT) throw new IllegalStateException(...);
        this.status = IN_PROGRESS;
    }

    public void cancel(String reason) {
        if (status == COMPLETED) throw new IllegalStateException(...);
        this.cancelledAt = LocalDateTime.now();
        this.cancellationReason = reason;
        this.status = CANCELLED;
    }
}
```

---

## 6. 알림 명세

### 6.1 이메일 + 인앱 매트릭스

| # | 시나리오 | 수신자 | 이메일 | 인앱 | 발송 시점 |
|---|---------|--------|--------|------|----------|
| **N1 (★ v1.4 재설계)** | **요청 접수 + 계정 활성화 안내** | Applicant(제출자 email) | ✅ **"LicenseKaki 컨시어지 서비스 신청이 접수되었습니다"** — ① 24h 연락 안내 ② **계정 생성 안내** (현재 `PENDING_ACTIVATION` 상태 명시) ③ **최초 로그인 시 활성화 안내** (옵션 B: 로그인 페이지로 이동 → 이메일 입력 → 인증 링크 수신) ④ 편의를 위한 Account Setup 즉시 링크(`/setup-account/{token}`, 48h 유효) ⑤ 본인 미신청 시 이의 제기 채널 | — | afterCommit of `SUBMITTED` (신규 User 생성인 경우) |
| **N1-Alt (★ v1.3)** | 기존 APPLICANT 연결 안내 | Applicant | ✅ "We linked your new Concierge request to your existing LicenseKaki account. [Log in to track progress]" (v1.4: status에 따라 "활성화 필요" 문구 추가될 수 있음) | ✅ | afterCommit of `SUBMITTED` (기존 User 연결인 경우, N1 대신 발송) |
| **N1-R (★ v1.3 → v1.4 재정의)** | **계정 미활성화 리마인더** | Applicant | ✅ "Your LicenseKaki account is still waiting for activation. Log in to activate — the setup link expires in {hours}h." | — | 스케줄러: 24h 경과 + `status=PENDING_ACTIVATION` + 토큰 유효 시 |
| **N-Activation (★ v1.4 신규)** | 로그인 시도 시 활성화 인증 링크 발송 (옵션 B) | Applicant | ✅ "Activate your LicenseKaki account — click the link to set your password and sign in. Valid for 48 hours." | — | `POST /api/auth/login/request-activation` 성공 시(status=PENDING_ACTIVATION일 때만 실제 발송, 외부로는 항상 고정 응답) |
| N2 | 신규 요청 접수 | 모든 Admin + 모든 Concierge Manager | ✅ "New Concierge request #C-… (SLA: 24h first contact)" | ✅ | afterCommit of `SUBMITTED` |
| N3 | 담당 배정 | 대상 Manager | ✅ "Assigned: #C-… — please contact applicant **within 24 hours**" | ✅ | afterCommit of `ASSIGNED` |
| ~~N4~~ | ~~계정 생성 안내 (별도 이메일)~~ | — | **v1.3에서 N1에 통합되어 제거** — N1 단일 이메일로 접수확인 + 계정 설정 링크 전달 | — | — |
| **N5** | **★ LOA 서명 요청 (필수 개입 지점 1)** | **Applicant** | ✅ **"Your licence application is ready — please log in and sign the LOA"** | ✅ | afterCommit of `AWAITING_APPLICANT_LOA_SIGN` |
| N5-R | LOA 서명 리마인더 | Applicant + 담당 Manager | ✅ "LOA signing reminder (24h/48h/72h)" | ✅ | 스케줄러 |
| **N5-Alt** | **★ 원격 서명 링크 발송 (경로 B, v1.2)** | **Applicant** | ✅ **"Sign your LOA without logging in — secure link inside (expires in 48h)"** + SMS(옵션) | — | Manager가 `request-remote-sign` 호출 시 즉시 |
| **N5-UploadConfirm** | **★ Manager 대리 업로드 확인 (경로 A, v1.2)** | **Applicant** | ✅ **"Your signature has been received and applied to LOA #{appSeq}. If this was not authorized by you, please contact us immediately at [link]"** | ✅ | afterCommit of `MANAGER_UPLOAD` 성공 |
| N6 | Application 상태 변경 | Applicant | (기존 Application 이메일 재사용) | (기존) | 기존 플로우 |
| **N6b** | **★ 라이선스료 결제 요청 (필수 개입 지점 2)** | Applicant | ✅ "Please pay the licence fee to proceed" | ✅ | afterCommit of `AWAITING_LICENCE_PAYMENT` |
| N6b-R | 라이선스료 결제 리마인더 | Applicant + 담당 Manager | ✅ "Payment reminder (48h/96h)" | ✅ | 스케줄러 |
| N7 | 완료 | Applicant | ✅ "Your licence has been issued" | ✅ | afterCommit of `COMPLETED` |
| N8 | 취소 | Applicant + 담당 Manager | ✅ | ✅ | afterCommit of `CANCELLED` |
| **N9** | **★ SLA 위반 경고 (24h 미연락)** | 담당 Manager + Admin | ✅ "SLA breached: 24h elapsed without contact for #C-…" | ✅ | 스케줄러 (시간당, 위반 시 1회) |

### 6.2 SLA 운영 정책 (확정)

**Concierge Manager는 요청 접수(`SUBMITTED`) 시점으로부터 24시간 이내에 신청자에게 최초 연락해야 한다.**

- 카운트 시작: `ConciergeRequest.createdAt`
- 카운트 종료: `firstContactAt` 최초 세팅 (= `CONTACTING` 상태 전이 시점)
- 24시간 경과 후에도 `firstContactAt IS NULL`이면 **SLA 위반**으로 분류
  - Admin 대시보드에 **빨간색 "SLA BREACHED" 뱃지** 노출
  - 필터: "SLA 위반만 보기" 옵션 제공
  - N9 알림 자동 발송 (담당 Manager + Admin 대상, 위반 건당 1회, 중복 방지)

**근거**: O-4 해결. 기존 "최대한 빨리" 표현 전량을 "24시간 이내"로 통일.

### 6.3 인앱 알림 문구 초안

| 이벤트 | Title | Message (영문 / 한국어) |
|--------|-------|-----------------------|
| N1 | "Request received" | "Thanks — a Concierge Manager will contact you within 24 hours." / "24시간 이내에 담당자가 연락드립니다." |
| N2 | "New Concierge request" | "Request #{publicCode} submitted by {submitterName} — 24h SLA started" |
| N3 | "Assigned to you" | "You've been assigned #{publicCode}. Contact the applicant within 24 hours." |
| **N5** | **"Your application is ready — please sign"** | **"귀하의 전기 면허 신청서가 준비되었습니다. 아래 링크로 로그인하여 LOA에 서명해주세요." / "We've prepared your application (#{appSeq}). Please log in and sign the LOA to proceed."** |
| **N5-Alt** | **"Remote signing link sent"** | **"Open the link we sent to {maskedContact} to sign your LOA without logging in. The link expires in 48 hours."** (인앱 미발송 — 경로 B는 비로그인 사용자 대상) |
| **N5-UploadConfirm** | **"Signature received"** | **"귀하의 서명이 LOA #{appSeq}에 적용되었습니다. 본인이 제공한 서명이 아닌 경우 [여기]를 클릭하여 즉시 문의해주세요." / "Your signature has been received and applied. If this was not you, please dispute immediately."** |
| N6b | "Payment required" | "Please pay the licence fee for application #{appSeq} to continue." |
| N7 | "Licence issued" | "Your electrical installation licence has been issued." |
| N8 | "Request cancelled" | "Concierge request #{publicCode} was cancelled. Refund status: {status}." |
| **N9** | **"SLA breached"** | **"24h elapsed without contact for #{publicCode}. Please contact the applicant immediately."** |

### 6.4 LOA 서명 요청 알림(N5) 설계 세부사항

LOA 서명은 **Concierge 플로우 전체에서 신청자의 유일한(또는 이에 준하는) 필수 직접 개입 지점**이므로 다음 원칙으로 강조한다:

1. **이메일 제목 강조**: `[Action Required] Please sign your LOA — LicenseKaki #{publicCode}`
2. **본문에 명시적 CTA 버튼**: "Log in and sign now" → 로그인 후 `/applicant/applications/{appSeq}?tab=loa` 로 deep link
3. **인앱 알림 우선순위**: high — 대시보드 상단에 sticky 배너 표시
4. **자동 리마인더**: 24h/48h/72h (N5-R). 72h 경과 시 담당 Manager에게 "applicant 미응답" 경고 알림 추가
5. **감사 로그**: N5 발송 및 각 리마인더 발송을 `NOTIFICATION_SENT` 액션으로 기록하여 "고지 사실" 증빙 확보

**위치**: 기존 Application의 LOA 서명 UI를 재사용하되, Concierge 경유 Application에는 상단에 "This application was prepared by your Concierge Manager — please sign to proceed" 배너 표시(§7.3).

### 6.4-1 N1 통합 이메일 설계 (★ v1.3 → v1.4 재설계, 옵션 B 기준)

**목적**: 기존 N1(확인 메일)과 N4(비번설정 메일)를 **단일 이메일**로 병합하여 UX 간결성 확보 + 발송 인프라 부담 감소. **v1.4에서는 "계정이 현재 비활성 상태이며 최초 로그인 시 활성화된다"는 안내를 본문 핵심 단락으로 승격**한다.

**이메일 제목**: `LicenseKaki 컨시어지 서비스 신청이 접수되었습니다 (#{publicCode})`

**이메일 본문 구조 (옵션 B, 권장안)**:

```
┌──────────────────────────────────────────────────────────────┐
│  LicenseKaki                                                   │
├──────────────────────────────────────────────────────────────┤
│                                                                │
│  안녕하세요 {fullName}님,                                      │
│                                                                │
│  ── 1. 신청 접수 확인 ──                                       │
│  Kaki Concierge Service 신청(#{publicCode})이 접수되었습니다. │
│  담당 매니저가 24시간 이내에 연락드리겠습니다.                │
│                                                                │
│  ── 2. 계정 생성 안내 (★ v1.4 핵심 단락) ──                   │
│  귀하의 LicenseKaki 계정이 {email} 으로 생성되었습니다.       │
│                                                                │
│  ⚠ 현재 계정은 [비활성화(PENDING_ACTIVATION)] 상태입니다.     │
│     최초 로그인 시 계정이 자동으로 활성화됩니다.              │
│                                                                │
│  ── 3. 최초 로그인(활성화) 방법 ──                             │
│  아래 중 한 가지 방법으로 로그인하실 수 있습니다:              │
│                                                                │
│  [방법 1] 이 이메일의 "계정 설정" 링크로 바로 비밀번호 설정   │
│  ┌──────────────────────────────────────────────┐             │
│  │  [ Set my password & sign in → ]             │             │
│  │  링크 유효 기간: 48시간                      │             │
│  └──────────────────────────────────────────────┘             │
│                                                                │
│  [방법 2] 로그인 페이지에서 이메일 입력 → 인증 링크 재수신    │
│  링크가 만료되었거나 나중에 로그인하실 때는 LicenseKaki       │
│  로그인 페이지에서 {email}을 입력해주세요. 본인 확인을 위한   │
│  인증 링크를 새로 보내드립니다.                                │
│  ┌──────────────────────────────────────────────┐             │
│  │  [ Go to Login page → ]                      │             │
│  └──────────────────────────────────────────────┘             │
│                                                                │
│  ── 4. 신청 정보 요약 ──                                       │
│  이름: {fullName}                                              │
│  이메일: {email}                                               │
│  모바일: {mobileNumber}                                        │
│  제출 시각: {submittedAt} (Asia/Singapore)                    │
│  담당 매니저: {assignedManagerName} ({managerEmail})          │
│                                                                │
│  ── 5. 본인이 신청하지 않으셨나요? ──                          │
│  [여기]를 클릭해 이의를 제기하시거나 support@licensekaki.sg   │
│  로 연락해주세요.                                              │
│                                                                │
│  ── 6. 다음 단계 ──                                            │
│  1) 담당 매니저 전화 연락(24시간 이내)                        │
│  2) 신청서 작성 대행                                           │
│  3) LOA 서명 (귀하의 로그인 필수)                              │
│  4) 라이선스 수수료 결제                                       │
│  5) EMA 발급                                                   │
│                                                                │
│  ── 지원 ──                                                    │
│  문의: support@licensekaki.sg · +65 xxxx xxxx                 │
│                                                                │
│  Footer: PDPA 수신거부(마케팅 동의한 경우 unsubscribe 링크)   │
│          법적 고지(약관 v{termsVersion}, 회사 정보)            │
└──────────────────────────────────────────────────────────────┘
```

**본문 필수 요소 (v1.4)**:
1. **접수 확인**: 24시간 이내 연락 예정
2. **★ 계정 안내 (핵심)**: 계정 생성 사실 + **현재 `PENDING_ACTIVATION` 상태** 명시 + **최초 로그인 시 자동 활성화**됨을 명시
3. **활성화 방법 2가지 병기**:
   - 방법 1: 이메일 내 Account Setup 링크 (즉시 비밀번호 설정, 48h 유효)
   - 방법 2: 로그인 페이지에서 이메일 입력 → 인증 링크 재수신 (옵션 B의 핵심 경로, 언제든 가능)
4. **신청 정보 요약**: publicCode, 이름·이메일·모바일, 제출 시각, 담당 Manager
5. **이의 제기 채널**
6. **다음 단계 여정**: Manager 연락 → 신청서 작성 → LOA 서명 → 결제 → 발급
7. **Footer**: PDPA 수신거부 링크(마케팅 동의한 경우), 약관 버전, 법적 고지

**HTML 템플릿**: `templates/email/concierge-request-received-with-activation.html` (기존 `concierge-request-received-with-setup.html`을 v1.4에서 교체). 기존 `SmtpEmailService`의 Thymeleaf 템플릿 패턴 준용.

### 6.4-1b ~~N1 대안 템플릿 (옵션 A)~~ — **★ v1.5에서 완전 폐기 (retired, H-5 / O-21)**

v1.4에서 "옵션 A 채택 시 사용" 부록으로 기록되었던 임시 비밀번호 이메일 템플릿은 **v1.5에서 전면 삭제된다**.

**폐기 근거**:
- **PDPA §24 (Protection Obligation)** — 민감 인증 정보를 이메일 본문에 평문으로 전송하는 것은 "적절한 보안 조치" 기준을 충족하지 못함
- **OWASP ASVS V2.1.6** — 사용자 자격증명을 이메일·SMS 등 안전하지 않은 채널로 전송 금지
- **보안 리뷰 H-5** — 이메일 서버 / 수신함 / 메일 서버 로그 / 포워딩 경로 전반에서 평문 노출 위험

**대체**: 로그인 활성화는 **옵션 B(이메일 인증 링크)** 단독으로 운영한다. 즉 N1은 §6.4-1 본 섹션의 단일 템플릿만 사용하며, §4 API 매트릭스에서 `POST /api/auth/login/force-change-password`도 함께 제거되었다.

**실패 허용 정책 (옵션 B 단독)**:
- N1 발송 실패는 비즈니스 트랜잭션을 롤백하지 않음 (afterCommit 훅 내부에서 catch-and-log)
- 발송 실패 시 success 페이지의 "Resend email" 버튼으로 재시도 가능
- 3회 이상 연속 실패 시 감사 로그 `EMAIL_DELIVERY_FAILED` + Admin 알림

### 6.4-2 N1-R (계정 미활성화 리마인더) 설계 (★ v1.3 → v1.4 재정의)

v1.3의 "계정 설정 미완료 리마인더"를 **"계정 미활성화 리마인더"**로 문구·발송 조건 재정의.

**발송 조건 (v1.4)**:
- `User.status = PENDING_ACTIVATION` AND
- `User.signupSource = CONCIERGE_REQUEST` AND
- 계정 설정 토큰 유효 (`expiresAt > NOW()`)
- 최초 N1 발송 후 24시간 경과 AND 해당 주기 리마인더 미발송

**발송 주기**:
- 24h 경과 시 1회
- 47h 경과 시 "만료 1시간 전 경고" 1회 (토큰 만료 직전)
- 만료 후에는 발송하지 않음 (Manager가 수동 재발송해야 함)

**본문**: "Your LicenseKaki account is still **not activated**. Log in or click the setup link now — the link expires in {hours}h." + 옵션 B 안내(로그인 페이지에서 이메일 입력 시 새 링크 자동 발송) + CTA 버튼 2개(Set password / Go to login)

**중복 방지**: `notification_sent_at` 컬럼 또는 감사 로그 `NOTIFICATION_SENT` 조회로 24h/47h 각 1회 발송 보장

### 6.4-2b N-Activation (로그인 시도 시 인증 링크 발송) 설계 (★ v1.4 신규, 옵션 B)

**트리거**: `POST /api/auth/login/request-activation` 호출 — 신청자가 로그인 페이지에서 이메일 입력 후 "활성화 링크 요청" 버튼 클릭

**발송 조건 (실제 메일 발송 기준, 응답과는 분리)**:
- 입력한 이메일에 `User.status = PENDING_ACTIVATION` 계정이 존재

**발송하지 않는 케이스** (이 경우에도 응답은 동일):
- 이메일이 DB에 존재하지 않음
- User.status가 `ACTIVE`, `SUSPENDED`, `DELETED` 중 하나
- deleted_at IS NOT NULL

**이메일 제목**: `LicenseKaki 계정 활성화 링크 — 로그인을 완료해주세요`

**이메일 본문**:
```
안녕하세요 {fullName}님,

LicenseKaki 로그인 페이지에서 계정 활성화 요청이 접수되었습니다.

아래 링크를 클릭하시면 비밀번호를 설정하고 바로 로그인하실 수 있습니다.

[ Activate my account → ]
(링크 유효 기간: 48시간)

── 본인 요청이 아니신가요? ──
이 이메일을 본인이 요청하지 않으셨다면 무시하셔도 안전합니다.
여러 번 반복되면 support@licensekaki.sg 로 연락해주세요.

── 보안 ──
- 링크는 1회 사용 후 만료됩니다
- URL 도메인은 licensekaki.sg 인지 확인해주세요
- 비밀번호를 누구와도 공유하지 마세요
```

**토큰 재사용 정책**:
- 기존 유효 AccountSetupToken이 있으면 **revoke 후 새 토큰 발급** (v1.2 O-17 정책 준용 — 활성 토큰 1개만 유지)
- 감사 로그 `ACCOUNT_SETUP_TOKEN_RESENT` (actor=Anonymous, metadata={email, trigger=LOGIN_REQUEST_ACTIVATION, ip, userAgent})

**UI/응답**: §4.4 "이메일 존재 여부 유출 방지" 정책 준수. 본 메일 발송 여부와 무관하게 로그인 페이지에는 항상 "If this email is registered and eligible for activation, we've sent an activation link." 고정 메시지 표시.

### 6.4-3 N5-UploadConfirm 설계 세부사항 (★ v1.2 → v1.5 강화, 경로 A 안전장치 + O-15 확정)

**Manager 대리 업로드는 위변조 위험이 가장 높은 경로**이므로 신청자에게 즉각 통지하고 이의 제기 채널을 강제로 노출한다. **★ v1.5에서 "7일 이의 제기 창구" 정책을 확정**한다(O-15 조건부 해결).

1. **이메일 제목**: `[Action May Be Required] A signature was applied on your behalf — LicenseKaki #{publicCode}`
2. **본문 필수 요소**:
   - 누가(Manager 이름), 언제(타임스탬프, Asia/Singapore TZ), 어떤 LOA(applicationSeq + publicCode)
   - Manager가 기록한 수령 채널과 메모 그대로 노출 (예: "Received via WhatsApp on 2026-04-19 14:32, memo: 'Sent by applicant directly'")
   - **이의 제기 CTA 버튼**: `[ I did NOT authorize this signature — Dispute now → ]` (deep link to `/applicant/disputes/new?app={appSeq}`)
   - **★ v1.5 신설 (O-15): 7일 묵시적 동의 창구 문구 필수 포함**:
     ```
     ⏰ 7-day dispute window:
     If you do NOT dispute this within 7 days (by {disputeDeadline}, Asia/Singapore),
     this signature will be treated as implicitly authorized by you for the purpose
     of this application.
     ```
   - 이의 제기 시 자동으로 ① Application LOCK ② Admin 긴급 알림 ③ 감사 로그 `LOA_SIGNATURE_DISPUTED` 생성 (별도 이슈로 Phase 3+ 구현)
3. **인앱 알림**: high priority, sticky 배너 **7일간** 유지 (이의 제기 창구와 동일 기간)
4. **감사 증적**: 발송 자체를 `NOTIFICATION_SENT` 액션으로 기록하여 "고지 사실"을 보존. 7일 경과 후 이의 없으면 `LOA_SIGNATURE_IMPLICIT_CONSENT_LAPSED` 감사 로그 자동 생성(Phase 3 스케줄러, O-22와 같은 인프라 재사용)
5. **법무 재검토 조건 (O-1 / O-15)**: 배포 전 싱가포르 법무 자문에서 7일 기간 적정성과 "묵시적 동의 간주" 문구의 ETA / PDPA §22A / EMA 가이드라인 충족 여부를 확인받아야 함. 자문 결과에 따라 기간·문구 조정 가능

### 6.4-4 N5-Alt 설계 세부사항 (★ v1.2, 경로 B 발송)

원격 링크 발송은 신청자가 로그인 없이 서명할 수 있어 편의성이 높지만 발송 채널이 손상되면 위변조 가능. 다음으로 보강:

1. **이메일 제목**: `[Action Required] Sign your LicenseKaki LOA — secure link inside`
2. **본문 필수 요소**:
   - "이 링크는 1회 사용 후 자동 만료됩니다 / 48시간 후 만료" 명시
   - "본인이 요청하지 않은 경우 즉시 [Manager 이름]에게 문의하세요" 안내
   - URL 미리보기를 평문으로 노출(`https://licensekaki.sg/sign/...`) — 피싱 의심 시 도메인 직접 확인 가능
3. **SMS 발송 시**: 짧은 안내문 + 단축 URL 금지(원본 도메인 그대로 발송, 피싱 오해 방지)
4. **링크 클릭 → 본인확인 단계까지가 1차 보안**, 5회 실패 시 잠김
5. **감사 증적**: 발송 채널/시점/대상을 `LOA_REMOTE_SIGN_TOKEN_ISSUED` metadata에 기록

### 6.5 발송 오케스트레이션 (`ConciergeNotifier` 신규)

Phase 3의 `DocumentRequestNotifier` 패턴을 그대로 답습:
- `NotificationService`(REQUIRES_NEW) + `EmailService`를 주입
- 모든 발송은 `TransactionSynchronizationManager.registerSynchronization(afterCommit())`로 감싸 롤백 안전 보장
- 개별 실패는 catch-and-log, 비즈니스 트랜잭션 롤백하지 않음
- SMTP 구현체는 기존 `SmtpEmailService` 확장, 로그온리는 `LogOnlyEmailService`에 stub 추가

---

## 7. On-Behalf-Of 작업 처리

### 7.1 설계 원칙

**선택지 비교**:

| 방식 | 설명 | 장점 | 단점 | 채택 |
|------|------|------|------|------|
| A. Impersonation (세션 탈취) | Manager가 일시적으로 Applicant로 로그인된 것처럼 동작 | 기존 ApplicationService 그대로 재사용 | 감사 로그 주체 모호, JWT/세션 오염 위험, 법적 신뢰성 낮음 | ❌ |
| B. **Ownership 할당 + Actor 분리** | 모든 요청은 Manager의 JWT로 인증하되, 서비스 레이어가 `ownerUserSeq`를 명시적 인자로 받아 Application.user = applicant로 세팅. `@CreatedBy`는 현재 principal(Manager) | 감사 로그 선명, JWT 오염 없음, 기존 패턴과 충돌 적음 | ApplicationService에 `createOnBehalfOf(ownerSeq, req)` 오버로드 필요 | ✅ **채택** |
| C. 별도 "대리 신청서" 엔티티 | Application과 분리된 DraftApplication을 Manager가 작성 후 Applicant가 수락 시 Application으로 승격 | 소유권 이전이 명시적 | 엔티티 이중화, LOA 생성 시점 혼란, UI 복잡도↑ | ❌ |

### 7.2 서명·인증 단계별 대행 가능 여부 조사 결과 (★ 중요)

각 단계별로 **기존 코드베이스의 검증 로직 / 법적·운영적 고려사항 / 대응**을 정리한다.

#### 7.2.1 본인 인증/개인 행위 (대행 불가 또는 제한적 보조 경로)

| 단계 | 사유 (코드 근거) | 대응 |
|------|----------------|------|
| **이메일 인증** | 개인 메일함 접근이 필요 — 서버가 본인 여부를 확인할 유일한 방법 | 자동 계정 생성 시 password-reset 토큰을 포함한 이메일 발송 → Applicant 본인이 받은편지함에서 링크 클릭 |
| **패스워드 재설정** | 토큰 기반 본인 인증(기존 `/api/auth/reset-password`) | Applicant 본인만 가능. Manager는 "재발송" 트리거만 보유 (`resend-setup-email` API) |

#### 7.2.1-LOA LOA 전자서명 — 3-경로 모델 (★ v1.2 재정의)

**원칙**: LOA 전자서명은 신청자 본인의 행위로 간주되어야 한다. 단, 신청자의 디지털 접근성·시간 제약을 고려해 **법적 무결성 등급을 명시한 3가지 경로**를 제공한다.

| 경로 | 주체 | 인증 수단 | 코드 진입점 | 법적 무결성 | 채택 단계 |
|------|------|----------|-----------|------------|----------|
| **원칙: APPLICANT_DIRECT** | 신청자 (본인 로그인) | JWT (이메일+비번 인증된 세션) | `POST /api/applications/{id}/loa/sign` (기존) — `LoaService.signLoa` 소유권 검증 통과 | **최고** — 본인 인증 + 자발적 행위가 모두 강한 증명 | Phase 1부터 |
| **경로 A: MANAGER_UPLOAD** | Manager (대리 업로드) | Manager JWT + 확인 체크박스 + 신청자 자동 확인 메일(N5-UploadConfirm) + **★ v1.5: 7일 이의 제기 창구** | `POST /api/concierge-manager/applications/{id}/loa/upload-signature` (**★ v1.5 경로 이관** — 기존 `/api/admin/...`에서 분리, H-4 반영. CONCIERGE_MANAGER + ADMIN만 접근 가능, LEW 제외) | **중** — 자필 서명물의 출처/진위는 Manager 확인에 의존. 이의 제기 채널 의무 노출 + **7일 묵시적 동의 창구**(O-15)로 보완 | Phase 1 |
| **경로 B: REMOTE_LINK** | 신청자 (간이 인증, 비로그인) | 1회성 토큰(URL path) + 본인확인(이메일 last-4 또는 OTP) + IP/UA 로그 | `POST /api/public/loa-sign/{token}/submit` (신규) | **상** — 본인 디바이스에서의 자발적 행위 증명 + 토큰 1회성 + 감사 로그. 단, 본인확인 강도는 **OTP 적용 시(Phase 3) 더 강화** | Phase 2 |

**3-경로의 코드 레벨 라우팅**:

```
                                    ┌─ APPLICANT_DIRECT ─► 기존 LoaService.signLoa
                                    │                       (소유권 검증, JWT subject = application.user)
                                    │
LOA 서명 발생 진입점 ────────────────┼─ MANAGER_UPLOAD ────► LoaService.uploadSignature(managerSeq, file, source, memo)
                                    │                       (Manager JWT + 확인 체크 + N5-UploadConfirm 발송)
                                    │
                                    └─ REMOTE_LINK ───────► LoaService.submitViaToken(token, signaturePng, ip, ua)
                                                            (토큰 검증 + IP/UA 기록 + 본인확인 통과 필수)
```

3개 경로 모두 **최종적으로 동일한 `embedSignatureIntoPdf()`를 호출**하여 LOA PDF에 서명 이미지를 임베드한다. 즉 `loa_signature_url`(서명 PNG storage URL)과 LOA PDF 자체는 단일 파이프라인을 통과하며, 차이는 **수집 메타데이터(`loa_signature_source`)에만 기록**된다.

**기존 LOA 정책과의 정합성**:
- `LoaService.signLoa`(기존)의 소유권 검증은 **그대로 유지**. Manager가 이 API를 호출하면 여전히 403 `LOA_SIGNATURE_REQUIRES_APPLICANT`. 경로 A/B는 별도 메서드로 분리되어 구현되며, 기존 검증 회피가 아니라 **명시적 우회 경로**임을 코드로 표현한다.
- LOA 스냅샷 4개 신원 컬럼(`@Column(updatable=false)`)은 v1.2에서도 변경 없음. 신원은 여전히 불변, 서명 출처 라벨만 가변.
- AC-6은 v1.2에서도 유효 (Manager가 *기존* `signLoa`를 호출하면 403). 새 경로 A/B는 별도 AC-15/16/17로 검증.

#### 7.2.2 법적/운영 검토 필요 항목

| 단계 | 이슈 | PRD 처리 |
|------|------|---------|
| **PDPA 개인정보 동의** | 대행 시 법적 책임은 신청자에게 귀속됨 — 제3자(Manager)가 대리 동의 시 PDPA 위반 소지 | **Concierge 신청 폼(§2.2)에서 신청자 본인이 PDPA 동의 체크박스에 직접 체크**. Manager는 **대리 동의 불가**. 동의 스냅샷은 `pdpaConsentAt` 타임스탬프로 기록 |
| **Concierge 서비스 요금 결제** | `Payment.createdBy`에 Manager 계정이 남으면 회계 감사 이슈(누가 지불했는가?) | 신청 폼에서 **신청자 본인이 즉시 결제**(Phase 2부터 PG 연동). `Payment.createdBy`는 Applicant. Manager는 결제 화면을 보지도 않음 |
| **라이선스 수수료 결제** | 동일 (회계 감사) | 원칙: **신청자 본인이 결제**. Manager는 결제 안내 리마인더만 발송. 예외적으로 대리 결제가 필요할 경우 Admin 승인 별도 워크플로 설계(Phase 3+) |

#### 7.2.3 대행 가능 (근거 있음)

| 단계 | 근거 | 비고 |
|------|------|------|
| **Application 엔티티 생성** | `OwnershipValidator.validateOwnerOrAdminOrAssignedLew()`가 `ROLE_ADMIN`을 통과시킴(`OwnershipValidator.java:38-42`). CONCIERGE_MANAGER는 `/admin/**` 권한 그룹 편입 예정 → Admin 권한 준용 | Manager JWT로 호출, `ownerUserSeq`를 인자로 받아 Application.user = Applicant로 세팅 |
| **문서 업로드** | 동일 (OwnershipValidator Admin bypass) | Manager가 업로드 시 `FileEntity.createdBy = Manager`로 기록 — 감사 로그에 대리 업로드 사실 남음 |
| **SLD REQUEST_LEW 플로우 진행** | `SldOption` 플래그로 분기, 오너십은 Application 소유자 기준 | Manager가 "SLD는 LEW에게 요청" 옵션을 대리 선택 |
| **LEW 할당 요청** | Admin이 실제 할당을 승인, Manager는 요청만 | Manager → Admin 할당 요청 → Admin 승인 → LEW 검토 시작 |
| **LOA PDF 생성 트리거** | `/api/admin/applications/{id}/loa/generate` (Admin 권한) | Manager가 **생성만** 호출, 서명은 Applicant가 수행 |

### 7.3 Concierge Manager 업무 플로우 (책임 분장표)

**[Manager가 대리 수행]**

| 순서 | 업무 | 엔드포인트 / 액션 |
|------|------|------------------|
| 1 | 신청자 최초 연락 (24h SLA) | `POST /api/concierge-manager/requests/{id}/notes` (첫 노트 추가 시 `CONTACTING` 전이) |
| 2 | Application 엔티티 생성 | `POST /api/concierge-manager/requests/{id}/applications` (Manager JWT, `ownerUserSeq = applicantUser.seq`) |
| 3 | 신청서 필드 입력, 서류 업로드 | 기존 `/admin/applications/{id}/**` 재사용 (Admin 권한 준용) |
| 4 | LEW 할당 요청 | `POST /api/admin/applications/{id}/assign-lew` (Admin 승인 대기) |
| 5 | **LOA PDF 생성 트리거** | `POST /api/admin/applications/{id}/loa/generate` → 상태 `AWAITING_APPLICANT_LOA_SIGN` |
| 6 | SLD 주문/생성 대행 | REQUEST_LEW 플로우 (SldOption) |
| 7 | 전 과정 상태 추적 및 진행 보고 | Manager 대시보드 + 신청자에게 주기 리포트 이메일 |

**[신청자 본인이 반드시 수행] — Manager가 절대 대신할 수 없는 5가지**

| 순서 | 업무 | 시점 |
|------|------|------|
| 1 | **PDPA 동의** | Concierge 신청 폼 제출 시 (§2.2 체크박스) |
| 2 | **Concierge 서비스 요금 결제** | 신청 폼 "Pay & Submit" 클릭 시 (Phase 2 이후) |
| 3 | **계정 활성화** | N4 이메일 수신 → 비밀번호 재설정 링크 클릭 → 비밀번호 설정 |
| 4 | **LOA 전자서명** ★ | N5 알림 수신 → 로그인 → `/applicant/applications/{appSeq}?tab=loa` → 서명 |
| 5 | **라이선스 수수료 결제** | Application이 `PENDING_PAYMENT` 도달 시 신청자가 직접 결제 (`PAID` 전환) |

### 7.4 구현 포인트

- **인증 주체**: Manager의 JWT. `@AuthenticationPrincipal User currentUser`는 항상 Manager.
- **리소스 소유자**: Application.user는 대상 APPLICANT. Service 레이어가 명시적으로 세팅.
- **Created By / Updated By**: 기존 `BaseEntity` + `AuditingEntityListener` 그대로 동작 — `created_by = Manager.userSeq`. 이것이 "대리 작성" 증거.
- **추가 플래그**: `Application.via_concierge_request_seq = 해당 요청 id` (§3.5).
- **LOA 서명 분리**: `LoaService.signLoa`의 소유권 검증(`LoaService.java:132`)은 변경하지 않는다. Manager가 서명 API를 호출하면 기존 로직이 403을 반환하므로 **추가 방어 코드 불필요**. 단, 에러 코드를 `ACCESS_DENIED` → `LOA_SIGNATURE_REQUIRES_APPLICANT`로 세분화하여 프론트엔드 UX 개선 권장.
- **감사 로그 필수 기록**:
    ```
    AuditAction.APPLICATION_CREATED_ON_BEHALF
      actor: Manager.userSeq
      target_user: Applicant.userSeq
      target_entity: "APPLICATION"
      target_id: newAppSeq
      metadata: { conciergeRequestSeq, ownerEmail }
    ```

### 7.5 신청자의 진행 상황 조회 및 LOA 서명 UX

- 신청자는 자기 이메일로 로그인 → 기존 `/applicant/applications` 목록에 그대로 노출 (기존 로직, `Application.user = me` 필터).
- 대시보드 상단 배너: "This application was prepared by your Concierge Manager ({name}). **Please sign the LOA to proceed.**"
- 배너 안에 `[ Sign LOA Now → ]` 1차 CTA 버튼 (deep link to LOA tab).
- 서명 미완료 상태에서 로그인 시 sticky toast 알림도 병행.

### 7.6 LOA 서명 정책 (법적 무결성, ★ v1.2 3-경로 모델로 갱신)

**원칙 재확인**: LOA는 신청자 의사를 표시하는 서명 이미지로 불변 스냅샷 기록됨 (`loa_signature_url`, 스냅샷 4개 신원 컬럼 `updatable=false`). 이는 **현행 Singapore EMA 실무의 위임장 요건** + Singapore Electronic Transactions Act (ETA, Cap 88) 전자서명 인정 범주와 정합.

**정책 (v1.2)**:
1. **기본 원칙**: LOA 서명은 신청자 본인의 의사 표시여야 하며, **3가지 수집 경로**(§7.2.1-LOA) 중 하나를 통해 수집된다.
2. **기존 코드 레벨 보장은 유지**: `LoaService.signLoa(userSeq, ...)`의 소유권 검증은 변경하지 않음. Manager의 *직접 서명*은 여전히 코드 레벨에서 차단(403 `LOA_SIGNATURE_REQUIRES_APPLICANT`).
3. **경로 A (MANAGER_UPLOAD)**: Manager가 신청자로부터 외부 채널(이메일/SMS/대면)로 받은 자필 서명 파일을 대리 업로드하는 별도 메서드(`uploadSignature`)를 통해서만 가능. 다음 모든 조건이 충족되어야 함:
   - Manager의 확인 체크박스(`managerConfirmation=true`) 필수
   - 수령 채널(`receivedVia`)과 메모(`memo`)가 감사 로그에 기록됨
   - 신청자에게 **N5-UploadConfirm 자동 발송** + 이의 제기 채널 노출 (즉시성: afterCommit 훅으로 트랜잭션 커밋 후 발송)
   - 신청자 응답 없이 7일 경과 시 묵시적 동의로 간주 (운영 정책, 법무 확정 필요 — O-1 참조)
4. **경로 B (REMOTE_LINK)**: 1회성 토큰을 통해 신청자가 비로그인으로 서명. 다음 보안 요구사항 충족:
   - 토큰 발급은 Manager만 가능, 토큰 자체는 신청자가 1회 사용
   - URL path에만 토큰 노출(쿼리스트링 ❌, Referer 유출 방지)
   - 본인확인 단계(이메일 last-4 또는 모바일 OTP) 통과 필수
   - 서명 시점 IP·User-Agent를 `LoaSigningToken.signedFromIp/UserAgent`에 기록
   - 5회 본인확인 실패 시 자동 잠김
5. Application이 `PENDING_REVIEW`에 도달해도 `loa_signature_url IS NULL`이면 LEW 검토 큐 진입 금지(기존 로직 유지, 3개 경로 어느 것을 통해서든 세팅되면 동일하게 진행).
6. Manager는 직접 서명 경로의 리마인더 이메일 재발송 액션 보유 (`POST /api/concierge-manager/requests/{id}/resend-loa-reminder`).

**법적 무결성 등급별 처리**:

| 경로 | 분쟁 발생 시 증거 우선순위 | 추가 증적 |
|------|--------------------------|---------|
| APPLICANT_DIRECT | 1순위 — JWT 인증 + 자발적 서명 행위 | 로그인 IP/UA(기존 audit) |
| REMOTE_LINK | 2순위 — 토큰 1회성 + 본인확인 + IP/UA | 토큰 발급/사용 시점, 본인확인 성공 로그 |
| MANAGER_UPLOAD | 3순위 — Manager 책임에 의존 | Manager 확인 체크 + 수령 채널 메모 + N5-UploadConfirm 발송 증적 + 이의 제기 무응답 7일 경과 |

분쟁 발생 시 우선순위가 낮은 경로일수록 추가 증적이 중요하므로, 감사 로그 보존 기간을 **최소 7년**으로 설정 (PDPA 보존 정책과 정합).

**잔여 의사결정 (O-1 재정의)**:
- 경로 A의 묵시적 동의 기간(7일) 적정성 → 법무
- 경로 B의 본인확인 수단으로 OTP 강제 시점 → Phase 3에서 결정 (기본은 이메일 last-4)
- ETA Section 8(전자서명 인정 요건)에 비추어 경로 B의 토큰+OTP 조합이 "secure electronic signature"에 해당하는지 검토 → 법무

### 7.7 기존 회원 vs 신규 가입 케이스 처리 (★ v1.3 신규)

컨시어지 신청 시 제출된 이메일이 기존 User와 매칭될 경우의 분기 처리. 서비스 레이어(`ConciergeRequestService.submit(...)`)에서 **트랜잭션 진입 직후 첫 번째로 수행**.

#### 7.7.1 케이스 분기표

| # | 매칭 상태 | 처리 | 사용자 경험 | 감사 로그 |
|---|----------|------|-----------|----------|
| **C1** | 미가입 이메일 | 신규 User 생성 (APPLICANT, `signupSource=CONCIERGE_REQUEST`, **`status=PENDING_ACTIVATION`**, 임시 해시) + `UserConsentLog` 4~5건 + ConciergeRequest 생성 + N1 이메일 | "Account created — check your inbox for activation instructions" | `CONCIERGE_ACCOUNT_AUTO_CREATED`, `USER_CONSENT_RECORDED`×N, `ACCOUNT_SETUP_TOKEN_ISSUED` |
| **C2** | 기존 APPLICANT 회원 (`status=ACTIVE`) | 기존 User에 ConciergeRequest 연결. 새로운 동의는 `UserConsentLog`에 기록(버전 업데이트 포함). **비밀번호·활성화 상태 유지** | "Linked to your existing account — log in to track progress" (N1-Alt 메일) | `CONCIERGE_EXISTING_USER_LINKED`, `USER_CONSENT_RECORDED`×N |
| **C3 (★ v1.4 재정의)** | 기존 APPLICANT 회원 (**`status=PENDING_ACTIVATION`**, 아직 활성화 미완료) | **중복 계정 생성 방지** — 기존 User에 ConciergeRequest 연결 + 계정 설정 토큰 **재발급**(기존 토큰 invalidate) + N1 재발송 | "We've sent a new activation link to your email." | `CONCIERGE_EXISTING_USER_LINKED`, `ACCOUNT_SETUP_TOKEN_RESENT`, `USER_CONSENT_RECORDED`×N |
| **C4** | LEW / ADMIN / SYSTEM_ADMIN / SLD_MANAGER / CONCIERGE_MANAGER | **신청 차단** — HTTP 409 `STAFF_EMAIL_NOT_ALLOWED` | "This email belongs to a staff account. Please contact support or use a different email." | `CONCIERGE_STAFF_EMAIL_BLOCKED` (actor=IP, metadata={email, attemptedRole}) |
| **C4b (★ v1.4 신규)** | `status=SUSPENDED` | **신청 차단** — HTTP 409 `ACCOUNT_SUSPENDED`. 문구에서는 사유 미공개(단순히 "Unable to process — contact support") | "We're unable to process this request. Please contact support." | `CONCIERGE_SUSPENDED_USER_BLOCKED` (Admin 감사용) |
| **C5** | `deleted_at IS NOT NULL` (soft-deleted, **`status=DELETED`**) | **오픈 이슈 O-18 — 법무 확정 전까지는 C1과 동일하게 처리**(신규 User 생성). 기존 soft-deleted row는 그대로 유지, 이메일 unique 제약을 피하기 위해 DB 레벨에서 soft-deleted row는 `(email, deleted_at)` 복합 유니크 적용 | "Account created" (사용자에게는 신규와 동일 UX) | `CONCIERGE_ACCOUNT_AUTO_CREATED` + metadata에 `hadSoftDeletedAccount=true` 플래그 |

#### 7.7.2 구현 세부사항

```java
// ConciergeRequestService.java (pseudocode)
@Transactional
public ConciergeRequestCreateResponse submit(ConciergeRequestCreateRequest req, String ip, String ua) {
    // 1. 이메일 정규화 (lowercase, trim)
    String normalizedEmail = req.email().toLowerCase().trim();

    // 2. 기존 User 조회 (soft-deleted 포함 여부는 별도 쿼리)
    Optional<User> existingActive = userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail);
    Optional<User> existingDeleted = userRepository.findByEmailAndDeletedAtIsNotNull(normalizedEmail);

    // 3. 케이스 분기 (★ v1.4: User.status 기준으로 C2/C3/C4b 분기)
    UserAccountResolution resolution;
    if (existingActive.isPresent()) {
        User existing = existingActive.get();
        if (existing.getRole() != UserRole.APPLICANT) {
            auditService.record(CONCIERGE_STAFF_EMAIL_BLOCKED, ...);
            throw new BusinessException("STAFF_EMAIL_NOT_ALLOWED", HttpStatus.CONFLICT);
        }
        resolution = switch (existing.getStatus()) {
            case ACTIVE             -> UserAccountResolution.existingActive(existing);           // C2
            case PENDING_ACTIVATION -> UserAccountResolution.existingPendingActivation(existing); // C3
            case SUSPENDED          -> {                                                         // C4b
                auditService.record(CONCIERGE_SUSPENDED_USER_BLOCKED, ...);
                throw new BusinessException("ACCOUNT_SUSPENDED", HttpStatus.CONFLICT);
            }
            case DELETED -> throw new IllegalStateException("status=DELETED but deleted_at=null");
        };
    } else if (existingDeleted.isPresent()) {
        // C5: O-18 확정 전까지 신규 생성 경로 (감사 로그에 플래그)
        resolution = UserAccountResolution.newAccountWithDeletedHistory();
    } else {
        resolution = UserAccountResolution.newAccount();             // C1
    }

    // 4. User 생성/업데이트
    User user = switch (resolution.getType()) {
        case NEW_ACCOUNT, NEW_WITH_DELETED_HISTORY ->
            userService.createForConcierge(req, /* status=PENDING_ACTIVATION */);
        case EXISTING_ACTIVE, EXISTING_PENDING_ACTIVATION ->
            resolution.getExistingUser();
    };

    // 5. 동의 스냅샷 기록 (UserConsentLog 4~5건)
    consentLogService.recordAll(user, req, ConsentSourceContext.CONCIERGE_REQUEST, ip, ua);

    // 6. ConciergeRequest 생성
    ConciergeRequest cr = ConciergeRequest.create(req, user, delegationConsentAt=NOW());
    conciergeRequestRepository.save(cr);

    // 7. 계정 설정 토큰 발급/재발급 (PENDING_ACTIVATION인 경우만)
    AccountSetupToken token = null;
    if (user.getStatus() == UserStatus.PENDING_ACTIVATION) {
        token = accountSetupTokenService.issueOrReissue(user);
    }

    // 8. afterCommit 알림 발송
    registerAfterCommit(() -> conciergeNotifier.sendSubmissionEmails(cr, resolution, token));

    return ConciergeRequestCreateResponse.of(cr, resolution, token);
}
```

#### 7.7.3 제약 및 주의

- **이메일 대소문자**: DB는 `email`을 lowercase로 저장하되, 조회 시에도 lowercase로 정규화하여 대소문자 차이로 인한 중복 생성 방지.
- **동시성**: 같은 이메일로 동시에 2건의 요청이 들어오면 DB 유니크 제약(`users.email`)이 2번째 INSERT를 실패시킴. 서비스 레이어에서 `DataIntegrityViolationException` 캐치하여 재조회 후 C2/C3 경로로 폴백.
- **Race condition** between check-email 응답과 실제 submit: check-email API는 **UX 힌트만**이며, 최종 결정은 submit 트랜잭션 내부에서 재확인.
- **Manager는 신청자의 계정 설정을 대행할 수 없음** — 본인 비밀번호 설정은 신청자 본인의 토큰 클릭으로만 가능. Manager는 "재발송" 트리거만 보유 (§7.3 책임 분장표와 정합).

---

## 8. 결제 통합

### 8.1 결제 엔티티 전략 — Payment 재사용 (확정)

**O-6 해결**: 새로운 `ConciergePayment` 엔티티를 만들지 않고, 기존 `Payment` 엔티티를 재사용한다. 다만 각 결제가 어떤 도메인(Application/SldOrder/ConciergeRequest)에 속하는지 식별할 수 있도록 **다형 참조 컬럼 2종을 추가**한다.

```java
// Payment 엔티티 확장 (§3.8.1 재게시)
public enum PaymentReferenceType {
    APPLICATION,         // 라이선스 신청 수수료 (기존 데이터)
    SLD_ORDER,           // SLD 주문 결제 (기존)
    CONCIERGE_REQUEST    // Concierge 서비스 요금 (신규)
}

@Entity
public class Payment extends BaseEntity {
    // 기존 application FK는 유지하되 nullable
    @ManyToOne @JoinColumn(name = "application_seq", nullable = true)
    private Application application;

    // 신규 컬럼 2종
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 30)
    private PaymentReferenceType referenceType;

    @Column(name = "reference_seq", nullable = false)
    private Long referenceSeq;
}
```

**장점**:
- PG 연동 코드(토큰 발급, 콜백 처리, 환불) 공통화
- 결제 내역 조회/리포트에서 `GROUP BY reference_type` 한 쿼리로 도메인별 집계 가능
- 기존 Payment 관련 테스트·서비스 코드 재사용

**트레이드오프**:
- `Payment.application` FK와 `reference_type`이 중복 정보 — backfill 규칙으로 해소(§3.8.2)
- 다형 참조의 참조 무결성은 DB가 강제할 수 없음 → 서비스 레이어 검증 필수

### 8.2 서비스 요금 정책 (가정값 — 확정 필요, O-5 미해결)

| 플랜 | 가격(SGD) | 포함 범위 |
|------|-----------|----------|
| Basic | 500 | NEW licence 단건 대행, LEW 조율, 문서 업로드 대행 |
| Plus | 800 | Basic + 긴급 처리(SLA 12h), 현장 사진 기반 SLD 요청 대행 |
| Renewal | 300 | RENEWAL 단건 대행 |

> EMA 법정 수수료, LEW 수수료, SLD 비용은 **불포함**. 이는 Application의 기존 결제 플로우를 따름.

### 8.3 Phase 2 결제 흐름 (Concierge 서비스 요금)

**신청 시점에 즉시 결제 — Manager는 결제 흐름에 개입하지 않는다 (§7.2.2 회계 감사 이슈).**

1. 신청자가 `/concierge/request` 폼에서 PDPA 동의 + 자동계정 동의 체크 + "Pay & Submit" 클릭
2. 서버가 `ConciergeRequest`(status=SUBMITTED, payment=null) 생성 → PG 결제창 URL 반환
3. 신청자 본인이 PG 결제 완료
4. 결제 콜백 수신 → `Payment` 생성
   ```java
   Payment.of(
       PaymentReferenceType.CONCIERGE_REQUEST,
       conciergeRequest.getConciergeRequestSeq(),
       amount,
       txnId,
       ...
   );
   // createdBy = Applicant (본인 결제)
   ```
5. `ConciergeRequest.payment` FK 세팅 (후처리)
6. 결제 실패 시: ConciergeRequest는 여전히 `SUBMITTED`로 남고, 프론트는 "결제 미완료, 재시도하세요" 안내. Manager 대시보드에는 "결제 대기" 배지 노출. **48h 미결제 시 자동 취소 스케줄러** (별도 이슈).

### 8.4 Payment 도메인별 조회 패턴

| 용도 | 쿼리 |
|------|------|
| Concierge 요청의 결제 조회 | `findByReferenceTypeAndReferenceSeq(CONCIERGE_REQUEST, requestSeq)` |
| 특정 고객의 전 도메인 결제 이력 | `findByCreatedBy(userSeq)` |
| 월별 도메인별 매출 리포트 | `SELECT reference_type, SUM(amount) FROM payments WHERE paid_at BETWEEN ? AND ? GROUP BY reference_type` |
| 기존 Application 결제 (legacy) | 기존 `findByApplication(app)` 유지 (application_seq가 여전히 채워져 있음) |

### 8.4b Payment 단건 조회 권한 분기 (★ v1.5 신규, 보안 리뷰 M-5 반영)

`GET /api/payments/{id}` 같은 단건 조회 API에서 `Payment.referenceType`에 따라 **서로 다른 오너십 검증**을 적용해야 한다. 통일된 권한 체크를 사용하면 Concierge 요청 결제를 Application 소유자 기준으로 조회해버리는 등 경계가 흐려진다.

| `referenceType` | 권한 검증 헬퍼 | 조회 가능 주체 |
|-----------------|--------------|--------------|
| `APPLICATION` | 기존 `OwnershipValidator.validateApplicationOwnerOrAssignedLewOrAdmin(paymentOwner, currentUser)` | 신청자 본인 / 배정된 LEW / ADMIN |
| `CONCIERGE_REQUEST` | ★ v1.5 신규 `OwnershipValidator.validateConciergeRequestOwnerOrAssignedManagerOrAdmin(request, currentUser)` | 신청자 본인 / 담당 Concierge Manager / ADMIN. **LEW는 접근 금지** |
| `SLD_ORDER` | 기존 SLD 주문 권한 체크 (별도 모듈) | SLD 주문자 / SLD_MANAGER / ADMIN |

**구현 포인트 (Phase 2 PR에 반영)**:
```java
@GetMapping("/api/payments/{id}")
public PaymentResponse getPayment(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
    Payment payment = paymentRepository.findByIdOrThrow(id);

    switch (payment.getReferenceType()) {
        case APPLICATION -> {
            Application app = applicationRepository.findByIdOrThrow(payment.getReferenceSeq());
            ownershipValidator.validateApplicationOwnerOrAssignedLewOrAdmin(app, currentUser);
        }
        case CONCIERGE_REQUEST -> {
            ConciergeRequest cr = conciergeRequestRepository.findByIdOrThrow(payment.getReferenceSeq());
            ownershipValidator.validateConciergeRequestOwnerOrAssignedManagerOrAdmin(cr, currentUser);
        }
        case SLD_ORDER -> {
            SldOrder order = sldOrderRepository.findByIdOrThrow(payment.getReferenceSeq());
            sldOwnershipValidator.validate(order, currentUser);
        }
    }
    return PaymentResponse.from(payment);
}
```

**테스트 매트릭스 (Phase 2 필수)**:
- LEW가 CONCIERGE_REQUEST 결제 조회 시도 → 403 (**M-5 핵심**)
- 다른 Concierge Manager가 본인 담당 아닌 요청 결제 조회 시도 → 403
- ADMIN은 모든 referenceType bypass
- SLD_ORDER 결제를 APPLICATION 경로로 조회 시도 → 403 (referenceType 교차 차단)

### 8.5 환불 정책 (초안 — 법무/비즈니스 확정 필요)

| 시점 | 환불 비율 |
|------|----------|
| 요청 제출 후 24h 이내 & 아직 `CONTACTING` 전 | 100% |
| `CONTACTING` 또는 `APPLICATION_CREATED` 이전 | 80% (상담 수수료 20%) |
| `APPLICATION_CREATED` 이후 | 환불 불가 (사례별 검토) |
| 24h SLA 미준수(Manager 귀책) | 100% (자동 승인 대상 고려) |
| Plus 플랜 SLA 미준수 | 차액 환불 (Basic 수준으로 자동 다운그레이드) |

**환불 구현**: `Payment.refund(amount, reason)` → 감사 로그 `PAYMENT_REFUNDED` → PG 환불 API 호출 → `ConciergeRequest.cancel(reason)`.

---

## 9. 수용 기준 (Acceptance Criteria)

1. **AC-1 (신청 제출)**
   GIVEN Visitor가 `/concierge/request`에서 유효한 이름·이메일·모바일·PDPA+자동계정 동의 체크
   WHEN `POST /api/concierge/requests` 호출
   THEN 201과 `{publicCode, status:SUBMITTED}`를 반환하고, DB에 ConciergeRequest + User(APPLICANT, password=랜덤, emailVerified=false, emailVerificationToken 세팅) + AuditLog(`CONCIERGE_REQUEST_SUBMITTED`, `CONCIERGE_ACCOUNT_AUTO_CREATED`) 생성.

2. **AC-2 (중복 이메일 재신청)**
   GIVEN 같은 이메일로 이미 User가 존재(일반 가입자)
   WHEN 신규 Concierge 요청 제출
   THEN User는 새로 생성하지 않고 기존 User에 연결하며, 이메일 재발송은 "Set password"가 아닌 "We received your new Concierge request" 안내.

3. **AC-3 (즉시 알림)**
   GIVEN 신규 요청 제출 트랜잭션 커밋
   WHEN afterCommit hook 실행
   THEN 제출자 이메일로 확인 메일 1통 + 비번설정 메일 1통, 모든 Admin + 모든 CONCIERGE_MANAGER에게 인앱 알림 + 이메일이 발송된다. 메일 전송 실패는 비즈니스 트랜잭션을 롤백하지 않는다.

4. **AC-4 (할당되지 않은 Manager 접근 차단)**
   GIVEN `assigned_manager_seq != currentUser`인 Manager가 `PATCH /status`
   THEN 403 `FORBIDDEN`. ADMIN은 통과.

5. **AC-5 (대리 Application 생성 소유권)**
   GIVEN Manager가 `POST /api/concierge-manager/requests/{id}/applications`를 본인 담당 요청에 호출
   WHEN 요청 body 유효
   THEN `Application.user = request.applicantUser`, `Application.created_by = Manager.userSeq`, `Application.via_concierge_request_seq = requestSeq` 로 저장. 상태 전이 `CONTACTING → APPLICATION_CREATED`. 감사 로그 `APPLICATION_CREATED_ON_BEHALF`.

6. **AC-6 (LOA 서명은 본인만)**
   GIVEN 대리 작성된 Application이 `loa_signature_url IS NULL`
   WHEN Manager의 JWT로 `POST /api/applications/{id}/loa/sign` 호출
   THEN 403 `LOA_SIGNATURE_REQUIRES_APPLICANT` (기존 `LoaService.signLoa` 소유권 검증으로 보장). Applicant JWT만 허용.

7. **AC-7 (상태 역행 금지)**
   GIVEN 현재 `COMPLETED`
   WHEN `PATCH /status` 로 `CONTACTING` 전이
   THEN 409 `INVALID_STATE_TRANSITION`.

8. **AC-8 (완료 자동 동기화)**
   GIVEN 연결된 Application이 `COMPLETED`로 전이
   WHEN Application 상태 변경 이벤트 리스너가 동작
   THEN ConciergeRequest.status = COMPLETED, completedAt 기록, Applicant 완료 이메일 발송.

9. **AC-9 (24h SLA 위반 경고)**
   GIVEN ConciergeRequest가 `SUBMITTED` 또는 `ASSIGNED` 상태이고 `createdAt + 24h < NOW()`이며 `firstContactAt IS NULL`
   WHEN SLA 스케줄러가 시간당 실행
   THEN N9 알림(담당 Manager + Admin 대상)이 1회 발송, Admin 대시보드에 "SLA BREACHED" 뱃지 노출, 감사 로그 `SLA_BREACHED` 기록. 중복 발송 방지를 위해 `sla_breach_notified_at` 컬럼 또는 감사 로그 존재 여부로 가드.

10. **AC-10 (Payment referenceType 무결성)**
    GIVEN Concierge 서비스 요금 결제가 발생
    WHEN `Payment` 엔티티 저장
    THEN `reference_type = CONCIERGE_REQUEST`, `reference_seq = conciergeRequest.seq`, `application_seq IS NULL`. 그리고 `ConciergeRequest.payment.paymentSeq == Payment.paymentSeq` 역방향 일치.

11. **AC-11 (Payment backfill 마이그레이션)**
    GIVEN 기존 운영 DB에 Application 결제 N건이 존재
    WHEN V_NN 마이그레이션 스크립트 실행
    THEN 모든 기존 Payment row에 `reference_type = 'APPLICATION'`, `reference_seq = application_seq`가 설정되며, `SELECT COUNT(*) FROM payments WHERE reference_type IS NULL` = 0.

12. **AC-12 (LOA PDF 생성 후 서명 대기 상태 전이)**
    GIVEN ConciergeRequest가 `APPLICATION_CREATED` 상태
    WHEN Manager가 `POST /api/admin/applications/{id}/loa/generate` 호출 성공
    THEN ConciergeRequest.status = `AWAITING_APPLICANT_LOA_SIGN`, N5 알림(LOA 서명 요청) 발송, 24h 후 N5-R 리마인더 예약.

13. **AC-13 (Applicant LOA 서명 시 상태 동기화)**
    GIVEN ConciergeRequest가 `AWAITING_APPLICANT_LOA_SIGN` 상태
    WHEN Applicant 본인이 LOA 서명 API 호출 성공 (`loa_signature_url` 세팅됨)
    THEN ConciergeRequest.status = `AWAITING_LICENCE_PAYMENT`로 자동 전이 (Application 이벤트 리스너).

14. **AC-14 (PDPA 동의 필수)**
    GIVEN `POST /api/concierge/requests` 요청 body의 `pdpaConsent=false`
    WHEN 서버 검증 수행
    THEN 400 `PDPA_CONSENT_REQUIRED`, ConciergeRequest 미생성, 계정 미생성.

15. **AC-15 (★ 경로 A — Manager 대리 서명 업로드 성공)**
    GIVEN Application이 `loa_signature_url IS NULL` 상태이고, Manager가 본인 담당 ConciergeRequest를 보유
    WHEN Manager JWT로 `POST /api/concierge-manager/applications/{id}/loa/upload-signature`(★ v1.5 경로 이관)를 PNG/JPG 파일 + `source=MANAGER_UPLOAD` + `receivedVia=EMAIL` + `managerConfirmation=true`와 함께 호출
    THEN
    - `loa_signature_url`이 세팅되고 PDF에 서명이 임베드됨
    - `loa_signature_source = MANAGER_UPLOAD`, `loa_signature_uploaded_by = manager.userSeq`, `loa_signature_uploaded_at = NOW()`, `loa_signature_source_memo`가 기록됨
    - 감사 로그 `LOA_SIGNATURE_UPLOADED_BY_MANAGER` 생성 (metadata 포함)
    - **N5-UploadConfirm 알림이 신청자에게 afterCommit으로 발송됨** (이의 제기 CTA + **★ v1.5: 7일 이의 제기 창구 문구 포함, AC-22b에서 검증**)
    - ConciergeRequest 상태가 `AWAITING_APPLICANT_LOA_SIGN` → `AWAITING_LICENCE_PAYMENT`로 자동 전이
    - `managerConfirmation=false`인 경우 400 `MANAGER_CONFIRMATION_REQUIRED` 반환

15b. **AC-15b (★ v1.5 신설, H-4 SecurityConfig 분리 검증)**
    GIVEN 다음 역할의 사용자가 **자기 소유가 아닌** Application에 대해 경로 A 업로드 시도
    - (a) LEW 역할 사용자
    - (b) 다른 Manager가 담당하는 ConciergeRequest의 Application에 대해 접근하는 CONCIERGE_MANAGER
    - (c) APPLICANT 역할 사용자
    WHEN `POST /api/concierge-manager/applications/{id}/loa/upload-signature` 호출
    THEN
    - (a) LEW: **403 FORBIDDEN** — `SecurityConfig`에서 `/api/concierge-manager/**` 경로에 LEW 권한 미부여 (SecurityConfigTest 매트릭스로 보장)
    - (b) 다른 Manager: 200 허용되지 않음 → **403 FORBIDDEN** — `OwnershipValidator`에서 `assigned_manager_seq != currentUser`이고 ADMIN 아님 확인
    - (c) APPLICANT: 403 FORBIDDEN
    - ADMIN만 bypass 허용
    - 감사 로그 `ACCESS_DENIED` + metadata={attemptedPath, attemptedRole, applicationSeq}

16. **AC-16 (★ 경로 B — 원격 서명 토큰 발급)**
    GIVEN Manager가 본인 담당 ConciergeRequest를 보유, Application이 `loa_signature_url IS NULL`
    WHEN Manager JWT로 `POST /api/admin/applications/{id}/loa/request-remote-sign`을 `deliveryMethod=EMAIL`로 호출
    THEN
    - `LoaSigningToken` 신규 row 생성: `tokenUuid`(UUIDv4), `expiresAt = NOW() + 48h`, `usedAt=null`, `failedAttempts=0`
    - 응답에 `signingUrl`, `qrPngBase64`, `expiresAt` 포함
    - 신청자 이메일로 N5-Alt 발송, `deliveredAt` 기록
    - 감사 로그 `LOA_REMOTE_SIGN_TOKEN_ISSUED` 생성
    - 같은 Application에 미사용 활성 토큰이 이미 존재하면 기존 토큰 자동 revoke (운영 정책 옵션, 토글 설정 가능)

17. **AC-17 (★ 경로 B — 원격 서명 제출 성공)**
    GIVEN 유효한 `LoaSigningToken`(`isUsable() = true`), 신청자가 본인확인 통과
    WHEN `POST /api/public/loa-sign/{token}/submit`을 서명 PNG와 함께 호출
    THEN
    - `loa_signature_url` 세팅, PDF에 서명 임베드
    - `loa_signature_source = REMOTE_LINK`, `loa_signature_uploaded_by = null`
    - `LoaSigningToken.usedAt = NOW()`, `signedFromIp`, `signedUserAgent` 기록
    - 감사 로그 `LOA_SIGNATURE_VIA_REMOTE_LINK` 생성 (actor=null, metadata={tokenSeq, ip, ua})
    - ConciergeRequest 상태 자동 전이
    - **재호출 시(같은 토큰)** 410 `TOKEN_ALREADY_USED` 반환

18. **AC-18 (★ 경로 B — 본인확인 5회 실패 시 잠금)**
    GIVEN 유효한 `LoaSigningToken`, `failedAttempts = 4`
    WHEN `POST /api/public/loa-sign/{token}/verify`에 잘못된 `emailLast4` 전송
    THEN
    - `failedAttempts = 5`, `lockedAt = NOW()`로 업데이트
    - 응답 401 `TOKEN_LOCKED`
    - 후속 모든 verify/submit 요청은 401 `TOKEN_LOCKED` 반환
    - 감사 로그 `LOA_REMOTE_SIGN_VERIFY_FAILED` 5건 누적 후 `LOA_REMOTE_SIGN_TOKEN_LOCKED` 1건 추가
    - Manager 대시보드에 잠김 상태 표시, 재발급 버튼 활성화

19. **AC-19 (★ 경로 B — 만료된 토큰 거부)**
    GIVEN `LoaSigningToken.expiresAt < NOW()`
    WHEN `GET /api/public/loa-sign/{token}` 호출
    THEN 410 `TOKEN_EXPIRED`. 응답에 "Please contact your Concierge Manager for a new link" 메시지 포함.

20. **AC-20 (★ 경로 B — Referer 유출 방지)**
    GIVEN 토큰을 query string으로 전달하는 요청 (예: `/api/public/loa-sign?token=xxx`)
    WHEN 서버가 라우팅
    THEN 404 또는 400 — 토큰은 **path parameter로만** 수용. 코드 레벨에서 query string에 토큰을 받는 핸들러를 두지 않는다.

21. **AC-21 (★ 경로 A/B 공통 — LOA 스냅샷 신원 컬럼 불변)**
    GIVEN Application의 LOA 스냅샷 4개 신원 컬럼이 이미 세팅됨
    WHEN 경로 A 또는 B로 서명을 적용
    THEN 신원 컬럼은 변경되지 않으며, `loa_signature_source` 등 메타데이터만 업데이트됨. 신원 컬럼 변경 시도 시 Hibernate 레벨에서 무시(`updatable=false`).

22. **AC-22 (★ v1.3 — 필수 동의 4종 검증)**
    GIVEN `POST /api/public/concierge/request` 요청 body에 `pdpaConsent`, `termsAgreed`, `signupConsent`, `delegationConsent` 중 하나라도 `false`
    WHEN 서버 검증 수행
    THEN 400 `{CODE}_CONSENT_REQUIRED` 에러(각 필드별로 고유 코드 반환), User·ConciergeRequest·UserConsentLog 모두 미생성. Bean Validation `@AssertTrue` 레벨 검증.

22b. **AC-22b (★ v1.5 신설, O-15 — N5-UploadConfirm 7일 이의 창구 문구 포함)**
    GIVEN 경로 A 업로드 성공으로 N5-UploadConfirm 이메일이 생성됨
    WHEN afterCommit 훅에서 이메일 본문 렌더링
    THEN 본문에 다음 요소가 모두 포함됨 (템플릿 단위 테스트로 검증):
    - `disputeDeadline` 변수가 `uploadedAt + 7 days`로 계산되어 표시됨
    - "7-day dispute window" 또는 "7일 이의 제기 창구" 문구
    - "implicitly authorized" 또는 "묵시적 동의" 문구
    - 이의 제기 CTA 버튼 URL
    - 담당 Manager 이름/이메일
    - 업로드 타임스탬프 (Asia/Singapore)
    - `templates/email/concierge-loa-upload-confirm.html` Thymeleaf 템플릿의 출력을 `ITemplateEngine.process(...)`로 렌더링 후 `contains` 검증

23. **AC-23 (★ v1.3 — 통합 가입 플로우 성공)**
    GIVEN 유효한 요청(필수 동의 4개 true) + 미가입 이메일
    WHEN `POST /api/public/concierge/request` 호출
    THEN 한 트랜잭션으로:
    - `User`(role=APPLICANT, signupSource=CONCIERGE_REQUEST, signupCompleted=false, passwordHash=임시 랜덤, emailVerified=false) 생성
    - `ConciergeRequest`(applicantUser 세팅, delegationConsentAt=NOW()) 생성
    - `UserConsentLog` 4건 이상 생성(PDPA, TERMS, SIGNUP, DELEGATION + marketingOptIn=true면 MARKETING까지 5건)
    - AccountSetupToken 발급 (48h 만료)
    - 감사 로그: `CONCIERGE_REQUEST_SUBMITTED`, `CONCIERGE_ACCOUNT_AUTO_CREATED`, `USER_CONSENT_RECORDED`×4~5, `ACCOUNT_SETUP_TOKEN_ISSUED`
    - afterCommit: N1 이메일 발송, N2 Manager/Admin 알림

24. **AC-24 (★ v1.3 — 기존 APPLICANT 이메일 재신청)**
    GIVEN 이메일이 기존 APPLICANT 계정과 매칭 (`signupCompleted=true`)
    WHEN `POST /api/public/concierge/request` 호출
    THEN
    - User 신규 생성 ❌ (기존 User 재사용)
    - ConciergeRequest.applicantUser = 기존 User
    - UserConsentLog에 새로운 동의 스냅샷 기록 (약관 버전 재확인)
    - N1-Alt 이메일 발송 ("Linked to your existing account")
    - 감사 로그 `CONCIERGE_EXISTING_USER_LINKED`

25. **AC-25 (★ v1.3 — staff 이메일 차단)**
    GIVEN 이메일이 LEW/ADMIN/SYSTEM_ADMIN/SLD_MANAGER/CONCIERGE_MANAGER 중 하나와 매칭
    WHEN `POST /api/public/concierge/request` 호출
    THEN 409 `STAFF_EMAIL_NOT_ALLOWED`, ConciergeRequest·User·UserConsentLog 미생성. 감사 로그 `CONCIERGE_STAFF_EMAIL_BLOCKED` (metadata={email, attemptedRole, ip}). 프론트는 "Please contact support or use a different email" 표시.

26. **AC-26 (★ v1.3 — Account Setup 토큰 만료 및 재발송)**
    GIVEN AccountSetupToken이 만료(`expiresAt < NOW()`)되었고 User.signupCompleted=false
    WHEN
    - **(본인)** 신청자가 `POST /api/public/account-setup/{token}/resend` 호출 또는 success 페이지에서 "Resend setup email" 클릭
    - **(Manager)** 담당 Manager가 `POST /api/concierge-manager/requests/{id}/resend-setup-email` 호출
    THEN
    - 기존 토큰 invalidate(revokedAt 기록)
    - 신규 AccountSetupToken 발급 (48h 만료)
    - N1 이메일 재발송
    - 감사 로그 `ACCOUNT_SETUP_TOKEN_RESENT` (actor=신청자 또는 Manager)
    - rate-limit: 본인 경로는 5분/1회, Manager 경로는 제한 없음

27. **AC-27 (★ v1.3 — UserConsentLog 불변성 및 감사 보존)**
    GIVEN ConciergeRequest 제출 시 UserConsentLog가 기록됨
    WHEN
    - 해당 row의 `consentType`, `consentAction`, `documentVersion`, `ipAddress`, `userAgent`, `consentedAt` 중 하나를 UPDATE 시도
    - User.deletedAt 세팅 (soft delete)
    THEN
    - UPDATE 시도는 Hibernate 레벨에서 무시 (`@Column(updatable=false)`)
    - soft-deleted User의 UserConsentLog는 그대로 조회 가능 (UserConsentLog 자체는 soft delete 없음)
    - `findByUserAndConsentType` 쿼리는 deleted User의 로그도 반환

28. **AC-28 (★ v1.3 — Account Setup 완료 후 자동 로그인)**
    GIVEN 유효한 AccountSetupToken + 복잡도 요건 충족 비밀번호
    WHEN `POST /api/public/account-setup/{token}` 호출
    THEN
    - User.passwordHash 업데이트
    - User.emailVerified = true
    - **★ v1.4: User.status = ACTIVE** (v1.3의 signupCompleted=true 대체)
    - AccountSetupToken.usedAt = NOW()
    - JWT access + refresh 토큰 응답
    - 감사 로그 `ACCOUNT_SETUP_COMPLETED`, `ACCOUNT_ACTIVATED`
    - 이후 로그인 API로 정상 인증 가능

28b. **AC-28b (★ v1.5 신설, H-3 — AccountSetupToken 5회 실패 잠금)**
    GIVEN 유효한 AccountSetupToken + `failedAttempts = 4`
    WHEN `POST /api/public/account-setup/{token}` 호출에서 비밀번호 복잡도 또는 확인 불일치 등 검증 실패
    THEN
    - `failedAttempts = 5`로 업데이트, `lockedAt = NOW()` 세팅
    - 응답 410 `TOKEN_LOCKED` + "Please contact your Concierge Manager for a new link" 메시지
    - 후속 모든 `GET`/`POST /api/public/account-setup/{token}...` 요청은 410 `TOKEN_LOCKED` 반환
    - 감사 로그 `ACCOUNT_SETUP_TOKEN_FAILED_ATTEMPT` × 5 누적 + `ACCOUNT_SETUP_TOKEN_LOCKED` 1건
    - **복구 경로**: Manager가 `POST /api/concierge-manager/requests/{id}/resend-setup-email` 호출 → 기존 토큰 `revokedAt` 세팅 + 새 토큰 발급 → 사용자에게 N1 재발송

28c. **AC-28c (★ v1.5 신설, O-17 — AccountSetupToken 활성 토큰 단일성)**
    GIVEN User에게 유효한(`isUsable()=true`) AccountSetupToken A가 이미 존재
    WHEN Manager 또는 신청자 본인이 재발송 트리거 → 신규 토큰 B 발급
    THEN
    - 토큰 A의 `revokedAt = NOW()`로 자동 invalidate (INSERT B 이전에 UPDATE A 수행)
    - 토큰 B만 `isUsable() = true`
    - 기존 토큰 A의 URL에 대한 모든 요청은 410 `TOKEN_REVOKED` 반환
    - 감사 로그 `ACCOUNT_SETUP_TOKEN_RESENT` (metadata에 revoked previous tokenSeq 포함)
    - 유저당 유효 토큰 수 = 1 이라는 DB 쿼리 불변식 테스트 (`SELECT COUNT(*) FROM account_setup_tokens WHERE user_seq = ? AND used_at IS NULL AND revoked_at IS NULL AND locked_at IS NULL AND expires_at > NOW()` = 1)

29. **AC-29 (★ v1.3 → v1.5 재작성, H-1 이메일 enumeration 방어)**
    로그인 응답은 **이메일 존재 여부를 어떤 방식으로도 노출하지 않아야 한다**. 본 AC는 §4.4 constant-time 패딩 전략과 직접 연결된다.
    
    GIVEN 다음 5가지 케이스 각각
    - (a) 이메일 존재하지 않음
    - (b) 이메일 존재 + `status=ACTIVE` + 비밀번호 틀림
    - (c) 이메일 존재 + `status=PENDING_ACTIVATION` + 비밀번호 틀림
    - (d) 이메일 존재 + `status=SUSPENDED` + 비밀번호 틀림
    - (e) 이메일 존재 + `status=DELETED` + 비밀번호 틀림
    
    WHEN `POST /api/auth/login`에 해당 이메일 + 잘못된 비밀번호로 호출
    
    THEN **5가지 케이스 모두 동일하게** 401 `INVALID_CREDENTIALS` + 고정 메시지 `"Invalid email or password."` 반환
    - status 체크는 **비밀번호 검증 성공 후에만** 실행됨 (비밀번호 검증 실패 시 status 분기 없음)
    - 이메일이 존재하지 않아도 `DUMMY_BCRYPT_HASH`에 대해 `passwordEncoder.matches(...)` 호출 (타이밍 동등성)
    - 단위 테스트: Mockito로 `passwordEncoder.matches` 호출 횟수 = 1 검증
    - **타이밍 통합 테스트**: 5케이스 각 100회 호출, `p95(각 케이스) - p50(baseline) < 200ms` (§4.4, O-23)
    - 감사 로그: 실제 결과를 구분 기록(`LOGIN_FAILED_UNKNOWN_EMAIL` / `LOGIN_FAILED_BAD_PASSWORD` / `LOGIN_FAILED_DELETED`)하되 응답으로는 유출 금지

30. **AC-30 (★ v1.4 — 신청 시 User는 PENDING_ACTIVATION으로 생성)**
    GIVEN 유효한 컨시어지 신청 폼 제출 (신규 이메일, 필수 동의 4개 true)
    WHEN `POST /api/public/concierge/request` 호출
    THEN 생성된 User의 `status = PENDING_ACTIVATION`, `activatedAt = NULL`, `firstLoggedInAt = NULL`. `signupSource = CONCIERGE_REQUEST`. 감사 로그 `CONCIERGE_ACCOUNT_AUTO_CREATED` metadata에 `initialStatus=PENDING_ACTIVATION` 기록.

31. **AC-31 (★ v1.4 → v1.5 수정, H-1 반영)**
    활성화 플로우 CTA는 **올바른 비밀번호를 입력한 PENDING_ACTIVATION 사용자에게만** 노출된다.
    
    GIVEN `User.status = PENDING_ACTIVATION`이고, **사용자가 이전에 비밀번호를 설정한 적 있음**(예: 이전 Account Setup 시도 중간 단계, 또는 신청자가 자체적으로 AccountSetupToken 없이 재설정 루트를 통해 임시 비밀번호를 설정한 운영 케이스)
    
    WHEN Applicant가 `POST /api/auth/login`에 이메일 + **올바른** 비밀번호로 호출
    
    THEN 서버는 401 `ACCOUNT_PENDING_ACTIVATION` + 본문 `{errorCode:"ACCOUNT_PENDING_ACTIVATION", activationFlow:"EMAIL_LINK"}` 반환. 프론트는 "이메일 인증 링크 요청" 버튼으로 UI 전환.
    
    **중요**: 비밀번호가 틀린 경우에는 AC-29의 `INVALID_CREDENTIALS` 응답이 우선 반환되며, `ACCOUNT_PENDING_ACTIVATION`은 노출되지 않는다. 이는 이메일 enumeration을 방지하기 위한 설계이며, 대부분의 컨시어지 신청자는 최초 로그인 시 비밀번호가 없으므로 `POST /api/auth/login/request-activation`을 직접 호출하는 것이 기본 경로다.
    
    - 프론트 로그인 페이지는 **로그인 응답과 무관하게** "Send me an activation link" 보조 버튼을 항상 노출하여, 사용자가 비밀번호 없이도 `request-activation` 플로우로 진입 가능
    - 감사 로그: `LOGIN_SUCCEED_BUT_PENDING_ACTIVATION` (드문 케이스 추적용)

32. **AC-32 (★ v1.4 — 인증 링크 클릭 → 비밀번호 설정 → 활성화 전환)**
    GIVEN 유효한 AccountSetupToken + User.status = PENDING_ACTIVATION + 복잡도 요건 충족 비밀번호
    WHEN `POST /api/public/account-setup/{token}` 호출
    THEN
    - User.passwordHash 업데이트
    - User.emailVerified = true
    - **User.status = ACTIVE** (v1.3의 signupCompleted=true 대체)
    - **User.activatedAt = NOW()** (이후 변경 불가, `@Column(updatable=false)`)
    - **User.firstLoggedInAt = NOW()**
    - AccountSetupToken.usedAt = NOW()
    - 감사 로그 2건: `ACCOUNT_SETUP_COMPLETED`, `ACCOUNT_ACTIVATED`
    - JWT access + refresh 토큰 응답

33. **AC-33 (★ v1.4 — ACTIVE 전환 후 일반 로그인)**
    GIVEN User.status = ACTIVE (활성화 완료)
    WHEN `POST /api/auth/login`에 올바른 이메일 + 비밀번호 호출
    THEN 200 + JWT 발급. 이후 로그인 시 `firstLoggedInAt`은 변경되지 않음(최초 1회만 세팅). 잘못된 비밀번호 시 401 `INVALID_CREDENTIALS` (status가 우선 반환되지 않음).

34. **AC-34 (★ v1.4 — Manager 대시보드 PENDING_ACTIVATION 필터)**
    GIVEN Concierge 요청 N건 중 일부 신청자의 User.status = PENDING_ACTIVATION
    WHEN Manager가 요청 목록 페이지의 "활성화 대기만 보기" 필터 토글
    THEN `u.status = 'PENDING_ACTIVATION' AND cr.status NOT IN ('COMPLETED','CANCELLED')` 조건으로 필터링된 목록 표시. KPI 카드 "PENDING_ACTIVATION N건"은 같은 조건의 COUNT.

35. **AC-35 (★ v1.4 — N1 이메일 본문에 활성화 안내 필수 요소 포함)**
    GIVEN 컨시어지 신청 제출 성공
    WHEN afterCommit으로 N1 이메일 발송
    THEN 이메일 본문에 다음 문자열이 모두 포함됨 (템플릿 테스트로 검증):
    - "계정이 생성되었습니다" 또는 "Account created"
    - "비활성" 또는 "PENDING_ACTIVATION" 또는 "not yet activated"
    - "최초 로그인" 또는 "first login" 또는 "first sign-in"
    - 로그인 페이지 URL
    - Account Setup 링크 URL (48h 만료 안내와 함께)
    - publicCode
    - 담당 Manager 이름/이메일 (배정 완료된 경우)

36. **AC-36 (★ v1.4 — PENDING_ACTIVATION 계정에 재신청 시 중복 생성 방지)**
    GIVEN 이메일 E로 C1 플로우로 User 생성됨 (status=PENDING_ACTIVATION)
    WHEN 같은 이메일 E로 다시 `POST /api/public/concierge/request` 호출
    THEN **신규 User 생성 없이** 기존 User에 새 ConciergeRequest 연결 (C3 플로우), 기존 AccountSetupToken revoke + 신규 발급, N1 재발송. DB에 `email=E`인 active User row는 여전히 1건.

37. **AC-37 (★ v1.4 — `activatedAt` 불변성)**
    GIVEN User.activatedAt이 이미 세팅됨 (ACTIVE로 전환된 계정)
    WHEN 서비스 레이어에서 `user.setActivatedAt(...)` 또는 직접 UPDATE 시도
    THEN Hibernate가 `@Column(updatable=false)` 적용으로 무시. DB의 activatedAt 값은 변경되지 않음. 테스트로 보장.

---

## 10. 오픈 이슈 & 의사결정 필요 항목

### 10.1 해결된 항목 (누적)

| ID | 이슈 | 결정 | 해결 버전 / 근거 |
|----|------|------|----------------|
| ~~O-3~~ | Concierge Manager 캐파시티 | **현 시점 제한 없음**, Round-robin 할당 로직만 준비하고 캐파 체크 로직은 Phase 4 이후 운영 지표 기반으로 재검토 | v1.1 — 초기 볼륨 불확실 |
| ~~O-4~~ | SLA "최대한 빨리" 구체화 | **접수 후 24시간 이내 최초 연락**으로 확정. 24h 경과 시 Admin 대시보드 경고 뱃지 + N9 알림 | v1.1 — §6.2 |
| ~~O-6~~ | 결제 엔티티 구조 | **Payment 재사용 + `reference_type`/`reference_seq` 컬럼 추가**. 기존 데이터는 `APPLICATION`으로 backfill | v1.1 — §3.8.1, §8.1 |
| ~~O-15~~ | **경로 A 묵시적 동의 기간** | **7일로 확정**. N5-UploadConfirm 이메일에 "7일 내 이의 없으면 묵시적 동의 간주" 명시. AC-22b로 검증. **배포 전 법무 재검토 조건부**(O-1과 연계) | **v1.5 — §6.4-3** |
| ~~O-17~~ | **AccountSetupToken 활성 토큰 다중 발급 허용** | **1개만 유지**. 신규 발급 시 기존 유효 토큰 `revokedAt` 세팅 후 INSERT. AC-28c로 검증 | **v1.5 — §3.12** |
| ~~O-19~~ | **약관 버전 관리 체계** | **Phase 1~2: Java 상수**(`TermsVersion.CURRENT = "2026-04-19"`) + `user_consent_logs.version` 기록 / **Phase 3: `terms_documents` DB 테이블 CMS**로 전환 | **v1.5 — §11 Phase 3** |
| ~~O-21~~ | **로그인 활성화 플로우: 옵션 A vs 옵션 B** | **옵션 B 단독 확정**. 옵션 A(이메일에 평문 임시 비밀번호)는 PDPA §24 + OWASP ASVS V2.1.6 위반 소지로 **완전 폐기**. `/api/auth/login/force-change-password` 엔드포인트 삭제, §6.4-1b "옵션 A 대안 템플릿" 섹션 폐기 표시 | **v1.5 — 보안 리뷰 H-5** |
| ~~O-22~~ | **PENDING_ACTIVATION 장기 미접속 자동 정리** | **90일 → SUSPENDED, 180일 → soft-delete + DELETED**. Phase 3에 `ConciergePendingCleanupScheduler` 구현. 기준 시점은 `GREATEST(created_at, last_setup_token_issued_at)` | **v1.5 — §3.4b-2** |
| ~~O-23~~ | **이메일 존재 여부 노출 방지 검증 방법** | **constant-time 패딩 전략 + p95<200ms 타이밍 통합 테스트 CI 필수**. 기존 `GenericRateLimiter` 재사용, Bucket4j 미도입. dummy BCrypt.verify + `@Async + afterCommit` 발송 격리 | **v1.5 — §4.4** |

### 10.2 잔여 오픈 이슈

| ID | 이슈 | 선택지 | 추천 | 승인 필요 | 시점 |
|----|------|--------|------|----------|------|
| **O-1 (★ v1.2 재정의 → v1.5 조건부 승인)** | **LOA 서명 3-경로의 법적 무결성 검토** | 각 경로별로 별도 법무 검토: ① APPLICANT_DIRECT (현행 인정) ② MANAGER_UPLOAD — 7일 묵시적 동의 + N5-UploadConfirm 고지로 충분한가 ③ REMOTE_LINK — Singapore ETA s.8 "secure electronic signature" 요건 충족 여부 | **Phase 1 착수 승인** + **배포 전 싱가포르 법무 자문 완료 필수**(ETA §3·§4 / PDPA §22A / EMA 가이드라인). 자문 결과에 따라 경로 A 문구·7일 기간·감사 로그 조정 | **법무(배포 전 필수)** | 배포 전 |
| O-2 | 자동 생성 계정 초기 비밀번호 정책 | A) 랜덤 발급 + 재설정 링크 메일 B) 재설정 링크 메일만(비번 미설정) | **B** — v1.5에서 AccountSetupToken 기반 활성화 플로우로 확정. 임시 해시는 `DUMMY_BCRYPT_HASH`로 채워져 어떤 평문도 매치하지 않음 | 보안 (v1.5 §3.12로 구현 명세 완료) | Phase 1 |
| O-5 | 서비스 요금 확정 | §8.2 가정값 | 비즈니스 승인 | 비즈니스 | Phase 2 |
| O-7 | 자동 배정 알고리즘 | A) 라운드로빈 B) 현재 부하 최소 C) 수동만 | A 1차, B를 Phase 4에 | 운영 | Phase 4 |
| O-8 | 요금 환불 시점의 정산 | §8.5 정책 확정 필요 | 법무/재무 | 법무·재무 | Phase 2 |
| O-9 | 재접수(중복 이메일) 처리 | §7.7 C1~C5 케이스 분기로 해결 | 확정 | 보안 (§7.7 검토 완료) | Phase 1 |
| O-10 | Concierge 가격 노출 위치 | 랜딩 섹션 하단 노출 / 모달 내 노출만 | 모달 내만 (리드 수집 우선) | UX | Phase 1 |
| O-11 | 라이선스료 결제 대리 허용 여부 | A) 신청자 본인만 (원칙) B) Admin 승인 하에 Manager 대리 가능 | A | 법무·재무 | Phase 3 |
| O-12 | `AWAITING_*` 상태 리마인더 상한 | 72h 이후 자동 취소 vs Manager 재확인 필요 | Manager 재확인 후 수동 취소 | 운영 | Phase 2 |
| **O-13 (★ v1.2)** | **OTP 발송 공급자 선정** (경로 B Phase 3 강화용) | A) Twilio Verify B) AWS SNS C) Singapore 로컬 SMS gateway | Phase 2 출시 후 사용량 기반 결정 | 운영·기술 | Phase 2 이후 |
| **O-14 (★ v1.2)** | **QR 코드 생성 라이브러리 선정** | A) ZXing B) `qrcode-svg` (npm) C) Google Charts API | **A (ZXing)** — 백엔드 PNG 생성 + base64, 외부 의존성 회피, GPL 호환성 검토 필요 | 기술 | Phase 2 |
| O-16 | 경로 B 본인확인 수단 기본값 | A) 이메일 last-4만 B) 모바일 OTP C) 둘 다 | A → Phase 3에 B/D로 강화 | 보안·UX | Phase 3 |
| **O-18 (★ v1.3)** | **Soft-deleted 계정 이메일 재신청 처리** | A) 신규 User 재생성 (`(email, deleted_at)` 복합 유니크) B) soft-deleted User 복구 C) 차단 | A — PDPA "잊혀질 권리" 보장. 법무 승인 전까지 감사 로그 `hadSoftDeletedAccount=true` 플래그로 추적 | **법무·보안** (배포 전 확인) | 배포 전 |
| **O-20 (★ v1.3)** | **마케팅 동의 철회(opt-out) 수단** | A) 프로필 페이지 토글 B) 이메일 footer 링크 C) 둘 다 | C — 프로필 + 원클릭 수신거부 링크 병행. Phase 2~3 구현 | 법무·UX | Phase 2 |

---

## 11. 개발 단계 분할 제안

### Phase 1 — MVP (2~3주, 결제 제외, **LOA 서명 = 직접 + 경로 A**, **★ v1.3: 통합 가입 플로우**, **★ v1.4: UserStatus + 옵션 B 활성화 플로우**)

**목표**: 대행 신청이 들어오면 **신청과 동시에 User 자동 생성(`status=PENDING_ACTIVATION`) + 동의 감사 로그 기록 + N1 이메일(계정 활성화 안내 포함) 발송**까지 원샷으로 처리되고, 담당자가 받아 Application 생성 + LOA 서명 수집(직접/경로 A)까지 종결되는 전체 백본. 신청자는 최초 로그인 시점에 옵션 B(이메일 인증 링크)로 활성화(`status=ACTIVE`)한다.

- **PR#1-Enhanced (Backend 도메인 + ★ v1.3 통합 가입 + ★ v1.4 활성화 상태)**:
  - `UserRole.CONCIERGE_MANAGER` 추가 + 시드 유저 1명
  - `ConciergeRequest` + `ConciergeNote` 엔티티 + Repository + Flyway 마이그레이션 (+ **★ v1.3: `delegationConsentAt` 컬럼**)
  - `ConciergeRequestStatus` enum, 도메인 메서드, 불변식 테스트
  - `NotificationType`, `AuditAction` 확장 (v1.2 LOA + v1.3 통합 가입 7종 + **★ v1.4 활성화 상태 전이 7종**)
  - **★ v1.2: `Application`에 LOA 서명 출처 컬럼 4종 추가 + `LoaSignatureSource` enum**
  - **★ v1.3 핵심**:
    - `User` 엔티티 확장 (`signupSource`, `signupConsentAt`, `termsVersion`, `marketingOptIn`, `marketingOptInAt`) + Flyway `V_NN__user_add_signup_columns.sql` (기존 사용자 backfill 포함)
    - `SignupSource` enum 신규
    - **`UserConsentLog` 엔티티 + Repository + Flyway `V_NN__user_consent_logs.sql`** (기존 PDPA 동의 backfill 포함)
    - `ConsentType`, `ConsentAction`, `ConsentSourceContext` enum 3종
    - 약관 버전 상수 관리 (`TermsConstants.CURRENT_TERMS_VERSION` 등, O-19 Phase 1 해법)
  - **★ v1.4 핵심 신규 (계정 활성화 상태)**:
    - **`UserStatus` enum** 신규 (`PENDING_ACTIVATION` / `ACTIVE` / `SUSPENDED` / `DELETED`)
    - `User` 엔티티에 `status`, `activatedAt`(`@Column(updatable=false)`), `firstLoggedInAt` 컬럼 추가
    - Flyway `V_NN__user_replace_signup_completed_with_status.sql` — 2단계 릴리스(R1: status 추가 + backfill, R2: signup_completed 제거)
    - 도메인 메서드: `User.activate()`, `User.suspend(reason)`, `User.unsuspend()`, `User.softDelete()` (상태 전이 단일 진입점)
    - 불변식 테스트: `activatedAt` `updatable=false` 보장(AC-37), 전이 도메인 메서드만 사용하는지 린트/테스트
  - **★ v1.5 핵심 신규 (보안 리뷰 반영, H-1~H-5)**:
    - **`AccountSetupToken` 엔티티 분리** (§3.12) + Flyway `V_NN__account_setup_tokens.sql` — 기존 PasswordResetToken 재활용 방침 폐기. `failedAttempts`, `lockedAt`, `revokedAt`, `issueSource` 필드
    - 도메인 메서드 `AccountSetupToken.recordFailedAttempt()`, `markUsed()`, `revoke()`, `isUsable()` + 불변식 테스트(유저당 유효 토큰 1개)
    - `AuthConstants.DUMMY_BCRYPT_HASH` 상수 (ApplicationContext 초기화 시 1회 생성, cost=10)
    - `NotificationType` + v1.5 없음 / `AuditAction` + v1.5 6종 (AC-29 구분 로그 3종 + AccountSetupToken 3종 + LOA 묵시적 동의 1종)
    - **`SecurityConfig.java` 매처 분리 (H-4)** + **`SecurityConfigTest` 매트릭스 전수 검증**
- **PR#2-Enhanced (Backend API + ★ v1.3)**:
  - **`POST /api/public/concierge/request`** 통합 가입 플로우 엔드포인트 (기존 `/api/concierge/requests` deprecate, 당분간 동시 지원)
    - 필수 동의 4종 `@AssertTrue` 검증
    - 이메일 기존 회원 분기 로직 (§7.7 C1~C5 케이스)
    - User 자동 생성 + UserConsentLog 4~5건 + AccountSetupToken 발급을 한 트랜잭션
    - Rate limit (IP + 이메일, 5req/hour/email)
  - **`GET /api/public/account-setup/{token}`**, **`POST /api/public/account-setup/{token}`**, **`POST /api/public/account-setup/{token}/resend`** 3종 엔드포인트
  - `POST /api/concierge-manager/requests/{id}/resend-setup-email` Manager 재발송 엔드포인트
  - `ConciergeNotifier` (afterCommit 훅) — **N1 통합 이메일** + N2 Manager/Admin 알림 + N1-Alt 기존 계정 연결
  - `AccountSetupTokenService` (기존 PasswordResetToken 인프라 재활용, `source=CONCIERGE_ACCOUNT_SETUP` 구분)
  - **★ v1.4 → v1.5 로그인 API 확장 (H-1 전면 재작성)**:
    - `POST /api/auth/login`: **비밀번호 검증 선행** → 실패 시 `INVALID_CREDENTIALS`. 성공 후에만 `status` 분기: `ACTIVE`→JWT / `PENDING_ACTIVATION`→401 `ACCOUNT_PENDING_ACTIVATION`+`activationFlow=EMAIL_LINK` / `SUSPENDED`→403 / `DELETED`→401 `INVALID_CREDENTIALS` (존재 감춤). 미존재 이메일에도 `DUMMY_BCRYPT_HASH`로 `passwordEncoder.matches` 필수 호출. (AC-29, AC-31, §4.4)
    - **`POST /api/auth/login/request-activation`** (옵션 B 단독) — **§4.4 constant-time 패딩 전략**: 공통 코드 경로 + dummy BCrypt.verify + `GenericRateLimiter` 재사용(IP 20/h + 이메일 5분/1회 내부 카운터) + `@Async + afterCommit` 발송 격리. p95<200ms CI 타이밍 테스트 빌드 차단
    - **~~`POST /api/auth/login/force-change-password`~~** — **★ v1.5에서 완전 폐기(H-5 / O-21). 구현하지 않음**
    - Account Setup 완료 시 `status=ACTIVE` + `activatedAt=NOW()` + `firstLoggedInAt=NOW()` 전이 (AC-32) + AccountSetupToken 실패 5회 잠금 처리 (AC-28b)
    - `ACCOUNT_ACTIVATED` 감사 로그 발행 + v1.5 신규 로그 6종(`LOGIN_FAILED_*`, `ACCOUNT_SETUP_TOKEN_*`)
  - **★ v1.5 LOA 경로 A 엔드포인트 이관 (H-4)**:
    - 기존 설계의 `POST /api/admin/applications/{id}/loa/upload-signature` → **`POST /api/concierge-manager/applications/{id}/loa/upload-signature`로 이관**
    - `SecurityConfig`에 `hasAnyRole('CONCIERGE_MANAGER','ADMIN')` 명시 — LEW는 접근 차단
    - `SecurityConfigTest`에 role × 엔드포인트 매트릭스 검증 필수 (AC-15b)
  - 감사 로그 연동
- PR#3-Enhanced (Frontend Landing + ★ v1.3 + ★ v1.4 활성화 플로우):
  - LandingPage에 Concierge 섹션 + CTA
  - `/concierge/request` 모달·페이지
    - 입력 필드 (이름, 이메일, 모바일, 메모)
    - **★ v1.3: 5종 동의 체크박스 (필수 4 + 선택 1) + "전체 동의" 편의 체크박스**
    - 각 약관은 별도 모달 또는 새 창으로 전문 열람
    - 제출 버튼은 필수 4개 체크 시만 활성화
  - `/concierge/request/success` 확인 페이지 (접수 확인 + 계정 활성화 안내 + Setup 링크 + 재발송 버튼)
  - **★ v1.3: `/setup-account/{token}` Account Setup 페이지**
    - 비밀번호 입력 (2회 확인, 복잡도 검증)
    - 토큰 만료/잠금 상태 표시
    - 성공 시 자동 로그인 + 대시보드 리다이렉트 + **★ v1.4: "계정이 활성화되었습니다" 토스트**
  - **★ v1.4 → v1.5: 로그인 페이지 활성화 플로우 (옵션 B 단독, 확정)**
    - `ACCOUNT_PENDING_ACTIVATION` 에러 응답 수신 시 UI를 "이메일 인증 링크 요청" 모드로 전환
    - **항상 노출되는 보조 버튼**: "Send me an activation link" (로그인 응답과 무관, 비밀번호 없이도 활성화 진입 가능)
    - 버튼 클릭 → `POST /api/auth/login/request-activation` 호출 → 고정 메시지 표시
    - 버튼 연타 방지를 위해 클라이언트 레벨 debounce + 서버 rate-limit 응답 처리
  - **~~★ v1.4 옵션 A (Phase 2 선택 과제)~~** — **★ v1.5에서 완전 폐기(H-5 / O-21). 구현하지 않음**
- PR#4 (Frontend Manager Dashboard):
  - 사이드바 신규 역할 라우팅
  - 요청 목록 + 상세 페이지
  - 상태 전이 UI, 노트 작성
  - **★ v1.3 → v1.4: "PENDING_ACTIVATION" 배지 + 재발송 버튼 + 집계 KPI 카드 + 필터 토글**
  - **★ v1.4: 요청 상세 페이지에 "Account Status" 패널** — `user.status`, `activatedAt`, `firstLoggedInAt`, 마지막 토큰 발급/만료 시각 표시
- PR#5 (On-Behalf-Of Application):
  - 상세 페이지 내 "Create Application on behalf" 버튼
  - ApplicationService에 `createOnBehalfOf(targetUserSeq, managerSeq, req)` 메서드 추가
  - `Application.via_concierge_request_seq` 컬럼 추가 (Flyway)
  - `APPLICATION_CREATED_ON_BEHALF` 감사 로그
- **★ PR#6-LOA-A (★ v1.2 신규, Phase 1 포함): LOA 서명 수집 — 경로 A**
  - `POST /api/admin/applications/{id}/loa/upload-signature` 엔드포인트
  - `LoaService.uploadSignature(managerSeq, file, source, memo)` 메서드 추가 (기존 `signLoa`와 분리)
  - 파일 검증(MIME, 크기), `embedSignatureIntoPdf()` 재사용
  - **N5-UploadConfirm 알림 발송** (afterCommit, 이의 제기 CTA 포함)
  - 감사 로그 `LOA_SIGNATURE_UPLOADED_BY_MANAGER`
  - 프론트: 요청 상세 페이지에 "LOA 서명 수집" 섹션의 **탭 ① + 탭 ②**만 활성화
  - 단위 테스트: AC-15, AC-21
- PR#7 (Admin & 동기화):
  - Admin Concierge 대시보드 섹션 (전체 목록, 수동 배정/재배정, SLA 지표)
  - Application 상태 변경 → ConciergeRequest 자동 동기화 리스너

### Phase 2 — 결제 통합 + **경로 B (원격 서명 링크/QR)** + **★ v1.3 check-email UX** (2~2.5주)

- **Payment 스키마 확장 Flyway 마이그레이션**: `reference_type`/`reference_seq` 컬럼 추가, 기존 데이터 `APPLICATION`으로 backfill (§3.8.2, AC-11)
- `PaymentReferenceType` enum 추가 + `Payment.of(referenceType, referenceSeq, ...)` 팩토리
- PG 연동(기존 Application 결제 패턴 준용)
- Concierge 요청 생성 시 즉시 결제 플로우 (신청자 본인 결제, §7.2.2)
- 환불 처리(Admin-only) + 감사 로그 `PAYMENT_REFUNDED`
- 48h 미결제 자동 취소 스케줄러
- **24h SLA 위반 알림 스케줄러** (N9, AC-9)
- **LOA 서명 리마인더 스케줄러** (N5-R, 24h/48h/72h)
- **★ v1.3: 계정 설정 리마인더 스케줄러 (N1-R)** — `signupCompleted=false` + 토큰 유효 + 24h 경과 시 발송, 47h 경과 시 "만료 직전" 경고
- **★ v1.3: 실시간 이메일 체크 UX** — `GET /api/public/concierge/check-email` 구현 + Frontend 블러 핸들러 + Debounce + 응답에 따른 안내 문구 UI
- **★ v1.2: 경로 B (원격 서명 링크/QR)**
  - `LoaSigningToken` 엔티티 + Flyway (`V_NN__loa_signing_tokens.sql`)
  - Manager 엔드포인트 3종: `request-remote-sign`, `revoke-remote-sign`, `remote-sign-tokens`
  - Public 엔드포인트 4종: 토큰 진입/본인확인/PDF/제출
  - QR 생성 라이브러리(O-14 결정에 따라 ZXing) 통합
  - 이메일/SMS 발송 통합 (이메일은 기존 SmtpEmailService 재사용, SMS는 O-13 결정 후 Phase 3에 본격화)
  - Rate limit: Bucket4j로 토큰 진입 + 본인확인 보호
  - 프론트:
    - 요청 상세 페이지 LOA 섹션의 **탭 ③** 활성화
    - 신규 페이지 `/sign/{token}` 풀페이지 (3-step 마법사)
  - 단위/통합 테스트: AC-16, AC-17, AC-18, AC-19, AC-20

### Phase 3 — 대리 작성 완성도 + **경로 B 강화 + 감사 대시보드 + 약관 CMS + ★ v1.5 PENDING 자동 정리** (2주)

- 대리 작성 UI에 Applicant 정보 배너 강화, 동의 체크 강제
- Applicant 측 대시보드에 "Concierge 경유" 배너 + LOA 서명 유도 CTA
- 대리 행위 감사 리포트(Admin 전용 익스포트)
- 자동 라운드로빈 배정 알고리즘
- PDPA 시나리오: Applicant 계정 삭제(`anonymize()`) 시 ConciergeRequest 개인정보 마스킹 정책
- **★ v1.2: 경로 B 보안 강화**
  - 모바일 OTP 본인확인 (O-13 공급자 선정 후 통합)
  - 이메일 last-4 + OTP 이중 인증 옵션 (O-16)
  - 의심 활동 탐지 (같은 IP에서 여러 토큰 시도 등)
- **★ v1.2: LOA 서명 감사 대시보드 (Admin 전용)**
  - 경로별 서명 통계 (APPLICANT_DIRECT vs MANAGER_UPLOAD vs REMOTE_LINK)
  - 이의 제기 건수 추적
  - 잠긴 토큰 / 만료 토큰 모니터링
- **★ v1.3 → v1.5: 약관 버전 관리 CMS (O-19 해결)**
  - `terms_documents` 테이블 신설(`version`, `title`, `content_html`, `effective_from`, `created_at`)
  - Admin UI에서 약관 편집 + 새 버전 발행 + 기존 버전 immutable
  - 개정 시 기존 동의자 일괄 재동의 요청 이메일 발송 스케줄러
  - UserConsentLog 기반 "재동의 필요 사용자 목록" 쿼리(`consentType=TERMS AND documentVersion != 최신`)
  - Phase 1~2의 `TermsVersion.CURRENT` 상수를 DB 조회로 대체
- **★ v1.3 → v1.5: 마케팅 동의 철회 플로우 (O-20 해결)**
  - 프로필 페이지 설정 토글
  - 이메일 footer 원클릭 수신거부 링크 (`/api/public/marketing-unsubscribe/{token}`, 토큰 기반)
  - 철회 시 UserConsentLog에 `WITHDRAWN` row 기록
- **★ v1.3: PDPA "잊혀질 권리" 안전망**
  - User soft delete 시 UserConsentLog는 보존 (현 구조)
  - Admin anonymize API 호출 시 ConciergeRequest 개인정보 마스킹 + UserConsentLog는 IP/UA만 마스킹
- **★ v1.5: `ConciergePendingCleanupScheduler` (O-22 해결)**
  - 일 1회 03:00 Asia/Singapore 실행, 배치 1000건 단위
  - **90일 미접속 + `status=PENDING_ACTIVATION`** → `SUSPENDED` 전환, 감사 로그 `ACCOUNT_AUTO_SUSPENDED_INACTIVE`, 이메일 알림
  - **180일 미접속 + `status=SUSPENDED`(자동 정리 경유)** → `softDelete()` → `deleted_at=NOW()` + `status=DELETED`, 감사 로그 `ACCOUNT_DELETED`
  - 기준 시점: `GREATEST(created_at, last_setup_token_issued_at)` — Manager 재발송 시 카운트 리셋
  - Admin 수동 SUSPENDED 계정은 자동 정리 대상에서 제외(감사 메타데이터로 구분)

### Phase 4 — 대시보드 고도화 & 운영 지표 (1주)

- KPI 카드(접수율, 평균 SLA, 전환율, 24h SLA 준수율)
- Concierge Manager 부하 기반 자동 배정
- **Manager 캐파시티 정책 재검토** (O-3) — Phase 1~3 운영 지표(Manager당 평균 동시 담당 건수, SLA 준수율)를 바탕으로 상한선 도입 여부 결정
- SLA 위반 알림 스케줄러 고도화 (플랜별 차등 SLA 지원)
- 월간 리포트 PDF 출력
- **★ v1.2: 묵시적 동의 기간 7일 효과 분석** (O-15) — 이의 제기율 통계로 기간 적정성 재평가

---

## 12. 기존 컨벤션 준수 체크리스트

- [ ] 한국어 커밋 메시지 ("feat: Kaki Concierge 요청 접수 API (Phase 1 PR#2)")
- [ ] Soft delete 패턴 (`deleted_at`, `@SQLDelete`, `@SQLRestriction`)
- [ ] DTO Request/Response 분리, `record` 활용
- [ ] BaseEntity 상속 (createdAt/updatedAt/createdBy/updatedBy)
- [ ] afterCommit 훅으로 알림·이메일 발송 (롤백 안전성)
- [ ] 감사 로그 카테고리 분리: `APPLICATION` / `ADMIN` / `AUTH` — Concierge는 **`ADMIN`** 기본, 요청 제출 자체는 `AUTH` 후보
- [ ] XSS 방어 이중 적용 (memo, note content, **★ v1.2: LOA 서명 메모, 본인확인 필드**)
- [ ] OwnershipValidator 패턴 준용
- [ ] LOA 스냅샷 신원 컬럼 불변 정책 유지 (대리 작성·v1.2 3-경로에서도 변경 없음)
- [ ] 파일 저장은 LocalFileStorageService(S3 인터페이스 호환), **★ v1.2: 경로 A 업로드 파일은 FileEncryptionService 적용**
- [ ] Flyway 마이그레이션 (V_NN 넘버링 규칙 준수)
- [ ] UserRole 확장 시 기존 시드 스크립트 갱신, 테스트 픽스쳐 갱신
- [ ] **★ v1.2: 토큰은 URL path만, 쿼리스트링 금지** (Referer 유출 방지, AC-20)
- [ ] **★ v1.2: 모든 public 토큰 엔드포인트는 HTTPS 강제** (SecurityConfig)
- [ ] **★ v1.2: Bucket4j rate limit** — 토큰 진입(IP당 시간당 10회), 본인확인(토큰당 5회 lifetime)
- [ ] **★ v1.3: 필수 동의 4종 @AssertTrue 검증** — PDPA/TERMS/SIGNUP/DELEGATION
- [ ] **★ v1.3: UserConsentLog `@Column(updatable=false)` + soft delete 미적용** — 감사 증적 무결성
- [ ] **★ v1.3: 이메일 정규화 (lowercase, trim)** — 중복 생성 방지 (§7.7.3)
- [ ] **★ v1.3 → v1.4: `status=PENDING_ACTIVATION` 계정 로그인 차단** — AC-29, AC-31 (status 분기 선행)
- [ ] **★ v1.3: 기존 User backfill 마이그레이션 검증** — `signupSource=DIRECT_SIGNUP`, `termsVersion='1.0'`
- [ ] **★ v1.3: AccountSetupToken rate limit** — 본인 재발송 5분/1회, Manager는 무제한
- [ ] **★ v1.4: `UserStatus` enum 도입 + `signupCompleted` 2단계 제거** — R1(status 추가+backfill), R2(컬럼 제거)
- [ ] **★ v1.4: `activatedAt` `@Column(updatable=false)`** — AC-37, 컴플라이언스 증적 불변성
- [ ] **★ v1.4: User 상태 전이는 도메인 메서드만** — `activate()`/`suspend()`/`softDelete()` 외 `setStatus` 직접 호출 금지 (테스트로 보장)
- [ ] **★ v1.4: `POST /api/auth/login/request-activation` 고정 응답 + 타이밍 동등성** — §4.4, O-23, AC-31
- [ ] **★ v1.4: Account Setup 완료 시 status/activatedAt/firstLoggedInAt 원자적 전이** — 감사 로그 2건(ACCOUNT_SETUP_COMPLETED + ACCOUNT_ACTIVATED)
- [ ] **★ v1.4: 기존 User backfill** — `deleted_at IS NOT NULL → DELETED`, `signup_completed=true → ACTIVE`, 그 외 `PENDING_ACTIVATION` (검증 쿼리 3개 0건 확인)
- [ ] **★ v1.5 (H-1): 로그인 API는 비밀번호 검증 선행** — AC-29 5케이스 동일 응답 + dummy BCrypt.verify 호출 검증 (Mockito) + 타이밍 통합 테스트 CI 빌드 차단
- [ ] **★ v1.5 (H-3): AccountSetupToken 5회 실패 잠금 + Manager 재발송 복구** — AC-28b 테스트
- [ ] **★ v1.5 (H-4): SecurityConfigTest 전체 role × 엔드포인트 매트릭스** — `/api/concierge-manager/**`에 LEW/APPLICANT 접근 차단 검증 + LOA 경로 A 업로드 API는 CONCIERGE_MANAGER+ADMIN만 (AC-15b)
- [ ] **★ v1.5 (O-17): AccountSetupToken 활성 토큰 1개만 유지** — AC-28c 테스트 (`SELECT COUNT(*) WHERE isUsable = 1` 불변식)
- [ ] **★ v1.5 (O-15): N5-UploadConfirm 7일 이의 제기 창구 문구** — AC-22b 템플릿 검증 테스트
- [ ] **★ v1.5 (O-22): Phase 3 `ConciergePendingCleanupScheduler` 기준 시점 `GREATEST(created_at, last_setup_token_issued_at)`** — 재발송 시 카운트 리셋 통합 테스트
- [ ] **★ v1.5 (H-5 / O-21): 옵션 A 완전 폐기 검증** — `POST /api/auth/login/force-change-password` 엔드포인트 부재 + N1 템플릿에 평문 비밀번호 변수 미사용 (템플릿 린트)
- [ ] **★ v1.5 (M-1 / O-23): `GenericRateLimiter` 재사용** — Bucket4j 미도입. IP 20/h + 이메일 내부 카운터 5분/1회 테스트
- [ ] **★ v1.5 (M-5): Payment `referenceType`별 권한 분기** — `APPLICATION`/`CONCIERGE_REQUEST`/`SLD_ORDER` 각각 다른 OwnershipValidator 적용 (Phase 2 구현 시)
- [ ] **★ v1.5 (O-1): LOA 경로 A 배포 전 싱가포르 법무 자문 완료** — ETA §3·§4 / PDPA §22A / EMA 가이드라인 문서화

---

## 13. 영향받는 주요 파일/모듈

**Backend 신규**
- `domain/concierge/ConciergeRequest.java`, `ConciergeRequestStatus.java`, `ConciergeNote.java`, `NoteChannel.java`
- `domain/concierge/ConciergeRequestRepository.java`, `ConciergeNoteRepository.java`
- `api/concierge/ConciergeController.java` (public)
- `api/concierge/ConciergeManagerController.java`
- `api/concierge/ConciergeService.java`, `ConciergeNotifier.java`
- `api/admin/AdminConciergeController.java`
- **★ v1.3 통합 가입 플로우 관련 신규**:
  - `domain/user/SignupSource.java` (enum: DIRECT_SIGNUP/CONCIERGE_REQUEST/ADMIN_INVITE)
  - `domain/user/consent/UserConsentLog.java` + `UserConsentLogRepository.java`
  - `domain/user/consent/ConsentType.java`, `ConsentAction.java`, `ConsentSourceContext.java` enum 3종
  - `api/user/consent/ConsentLogService.java` — 동의 기록 API (내부 호출용)
  - `api/auth/AccountSetupController.java` — `/api/public/account-setup/**` public
  - `api/auth/AccountSetupService.java`, `AccountSetupTokenService.java`
  - `api/concierge/EmailCheckController.java` — `/api/public/concierge/check-email` (Phase 2)
  - `common/terms/TermsConstants.java` — 약관 버전 상수 (O-19 Phase 1 해법)
- **★ v1.2 LOA 서명 수집 관련 신규**:
  - `domain/loa/LoaSignatureSource.java` (enum: APPLICANT_DIRECT/MANAGER_UPLOAD/REMOTE_LINK)
  - `domain/loa/LoaSigningToken.java`, `LoaSigningTokenRepository.java` (Phase 2)
  - `api/loa/LoaSignatureUploadController.java` (Manager 대리 업로드, Phase 1)
  - `api/loa/PublicLoaSignController.java` (Phase 2, 토큰 기반 public)
  - `api/loa/LoaRemoteSignController.java` (Manager용 토큰 발급/폐기, Phase 2)
  - `api/loa/QrCodeGenerator.java` (ZXing 래퍼, Phase 2)
  - `api/loa/LoaSignatureNotifier.java` (N5-Alt, N5-UploadConfirm 발송 오케스트레이션)

**Backend 수정**
- `domain/user/UserRole.java` (+ CONCIERGE_MANAGER)
- **★ v1.3 → v1.4: `domain/user/User.java`** — v1.3 필드(`signupSource`, `signupConsentAt`, `termsVersion`, `marketingOptIn`, `marketingOptInAt`) + **★ v1.4: `status`, `activatedAt`(`updatable=false`), `firstLoggedInAt` 컬럼 추가**. `signupCompleted` 컬럼은 v1.4 R2 릴리스에서 제거
- **★ v1.4: `domain/user/UserStatus.java`** (신규 enum)
- **★ v1.4: User 도메인 메서드** — `activate()`, `suspend(reason)`, `unsuspend()`, `softDelete()` (상태 전이 단일 진입점)
- `domain/notification/NotificationType.java` (+ v1.2 2종 + v1.3 3종 + **★ v1.4 1종: ACCOUNT_ACTIVATION_LINK_SENT**)
- `domain/audit/AuditAction.java` (+ v1.2 5종 + v1.3 7종 + v1.4 7종 + **★ v1.5 3종: LOGIN_FAILED_UNKNOWN_EMAIL, LOGIN_FAILED_BAD_PASSWORD, LOGIN_FAILED_DELETED** + **ACCOUNT_SETUP_TOKEN_FAILED_ATTEMPT, ACCOUNT_SETUP_TOKEN_LOCKED, LOA_SIGNATURE_IMPLICIT_CONSENT_LAPSED**)
- **★ v1.4 → v1.5: `api/auth/AuthService.java`** — **비밀번호 검증 선행** 후에만 `status` 분기(H-1). 미존재 이메일에도 `DUMMY_BCRYPT_HASH`로 `passwordEncoder.matches` 호출. DELETED/미존재 모두 `INVALID_CREDENTIALS`로 통일
- **★ v1.4 → v1.5: `api/auth/LoginActivationService.java`** (신규) — `requestActivation(email, ip, ua)`. **§4.4 constant-time 패딩 전략 구현**. 기존 `GenericRateLimiter` 재사용, dummy BCrypt.verify, `@Async + afterCommit` 발송 격리
- **★ v1.5 신규: `domain/auth/AccountSetupToken.java`** (§3.12) — 독립 엔티티 분리. `failedAttempts`, `lockedAt`, `revokedAt`, `issueSource` 필드. 도메인 메서드 `recordFailedAttempt()`, `markUsed()`, `revoke()`, `isUsable()`
- **★ v1.5 신규: `domain/auth/AccountSetupTokenRepository.java`** — 활성 토큰 단일성을 위한 `findActiveByUser(user)` 메서드 + 비관적 락 고려
- **★ v1.5 신규: `api/auth/AccountSetupTokenService.java`** — `issueOrReissue(user, IssueSource)` 메서드(기존 활성 토큰 자동 revoke 후 INSERT, 트랜잭션 내 원자성 보장)
- **★ v1.5 신규: `config/AuthConstants.java`** — `DUMMY_BCRYPT_HASH` 사전 생성 상수 (ApplicationContext 초기화 시 1회)
- **★ v1.5 수정: `config/SecurityConfig.java`** — `/api/concierge-manager/**` 경로는 `hasAnyRole('CONCIERGE_MANAGER','ADMIN')`. `/api/admin/**`은 ADMIN 전용. LOA 경로 A 업로드 API를 `/api/admin/...`에서 `/api/concierge-manager/applications/{id}/loa/upload-signature`로 **경로 이관**(H-4)
- **★ v1.5 신규 테스트: `SecurityConfigTest`** — role × 엔드포인트 매트릭스 전수 검증 (LEW가 concierge-manager 경로 403, APPLICANT가 admin 경로 403 등)
- **★ v1.5 신규: `common/terms/TermsVersion.java`** — `CURRENT = "2026-04-19"` 상수 (O-19 Phase 1~2)
- **★ v1.3 → v1.4 → v1.5: `api/user/UserService.java`** — `createForConcierge(...)` 메서드 (임시 해시=DUMMY_BCRYPT_HASH로 세팅, **status=PENDING_ACTIVATION**)
- **★ v1.3: `api/concierge/ConciergeRequestService.java`** — 통합 가입 플로우 (§7.7 케이스 분기 로직, v1.4 status 기준 C2/C3/C4b 분기)
- **★ v1.3: `api/concierge/ConciergeNotifier.java`** — N1 통합 이메일, N1-Alt, N1-R 발송 추가
- **★ v1.3 → v1.4: `api/email/EmailService.java`** — v1.3 메서드 + **★ v1.4: `sendAccountActivationLink(...)` (N-Activation)**
- **★ v1.4: Account Setup 서비스** — 완료 시 `user.activate()` 호출 (status=ACTIVE + activatedAt + firstLoggedInAt 원자적 전이)
- `api/email/EmailService.java` + `SmtpEmailService`, `LogOnlyEmailService` (+ 신규 7~9 메서드 + **N5-Alt, N5-UploadConfirm 메서드**)
- `api/sms/SmsService.java` (Phase 2 신규 인터페이스, 경로 B SMS 발송)
- `api/application/ApplicationService.java` (+ `createOnBehalfOf`)
- `domain/application/Application.java` (+ `via_concierge_request_seq` 컬럼, updatable=false. **★ v1.2: `loa_signature_source`, `loa_signature_uploaded_by`, `loa_signature_uploaded_at`, `loa_signature_source_memo` 컬럼 4종 추가**)
- **`api/loa/LoaService.java`** — 기존 `signLoa` 소유권 검증 유지, 에러 코드 `LOA_SIGNATURE_REQUIRES_APPLICANT`로 세분화. **★ v1.2: `uploadSignature(managerSeq, file, source, memo)`, `submitViaToken(token, signaturePng, ip, ua)` 메서드 추가. 3개 메서드는 모두 동일한 `embedSignatureIntoPdf()` 호출**
- **`domain/payment/Payment.java`** — `referenceType`/`referenceSeq` 컬럼 추가, `application_seq` nullable 전환, `of(referenceType, …)` 팩토리 추가
- **`domain/payment/PaymentReferenceType.java`** (신규 enum)
- `domain/payment/PaymentRepository.java` — `findByReferenceTypeAndReferenceSeq` 추가
- `api/payment/PaymentService.java` — 도메인별 결제 생성 경로 분기
- `config/SecurityConfig.java` (+ `/api/concierge-manager/**` 권한, `/api/concierge/requests` public, **★ v1.2: `/api/public/loa-sign/**` public + HTTPS 강제**)
- `config/RateLimitConfig.java` — Bucket4j 설정 (**★ v1.2: 토큰 진입 + 본인확인 보호**)
- 시드 스크립트 / test fixtures (`kaki@bluelight.sg` concierge manager)

**Backend 마이그레이션**
- `V_NN__concierge_request_tables.sql` — `concierge_requests`, `concierge_notes` (+ **★ v1.3: `delegation_consent_at` 컬럼**)
- `V_NN__application_add_via_concierge_request.sql`
- **`V_NN__payment_add_reference_type.sql`** — reference_type/reference_seq 추가 + backfill (§3.8.2)
- **★ v1.2: `V_NN__application_add_loa_signature_source.sql`** — Application에 LOA 서명 출처 컬럼 4종 추가 (Phase 1)
- **★ v1.2: `V_NN__loa_signing_tokens.sql`** — LoaSigningToken 테이블 (Phase 2)
- **★ v1.3 → v1.4 조정: `V_NN__user_add_signup_columns.sql`** — User에 signupSource/signupConsentAt/termsVersion/marketingOptIn/marketingOptInAt 5종 추가 + 기존 사용자 backfill (Phase 1). **v1.4에서 `signupCompleted` 컬럼은 이 마이그레이션에 포함하지 않고 별도 `V_NN__user_replace_signup_completed_with_status.sql`로 처리**
- **★ v1.3: `V_NN__user_consent_logs.sql`** — UserConsentLog 테이블 생성 + 기존 pdpaConsentAt backfill (Phase 1)
- **★ v1.4: `V_NN__user_add_status_column.sql` (R1)** — `status`, `activated_at`, `first_logged_in_at` 컬럼 추가 + backfill(`deleted_at IS NOT NULL → DELETED`, `signup_completed=true → ACTIVE`, else `PENDING_ACTIVATION`). 인덱스 `idx_user_status`, `idx_user_status_created` 추가
- **★ v1.4: `V_NN__user_drop_signup_completed.sql` (R2, 안정화 후 별도 릴리스)** — R1 배포 후 최소 2주 관측 후 `signup_completed` 컬럼 제거
- **★ v1.5: `V_NN__account_setup_tokens.sql`** (Phase 1) — `token_uuid`(UK) / `user_seq` / `issue_source` / `expires_at` / `used_at` / `failed_attempts` / `locked_at` / `revoked_at` / `deleted_at` + 인덱스 `uk_account_setup_token_uuid`, `idx_account_setup_token_user`, `idx_account_setup_token_expires`
- **★ v1.5: `V_NN__add_concierge_manager_loa_upload_endpoint.sql` (신규 Flyway 불필요, 단 SecurityConfigTest 매트릭스 보장)** — LOA 경로 A 업로드 API를 `/api/concierge-manager/...`로 이관(H-4)
- **★ v1.5 Phase 3: `V_NN__terms_documents.sql`** — 약관 CMS 테이블 (O-19 완전 해결), `TermsVersion.CURRENT` 상수 대체

**Frontend 신규**
- `pages/concierge/ConciergeRequestPage.tsx` (모달 or 풀페이지) — **★ v1.3: 5종 동의 체크박스 섹션 포함**
- `pages/concierge/ConciergeRequestSuccessPage.tsx` — **★ v1.3: 재발송 버튼 + 기존 계정 연결 분기**
- **★ v1.3: `pages/auth/AccountSetupPage.tsx`** — `/setup-account/{token}` 풀페이지 (비밀번호 설정 + 자동 로그인)
- **★ v1.3: `components/consent/ConsentCheckboxGroup.tsx`** — 5종 동의 + 전체 동의 토글 재사용 컴포넌트
- **★ v1.3: `components/consent/TermsModal.tsx`** — 약관 전문 표시 모달
- `pages/concierge-manager/ConciergeManagerDashboardPage.tsx` — **★ v1.3: "계정 설정 대기" KPI 카드**
- `pages/concierge-manager/ConciergeRequestListPage.tsx`
- `pages/concierge-manager/ConciergeRequestDetailPage.tsx` — **★ v1.3: Account Status 패널 + 재발송 버튼**
- `pages/concierge-manager/sections/*` (Timeline, NotesPanel, ActionBar)
- **★ v1.2: `pages/concierge-manager/sections/LoaSignatureCollectionPanel.tsx`** — 3-탭 LOA 서명 수집 UI (탭 ① 직접 / 탭 ② 업로드 / 탭 ③ 원격 링크)
- **★ v1.2: `pages/public/RemoteLoaSignPage.tsx`** — `/sign/{token}` 풀페이지 3-step 마법사 (Phase 2)
- **★ v1.2: `components/loa/QrCodeDisplay.tsx`** — base64 QR PNG 표시 + 복사/다운로드
- `stores/conciergeStore.ts`
- `api/conciergeApi.ts` — **★ v1.3: `submitConciergeRequest`, `checkEmail` 메서드 포함**
- **★ v1.2: `api/loaSignatureApi.ts`** — Manager 업로드 + 원격 링크 API 클라이언트
- **★ v1.2: `api/publicLoaSignApi.ts`** — 토큰 기반 public API (Phase 2)
- **★ v1.3: `api/accountSetupApi.ts`** — Account Setup GET/POST/Resend 클라이언트

**Frontend 수정**
- `pages/LandingPage.tsx` (Concierge Section 추가)
- `stores/authStore.ts` (CONCIERGE_MANAGER 라우팅 분기 + **★ v1.4: `ACCOUNT_PENDING_ACTIVATION` / `ACCOUNT_SUSPENDED` 응답 분기 처리**)
- `pages/admin/sections/AdminSidebar.tsx` (Concierge 메뉴)
- `pages/applicant/ApplicationDetailPage.tsx` (Concierge 경유 배너 + **★ v1.2: 서명이 MANAGER_UPLOAD 출처일 경우 출처 안내 + 이의 제기 링크 표시**)
- **★ v1.3 → v1.4: `pages/auth/LoginPage.tsx`** — `ACCOUNT_PENDING_ACTIVATION` 에러 수신 시 "Send activation link" 모드로 전환 + `POST /api/auth/login/request-activation` 호출 + 고정 응답 표시
- **★ v1.4 신규: `components/auth/ActivationLinkRequestButton.tsx`** — 이메일 입력 + 버튼 + rate-limit 처리 공용 컴포넌트
- `App.tsx` 또는 라우터 파일 (**★ v1.2: `/sign/{token}` + ★ v1.3: `/setup-account/{token}` public 라우트 등록**)

---

## 14. 다음 단계

1. **본 PRD v1.5는 Phase 1 착수 승인 완료**. 오픈 이슈 대부분 해결(§10.1). Phase 1 PR#1-Enhanced 착수 가능
2. **배포 전 법무 자문 필수 (O-1)**:
   - LOA 경로 A (MANAGER_UPLOAD) + 7일 묵시적 동의 창구의 ETA §3·§4 / PDPA §22A / EMA 가이드라인 충족 여부 확인
   - 자문 결과에 따라 경로 A 문구·동의 기간·감사 로그 조정 가능 (코드 변경 최소화 설계)
3. **배포 전 법무 확인 (O-18)**: soft-deleted 계정 이메일 재신청 시 "신규 생성" 처리의 PDPA 적법성 확인
4. **Phase 2 의사결정**: O-5 (서비스 요금), O-8 (환불 정책), O-13 (OTP 공급자), O-14 (QR 라이브러리)
5. **Phase 3 의사결정**: O-11 (라이선스료 대리 결제), O-16 (경로 B 본인확인 수단 강화)
6. **Phase 4 의사결정**: O-3 (Manager 캐파시티 상한)
7. 승인 완료 시 `doc/Project execution/phase-concierge/` 디렉토리 신설 후 **Phase 1 PR#1-Enhanced ~ PR#7** `01-spec.md` 분해 착수

**v1.5에서 추가 확정된 사항 요약 (보안 리뷰 기반 일괄 승인)**:
- **옵션 A 완전 폐기** (H-5 / O-21) — `/api/auth/login/force-change-password` 엔드포인트 제거, §6.4-1b 템플릿 폐기 표시. 로그인 활성화는 **옵션 B 단독**
- **AC-29 재작성** (H-1) — 비밀번호 검증 선행 + dummy BCrypt.verify + DELETED/미존재 공통 `INVALID_CREDENTIALS`. 5케이스 동일 응답 + p95<200ms 타이밍 CI
- **AC-31 수정** (H-1) — 활성화 플로우 CTA는 비밀번호 검증 성공 + PENDING_ACTIVATION인 경우에만 노출. 프론트는 "Send me an activation link" 보조 버튼을 로그인 응답과 무관하게 항상 노출
- **AccountSetupToken 독립 엔티티 분리** (H-3 / O-17) — §3.12 신설. `failedAttempts`(5회 잠금), `lockedAt`, `revokedAt` 필드. 유저당 유효 토큰 1개만 유지. AC-28b, AC-28c 신설
- **SecurityConfig 매처 분리** (H-4) — `/api/admin/**` ADMIN 전용, LOA 경로 A 업로드 API는 `/api/concierge-manager/applications/{id}/loa/upload-signature`로 이관(CONCIERGE_MANAGER+ADMIN만, LEW 제외). AC-15b 신설 + SecurityConfigTest 매트릭스 필수
- **§4.4 constant-time 패딩 전략** (M-1 / O-23) — Bucket4j 대신 `GenericRateLimiter` 재사용. Java/Spring 의사코드 삽입(`DUMMY_BCRYPT_HASH`, `@Async + afterCommit`, `MessageDigest.isEqual`). p95<200ms CI 빌드 차단
- **PENDING_ACTIVATION 자동 정리 확정** (O-22) — 90일 SUSPENDED / 180일 soft-delete+DELETED. Phase 3 `ConciergePendingCleanupScheduler` 구현
- **경로 A 7일 이의 제기 창구** (O-15) — N5-UploadConfirm 템플릿 필수 문구 + AC-22b 신설 + Phase 3 `LOA_SIGNATURE_IMPLICIT_CONSENT_LAPSED` 감사 로그
- **약관 버전 관리 확정** (O-19) — Phase 1~2: `TermsVersion.CURRENT` 상수 / Phase 3: `terms_documents` DB CMS
- **LOA 경로 A 법무 조건부 승인** (O-1) — Phase 1 착수 가능 + **배포 전 싱가포르 법무 자문 완료 필수**
- **Payment referenceType 권한 분기** (M-5, §8.4b 신규) — `APPLICATION`/`CONCIERGE_REQUEST`/`SLD_ORDER` 각각 다른 OwnershipValidator. LEW의 CONCIERGE_REQUEST 결제 접근 차단
- **오픈 이슈 대폭 정리**: O-15/O-17/O-19/O-21/O-22/O-23 해결 완료 이동, O-1은 조건부 승인. 잔여: O-3(Phase 4), O-13/O-14(Phase 2), O-18(법무)
- **신규 AC 5종** (AC-15b, AC-22b, AC-28b, AC-28c + AC-29/AC-31 재작성)

**v1.4에서 추가 확정된 사항 요약**:
- **계정 활성화 정책 재정의**: v1.3의 `signupCompleted` boolean → **`UserStatus` enum (`PENDING_ACTIVATION` / `ACTIVE` / `SUSPENDED` / `DELETED`)**. 컨시어지 자동 생성 계정은 `PENDING_ACTIVATION`으로 시작, **최초 로그인 성공 시 `ACTIVE`로 전환**
- **User 엔티티 컬럼 교체**: `signupCompleted` 제거 + `status`, `activatedAt`(`updatable=false`), `firstLoggedInAt` 추가 (2단계 릴리스 R1/R2)
- **User 상태 머신 (§5.1c)**: 전이 다이어그램 + 도메인 메서드(`activate`/`suspend`/`softDelete`) + 불변식
- **N1 이메일 전면 재설계**: "계정 생성 + 비활성 상태 + 최초 로그인 시 활성화" 핵심 단락 + 로그인 페이지 CTA
- **N-Activation 신규 알림**: 로그인 시도 시 활성화 인증 링크 발송 (옵션 B 권장안)
- **로그인 API 분기 선행**: `POST /api/auth/login`에서 비밀번호 검증 전에 status 체크, `PENDING_ACTIVATION` → 401 + activationFlow 안내
- **`POST /api/auth/login/request-activation` 신규**: §4.4 이메일 존재 노출 방지 정책 완전 적용
- **옵션 A vs B 2가지 경로 제시**: B 권장, A는 대안으로 병기 (O-21)
- **기존 회원 분기 C3 재정의** (§7.7): "PENDING_ACTIVATION 계정 재신청 → 중복 생성 방지" + C4b(SUSPENDED 차단) 추가
- **신규 AC 8종** (AC-29 재정의 + AC-30 ~ AC-37)
- **신규 오픈 이슈 3종** (O-21/22/23)

**v1.3에서 추가 확정된 사항 요약**:
- **통합 가입 플로우**: 컨시어지 신청 시점에 User 자동 생성 + ConciergeRequest + UserConsentLog가 **한 트랜잭션**으로 기록
- **5종 동의 체계**: 필수 4 (PDPA / ToS / 회원가입 / 대행위임) + 선택 1 (마케팅)
- **User 엔티티 6컬럼 확장** (v1.4에서 signupCompleted 제거 후 5컬럼 + status/activatedAt/firstLoggedInAt 구성으로 재조정)
- **신규 엔티티 `UserConsentLog`** — 동의/철회 전수 감사 보존 (soft delete 미적용, updatable=false)
- **Account Setup 플로우** — `/setup-account/{token}` 페이지 + 3종 public API + 자동 로그인 (v1.4에서 완료 시 `status=ACTIVE` 전이 추가)
- **N1 통합 이메일** (기존 N1 + N4 통합 → v1.4 재설계), **N1-R 리마인더** (v1.4 문구 재정의), **N1-Alt 기존 계정 연결 안내**
- **기존 회원 분기** 5케이스 (§7.7): 미가입 / 활성 APPLICANT / PENDING_ACTIVATION / staff / soft-deleted (v1.4에서 SUSPENDED C4b 추가)
- **신규 AC 8종** (AC-22 ~ AC-29, AC-29는 v1.4에서 재정의)
- **신규 오픈 이슈 3종** (O-18/19/20)

**v1.2에서 확정된 사항 (유지)**:
- LOA 서명 수집: **3-경로 모델** (직접/MANAGER_UPLOAD/REMOTE_LINK)
- Application에 LOA 서명 출처 컬럼 4종 추가
- 신규 엔티티 `LoaSigningToken` (1회성 토큰, 48h 만료)
- 신규 알림 N5-Alt, N5-UploadConfirm
- 신규 AC 7종 (AC-15 ~ AC-21)

**v1.1에서 확정된 사항 (유지)**:
- SLA: **24시간 이내 최초 연락** (O-4)
- Manager 캐파시티: **현 시점 제한 없음**, Phase 4 재검토 (O-3)
- 결제 엔티티: **Payment 재사용 + referenceType/referenceSeq** (O-6)
- On-Behalf-Of 범위: Application/문서/SLD/LOA 생성은 대행 가능, **LOA 서명·PDPA 동의·결제는 신청자 본인의 의사 표시여야 함** (v1.2에서 LOA 서명은 3-경로로 수집 방식 확장, **v1.3에서 PDPA 동의는 가입 시 명시 체크 + UserConsentLog에 증적 보존**)
- 상태 머신: `AWAITING_APPLICANT_LOA_SIGN`, `AWAITING_LICENCE_PAYMENT` 상태 신설 (**★ v1.3: `User.signupCompleted` 병행 상태 추가** → **★ v1.4: `UserStatus` enum으로 대체, §5.1c User 상태 머신 다이어그램 추가**)

---

**작성 근거**:
- 랜딩페이지 구조: `blue-light-frontend/src/pages/LandingPage.tsx`
- 역할 체계: `blue-light-backend/src/main/java/com/bluelight/backend/domain/user/UserRole.java`
- 알림 패턴: `api/document/DocumentRequestNotifier.java` (afterCommit 오케스트레이션의 레퍼런스 구현)
- 감사 로그 규약: `domain/audit/AuditAction.java`, `AuditCategory.java`
- Application 소유권/LOA 불변 정책: `domain/application/Application.java` JavaDoc
- **LOA 서명 본인 검증**: `api/loa/LoaService.java:132` — `!application.getUser().getUserSeq().equals(userSeq)` 소유권 검증 (v1.2에서도 그대로 유지, 경로 A/B는 별도 메서드)
- **Admin 우회 패턴**: `common/util/OwnershipValidator.java:38-42` — `validateOwnerOrAdmin` (CONCIERGE_MANAGER도 Admin 우회 그룹에 편입 예정)
- Payment 패턴(SLD Order 선례): CLAUDE.md Key Decisions "SLD 주문: Application과 분리된 독립 도메인"
- Phase 3 스펙 포맷 참고: `doc/Project execution/phase3-lew-document-workflow/01-spec.md`
- **★ v1.2 법무 근거 확인 필요**:
  - Singapore Electronic Transactions Act (ETA, Cap 88) — 특히 s.8 (electronic signatures) 및 s.9 (secure electronic signatures)
  - Personal Data Protection Act (PDPA) — 동의 수집·증적 보존 요건
  - EMA Licensing Regulations — LOA 서명 형식 요건 재확인
- **★ v1.3 법무·보안 근거 확인 필요**:
  - PDPA Section 13 (Consent Obligation) — 개별 동의 분리 수집 요건 (PDPA/ToS/SIGNUP/DELEGATION/MARKETING 별도)
  - PDPA Section 16 (Withdrawal of Consent) — 동의 철회의 용이성 보장 (O-20 원클릭 수신거부 링크)
  - PDPA Advisory Guidelines on Key Concepts §12 — 동의 수집 증적 보존 기간 (권고 7년, `UserConsentLog` soft delete 미적용으로 달성)
  - 기존 LicenseKaki `PasswordResetToken` 인프라 — Account Setup 토큰에 재활용 (`source=CONCIERGE_ACCOUNT_SETUP` 구분)
  - 기존 `UsersRepository`, `AuthService`, `SmtpEmailService` 템플릿 패턴 — 신규 N1 통합 이메일 구현 시 참조
- **★ v1.4 보안 근거 확인 필요**:
  - NIST SP 800-63B §5.2.2 (Verifier Impersonation Resistance) + §5.1.1.2 (Memorized Secret Verifiers) — 로그인 관련 에러가 사용자 존재 여부를 유출하지 않아야 함 (§4.4)
  - OWASP ASVS V2.2.1, V2.2.2 — 인증 에러의 균일성 요구사항
  - 기존 LicenseKaki `AuthService` 로그인 로직 — `UserStatus` 분기를 비밀번호 검증 전에 삽입하는 위치 재검토
  - PDPA §24 (Protection Obligation) — 평문 비밀번호 이메일 전송(옵션 A)의 데이터 보호 리스크 판단 근거
  - Singapore Electronic Transactions Act §10 (Electronic records as originals) — 계정 활성화 전·후의 "전자 기록" 법적 효력 차이
- **★ v1.5 반영 근거**:
  - `doc/Project Analysis/kaki-concierge-security-review.md` (Phase 1 사전 보안 검토, 2026-04-19) — H-1 ~ H-5 + M-1 ~ M-5 + L-1 ~ L-3 전수 분석 결과
  - CWE-204 (Response Discrepancy Information Exposure) — 로그인 응답의 status 분기로 인한 이메일 enumeration 방지 (H-1)
  - OWASP ASVS V2.1.6 — 사용자 자격증명을 이메일·SMS로 전송 금지 (H-5, 옵션 A 폐기 근거)
  - LoaSigningToken §3.10 AC-18의 대칭 설계 원칙 — AccountSetupToken에도 동일한 5회 실패 잠금 + 활성 토큰 1개 유지 적용 (H-3 / O-17)
  - 기존 `SecurityConfig.java` / `OwnershipValidator.java:38-42` — `/api/admin/**`이 LEW에게도 허용되던 구조를 매처 분리로 해결 (H-4)
  - 기존 `GenericRateLimiter` (develop 브랜치) — Bucket4j 신규 도입 대신 재사용 결정 (M-1)
  - `MessageDigest.isEqual` (java.security) — 상수 시간 문자열 비교 표준 API
