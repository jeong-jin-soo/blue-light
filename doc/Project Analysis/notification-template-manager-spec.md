# 알림 템플릿 관리 기능 (Notification Template Manager) — 통합 스펙

> **작성일**: 2026-05-25
> **상태**: Draft v1.0 — 5개 에이전트(strategist/PM/UX/developer/security) 통합 + 8건 결정 확정
> **선행 문서**:
> - 카탈로그: [`notification-catalog.md`](./notification-catalog.md) — 97종 알림 매트릭스
> - 카피북: [`notification-copy-templates.en.md`](./notification-copy-templates.en.md) — 전체 영문 카피
> - 전략: [`notification-strategy.md`](./notification-strategy.md), 요구사항: [`notification-requirements.md`](./notification-requirements.md)
> - WhatsApp 로드맵: `~/.claude/projects/-Users-ringo-Projects-blue-light/memory/whatsapp-notification-rollout.md`
> **목적**: 관리자가 코드 배포 없이 알림 카피를 편집·미리보기·발송 테스트·롤백할 수 있는 운영 도구를 만든다. 본 문서가 구현·QA·법무 검토의 단일 정본.

---

## §1. 개요 — 문제·목표

### 1.1 Problem
현재 97종 알림 카피는 `data.sql` 시드로만 존재해, 단순 오탈자·법무 문구 수정·새 locale 추가에도 백엔드 배포가 필요하다. 운영 시 카피 정정 lead time이 길고, ko/zh-Hans 추가는 개발자 종속이다.

### 1.2 정량 목표 (출시 후 1개월 측정)
1. 카피 1건 수정 lead time: **현 1~3일 → 30분 이내** (배포 없이 admin UI publish)
2. 새 locale 1개(ko) 전체 카탈로그 번역 투입 시간: **현 N/A → 5 영업일 이내** (병렬 편집 + 변수 검증 자동화)
3. 출시 후 카피 정정 사이클: **첫 30일 P0 알림당 ≤2회, P1 ≤3회** (편집 마찰을 낮춰 잦은 미세조정 허용)

### 1.3 비목표 (Out of Scope)
- WYSIWYG 드래그앤드롭 에디터 (P3 이후)
- A/B 테스트, 캠페인 빌더, 세그멘테이션 (transactional 도구 한정)
- 푸시 알림 채널, ESP 교체, 마케팅 자동화
- 동적 콘텐츠 블록(if/else 분기 로직)
- 자동 번역(Gemini 등) — 법정 문구 누락 위험으로 명시 배제

---

## §2. 결정 매트릭스 (확정)

| ID | 결정 | 영향·구현 노트 |
|---|---|---|
| **H-S1** | IN_APP=text only / EMAIL=Jsoup `Safelist.basic()` | `org.jsoup:jsoup` 의존성. EMAIL은 b/i/u/a/p/br/ul/li만 허용. 카피북의 markdown bold/링크 표현 유지 가능 |
| **H-S2** | `paynow_block` immutable system block 분리 + 4-eye 승인 | PAYMENT 카테고리 publish는 SYSTEM_ADMIN 2nd approval 필수. 변수 `{{paynowUen}}` 등은 `system_settings`에서 런타임 주입, 본문 리터럴 입력 차단(정규식) |
| **H-S3** | SECURITY 토글 잠금 + SYSTEM_ADMIN override + 알림 | A-04 등 disable 시 사유 입력 + 보안팀(Slack/email) 자동 통지. NOTIFICATION_MANAGER는 SECURITY 카테고리 disable 불가 |
| **D-1** | 2-step publish (NM 편집 → SYSTEM_ADMIN publish) | `notification_template_drafts` 신설. PAYMENT/SECURITY/MARKETING은 항상 2-step, 그 외도 2-step 통일(혼동 방지) |
| **D-3** | subject 시작 `[ADV]` 하드 검증 | category=MARKETING이면 lint가 저장 차단, opt-out 변수 `{{optOutUrl}}` 강제 |
| **D-5** | 본인 역할 수신 템플릿만 read | `recipient_roles` 컬럼 신설(다중값, comma-separated). LEW/CM/SM은 자기 역할 포함된 row만 조회 |
| **D-2** | 외주 LSP + XLIFF/CSV import (P1) | MVP는 en-SG 전용. 구조는 locale 컬럼 유지, P1에 import API 추가 |
| **D-6** | change_reason: SECURITY/PAYMENT/MARKETING만 필수 | 기타 카테고리는 옵션. lint 규칙에 category→필수 매핑 |

