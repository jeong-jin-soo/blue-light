# ADMIN 수동 이메일 발송 (Admin Manual Email Dispatch) 스펙

> **작성일**: 2026-05-01
> **작성자**: Product Manager (LicenseKaki)
> **상태**: Draft v1.0 — 검토자 결정 7건 미해결 (§11), 결정 후 PR-1 발주 예정
> **대상 독자**: Backend·Frontend 개발자, Admin 운영팀, 보안·법무
> **선행 의존**: `doc/Project Analysis/notification-strategy.md`, `doc/Project Analysis/notification-catalog.en.md`, `doc/Project Analysis/notification-copy-templates.en.md`
> **권한 모델 의존**: `memory/MEMORY.md`의 시드 사용자 (admin@licensekaki.sg / sysadmin@licensekaki.sg)

---

## §1. 요구사항 요약

LicenseKaki ADMIN 사용자가 시스템 내에서 본문·제목을 직접 입력해 단일 또는 복수 수신자에게 ad-hoc 이메일을 발송할 수 있도록 한다 — 자동 트랜잭션 알림으로 다룰 수 없는 비정형 운영 안내(시스템 점검 사전 공지, 특정 신청건 별도 안내 등)를 정식 채널로 전달하기 위한 기능. 전 발송 건은 `manual_email_dispatches` 테이블에 영구 보관되어 감사·재현 가능해야 한다.

---

## §2. 범위 / 비범위

### 2.1 범위 (In Scope)

- **권한**: `hasAnyRole('ADMIN', 'SYSTEM_ADMIN')` — SYSTEM_ADMIN 은 ADMIN 슈퍼셋이라 접근 허용. 단, 좌측 메뉴 항목은 ADMIN 역할에만 노출(SYSTEM_ADMIN 은 직접 URL 진입 가능).
- **수신자 타입**: APPLICANT(시스템 사용자) / LEW(시스템 사용자) / EXTERNAL(임의 이메일 주소) / MULTI(상기 타입 혼합 다수).
- **본문 입력**: PLAIN_TEXT, 최대 50,000자, 시스템 자동 헤더·푸터 부착 후 발송.
- **발송 이력**: ADMIN/SYSTEM_ADMIN 누구나 본인 + 타 ADMIN 발송분 모두 조회 (운영 투명성).
- **감사 로그**: `AuditAction.MANUAL_EMAIL_DISPATCHED` 신규 추가.
- **인앱 알림 동반(D4 결정)**: 시스템 사용자 수신자에게 인앱 `Notification` row 동반 생성 옵션.

### 2.2 비범위 (Out of Scope)

- 마케팅 캠페인 (별도 마케팅 도메인 — Spam Control Act §ADV 라벨, opt-in 관리, DNC 체크 별도 인프라 필요).
- 일괄 mail merge (수신자별 변수 치환 — 본 PR 미지원, 동일 본문만 발송).
- 첨부 파일 (D1 결정 후 별도 PR — MVP 미포함).
- 외부 메일 시스템 연동 (Mailchimp/SendGrid Campaign 등).
- 신청자(APPLICANT) / LEW 측 발송 권한 (본 기능은 ADMIN 전용).
- HTML 본문 입력 (XSS·피싱 위험 — MVP 는 PLAIN_TEXT 전용).
- 발송 후 취소/리콜 (이메일 특성상 불가 — 사전 미리보기·확인 모달로 대체).
- 카테고리 × 채널 옵트아웃 매트릭스 적용 — 본 기능은 ADMIN 운영 안내 = Transactional 분류 → 옵트아웃 불가 (`notification-strategy.md` §5.1, §5.2 의 [고정:ON] 정책에 부합).

---

## §3. 사용자 시나리오

### S1. 단일 신청건 컨텍스트 안내 (가장 흔한 케이스)
ADMIN 이 신청건 #1234 의 신청자에게 "결제 확인이 시스템 점검으로 내일 오후까지 지연됩니다. 양해 부탁드립니다." 라는 이메일을 보낸다. ADMIN 은 좌측 메뉴 "Manual Email" → Compose → 수신자 타입 APPLICANT → 신청자 검색(이메일 또는 신청번호) → relatedApplicationSeq 자동 채워짐 → Subject + Body 입력 → Preview → Send.

### S2. 단일 외부 협력처 안내
ADMIN 이 외부 SP Group 담당자(예: external@spgroup.com.sg)에게 "특정 LEW 인증 관련 문의 회신" 이메일을 보낸다. 수신자 타입 EXTERNAL → 이메일 직접 입력 → Subject + Body → Preview → Send.

### S3. 다수 신청자 동일 안내
ADMIN 이 5월 결제 마감 임박 batch 의 신청자 12명에게 동일 안내 메일을 보낸다. 수신자 타입 MULTI → 신청자 검색하며 chip 으로 12명 추가 → Subject + Body → Preview → Send (확인 모달: "Send to 12 recipients?").