---

## §3. 페르소나·RBAC

### 3.1 역할 정의
- **NOTIFICATION_MANAGER (신규)** — 편집·미리보기·테스트 발송·draft 저장. 책임이 시스템 설정(API 키 등)과 다르므로 ADMIN/SYSTEM_ADMIN에 일괄 부여하지 않는다.
- **SYSTEM_ADMIN** — publish 권한 + 모든 NM 권한 + SECURITY/PAYMENT disable override
- **ADMIN, LEW, CONCIERGE_MANAGER, SLD_MANAGER** — read-only, 본인 역할 수신 템플릿만(`recipient_roles` 필터)
- **APPLICANT** — 권한 없음

### 3.2 RBAC 매트릭스

| Action | NOTIFICATION_MANAGER | SYSTEM_ADMIN | ADMIN/LEW/CM/SM |
|---|---|---|---|
| list/filter | ✅ all | ✅ all | ✅ own-role only |
| get detail | ✅ all | ✅ all | ✅ own-role only |
| create draft | ✅ | ✅ | ❌ |
| edit draft (PATCH) | ✅ | ✅ | ❌ |
| preview (render) | ✅ | ✅ | ❌ |
| test-send (self only) | ✅ | ✅ | ❌ |
| **publish** | ❌ | ✅ | ❌ |
| enable | ✅ (with reason) | ✅ | ❌ |
| disable (general) | ✅ (with reason) | ✅ | ❌ |
| **disable SECURITY/PAYMENT** | ❌ | ✅ (with reason + alert) | ❌ |
| view history | ✅ all | ✅ all | ✅ own-role |
| catalog meta | ✅ | ✅ | ✅ |

### 3.3 RoleMetadata 시드
`role_metadata` 테이블에 `NOTIFICATION_MANAGER` 추가. 한국어 라벨 "알림 매니저", 영문 "Notification Manager". 가입 흐름은 ADMIN/SYSTEM_ADMIN과 동일(승인제).

---

## §4. 스코프

### 4.1 P0 (MVP — 본 스펙 범위)
- 카탈로그 list/filter (매트릭스 도트 뷰)
- Draft 편집 (2-pane: 좌 편집/우 라이브 미리보기)
- 채널×로케일 동시 편집 (같은 code의 EMAIL/IN_APP/SMS/WhatsApp 한 화면)
- 차단형 lint 8종 (§8)
- Preview (변수 sample 입력 → 실제 렌더링)
- Publish 2-step (NM submit → SYSTEM_ADMIN approve)
- Enable/Disable (사유 입력, 카테고리별 강제 여부)
- History/Rollback (diff 표시, 임의 시점 복구)
- en-SG base 편집 + ko/zh-Hans 사본 생성 (변수 일관성 검증)

### 4.2 P1 (같은 분기)
- 테스트 발송 (자기 자신, EMAIL/IN_APP만)
- 발송 성과 인라인 뷰 (30일 sent/failed/bounce율)
- XLIFF/CSV import (외주 번역 라운드)
- Meta WhatsApp 승인 상태 폴링 잡

### 4.3 P2 (후속)
- SMS/WhatsApp 테스트 발송 (번호 검증 + 비용 가드)
- 변수 카탈로그 (`applicantName` 같은 globals 정의)
- locale 매트릭스 동시 편집 뷰

---

## §5. 데이터 모델

### 5.1 기존 테이블 변경 — `notification_templates`

기존 컬럼 유지 + 다음 5개 추가:
```sql
ALTER TABLE notification_templates
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0,                  -- @Version 낙관락
  ADD COLUMN catalog_meta_key VARCHAR(60),                       -- 예: 'A-17'
  ADD COLUMN category VARCHAR(30),                                -- SECURITY/STATUS/PAYMENT/REMINDER/VISIT/REASSURANCE/EXPIRY/MARKETING/FEEDBACK/OPS
  ADD COLUMN severity VARCHAR(20),                                -- CRITICAL/IMPORTANT/INFORMATIONAL/MARKETING
  ADD COLUMN recipient_roles VARCHAR(200);                        -- comma-separated: APPLICANT,LEW,ADMIN,...
```

### 5.2 신규 테이블 — `notification_template_drafts`
```sql
CREATE TABLE notification_template_drafts (
  draft_seq BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_seq BIGINT NOT NULL,                    -- FK to notification_templates (null이면 신규)
  template_code VARCHAR(80) NOT NULL,
  channel VARCHAR(20) NOT NULL,
  locale VARCHAR(10) NOT NULL,
  subject VARCHAR(200),
  body_text TEXT NOT NULL,
  variables_json TEXT,
  provider_template_name VARCHAR(120),
  category VARCHAR(30),
  severity VARCHAR(20),
  recipient_roles VARCHAR(200),
  submitted_by BIGINT NOT NULL,                    -- NM user_seq
  submitted_at TIMESTAMP NOT NULL,
  submission_note VARCHAR(500),                    -- "오탈자 수정", "법무 변경 요청 반영" 등
  status VARCHAR(20) NOT NULL,                     -- PENDING/APPROVED/REJECTED/WITHDRAWN
  reviewed_by BIGINT,                              -- SYSTEM_ADMIN user_seq
  reviewed_at TIMESTAMP,
  review_note VARCHAR(500),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  deleted_at TIMESTAMP,
  INDEX idx_draft_status (status, submitted_at)
);
```

### 5.3 신규 테이블 — `notification_template_history`
```sql
CREATE TABLE notification_template_history (
  history_seq BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_seq BIGINT NOT NULL,
  change_type VARCHAR(20) NOT NULL,                -- CREATE/PUBLISH/ENABLE/DISABLE/ROLLBACK
  diff_json TEXT NOT NULL,                          -- {before:{...changed fields...}, after:{...}}
  before_snapshot_json TEXT NOT NULL,              -- 전체 row 스냅샷 (롤백용)
  after_snapshot_json TEXT NOT NULL,
  change_reason VARCHAR(500),                       -- SECURITY/PAYMENT/MARKETING은 필수
  actor_user_seq BIGINT NOT NULL,
  actor_ip VARCHAR(45),
  changed_at TIMESTAMP NOT NULL,
  INDEX idx_history_template (template_seq, changed_at DESC)
);
```

### 5.4 신규 테이블 — `notification_catalog` (메타 SSOT)
```sql
CREATE TABLE notification_catalog (
  catalog_seq BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_code VARCHAR(80) NOT NULL UNIQUE,        -- 'A-17' 같은 카탈로그 ID
  allowed_variables_json TEXT NOT NULL,             -- ["applicantName","amount",...]
  default_category VARCHAR(30) NOT NULL,
  default_severity VARCHAR(20) NOT NULL,
  default_recipient_roles VARCHAR(200) NOT NULL,
  description VARCHAR(500),                          -- "결제 요청 알림 (PENDING_PAYMENT 전이)"
  required_tokens_json TEXT,                         -- ["{{paynowUen}}","{{paynowReference}}"] 등 카테고리별 강제 토큰
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  deleted_at TIMESTAMP
);
```

### 5.5 기존 outbox 컬럼 추가 — `notification_outbox`
```sql
ALTER TABLE notification_outbox
  ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION',   -- PRODUCTION/ADMIN_TEST
  ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE,             -- 인박스 카운트 제외용
  ADD COLUMN render_warnings_json TEXT;                          -- {missingKeys:[...]}
```

### 5.6 ENUM 정의
```java
public enum TemplateDraftStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }
public enum TemplateChangeType { CREATE, PUBLISH, ENABLE, DISABLE, ROLLBACK }
public enum NotificationCategory { SECURITY, STATUS, PAYMENT, REMINDER, VISIT, REASSURANCE, EXPIRY, MARKETING, FEEDBACK, OPS }
public enum NotificationSeverity { CRITICAL, IMPORTANT, INFORMATIONAL, MARKETING }
```

---

## §6. API 설계

### 6.1 컨트롤러 구조
패키지: `api/admin/notification/template/`
- `AdminNotificationTemplateController` — CRUD/preview/test/draft/publish
- `AdminNotificationTemplateCatalogController` — 메타 카탈로그
- `AdminNotificationTemplateHistoryController` — 변경 이력

전 엔드포인트 `@PreAuthorize` (역할은 매트릭스 §3.2 참조).

### 6.2 엔드포인트 표