### S4. 발송 이력 조회·재확인
ADMIN 이 어제 보낸 메일이 실제 도착했는지 확인한다. History 탭 → 본인 발송분 필터 → 발송일시 정렬 → 행 클릭 → 상세 모달에서 sentCount/failedCount, 수신자 풀 리스트, 실패 사유 확인.

---

## §4. 데이터 모델

### 4.1 신규 엔티티 `ManualEmailDispatch`

테이블명: `manual_email_dispatches`

| 컬럼 | 타입 | 제약 | 비고 |
|------|------|------|------|
| `dispatch_seq` | BIGINT | PK, AUTO_INCREMENT | |
| `sender_user_seq` | BIGINT | NOT NULL, FK → users | 발송한 ADMIN/SYSTEM_ADMIN seq |
| `recipient_type` | VARCHAR(20) | NOT NULL | enum: `APPLICANT` / `LEW` / `EXTERNAL` / `MULTI` |
| `recipient_user_seqs_json` | JSON | NULLABLE | 시스템 사용자 대상 시 user seq 배열 (예: `[12, 45, 78]`) |
| `recipient_emails_json` | JSON | NULLABLE | EXTERNAL/MULTI 시 이메일 배열 (예: `["a@x.com", "b@y.com"]`) |
| `related_application_seq` | BIGINT | NULLABLE, FK → applications | 신청 컨텍스트 연결 시 |
| `subject` | VARCHAR(200) | NOT NULL | trim 후 빈 문자열 거부 |
| `body_text` | TEXT | NOT NULL | PLAIN_TEXT, 최대 50,000자 |
| `body_format` | VARCHAR(20) | NOT NULL, DEFAULT 'PLAIN_TEXT' | enum: `PLAIN_TEXT` / `HTML` (MVP는 PLAIN_TEXT 만 허용) |
| `category_tag` | VARCHAR(50) | NULLABLE | 자유 분류 (예: `PAYMENT_NOTICE`, `MAINTENANCE`, `MISC`). 시스템 enum 이 아닌 ADMIN 자유 입력 + 추천 드롭다운 |
| `dispatch_status` | VARCHAR(20) | NOT NULL | enum: `PENDING` / `SENT` / `PARTIAL_FAILED` / `FAILED` |
| `sent_count` | INT | NOT NULL, DEFAULT 0 | 실제 발송 성공 수 |
| `failed_count` | INT | NOT NULL, DEFAULT 0 | SMTP 실패 수 |
| `failed_reason` | TEXT | NULLABLE | 실패 시 스택트레이스 또는 SMTP 응답 (수신자별 멀티라인) |
| `dispatched_at` | DATETIME(6) | NULLABLE | 실제 SMTP 발송 시각 (afterCommit) |
| `created_at` / `updated_at` / `created_by` / `updated_by` | — | — | `BaseEntity` 표준 |

**중요 제약**:
- `Soft delete 금지` — 감사 무결성. `@SQLDelete`/`@SQLRestriction` 미적용. `deleted_at` 컬럼 없음.
- `recipient_user_seqs_json` + `recipient_emails_json` 은 **합쳐서 1건 이상** 이어야 한다 (체크 제약 또는 service-layer 검증).
- `recipient_type='MULTI'` 인 경우 두 컬럼 합산 ≥ 2 권장 (단일이면 단일 타입으로 정규화).

### 4.2 인덱스

- `idx_manual_email_sender` ON `(sender_user_seq, created_at DESC)` — "내 발송 이력" 조회용
- `idx_manual_email_status` ON `(dispatch_status, created_at DESC)` — 실패 모니터링
- `idx_manual_email_application` ON `(related_application_seq)` — 신청건 사이드패널에서 "이 신청건에 보낸 메일" 조회 시
- `idx_manual_email_created` ON `(created_at DESC)` — 전체 이력 페이지네이션

### 4.3 기존 모델 영향

- `AuditAction` enum 에 `MANUAL_EMAIL_DISPATCHED` 추가
- `NotificationType` enum 에 `MANUAL_EMAIL_NOTICE` 추가 (D4=B 채택 시 인앱 동반 발송용)
- `EmailService` 인터페이스에 `sendManualNotice(...)` 메서드 추가 (§14 참조)

---

## §5. 엔드포인트

### 5.1 발송

`POST /api/admin/manual-emails`

- 권한: `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")`
- Request Body (DTO):
  ```json
  {
    "recipientType": "APPLICANT | LEW | EXTERNAL | MULTI",
    "recipientUserSeqs": [12, 45],
    "recipientEmails": ["external@partner.com"],
    "relatedApplicationSeq": 1234,
    "subject": "...",
    "bodyText": "...",
    "categoryTag": "PAYMENT_NOTICE",
    "alsoCreateInAppNotification": true
  }
  ```
- Response: `200 OK` + `{ dispatchSeq, dispatchStatus, sentCount, failedCount, dispatchedAt }`
- 에러: `400` (필수값 누락·형식 오류·길이 초과), `403` (권한 부족), `409` (멱등 충돌, D3 결정 적용 시), `429` (Daily cap 초과, D5 결정 적용 시)