| Method | Path | 권한 | 비고 |
|---|---|---|---|
| GET | `/api/admin/notification-templates` | NM/SA/Read | query: code, channel, locale, enabled, category, severity, role, page, size |
| GET | `/api/admin/notification-templates/{seq}` | NM/SA/Read | 응답에 `ETag: "{version}"` 헤더 |
| POST | `/api/admin/notification-templates/drafts` | NM/SA | 새 draft 생성 (PENDING 상태) |
| PATCH | `/api/admin/notification-templates/drafts/{draftSeq}` | NM/SA (작성자) | If-Match 헤더 필수, 본인 draft만 |
| POST | `/api/admin/notification-templates/drafts/{draftSeq}/submit` | NM/SA | PENDING으로 전환, 리뷰 대기 큐 진입 |
| POST | `/api/admin/notification-templates/drafts/{draftSeq}/withdraw` | NM/SA (작성자) | 본인 회수 |
| GET | `/api/admin/notification-templates/drafts?status=PENDING` | SA | 리뷰 큐 목록 |
| POST | `/api/admin/notification-templates/drafts/{draftSeq}/approve` | SA | publish → 본 테이블 commit + history insert |
| POST | `/api/admin/notification-templates/drafts/{draftSeq}/reject` | SA | review_note 필수 |
| POST | `/api/admin/notification-templates/{seq}/enable` | NM/SA | (SECURITY는 SA만) |
| POST | `/api/admin/notification-templates/{seq}/disable` | NM/SA | 사유 필수, SECURITY/PAYMENT는 SA + Slack 알림 |
| POST | `/api/admin/notification-templates/{seq}/preview` | NM/SA | `{payload: Map}` → `{subject, body, charCount, smsSegments, missingKeys}` |
| POST | `/api/admin/notification-templates/{seq}/test-send` | NM/SA | 항상 본인(SecurityContext) 수신, EMAIL/IN_APP만 |
| POST | `/api/admin/notification-templates/{seq}/rollback` | SA | history snapshot으로 복구 → 새 publish |
| GET | `/api/admin/notification-templates/{seq}/history` | NM/SA/Read | 페이지네이션 |
| GET | `/api/admin/notification-templates/{seq}/metrics?days=30` | NM/SA | P1 — sent/delivered/failed/render_warnings |
| GET | `/api/admin/notification-templates/catalog` | NM/SA/Read | code → 변수/카테고리 메타 매트릭스 |
| GET | `/api/admin/notification-templates/coverage` | NM/SA | locale × code 커버리지% |

### 6.3 동시 편집 — 낙관락
- `notification_templates`에 `@Version Long version` 추가
- GET 응답에 `ETag: "{version}"` 헤더
- PATCH 요청에 `If-Match` 헤더 필수
- 미일치 → 412 Precondition Failed
- OptimisticLockingFailureException → 409 Conflict + "Reload required"
- 자동 3-way merge 안 함 (admin이 명시적으로 처리)

### 6.4 권한 체크 — 컨트롤러 + 서비스 이중

```java
// 컨트롤러: @PreAuthorize로 role gate
@PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
public ResponseEntity<...> patchDraft(...) { ... }

// 서비스: 본인 draft 또는 SA만 편집 가능 (작성자 체크)
service.ensureCanEditDraft(draft, currentUser);
```

---

## §7. UX — IA + 핵심 화면

### 7.1 IA / 라우트
- 좌측 메뉴 `Settings → Notifications` 그룹 하위 탭
- 라우트:
  - `/admin/notifications/templates` — List
  - `/admin/notifications/templates/:code` — Edit (채널/로케일 내부 탭)
  - `/admin/notifications/templates/drafts` — Draft 큐 (SYSTEM_ADMIN 리뷰)
  - `/admin/notifications/delivery` — 발송 로그 (template_code deep-link)

### 7.2 화면 1 — List (매트릭스 도트 뷰)

```
┌─ Notifications ▸ Templates ──────────────────────────────────────────────┐
│ [Search: code/subject/body/{{var}} ____________]                          │
│ Role:[All▾] Channel:[All▾] Locale:[All▾] Status:[☑Enabled ☐Draft         │
│       ☐Missing locale]  Priority:[P0][P1][P2]                             │
├──────────────────────────────────────────────────────────────────────────┤
│ ★ P0  A-17 · payment.requested                              4 channels   │
│       Applicant · "Payment requested · #{{publicCode}}"                  │
│       IN_APP●en ●ko ●zh  EMAIL●en ○ko ○zh  SMS — WA●en(pending)        │
│       last edited 2d ago by jane@  ·  fired 1,204× / 7d                  │
└──────────────────────────────────────────────────────────────────────────┘
```

- code 단위 그룹 (평면 row가 아닌 매트릭스)
- 도트 범례: ●=enabled, ○=draft, —=N/A, ⚠=fallback/issue
- 도트 클릭 → 해당 (channel, locale)로 Edit 직접 진입
- 검색은 code/subject/body/variables 모두 hit, 매치 하이라이트

### 7.3 화면 2 — Edit (2-pane)

```
┌─ A-17 · payment.requested  ★P0 ────────────────[Preview & Test Send]──┐
│ Channels:[IN_APP●][EMAIL●][SMS○][WhatsApp(approved)]                  │
│ Locale:[en][ko][zh-Hans  + add]    Status:[Enabled ⬤]                  │
├──────────────────────────────┬───────────────────────────────────────┤
│ EMAIL · en                    │  ▼ EMAIL preview (en)                 │
│ Subject                       │  ┌─────────────────────────────────┐ │
│ [Payment requested · #{{...}}]│  │ From: noreply@licensekaki.sg    │ │
│                              │  │ Subj: Payment requested · #A-...│ │
│ Body (Jsoup-safe HTML)        │  ├─────────────────────────────────┤ │
│ [Hi {{applicantName}},        │  │ Hi Tan Ah Kow,                  │ │
│  Your application ...         │  │ Your application ...             │ │
│  {{unknownVar}} ← red wave    │  │ [locked footer: anti-phishing]   │ │
│ ]                             │  └─────────────────────────────────┘ │
│                              │  Sample values:                       │
│ Variables used (auto):        │  applicantName=[Tan Ah Kow]          │
│ • {{applicantName}} ✓ schema  │  publicCode=[A-2026-0421]            │
│ • {{publicCode}}    ✓         │  [Load from real app ▾]              │
│ • {{unknownVar}}    ✗ undef   │                                       │
│                              │  [Send test to me]                    │
└──────────────────────────────┴───────────────────────────────────────┘
[Discard]                    [Save draft] [Submit for approval]
```

- 채널·로케일 동시 편집 (탭 + 미저장 표시 ●)
- variables_json은 raw 노출 X, 자동 파싱 + 스키마 대조
- 200ms debounce 라이브 미리보기
- footer/anti-phishing은 회색 lock 영역(편집 불가)

### 7.4 화면 3 — Preview & Test Send (모달)

채널별 충실 렌더:
- **EMAIL**: HTML 미리보기(DOMPurify로 클라이언트 sanitize)
- **IN_APP**: 알림 카드 mockup
- **SMS**: char counter `142/160 · 1 segment · GSM-7 ✓`
- **WhatsApp**: chat bubble mockup + Meta 승인 상태

테스트 발송:
- 수신자: 본인 고정 (변경 불가)
- 채널: EMAIL/IN_APP만 (SMS/WhatsApp은 disabled + tooltip "P2 예정")
- daily quota: 편집자당 50통/일

### 7.5 화면 4 — Draft 리뷰 큐 (SYSTEM_ADMIN)

```
┌─ Pending Drafts (3) ─────────────────────────────────────────────────┐
│ A-17 EMAIL en   submitted 2h ago by jane@                            │
│ note: "법무팀 요청 — opt-out 링크 위치 변경"                          │
│ [Diff] [Preview] [Approve] [Reject with note]                        │
├──────────────────────────────────────────────────────────────────────┤
│ L-09 EMAIL en   submitted 1d ago by paul@                            │
│ ⚠ SECURITY category — additional confirmation required               │
│ [Diff] [Preview] [Approve (SA only)] [Reject with note]              │
└──────────────────────────────────────────────────────────────────────┘
```

- Diff는 빨강/초록 line-level
- Approve 클릭 시 confirm modal (영향받는 발송 수 표시 + 사유 입력)

---

## §8. 가드레일 — 차단형 lint 8종

저장(`POST /drafts`, `PATCH /drafts/{seq}`) 시점에 `TemplateLinter` 컴포넌트가 검증. **하나라도 fail → 저장 차단(400)**.

| # | 규칙 | 동작 |
|---|---|---|
| L1 | **변수 화이트리스트** | body+subject의 모든 `{{x}}` ⊂ `variables_json` ∪ `notification_catalog.allowed_variables`. 미정의 키 발견 → `{missingKeys:[...]}` |
| L2 | **SMS 160자** | channel=SMS이면 prefix `[LicenseKaki] ` 포함 160자 초과 차단 |
| L3 | **ADV prefix** | category=MARKETING이면 subject가 `[ADV]`로 시작해야 함 |
| L4 | **Opt-out 변수** | category=MARKETING이면 body에 `{{optOutUrl}}` 필수 |
| L5 | **PayNow 리터럴 차단** | body에 정규식 `\b\d{8,10}[A-Z]?\b` (UEN 형식) 또는 `T\d{2}\w{7}[A-Z]` 발견 시 "변수 `{{paynowUen}}`로 바꾸세요" |
| L6 | **PII subject/SMS 가드** | subject 또는 SMS body에 `{{applicantName}}`, `{{installationAddress}}`, `{{licenceNumber}}`, `{{amount}}` 포함 시 경고 배너 (PDPA — public inbox 안전) |
| L7 | **Footer 토큰** | channel=EMAIL이면 body 끝에 `{{footerBlock}}` 토큰 필수 (시스템 자동 주입) |
| L8 | **WhatsApp 위치 변수** | channel=WHATSAPP이면 body에 `{{1}}, {{2}}, ...` 위치 변수만 허용 (Meta 규칙) |