### 5.2 발송 이력 목록

`GET /api/admin/manual-emails`

- 권한: `@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")`
- Query 파라미터:
  - `senderUserSeq` (옵션) — 본인만 보기 vs 전체
  - `dispatchStatus` (옵션) — `SENT` / `PARTIAL_FAILED` / `FAILED` 필터
  - `relatedApplicationSeq` (옵션)
  - `from` / `to` (날짜 범위)
  - `page` / `size` (기본 0 / 20)
- Response: `Page<ManualEmailDispatchSummaryResponse>` — subject, recipient summary("APPLICANT × 1" 또는 "MULTI × 12"), status, sent/failed count, dispatchedAt.

### 5.3 발송 이력 상세

`GET /api/admin/manual-emails/{seq}`

- Response: 전체 본문 + 수신자 풀 리스트(이메일 형식으로 정규화) + 실패 메시지 + 발송자 정보.

### 5.4 미리보기

`POST /api/admin/manual-emails/preview`

- Request: 동일 DTO (subject + bodyText + categoryTag)
- Response: 렌더링된 HTML 문자열 (LicenseKaki 헤더 + 본문 + 자동 푸터). 실제 DB 저장 없음.
- 용도: ADMIN 이 발송 전 받게 될 메일 모양을 모달로 확인.

---

## §6. Given-When-Then 수용 기준

### AC-A1. APPLICANT 단일 발송
**GIVEN** ADMIN 이 로그인되어 있고 APPLICANT user(seq=12, email=alice@example.com) 가 존재한다
**WHEN** ADMIN 이 `POST /api/admin/manual-emails` 를 `recipientType=APPLICANT, recipientUserSeqs=[12], subject="...", bodyText="..."` 로 호출한다
**THEN**
- 200 OK 응답에 `dispatchSeq`, `dispatchStatus=SENT`, `sentCount=1`, `failedCount=0` 포함
- `manual_email_dispatches` 에 row 1건 생성 (sender_user_seq = 호출 ADMIN, recipient_user_seqs_json=[12])
- `alice@example.com` 메일함에 LicenseKaki 헤더 + 본문 + 자동 푸터 + 반피싱 푸터 포함된 메일 도착
- `audit_logs` 에 `MANUAL_EMAIL_DISPATCHED` 액션 1건 기록

### AC-A2. LEW 단일 발송 (assignment 무관)
**GIVEN** ADMIN 이 로그인되어 있고 LEW user(seq=45, status=APPROVED) 가 존재한다 — ADMIN 본인이 처리 중인 신청건에 배정된 LEW 가 아니어도 무방
**WHEN** ADMIN 이 `recipientType=LEW, recipientUserSeqs=[45]` 로 발송한다
**THEN** AC-A1 와 동일한 결과. 권한 가드는 추가하지 않는다 (D2 결정 → A 추천).

### AC-A3. EXTERNAL 임의 이메일 발송 (D1 결정 영역)
**GIVEN** ADMIN 이 로그인되어 있다
**WHEN** ADMIN 이 `recipientType=EXTERNAL, recipientEmails=["partner@spgroup.com.sg"], relatedApplicationSeq=null` 로 발송한다
**THEN** (D1=A 채택 시) 200 OK + 정상 발송. relatedApplicationSeq 없이도 발송 가능.
**AND** (D1=B 채택 시) `relatedApplicationSeq` 누락이면 400 거부 + 메시지 "External email requires application context".