- L1~L5, L7, L8: hard block (저장 불가)
- L6: 경고만 (저장 허용, 사용자 confirm 모달)
- 모든 lint 통과 후 `TemplateRenderer` 발송 직전에 L1만 재검증 (이중 방어)

---

## §9. 발행 워크플로 (Publish 2-step)

### 9.1 정상 흐름
```
NM 편집 → [Save draft]              status=PENDING (자동저장 OK)
NM 편집 → [Submit for approval]     submission_note 입력, SA에게 알림
SA 리뷰 → [Approve]                 published_at, 본 테이블 overwrite, history insert
                                    → notification_template_drafts.status=APPROVED
SA 리뷰 → [Reject with note]        review_note 입력, NM에게 알림
                                    → status=REJECTED, NM이 재편집 후 resubmit
```

### 9.2 PAYMENT/SECURITY 추가 가드
- Approve 모달에 추가 확인 단계: "이 템플릿은 PAYMENT 카테고리입니다. 변경된 PayNow 정보는 system_settings에서 검증되었습니까?"
- SECURITY disable 시: SYSTEM_ADMIN만, 사유 50자 이상, 자동 알림(Slack `#security-ops` + email security@licensekaki.sg)

### 9.3 Rollback
- History의 임의 시점 클릭 → "Rollback to v{N}" 버튼
- 새 draft 생성 (status=PENDING) → 일반 publish 흐름 따라감 (롤백도 SA 승인 필요)

### 9.4 알림 (Publish 워크플로 자체에 대한 알림)
- NM이 submit → SA에게 인앱 알림 (`PENDING_TEMPLATE_REVIEW`)
- SA가 approve/reject → NM에게 인앱 알림 (`TEMPLATE_REVIEW_RESULT`)
- 이 알림 자체도 `notification_template`로 관리 (자기참조 — 시드만 박힘)

---

## §10. 다국어 워크플로

### 10.1 MVP (P0)
- en-SG가 base. 모든 template_code는 en row 필수
- ko/zh-Hans 사본 생성: en row를 복제 → 본문 비움 → "Translation pending" 상태
- locale 사본의 변수 집합이 en과 다르면 저장 차단 (L1 강화)

### 10.2 P1 — 외주 LSP import
- Export: `GET /api/admin/notification-templates/export?locale=en` → XLIFF/CSV 파일
- Import: `POST /api/admin/notification-templates/import?locale=ko` (multipart)
  - 각 row가 기존 (code, channel) 매칭 → 새 draft 생성 (status=PENDING)
  - SA가 일괄 approve 가능

### 10.3 Fallback 동작 (기존 유지)
`NotificationTemplateRegistry.findActive`:
1. 요청 locale 활성 row 조회
2. 없으면 en 폴백 (WARN 로그)
3. 그것도 없으면 발송 SKIP

UI에서 "ko EMAIL is rendering en fallback" 배지로 가시화.

---

## §11. 비기능 요구

### 11.1 캐싱
- **MVP는 캐시 미적용** (편집 즉시 반영이 핵심 UX)
- 도입 조건: 발송 QPS > 50 측정 시
- 전략: Caffeine `(code,channel,locale)→TemplateSnapshot` (불변 DTO), TTL 5분, **모든 mutating API 응답 직전 명시 evict (after-commit)**
- `@CacheEvict` AOP 금지 — 명시 호출만

### 11.2 트랜잭션
- PATCH/POST: `@Transactional REQUIRED`. 트랜잭션 안에서 (a) Template/Draft update (b) History insert (c) lint 검증 — 같은 commit
- test-send: 템플릿 조회는 readOnly, `NotificationOrchestrator.dispatch`는 자체 트랜잭션(REQUIRES_NEW)으로 outbox 적재. 외부 발송은 afterCommit 디스패치 패턴 (기존 그대로)

### 11.3 감사
- 모든 publish/enable/disable/rollback → `notification_template_history` 행
- `audit_log` 전역 테이블에도 별도 행 (actor_ip, user_agent 포함)
- 보유 기간 PDPA §24 합리적 보안: **7년** (TODO: legal 확정 필요)

### 11.4 성능 SLA
- list 페이지 200건 응답 ≤1초 P95
- preview 렌더 ≤300ms
- publish 트랜잭션 ≤500ms

### 11.5 에러 처리
- TemplateNotFoundException → 발송 SKIP (기존 정책 유지)
- LinterException → 400 + `{rule, details}`
- OptimisticLockingFailure → 409 + "Reload required"
- Unauthorized → 403 + role 요구 메시지

---

## §12. 마이그레이션

### 12.1 Flyway 스크립트 순서
1. `V20260530_001__notification_catalog_seed.sql` — `notification_catalog` 테이블 + 97개 카탈로그 INSERT (markdown→sql 스크립트로 생성)
2. `V20260530_002__notification_template_columns.sql` — `notification_templates`에 version/catalog_meta_key/category/severity/recipient_roles 추가
3. `V20260530_003__notification_template_seed_migration.sql` — 기존 `data.sql` 시드를 Flyway로 이관, 운영 row 보존 (`WHERE NOT EXISTS` 가드)
4. `V20260530_004__notification_template_drafts.sql` — 신규 테이블
5. `V20260530_005__notification_template_history.sql` — 신규 테이블
6. `V20260530_006__notification_outbox_test_columns.sql` — source/is_test/render_warnings_json 추가

### 12.2 카피북 import 스크립트
`scripts/import_notification_copy.py` 신설:
- 입력: `doc/Project Analysis/notification-copy-templates.en.md`
- 출력: `V20260530_001__notification_catalog_seed.sql` + `V20260530_003__notification_template_seed_migration.sql`
- 카드 파싱 → 코드 메타 + en 본문 추출 → INSERT 문 생성
- CI에서 markdown↔DB 일치 검증 (drift 감지)

### 12.3 data.sql 정리
- 기존 알림 템플릿 시드 → 삭제 (Flyway가 정본)
- 다른 시드는 그대로 유지

---

## §13. PR 분할 계획

| # | PR | 산출물 | 의존 |
|---|---|---|---|
| **PR-T1** | DB 스키마 + 엔티티 | Flyway 6개, `notification_template_drafts`/`_history`/`_catalog` 엔티티/Repository, `@Version` 추가 | — |
| **PR-T2** | 서비스 + 검증기 | `TemplateVariableValidator`, `TemplateLinter` (8 규칙), `NotificationTemplateAdminService`, `DraftReviewService`. 단위 테스트 only | PR-T1 |
| **PR-T3** | Admin Controller (read/CRUD/draft) | list/get/draft CRUD + ETag/If-Match. WebMvc 테스트. publish/approve/reject 포함 | PR-T2 |
| **PR-T4** | Preview + Test-send | preview 엔드포인트, test-send + outbox source/is_test, `NotificationService.unreadCount` 보정 | PR-T3 |
| **PR-T5** | Catalog + History API + Migration script | catalog 엔드포인트, history 페이지네이션, `import_notification_copy.py` 스크립트, CI drift 검증 | PR-T2 |
| **PR-T6** | Frontend | `notificationTemplateStore` (Zustand), List/Edit/Preview/Draft 큐 4개 화면, DOMPurify, jsoup wrapper | PR-T3, PR-T4 |
| **PR-T7** (P1) | 테스트 발송 SMS/WhatsApp + Metrics + XLIFF import | — | PR-T6 |

각 PR은 한국어 커밋 메시지로 stacked.

---

## §14. 위험 · 완화책