### AC-A4. MULTI 다수 수신자 부분 실패
**GIVEN** ADMIN 이 4명의 수신자(3명 정상 + 1명은 SMTP bounce 예상)에게 발송한다
**WHEN** SMTP 가 1명에 대해 실패한다
**THEN**
- `dispatchStatus=PARTIAL_FAILED`, `sentCount=3`, `failedCount=1`
- `failed_reason` 에 실패한 수신자 이메일 + 사유 기록
- 트랜잭션 롤백 없이 1건의 dispatch row 보존 (실패 격리, `notification-catalog.en.md` 공통 원칙 #8)

### AC-A5. 필수값 누락
**WHEN** ADMIN 이 `subject=""` 또는 `bodyText=""` 또는 두 컬럼 모두 빈 문자열로 발송한다
**THEN** 400 Bad Request + 에러 메시지("Subject and body are required").

### AC-A6. 본문 길이 상한
**WHEN** `bodyText` 가 50,001자 이상이면
**THEN** 400 Bad Request + 메시지("Body exceeds 50,000 characters").
**WHEN** `subject` 가 201자 이상이면 400 거부.

### AC-A7. 권한 가드 — APPLICANT/LEW 임의 발송 (D2 결정 영역)
**GIVEN** ADMIN 이 처리 중인 신청건 #100 의 신청자가 alice 인 상황에서 ADMIN 이 다른 신청건의 신청자 bob 에게 발송하려 한다
**WHEN** `recipientType=APPLICANT, recipientUserSeqs=[bobSeq], relatedApplicationSeq=100` (불일치)
**THEN** (D2=A 채택 시) 200 OK + 정상 발송. ADMIN 은 모든 사용자에게 발송 가능.
**AND** (D2=B 채택 시) 400 거부 + 메시지 "Recipient must match application context".

### AC-A8. SMTP 실패 격리
**GIVEN** SMTP 서버가 다운되어 있다
**WHEN** ADMIN 이 발송한다
**THEN**
- 트랜잭션 롤백 발생하지 않음 (`afterCommit` 훅에서 SMTP 호출, 실패는 격리)
- `dispatch_status=FAILED`, `failed_count=N`, `sent_count=0`, `failed_reason` 에 SMTP 응답 기록
- 200 OK 응답 (단, 응답 body 의 status 필드로 클라이언트가 실패 인지)
- 클라이언트 toast: "Email queued — delivery failed for N recipients. See history for details."

### AC-A9. 멱등성 (D3 결정 영역)
**GIVEN** ADMIN 이 30초 이내 동일 (subject + bodyText 해시 + 정렬된 수신자 리스트) 조합을 두 번 호출한다
**WHEN** 두 번째 호출이 발생한다
**THEN** (D3=B 추천 채택 시) 409 Conflict + 메시지 "Duplicate dispatch detected within 30s. Confirm intent."
**AND** 클라이언트는 "Send anyway" 버튼으로 재시도 가능 (같은 요청에 `forceDuplicate=true` 플래그 추가).

### AC-A10. 발송 이력 투명성
**GIVEN** ADMIN A 가 발송한 5건, ADMIN B 가 발송한 3건이 있다
**WHEN** ADMIN A 가 `GET /api/admin/manual-emails` 를 호출한다 (필터 없음)
**THEN** 8건 모두 반환. 본인 필터(`senderUserSeq=A`)로 제한 가능.

### AC-A11. 인앱 알림 동반 (D4 결정 영역)
**GIVEN** ADMIN 이 APPLICANT 수신자에게 `alsoCreateInAppNotification=true` 로 발송한다
**WHEN** 발송이 성공한다
**THEN** (D4=B 추천 채택 시) `Notification` row 가 수신자별 1건 생성됨 (type=`MANUAL_EMAIL_NOTICE`, title=subject, message=bodyText 100자 미리보기, referenceType=`MANUAL_EMAIL_DISPATCH`, referenceId=dispatchSeq).
**AND** EXTERNAL 수신자는 인앱 알림 대상이 아니므로 무시 (시스템 사용자가 아님).

### AC-A12. Daily cap (D5 결정 영역)
**GIVEN** ADMIN 이 오늘 이미 100건의 manual email 을 발송했다 (D5=B 추천 채택 시)
**WHEN** 101번째 발송을 시도한다
**THEN** 429 Too Many Requests + 메시지 "Daily limit of 100 manual emails reached. Resets at 00:00 SGT."
**AND** 한도 값은 `system_settings.admin_manual_email_daily_cap` 으로 관리 (하드코딩 금지, 설계 원칙 §1).

### AC-A13. 자동 푸터 — 발송자 신원 노출
**WHEN** 발송된 이메일을 수신자가 본다
**THEN** 본문 하단에 자동 푸터:
> "This message was sent manually by a LicenseKaki administrator: {{senderEmail}}. If you have questions, reply to support@licensekaki.sg or visit https://app.licensekaki.sg/help."
**AND** 그 아래 표준 반피싱 푸터(notification-copy-templates.en.md §1.2 그대로) 부착.

---

## §7. 프론트엔드 UI

### 7.1 메뉴 위치

- ADMIN 사이드바 (`AdminSidebar` 또는 `AdminLayout` 좌측 메뉴)에 신규 항목 "Manual Email" 추가.
- 위치 권장: "Settings" 그룹 위, "Applications" 그룹 아래 — 운영 도구 분류.
- 아이콘: 봉투 아이콘 (lucide-react `Mail`).
- 라우트: `/admin/manual-emails`.
- SYSTEM_ADMIN 은 메뉴 비노출이지만 직접 URL 접근 시 가드 통과 (운영 백업 경로).

### 7.2 페이지 구성

페이지 헤더: "Manual Email Dispatch"
탭: **Compose** (기본) / **History**

#### 7.2.1 Compose 탭

```
┌──────────────────────────────────────────────────────┐
│ Recipient type                                       │
│  ( ) APPLICANT   ( ) LEW   ( ) EXTERNAL   ( ) MULTI  │
├──────────────────────────────────────────────────────┤
│ Recipients                                            │
│  [autocomplete: search by email/name/app#...]        │
│  ✓ alice@example.com (APPLICANT, app #1234) [×]      │
│  ✓ bob@example.com (LEW Grade 9) [×]                 │
├──────────────────────────────────────────────────────┤
│ Related application (optional)                       │
│  [autocomplete: search by app # or applicant name]   │
│  Selected: #1234 — alice@example.com — 30 Marina Bay │
├──────────────────────────────────────────────────────┤
│ Category tag (optional)                              │
│  [PAYMENT_NOTICE ▾]   or type custom...              │
├──────────────────────────────────────────────────────┤
│ Subject *                                            │
│  [_____________________________________] 0 / 200    │
├──────────────────────────────────────────────────────┤
│ Body * (plain text)                                  │
│  ┌─────────────────────────────────────────────┐    │
│  │                                              │    │
│  │                                              │    │
│  │                                              │    │
│  └─────────────────────────────────────────────┘    │
│                                          0 / 50,000  │
├──────────────────────────────────────────────────────┤
│ ☑ Also create in-app notification for system users   │
├──────────────────────────────────────────────────────┤
│              [ Preview ]    [ Send ]                 │
└──────────────────────────────────────────────────────┘
```

**필드 동작**:
- Recipient type 변경 시 Recipients 입력 영역 동적 변경:
  - APPLICANT/LEW: `userApi.searchUsers(role, query)` autocomplete
  - EXTERNAL: 자유 이메일 입력 (정규식 검증)
  - MULTI: APPLICANT/LEW/EXTERNAL 혼합 입력 가능
- Recipients 는 chip UI — 추가/제거 가능, 최소 1개 ~ 최대 100명 (UI 가드. 100 = D7 청크 정책 정합).
- Related application autocomplete: `adminApplicationApi.search(query)` — 기존 패턴 재사용
- Category tag 드롭다운: 추천값 `PAYMENT_NOTICE` / `MAINTENANCE` / `INFO` / `MISC` + 자유 입력. 하드코딩 금지 → `system_settings.admin_manual_email_category_suggestions` (CSV) 로드. 입력은 자유 텍스트 보존.
- Subject 카운터, Body 카운터 실시간 표시 (200/50,000 도달 시 빨강).
- "Preview" 클릭 → `POST /api/admin/manual-emails/preview` 호출 → 모달에 렌더링된 HTML 표시.
- "Send" 클릭 → 확인 모달:
  > "Send to {{N}} recipients? Once sent, emails cannot be recalled. Sender's identity ({{adminEmail}}) will be visible in the footer."
- 모달 확인 → `POST /api/admin/manual-emails` → toast (성공/부분실패/실패) → 폼 초기화.

#### 7.2.2 History 탭

표 컬럼:
| Sent at | Sender | Recipients | Subject | Status | Sent/Failed |
|---------|--------|------------|---------|--------|-------------|

- 행 클릭 → 상세 모달 (전체 본문 + 수신자 풀 리스트 + 실패 메시지 + Re-send 버튼: 본문/수신자 그대로 Compose 탭 prefill).
- 필터:
  - "My dispatches only" toggle (기본 OFF)
  - 날짜 범위 (default: last 30 days)
  - Status (multi-select)
  - Application # 검색
- 페이지네이션 20건/페이지.

### 7.3 신청건 사이드패널 통합 (옵션, PR-3 후속)

- `/admin/applications/{seq}` 상세 페이지 우측 사이드패널에 "Send manual email" 버튼 추가.
- 클릭 시 Compose 탭으로 이동 + `relatedApplicationSeq` 자동 prefill + 수신자에 신청자 자동 추가.

---

## §8. 백엔드 구현 메모

### 8.1 클래스 구조

- `ManualEmailController` (REST controller) → `ManualEmailService` → `ManualEmailDispatcher` (notifier)
- `ManualEmailDispatcher`:
  - `@Transactional` 내에서 `ManualEmailDispatch` row 저장
  - `TransactionSynchronizationManager.registerSynchronization(...)` 의 `afterCommit()` 에서 SMTP 발송 (실패 격리)
  - 다중 수신자 loop → 각 try/catch → sentCount/failedCount 누적 → row 업데이트
  - 청크 정책 (D7=B 추천): 5건씩 묶어 100ms delay (SMTP rate limit 보호)

### 8.2 EmailService 확장

```java
// EmailService 인터페이스에 추가
String sendManualNotice(String to, String senderEmail, String senderName,
                         String subject, String bodyTextPlain,
                         Long relatedApplicationSeq);
```

- 반환값: SMTP Message-ID (감사 조인용, 실패 시 null)
- LogOnlyEmailService 와 SmtpEmailService 양쪽 구현 필수
- SmtpEmailService 는 PLAIN_TEXT 본문을 `<pre>` 또는 `\n → <br>` 변환 후 표준 헤더/푸터로 감쌈
- HTML escape: 본문은 `HtmlUtils.htmlEscape` 통과 (XSS 차단)

### 8.3 멱등성 (D3 추천 시)

- 발송 직전 키 계산: `sha256(senderUserSeq + sortedRecipients + sha256(subject) + sha256(bodyText))`
- Redis 또는 DB(전용 테이블 `manual_email_idempotency_keys`)에 30초 TTL 로 저장
- 충돌 시 409 + 클라이언트가 `forceDuplicate=true` 로 우회

### 8.4 Daily cap (D5 추천 시)

- 발송 직전 `manual_email_dispatches` 에서 `sender_user_seq + DATE(created_at) = 오늘 SGT` 카운트 조회
- `system_settings.admin_manual_email_daily_cap` (default 100) 초과 시 429
- 한도는 ADMIN 별 독립

### 8.5 인앱 알림 동반 (D4=B 추천 시)

- `ManualEmailService` 에서 시스템 사용자 수신자만 추출 → `NotificationService.create(...)` 호출
- type=`MANUAL_EMAIL_NOTICE`, title=subject (200자), message=bodyText 100자 trim, referenceType=`MANUAL_EMAIL_DISPATCH`, referenceId=dispatchSeq
- deepLinkPath: `/notifications/{notificationSeq}` (수신자가 클릭 시 본문 전체 표시 모달 — 본 PR 미포함, 후속)

### 8.6 자동 푸터 빌더

- `EmailFooterBuilder.buildAdminManualNotice(senderEmail, senderName)` 헬퍼 신규
- 출력: HTML 푸터 (notification-copy-templates.en.md §1.2 표준 + ADMIN 신원 표기 라인 추가)

### 8.7 트랜잭션 경계 (감사 무결성)

- DB 저장 트랜잭션 vs SMTP 발송 분리
- SMTP 실패 시 row 의 `dispatch_status` 만 업데이트 (별도 트랜잭션) — 비즈니스 트랜잭션 롤백 금지
- 실패 시 WARN 로그 + Sentry 전송

---

## §9. 보안·PDPA 고려

### 9.1 사칭·피싱 위험 완화

- **자동 푸터로 발송자 신원 강제 노출** (AC-A13). ADMIN 이 본문에 거짓 신원을 적어도 푸터로 차단됨.
- **표준 반피싱 푸터** (notification-copy-templates.en.md §1.2 그대로): "Our only sender domain is @licensekaki.sg" / "We will never ask for password/OTP/PIN".
- **HTML 인젝션 차단**: PLAIN_TEXT 만 허용. 본문은 HtmlUtils.htmlEscape → `<br>` 변환만 적용.
- **링크 자동 화이트리스트 검사 (옵션, D6 결정)**: 본문에 https:// URL 이 포함된 경우 도메인 화이트리스트 외 도메인 → 경고 모달 또는 차단.

### 9.2 EXTERNAL 수신자 통제

- 이메일 정규식 검증 (RFC 5322 단순화 버전)
- Disposable email 차단 (D6 결정 영역) — A=무제한 / B=차단 / C=화이트리스트
- (D6=A 추천 시) 운영 신뢰 기반, 추가 차단 없음 + audit log 로 사후 추적

### 9.3 PDPA·Spam Control Act 분류

- **Transactional 분류**: 본 기능은 ADMIN 의 운영 안내 — Spam Control Act §11 (마케팅 메일) **미적용**.
- 단, ADMIN 이 명백한 마케팅 콘텐츠를 본문에 적을 수 없도록 운영 가이드 명시 + 감사로 사후 발견.
- 명시적 마케팅 발송이 필요하면 별도 마케팅 도메인 사용 (본 기능 비범위 §2.2).
- consent log 별도 기록 불요 (Transactional).

### 9.4 데이터 보관·접근 통제

- 모든 발송 영구 보관 (감사) — soft delete 금지.
- 본문에 ADMIN 이 민감 정보 입력 시 → DB 평문 저장. **DB 접근 통제 필수** (현행 RBAC + 운영팀 SOP 의존).
- 향후 enhancement: 본문 암호화(AES-256-GCM, 기존 FILE_ENCRYPTION_KEY 패턴 차용) — 본 PR 비범위.

### 9.5 PII 노출 (이메일 제목)

- Subject 는 ADMIN 자유 입력 — 신청자 이름/주소/금액 등을 적을 수 있음.
- 다른 자동 알림은 PDPA 최소화로 publicCode 만 제목에 노출하지만, 본 기능은 운영 자유도 우선.
- 운영 가이드: "제목에 PII 최소화 권장" — 시스템 강제 아닌 안내.

---

## §10. 위험 분석

| # | 위험 | 영향도 | 완화 |
|---|------|--------|------|
| R1 | ADMIN 이 잘못된 수신자에게 발송 | High | (a) Preview 모달 강제, (b) 확인 모달 ("Send to N recipients?"), (c) 발송 후 취소 불가 명시 |
| R2 | ADMIN 사칭(외부에서 본인 행세) | Medium | 자동 푸터로 senderEmail 노출 + 반피싱 푸터 + audit log 강제 |
| R3 | SMTP rate limit 초과 (다수 발송 시) | Medium | D7=B 추천: 5건씩 청크 + 100ms delay. 향후 SQS 대안 |
| R4 | 운영 비밀 노출 (본문에 민감 정보 입력) | Medium | 본문 평문 보관 → DB 접근 통제 의존. 향후 본문 암호화 enhancement |
| R5 | ADMIN 권한 탈취 시 대량 피싱 | High | (a) Daily cap (D5=B 추천), (b) audit log 즉시 감지, (c) 비정상 패턴 알람 (Phase 2) |
| R6 | EXTERNAL 발송 악용 | Medium | (D1 결정 영역) audit log 강제, 도메인 차단 정책 검토 |
| R7 | 본문 내 악성 링크 | Medium | HTML 비활성, 링크 자동 검사(옵션), 반피싱 푸터 |
| R8 | 발송 후 즉시 후회 (recall 불가) | Low | Preview + 확인 모달 + 30초 멱등성(D3=B 추천) |

---

## §11. 검토자 결정 결과 (확정 2026-05-04)

**모든 항목 추천대로 채택** — D1=A / D2=A / D3=B / D4=B / D5=B / D6=A / D7=B. PR-1부터 즉시 발주 진행.

| ID | 결정 사항 | 옵션 | 추천 | 근거 |
|----|----------|------|------|------|
| **D1** | EXTERNAL 발송 권한 범위 | A: 항상 허용(relatedApplication 없어도 OK) / B: relatedApplicationSeq 필수 / C: ADMIN 만 허용, 하위 운영 role 차단 | **A** | 운영 자유도 + audit log 로 사후 통제. 본 PR 에 권한 분화는 과도. |
| **D2** | APPLICANT/LEW 수신자 권한 매칭 가드 | A: ADMIN 은 모든 사용자 발송 가능 / B: relatedApplicationSeq 와 수신자 일치 검증 | **A** | ADMIN = 운영 슈퍼유저. 가드는 과도하며 ad-hoc 안내 유스케이스 차단 가능. |
| **D3** | 30초 내 동일 (subject+body+수신자) 멱등 처리 | A: 멱등 마킹 후 통과 / B: 409 거부 + forceDuplicate 우회 / C: 무시 (그대로 발송) | **B** | 실수 더블클릭 방지 + 의도적 재발송은 명시 플래그. |
| **D4** | 시스템 사용자 수신자에게 인앱 알림 동반 | A: 항상 / B: 옵션 체크박스(기본 ON) / C: 항상 안 함 | **B** | UX 일관성(다른 알림은 인앱+이메일 쌍) + ADMIN 의 의도적 이메일-only 케이스 허용. |
| **D5** | ADMIN 1인당 일 발송 한도 | A: 제한 없음 / B: 100건 / C: 50건 | **B** | 권한 탈취 시 대량 피싱 차단. 100건은 정상 운영에 충분. system_settings 로 조정 가능. |
| **D6** | EXTERNAL 도메인 정책 | A: 제한 없음 / B: disposable email 차단 리스트 / C: 화이트리스트만 허용 | **A** | ADMIN 신뢰 + 운영 자유도. B/C 는 과도한 마찰. |
| **D7** | 다중 발송 청크·쓰로틀링 | A: 무제한 동시 / B: 5건 청크 + 100ms delay / C: SQS/큐 도입 | **B** | SMTP rate limit 보호 + 단순. C 는 인프라 변경 필요로 본 PR 범위 외. |

---

## §12. PR 분할

| PR | 사이즈 | 기간 | 내용 |
|----|--------|------|------|
| **PR-1** | M | 2일 | 데이터 모델 (`ManualEmailDispatch` 엔티티 + 마이그레이션) + 백엔드 발송 서비스 + 단일 수신자 발송 + `GET /api/admin/manual-emails` 목록 + 자동 푸터 빌더 + 단위 테스트 |
| **PR-2** | S | 1일 | MULTI 수신자 + 부분 실패 처리 + 청크 (D7) + 멱등성 (D3) + Daily cap (D5) + EXTERNAL 검증 (D1, D6) |
| **PR-3** | M | 2일 | 프론트 Compose 탭 + History 탭 + Preview 모달 + 확인 모달 + ADMIN 사이드바 메뉴 항목 + e2e 테스트 |
| **PR-4** | S | 1일 (옵션) | 인앱 알림 동반 (D4) + categoryTag UX 개선 (system_settings 추천 드롭다운) + 신청건 사이드패널 통합 (§7.3) |

---

## §13. 마이그레이션 영향

### 13.1 신규 테이블

`DatabaseMigrationRunner` 에 idempotent CREATE TABLE 추가:

```sql
CREATE TABLE IF NOT EXISTS manual_email_dispatches (
  dispatch_seq BIGINT PRIMARY KEY AUTO_INCREMENT,
  sender_user_seq BIGINT NOT NULL,
  recipient_type VARCHAR(20) NOT NULL,
  recipient_user_seqs_json JSON NULL,
  recipient_emails_json JSON NULL,
  related_application_seq BIGINT NULL,
  subject VARCHAR(200) NOT NULL,
  body_text TEXT NOT NULL,
  body_format VARCHAR(20) NOT NULL DEFAULT 'PLAIN_TEXT',
  category_tag VARCHAR(50) NULL,
  dispatch_status VARCHAR(20) NOT NULL,
  sent_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  failed_reason TEXT NULL,
  dispatched_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  INDEX idx_manual_email_sender (sender_user_seq, created_at DESC),
  INDEX idx_manual_email_status (dispatch_status, created_at DESC),
  INDEX idx_manual_email_application (related_application_seq),
  INDEX idx_manual_email_created (created_at DESC),
  CONSTRAINT fk_manual_email_sender FOREIGN KEY (sender_user_seq) REFERENCES users(user_seq),
  CONSTRAINT fk_manual_email_application FOREIGN KEY (related_application_seq) REFERENCES applications(application_seq)
);
```

### 13.2 enum 추가

- `AuditAction` 에 `MANUAL_EMAIL_DISPATCHED` 추가 (DB ENUM → VARCHAR 정책에 따라 코드만 추가)
- `NotificationType` 에 `MANUAL_EMAIL_NOTICE` 추가 (D4=B 채택 시)

### 13.3 system_settings 신규 키

- `admin_manual_email_daily_cap` (default `100`) — D5 채택 시
- `admin_manual_email_category_suggestions` (default `PAYMENT_NOTICE,MAINTENANCE,INFO,MISC`) — Compose 탭 추천 드롭다운

### 13.4 기존 데이터 영향

- 영향 없음 (신규 테이블 + enum 추가만).

---

## §14. 관련 기존 파일

| 파일 | 변경 종류 | 내용 |
|------|----------|------|
| `blue-light-backend/src/main/java/com/bluelight/backend/api/email/EmailService.java` | 메서드 추가 | `sendManualNotice(...)` 시그니처 추가 |
| `blue-light-backend/src/main/java/com/bluelight/backend/api/email/SmtpEmailService.java` | 구현 추가 | `sendManualNotice` 구현 + 헤더/푸터 빌더 호출 |
| `blue-light-backend/src/main/java/com/bluelight/backend/api/email/LogOnlyEmailService.java` | 구현 추가 | `sendManualNotice` 로그 출력 구현 |
| `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/Notification.java` | 변경 없음 | (D4=B 채택 시) referenceType="MANUAL_EMAIL_DISPATCH" 사용 |
| `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationType.java` | enum 값 추가 | `MANUAL_EMAIL_NOTICE` |
| `AuditAction.java` (위치는 audit 패키지) | enum 값 추가 | `MANUAL_EMAIL_DISPATCHED` |
| `DatabaseMigrationRunner` (또는 동등 마이그레이션 클래스) | 마이그레이션 추가 | `manual_email_dispatches` CREATE TABLE |
| `AdminLayout` 또는 `AdminSidebar` (frontend) | 메뉴 항목 추가 | "Manual Email" 링크 (`/admin/manual-emails`) |
| `frontend/src/api/` | 신규 모듈 | `manualEmailApi.ts` (send, list, detail, preview) |
| `frontend/src/pages/admin/` | 신규 페이지 | `ManualEmailPage.tsx` + Compose/History 컴포넌트 |
| `system_settings` 시드 (data.sql 또는 마이그레이션) | row 추가 | `admin_manual_email_daily_cap`, `admin_manual_email_category_suggestions` |

---

## 부록 A. 향후 확장 (Phase 2)

- 첨부 파일 (PDF/이미지) — `LocalFileStorageService` 연동, 본문 암호화와 함께
- HTML 본문 + WYSIWYG 에디터 (XSS 방어 강화 후)
- mail merge (수신자별 변수 치환 — `{{applicantName}}` 등)
- 본문 평문 → AES-256-GCM 암호화 (FILE_ENCRYPTION_KEY 패턴 재사용)
- 발송 스케줄링 (특정 시각 예약 발송)
- 비정상 패턴 알람 (1시간 내 50건 이상 발송 등 → SYSTEM_ADMIN 알림)
- Marketing 분류 별도 도메인 + Spam Control Act §ADV + DNC 체크 + opt-in 매트릭스

## 부록 B. 참고 자료

- `doc/Project Analysis/notification-strategy.md` — Quiet Hours, Daily caps, 멱등성, 실패 격리 공통 원칙
- `doc/Project Analysis/notification-catalog.en.md` — 알림 카탈로그 + 공통 원칙 #6 #7 #8
- `doc/Project Analysis/notification-copy-templates.en.md` §1.1 §1.2 — 표준 헤더·푸터·반피싱 푸터 템플릿
- `CLAUDE.md` §설계 원칙 — 설정 우선 / JIT
- Singapore PDPA §13 (consent), §26D (data breach), Spam Control Act §11 (marketing), §ADV labeling