| 위험 | 영향 | 완화책 |
|---|---|---|
| 캐시 도입 후 invalidation 누수 | "고친 줄 알았는데 옛 본문" 사고 | MVP 캐시 OFF. 도입 시 명시 evict만 (after-commit), `@CacheEvict` AOP 금지 |
| 잘못된 변수 매핑으로 발송 실패 | 비즈니스 트랜잭션 영향 | TemplateRenderer lenient + afterCommit 디스패치 (기존 패턴 유지). `render_warnings_json`에 missing keys 기록 → admin 가시화 |
| 다국어 미완성 row가 폴백 동작 영향 | 사용자가 의도와 다른 언어 수신 | `findActive`가 enabled=false 폴백 후보 제외 (기존). UI에서 `⚠ ko-fallback` 배지로 명시 |
| WhatsApp providerTemplateName Meta 승인 sync 실패 | Meta가 거절한 템플릿으로 발송 시도 | template detail 응답에 `providerSyncStatus` 필드 (UNKNOWN/APPROVED/REJECTED/PENDING). MVP는 수동 입력, P1에 Meta Graph API 폴링 |
| admin 1명 탈취 → XSS/phishing | 전 사용자 세션 위험 | H-S1 sanitize + H-S2 paynow 분리 + 2-step approval + audit 7년 |
| Draft 큐 적체 (SA 리뷰 지연) | lead time 30분 목표 미달 | SA에게 PENDING > 4h 시 인앱 + 이메일 알림, dashboard에 적체 KPI 표시 |
| Lint 규칙 자체의 false positive | 정당한 카피가 차단 | L6는 경고형, L1~L5/L7/L8만 hard block. 모든 lint 규칙에 admin override 권한은 부여하지 않음 (NM/SA 누구도 우회 불가, 코드 수정만이 변경 경로) |

---

## §15. Definition of Done

1. `NOTIFICATION_MANAGER` 역할 + `role_metadata` 시드 적용
2. Flyway 6개 + 카탈로그 import 스크립트 + CI drift 검증 통과
3. `/api/admin/notification-templates/**` 전체 엔드포인트 통합 테스트 통과 (권한 매트릭스 §3.2 전 항목)
4. Lint 8종 단위 테스트 각 3+ 케이스 (positive/negative/edge)
5. Frontend 4개 화면 (List/Edit/Preview/Draft 큐) Tailwind + Zustand
6. 카탈로그 97종 중 P0 알림 전체 publish → 실수신 e2e 1회 (EMAIL+IN_APP)
7. SECURITY 카테고리 disable 시 Slack/email 자동 통지 동작 확인
8. 운영 매뉴얼: `doc/manual/admin/notification-templates/` (편집 절차 + rollback + 다국어 워크플로 스크린샷)
9. 한국어 커밋 메시지로 PR 분할 (T1~T6 stacked)
10. 개발서버 배포 후 NM 계정으로 카피 1건 수정 → SA approve → 실발송까지 30분 이내 측정

---

## §16. 참조 문서·코드

### 16.1 문서
- 카탈로그: [`notification-catalog.md`](./notification-catalog.md)
- 카피북: [`notification-copy-templates.en.md`](./notification-copy-templates.en.md)
- 전략: [`notification-strategy.md`](./notification-strategy.md)
- 요구사항: [`notification-requirements.md`](./notification-requirements.md)
- 운영 원칙: `CLAUDE.md §설계 원칙` (설정 우선 + JIT)

### 16.2 코드 (기존)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationTemplate.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationTemplateRepository.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/template/NotificationTemplateRegistry.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/template/TemplateRenderer.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/orchestrator/NotificationOrchestrator.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/channel/EmailChannelAdapter.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/channel/InAppChannelAdapter.java`

### 16.3 신설 예정 코드
- `api/admin/notification/template/AdminNotificationTemplateController.java`
- `api/admin/notification/template/AdminNotificationTemplateCatalogController.java`
- `api/admin/notification/template/AdminNotificationTemplateHistoryController.java`
- `api/admin/notification/template/NotificationTemplateAdminService.java`
- `api/admin/notification/template/DraftReviewService.java`
- `api/notification/template/TemplateVariableValidator.java`
- `api/notification/template/TemplateLinter.java`
- `domain/notification/NotificationTemplateDraft.java`
- `domain/notification/NotificationTemplateHistory.java`
- `domain/notification/NotificationCatalog.java`
- `scripts/import_notification_copy.py`

### 16.4 외부 의존성 신규
- Backend: `org.jsoup:jsoup:1.17.x` (HTML sanitize)
- Frontend: `dompurify` (preview 안전 렌더)

---

## §17. 후속 결정 (Phase 2+)

- `is_test=true` 인앱 알림을 admin 본인 인박스에 노출할지 vs 완전 숨길지 (현재: 숨김 — `unread_count`에서 제외)
- Meta WhatsApp 승인 상태 폴링 잡 도입 시점 (PR-T7 또는 별도)
- "긴급 disable" vs "예약 disable" 모드 분리 (현재: 즉시 disable만)
- 변수 카탈로그 globals (`applicantName` 같은 공통 변수 정의) — P2
- 진정한 4-eyes를 위한 reviewer-author 분리 강제 (현재: SA가 본인 draft 승인 가능)
- 감사 보유 기간 7년의 legal 확정
