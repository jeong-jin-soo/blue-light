# Kaki Concierge Service — Phase 1 UX Flow Spec

**버전**: 1.0 (PRD v1.4 기준)
**작성일**: 2026-04-19
**스코프**: Phase 1 MVP — 통합 가입(C1) + 옵션 B 활성화 + LOA 경로 ①·② + Manager MVP 대시보드
**참조**: `kaki-concierge-service-prd.md` (v1.4), 기존 컴포넌트 시스템 (`blue-light-frontend/src/components/ui/*`)

> **디자인 원칙 (5가지)**
> 1. **두려움 제거** — 자동 계정 생성은 사용자에게 낯설다. "왜 만들어졌는지/지금 어떻게 되어 있는지/다음 무엇을 해야 하는지"를 항상 한 화면 안에서 보이게 한다.
> 2. **동의 = 1급 시민** — 5종 동의를 작은 글씨가 아닌 **명시적 폼 영역**으로 다룬다. 감사로그 가치만큼의 시각적 무게를 부여한다.
> 3. **이메일 enumeration 절대 금지** — 사용자에게도, 공격자에게도 "이 이메일이 존재한다/존재하지 않는다"를 어떤 채널로도 노출하지 않는다 (PRD §4.4).
> 4. **24h SLA를 시각화** — Manager 대시보드의 모든 행은 SLA 잔여 시간이 색·뱃지로 보이도록.
> 5. **기존 컴포넌트 우선 재사용** — `Modal`, `Input`, `Button`, `Card`, `InfoBox`, `Toast`는 신규 디자인 만들지 않고 그대로 사용. 신규는 ConsentChecklist 1개만 도입.

---

## 0. 컴포넌트 인벤토리 (재사용/신규 구분)

| 컴포넌트 | 출처 | 본 스펙에서 용도 |
|---|---|---|
| `Modal` | 기존 ui/Modal | 컨시어지 신청 모달(데스크톱), 약관 전문 보기 |
| `Input`, `Textarea`, `Button`, `Badge`, `Card`, `InfoBox`, `Toast`, `LoadingSpinner` | 기존 | 폼/배너/상태 표시 전반 |
| `StatusBadge` | 기존 domain | Concierge 요청 상태 표시 (`SUBMITTED`/`CONTACTING` 등) |
| `DashboardCard` | 기존 domain | KPI 카드 |
| **`ConsentChecklist`** | **신규 (필수)** | 5종 동의 + "전체 동의" + 약관 전문 다이얼로그 트리거 |
| **`SlaBadge`** | **신규 (선택)** | 24h SLA 잔여시간 색 코딩 (안전/주의/위반) |
| **`ActivationLinkPanel`** | **신규** | 로그인 페이지 활성화 모드 전환 패널 (옵션 B) |

신규는 3종으로 한정. 모두 Tailwind만 사용하며 새 디자인 토큰은 도입하지 않는다.

---

## 1. 랜딩페이지 CTA 통합

### 1.1 목적 / 사용자 / 진입점
- **목적**: "직접 신청"과 "대행 신청" 두 갈래를 시각적으로 명확히 분리하면서, 직접 신청 플로우를 위축시키지 않는다.
- **사용자**: Visitor (비로그인). 시간/전문성 부족으로 대행을 선호할 가능성이 있는 사용자.
- **진입점**: `https://licensekaki.sg/` 직접 방문, 검색 광고, SP Group 추천 채널.

### 1.2 배치 결정 — Hero 직하 (Hero ↔ Features 사이)

기존 LandingPage는 `A: Nav → B: Hero → C: Features → D: How It Works → ...` 순서. **Concierge 섹션은 B와 C 사이**에 배치한다.

**근거**:
- Features 직후에 두면 "기능 소개를 다 본 다음의 부가 옵션"으로 약화됨.
- Hero 직후에 두면 **"직접 할까 / 맡길까"** 의 양자택일을 가장 빠른 시점에 보여줄 수 있음 → Visitor의 self-segmentation을 빠르게 유도.
- 단, Hero CTA(`Apply for a Licence`)와 직접 경쟁하지 않도록 **시각적 위계 차등**: Hero CTA는 primary, Concierge CTA는 outline + 다른 색조(slate-50 배경 카드 안).

### 1.3 와이어프레임

```
┌───────── A. Nav ─────────┐  Sign In  |  Get Started (primary)
└──────────────────────────┘

┌───────── B. Hero (기존 유지) ───────────────────┐
│  Electrical Installation Licences, Simplified.  │
│  [Apply for a Licence] (primary lg)             │
│  [Learn More ↓]        (ghost lg)               │
└─────────────────────────────────────────────────┘

────── B'. NEW: Concierge Section (slate-50/blue-50 배경) ──────
┌─────────────────────────────────────────────────────────────┐
│  badge: "White-Glove Service"                                │
│                                                              │
│  H2: Too busy to handle the licensing yourself?             │
│      Let our team take over.                                 │
│                                                              │
│  ┌─ 좌 (copy) ─────────────┐  ┌─ 우 (visual / CTA) ──────┐  │
│  │ "LicenseKaki offers a   │  │  ✓ Dedicated Manager    │  │
│  │  White-Glove Licensing  │  │  ✓ We submit on your    │  │
│  │  Service, where our team│  │    behalf               │  │
│  │  personally manages your│  │  ✓ You only sign LOA    │  │
│  │  entire electrical      │  │  ✓ First contact ≤ 24h  │  │
│  │  licensing process —    │  │                         │  │
│  │  from submission to     │  │  [ Request Concierge    │  │
│  │  approval."             │  │    Service →  ]         │  │
│  │                         │  │                         │  │
│  │ small: Service fee      │  │  fine: Free quote · No  │  │
│  │ from S$--- · See FAQ    │  │   commitment until paid │  │
│  └─────────────────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘

┌───────── C. Features (기존 유지) ───────────────┐
└─────────────────────────────────────────────────┘
```

### 1.4 CTA 카피 결정

| 위치 | 문구 | 변형 (모바일) |
|---|---|---|
| 섹션 헤더 H2 | "Too busy to handle licensing yourself?" | (동일) |
| 본문 인용구 | PRD v1.4 원문 그대로 | 처음 1문장만, 나머지 fold |
| primary CTA | **"Request Concierge Service →"** | "Request Concierge" |
| 보조 링크 | "See pricing & FAQ" (앵커 `#concierge-faq`) | (동일) |

### 1.5 시각적/정보 구조적 분리

- **배경**: 기존 Hero(`from-slate-50 to-blue-50`)와 다른 톤의 단색 `bg-slate-50`. Hero와 한눈에 구분.
- **CTA 스타일**: Hero는 `<Button size="lg">` (filled primary), Concierge는 `<Button variant="outline" size="lg">` + emerald 액센트 텍스트. 둘이 동시에 노출되어도 **"같은 비중의 두 선택지가 아니라 1순위/대안"** 임을 무의식적으로 전달.
- **모바일**: 좌우 2-col 그리드는 1-col 스택으로 변환. CTA가 fold-line 위에 보이도록 좌측 카피의 fold 길이 조정 (3문장 → 1문장 + "more").

### 1.6 접근성
- H2/H3 위계 유지 (h1 = Hero, h2 = "Too busy...", h3 = bullet 리스트 헤더 없음 처리).
- CTA 버튼은 `aria-describedby`로 fine print 연결.
- 인용구는 `<blockquote>` 시맨틱 사용.

---

## 2. 컨시어지 신청 모달 (`/concierge/request`)

### 2.1 목적 / 사용자 / 진입점
- **목적**: 5종 동의를 마찰 없이 받으면서, 사용자가 "회원가입을 하고 있다"는 사실을 인지시킨다.
- **사용자**: Visitor. 이메일 자판 입력 + 동의 5개 클릭이 모바일에서 가장 큰 마찰점.
- **진입점**: 랜딩 CTA, 직접 URL, (Phase 2) QR/SMS 링크.

### 2.2 모달 vs 풀페이지 결정

| 디바이스 | 결정 | 이유 |
|---|---|---|
| 데스크톱 (≥1024px) | **Modal** (max-w-3xl) | Hero 컨텍스트 유지, "취소하면 랜딩으로 복귀" 직관 |
| 태블릿 (768~1023) | **Modal** (max-w-xl, 좌측 설명 카피 collapse) | |
| 모바일 (<768) | **풀페이지** (`/concierge/request` 라우트로 push) | 키보드 + 동의 체크박스 5개 + 약관 전문 보기에서 모달 컨텍스트 보존이 어려움. 컨버전 데이터상 모바일은 풀페이지가 우세. |

라우트는 항상 `/concierge/request`. 모달일 때는 `?from=landing` 쿼리로 닫기 시 `navigate(-1)` 처리.

### 2.3 와이어프레임 (데스크톱 모달)

```
┌─ Modal (max-w-3xl) ───────────────────────────────────────────────┐
│  [Logo]  Kaki Concierge Service · Step 1 of 1            [×]      │
│                                                                    │
│  ┌─ 좌 1/2 (스크롤, 서비스 설명) ──┬─ 우 1/2 (sticky, 폼) ─────┐  │
│  │                                  │                           │  │
│  │  H2 What we do                   │  H3 Request this service  │  │
│  │  "Our team personally manages    │                           │  │
│  │   your entire electrical         │  ─ Contact ─               │  │
│  │   licensing process."            │  Full Name *               │  │
│  │                                  │  [____________________]    │  │
│  │  Steps:                          │  Email *                   │  │
│  │   1. Initial consultation call   │  [____________________]    │  │
│  │   2. Document collection         │  Mobile (+65) *            │  │
│  │   3. Application drafting        │  [+65 ____________]        │  │
│  │   4. LEW coordination            │  Memo (optional)           │  │
│  │   5. Payment & issuance          │  [____________________]    │  │
│  │                                  │  ↑ 0/500                  │  │
│  │  SLA: First contact ≤ 24h       │                           │  │
│  │                                  │  ─ Consent (5) ─           │  │
│  │  Your account will be auto-      │  ▢ Agree to all            │  │
│  │  created. You'll set your        │  ─────────────             │  │
│  │  password from the email.        │  ▢ 1. PDPA Notice *        │  │
│  │                                  │     [Read full text ↗]     │  │
│  │  Not included:                   │  ▢ 2. Terms of Service *   │  │
│  │   - EMA fees                     │     v3 [Read ↗]            │  │
│  │   - LOA signing (you must do)    │  ▢ 3. Account creation *   │  │
│  │                                  │     [What this means ↗]    │  │
│  │  ▾ FAQ (collapsed)               │  ▢ 4. Delegation *         │  │
│  │     - Cancellation policy        │     [Read ↗]               │  │
│  │     - Pricing                    │  ─────────────             │  │
│  │     - LOA explained              │  ▢ 5. Marketing emails     │  │
│  │     - Refund                     │     (optional)             │  │
│  │                                  │                           │  │
│  │                                  │  ┌──────────────────────┐ │  │
│  │                                  │  │ [ Submit Request ]   │ │  │
│  │                                  │  │ disabled until 4 ✓   │ │  │
│  │                                  │  └──────────────────────┘ │  │
│  │                                  │  small: We'll email you a │  │
│  │                                  │  setup link. First call   │  │
│  │                                  │  within 24h.              │  │
│  └──────────────────────────────────┴───────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

### 2.4 ConsentChecklist 컴포넌트 명세 (신규)

```tsx
<ConsentChecklist
  items={[
    { key: 'pdpa',        required: true, label: 'PDPA Notice',
      version: 'v2',  termsHref: '/legal/pdpa' },
    { key: 'terms',       required: true, label: 'Terms of Service',
      version: 'v3',  termsHref: '/legal/terms' },
    { key: 'signup',      required: true, label: 'Account creation',
      explainerHref: '/legal/account-explainer' },
    { key: 'delegation',  required: true, label: 'Delegation to Concierge',
      termsHref: '/legal/delegation' },
    { key: 'marketing',   required: false, label: 'Marketing emails (optional)' },
  ]}
  value={consents}
  onChange={setConsents}
/>
```

**동작 규칙**:
1. 최상단 "Agree to all" 체크박스는 **편의 기능**이고, 모든 5개를 함께 toggle. 사용자가 개별 항목을 해제하면 "Agree to all"도 자동 해제 (indeterminate 상태 가능 — `aria-checked="mixed"`).
2. 약관 텍스트의 `[Read full text ↗]` 링크는 **새 창**이 아니라 **드로어**(우측 슬라이드, max-w-md)로 표시. 사용자가 모달을 떠나지 않게 한다. 드로어 닫기 시 체크 상태 유지.
3. 필수 4개 모두 체크되면 Submit 버튼 활성화. 미체크 시 버튼은 `disabled` + 호버 툴팁: `"Please agree to the 4 required items above"`. **체크 안 한 항목 옆에 빨간 점**을 동적으로 표시(클릭 시 해당 체크박스로 스크롤 + focus ring).
4. 체크박스 라벨 클릭 영역 ≥ 44×44px (모바일 터치 타겟).
5. 키보드: Tab으로 순회, Space로 toggle. "Agree to all"은 첫 번째 tabstop.

### 2.5 폼 필드 검증 정책

| 필드 | 검증 시점 | 규칙 | 에러 노출 |
|---|---|---|---|
| Full Name | blur, submit | 2~80자 trim 후, 빈 값 금지 | 필드 하단 빨간 글씨 (`text-error-600 text-xs`) |
| Email | blur, submit | RFC 5322 + 도메인 dot 포함 | 동일 |
| Email (Phase 2 hint) | blur (debounced 500ms) | `check-email` 호출 — **기존 회원이라도 enumeration 노출 금지** | "We'll handle this on submit" 같은 **고정 메시지**만 표시. `exists=true/false` 차이를 노출하지 않음 |
| Mobile | blur | `+65` 자동 prefix, 8자리 숫자, 숫자 외 입력 자동 strip | 동일 |
| Memo | input | `maxLength={500}` + 카운터 `0/500` | 초과 입력 자체가 차단되므로 에러 메시지 불요 |

> **§4.4 노출 방지 디자인 결정**: Phase 2의 `check-email` UI 힌트는 PRD에 있지만 **enumeration 위험이 미해결이면 출시 금지**한다. Phase 1은 서버 검증만 사용하고 프론트 힌트는 비활성. v1.4 §4.4 정책에 부합.

### 2.6 결제 영역 자리 확보 (Phase 2)

```
─ Payment (coming soon) ─
┌────────────────────────────────────────────┐
│  Service fee:   S$ 500.00                  │
│  ⓘ Phase 1: We'll send an invoice after    │
│     first contact. No upfront payment.     │
└────────────────────────────────────────────┘
```

Phase 2에서 이 박스가 PayNow/카드 위젯으로 교체될 자리. Phase 1에서는 **회색 InfoBox**로 placeholder 처리하여 가격을 미리 노출 (O-10 결정에 따라 모달 내에만).

### 2.7 상태별 UX

| 상태 | UI |
|---|---|
| Idle (입력 중) | Submit 버튼 disabled (필수 동의 미완료 시) / enabled (완료 시) |
| Submitting | 버튼 → `<LoadingSpinner size="sm" />` + "Submitting…" + 폼 전체 `pointer-events-none` |
| Success | 같은 라우트가 `/concierge/request/success`로 redirect (모달 닫지 않고 풀페이지 전환) |
| Server error 5xx | InfoBox 빨간색 상단 고정: "We couldn't submit. Please try again or contact support@licensekaki.sg." + Submit 버튼 다시 활성화 |
| Validation error 400 | 필드별 인라인 에러 + InfoBox 상단 요약 |
| Email 충돌 (C2/C3, server 응답) | **모달을 닫고** 성공 페이지로 보내되, 성공 페이지가 분기 메시지를 분기. (사용자에게는 동일 경험 — enumeration 방지 위해 폼에서는 충돌을 노출하지 않음) |
| Network 실패 | Toast: "Network error. Your draft is saved locally." + 폼 데이터를 `sessionStorage`에 임시 저장, 재진입 시 복구 |

### 2.8 접근성 체크리스트

- [ ] 모달 오픈 시 첫 번째 입력(Full Name)에 자동 focus, 닫기 시 트리거 버튼으로 focus 복원
- [ ] `<dialog>` 또는 ARIA `role="dialog"` + `aria-modal="true"` + `aria-labelledby` (제목)
- [ ] ESC 키로 닫기 (단 폼이 dirty면 confirm 다이얼로그)
- [ ] 동의 체크박스는 `<input type="checkbox">` 시맨틱 사용, label `htmlFor` 연결
- [ ] 약관 드로어 열림 시 모달과 별도의 focus trap, 닫으면 체크박스로 focus 복귀
- [ ] 색에만 의존하지 않고 미체크 항목은 `⚠` 아이콘 추가 (색각이상 대응)
- [ ] 로딩 상태에 `aria-busy="true"`
- [ ] Submit 버튼 disabled 상태에서도 `aria-disabled="true"` (truly disabled가 아니라 hint로) — 클릭 시 미체크 항목 안내 토스트

### 2.9 에러 처리 매트릭스

| 시나리오 | 표시 위치 | 메시지 (사용자에게) | 복구 액션 |
|---|---|---|---|
| 필수 동의 미체크 + Submit 클릭 | 토스트 (3s) + 첫 미체크 항목 focus | "Please agree to the 4 required items to continue." | 자동 스크롤 |
| 이메일 중복 (서버) | (위 2.7 참조) | 노출하지 않음 — 성공 페이지에서 분기 | — |
| 모바일 형식 오류 | 필드 하단 인라인 | "Singapore mobile must be 8 digits, e.g. 9123 4567" | — |
| 네트워크 끊김 | Toast (sticky) + 모달 상단 InfoBox 빨간 | "Connection lost. We've saved your form." | "Retry" 버튼 |
| 토큰/CSRF 만료 | 모달 상단 InfoBox | "Your session expired. Please refresh and try again." | "Refresh now" 버튼 |
| 서버 500 | InfoBox 상단 | "Something went wrong on our end. Please try again." | Retry |

---

## 3. 제출 완료 화면 (`/concierge/request/success`)

### 3.1 와이어프레임 (C1 — 신규 계정 생성 케이스)

```
┌─ Page (max-w-2xl, 가운데) ────────────────────────────────────────┐
│                                                                    │
│      ✓  Request received  +  Account created                       │
│                                                                    │
│      Thanks, Tan Wei Ming.                                         │
│      Your Concierge request #C-2026-0010 has been received.        │
│                                                                    │
│  ┌─ Your account ──────────────────────────────────────────────┐  │
│  │  We've created a LicenseKaki account at tan@example.sg.     │  │
│  │                                                              │  │
│  │  ⓘ Your account is currently INACTIVE.                      │  │
│  │     It will activate the first time you sign in.            │  │
│  │                                                              │  │
│  │  Two ways to activate:                                       │  │
│  │   ① Click the setup link in the email we sent you           │  │
│  │      ↳ link expires in 48 hours                              │  │
│  │   ② Or, on the Login page, enter your email and we'll send  │  │
│  │      a fresh activation link any time                        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  ┌─ Next steps ────────────────────────────────────────────────┐  │
│  │  1.  Check your inbox (also: spam/promotions)               │  │
│  │  2.  Set your password (link valid 48h)                     │  │
│  │  3.  A Concierge Manager will call you within 24h at        │  │
│  │      +65 9*** 4567                                           │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  Didn't get the email?                                             │
│  [ Resend setup email ]   [ Go to Login ]   [ Back to home ]      │
│                                                                    │
│  ── Not you? ──                                                    │
│  If you didn't request this service, click here to dispute or      │
│  email support@licensekaki.sg.                                     │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 분기 (서버가 알려준 케이스)

| 케이스 | 응답 | 화면 변형 |
|---|---|---|
| C1 (신규) | `{ caseType: 'NEW_ACCOUNT' }` | 위 와이어프레임 그대로 |
| C2 (기존 ACTIVE) | `{ caseType: 'LINKED_ACTIVE' }` | "Account" 박스 → "We linked this request to your existing LicenseKaki account at {email}. Sign in to track progress." + CTA `[ Log in ]` |
| C3 (기존 PENDING_ACTIVATION) | `{ caseType: 'LINKED_PENDING' }` | "Account" 박스 → "You already have a LicenseKaki account waiting for activation. We've sent a fresh setup link to {email}." (활성화 안내는 C1과 동일) |

> **enumeration 우려 검토**: 성공 페이지는 **이메일을 입력한 본인의 화면**이므로 `caseType` 노출은 위험이 낮음. 단, URL이 공유되었을 때 새로고침으로도 같은 분기를 보여서는 안 됨 → `caseType`은 응답으로만 받고 URL/state에는 저장하지 않음 (새로고침 시 일반 메시지로 fallback).

### 3.3 "Resend setup email" 동작

- 클릭 → `POST /api/public/concierge/resend-setup` (이메일/publicCode 모두 필요)
- Rate limit: 5분당 1회. 초과 시 버튼 회색 + 카운트다운 텍스트 ("Try again in 4m 12s")
- **응답은 항상 200 + 동일 토스트** ("If the email is registered, we've sent a fresh link.") — enumeration 방지

### 3.4 "본인 신청이 아니라면" 이의 제기

- 클릭 → `/concierge/dispute?code=C-2026-0010` 라우트로 이동 (Phase 1: 단순 폼 — 이메일/메모 → support 메일함으로 발송)
- Phase 1 단순 구현: `mailto:support@licensekaki.sg?subject=Dispute%20C-2026-0010` 링크로도 충분
- 신청 번호 = publicCode는 항상 표시. 이의 제기 시 자동 prefill.

### 3.5 접근성

- 페이지 진입 시 첫 헤딩(`<h1>`)에 focus + `aria-live="polite"`로 성공 메시지 스크린리더 announce
- 모든 CTA 버튼은 키보드 순회 가능, focus ring 명확
- 신청 번호는 `<code>` + 클릭 시 클립보드 복사 (UX 보너스)

---

## 4. Account Setup / 옵션 B 로그인 활성화 플로우

PRD §5.1c, §4.4, AC-29~32 기반. **두 진입 경로**가 있다:
- (A) 이메일 N1의 setup 링크 클릭 → `/setup-account/{token}` 직접 진입
- (B) 사용자가 `/login`에 접근 → 활성화 모드 전환 → 인증 링크 재발송 → 메일 클릭 → A와 동일 경로 합류

### 4.1 플로우 다이어그램 (옵션 B)

```
┌──────────────┐
│  사용자가     │
│  /login 접근  │
└──────┬───────┘
       │ 이메일 + 비밀번호 입력 후 Submit
       ▼
   POST /api/auth/login
       │
       ├─ status=ACTIVE → 200 + JWT → 대시보드
       ├─ INVALID_CREDENTIALS → 401 → "Invalid email or password" (status 노출 X)
       └─ PENDING_ACTIVATION → 401 + {errorCode, activationFlow:"EMAIL_LINK"}
                │
                ▼
       UI 전환: ActivationLinkPanel 표시
       "It looks like this account hasn't been activated.
        We'll email you an activation link."
                │
                │ [ Send activation link ] 클릭
                ▼
       POST /api/auth/login/request-activation
                │
                │ 항상 200 + 고정 메시지 (enumeration 방지)
                ▼
       "If your email is registered, we've sent an
        activation link. Check your inbox."
                │
                │ (사용자가 메일의 링크 클릭)
                ▼
   GET /api/public/account-setup/{token}
                │
                ├─ valid → /setup-account/{token} 페이지 → 비밀번호 설정
                └─ expired → 410 → "Link expired" 화면
                                  │
                                  ▼
                          [ Send new link ] CTA
                                  ▼
                         (다시 /login 활성화 모드)

   POST /api/public/account-setup/{token}
                │
                │ 비밀번호 + 확인
                ▼
   서버: passwordHash 저장 + emailVerified=true
         + status: PENDING_ACTIVATION → ACTIVE
         + activatedAt = now (불변)
         + JWT 자동 발급
                │
                ▼
       /dashboard로 redirect (자동 로그인 상태)
```

### 4.2 단계별 와이어프레임

#### 4.2.1 Step 1 — `/login` (활성화 모드 진입 전)

```
┌─ AuthLayout ─────────────────────────────┐
│  Sign in to your account                  │
│  ┌─────────────────────────┐              │
│  │ Email                   │              │
│  │ [______________________]│              │
│  │ Password                │              │
│  │ [______________________]│ [forgot?]    │
│  │                         │              │
│  │ [   Sign in   ]         │              │
│  │                         │              │
│  │ — or —                  │              │
│  │ Don't have an account?  │              │
│  │ [ Create one ]          │              │
│  └─────────────────────────┘              │
└──────────────────────────────────────────┘
```

#### 4.2.2 Step 2 — Login 시도 결과 분기

**케이스 1: PENDING_ACTIVATION (서버 401 + activationFlow=EMAIL_LINK)**

폼이 사라지지 않고, 폼 위쪽에 **ActivationLinkPanel** (신규)이 등장:

```
┌─ AuthLayout ─────────────────────────────────────────┐
│  Sign in to your account                              │
│  ┌─ ActivationLinkPanel (InfoBox blue) ──────────┐    │
│  │ ⓘ Your account isn't activated yet            │    │
│  │   We'll email you a link to set your password │    │
│  │   and activate your account.                  │    │
│  │                                                │    │
│  │   Email: tan@example.sg (편집 가능)           │    │
│  │   [ Send activation link ]                    │    │
│  └────────────────────────────────────────────────┘    │
│  ┌─ 기존 폼 (그대로) ─┐                              │
│  │ ...                │                                │
└────────────────────────────────────────────────────────┘
```

> **enumeration 방어**: 이 패널은 **항상 같은 메시지**를 띄운다. 즉 "잘못된 비밀번호"인 경우와 "활성화 필요"인 경우의 시각적 차이가 사용자에게 단서로 작동하지 않도록 — 단, 서버는 401에서 다른 errorCode를 보내야 클라이언트가 분기할 수 있다. 트레이드오프: **PENDING vs INVALID 분기는 노출하되, "이메일 존재함/없음"은 노출하지 않는다.** PRD §4.4가 보호 대상으로 정의한 것은 후자.
>
> 따라서: `/login` 단계에서 INVALID_CREDENTIALS와 PENDING_ACTIVATION을 다르게 처리하는 것은 허용 (이미 비밀번호를 입력한 사용자만 도달 가능). `/auth/login/request-activation` 에서만 enumeration 방지가 필요.

**케이스 2: INVALID_CREDENTIALS**

```
┌─ AuthLayout ─────────────────────────────┐
│  Sign in to your account                  │
│  ┌─ Error InfoBox red ─────────────────┐  │
│  │ Invalid email or password.          │  │
│  └─────────────────────────────────────┘  │
│  ┌─ 기존 폼 ─┐                           │
└──────────────────────────────────────────┘
```

**케이스 3: ACCOUNT_SUSPENDED / DELETED**

```
┌─ Error InfoBox amber ─────────────────────────┐
│ This account is unavailable. Please contact   │
│ support@licensekaki.sg.                       │
└───────────────────────────────────────────────┘
```

#### 4.2.3 Step 3 — Activation Link 발송 후 (고정 메시지)

```
┌─ AuthLayout ─────────────────────────────────────────┐
│  ┌─ Success InfoBox green ─────────────────────────┐ │
│  │ ✓ If your email is registered, we've sent an    │ │
│  │   activation link. Check your inbox (also spam).│ │
│  │                                                  │ │
│  │   The link expires in 48 hours.                 │ │
│  │                                                  │ │
│  │   [ Resend in 5:00 ] (disabled, countdown)      │ │
│  │   [ Back to sign in ]                           │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

> **포인트**: "we've sent" 표현은 사실과 다를 수 있지만(이메일이 존재하지 않을 때) 의도된 모호함. 동일 메시지를 모든 케이스에 사용.

#### 4.2.4 Step 4 — `/setup-account/{token}` (메일 링크 클릭 후)

```
┌─ AuthLayout (max-w-md) ──────────────────────────────┐
│  Set up your LicenseKaki account                      │
│                                                        │
│  Hi T*** W*** M***,                                   │
│  Account: tan@***.sg  (verified via this link)       │
│  ⏱ Link expires in 47h 02m                            │
│                                                        │
│  ─ Set your password ─                                │
│  New password *                                       │
│  [______________________]  [👁 show]                  │
│  Confirm password *                                   │
│  [______________________]                             │
│                                                        │
│  Password must:                                       │
│   ✓ At least 8 characters                            │
│   ✓ Upper + lower case                               │
│   ✓ At least one number                              │
│   ✓ At least one symbol (!@#$...)                    │
│  (각 항목은 입력 시 실시간 ✓ 전환)                    │
│                                                        │
│  [ Set password & sign in → ]                         │
│                                                        │
│  Trouble? Reply to the setup email or contact your   │
│  Concierge Manager.                                  │
└──────────────────────────────────────────────────────┘
```

### 4.3 에러 케이스

| 시나리오 | 화면 | 복구 액션 |
|---|---|---|
| 토큰 만료 (410) | "This link has expired." 풀페이지 메시지 + 마스킹된 이메일 | `[ Send a new link ]` → `/login`으로 이동, 이메일 prefill |
| 토큰 재사용 (이미 활성화 완료) | "This account is already active." | `[ Sign in ]` |
| 토큰 위변조 (404/400) | "This link is invalid." | `[ Go to home ]` (구체적 이유 노출 X) |
| 비밀번호 복잡도 미달 | 인라인 — 각 규칙 옆 ✗/✓ 토글, Submit 버튼 disabled | 사용자 자가수정 |
| 비밀번호 불일치 | "Passwords don't match" 인라인 | — |
| 서버 500 | 토스트 "Couldn't save. Try again." Submit 다시 활성화 | Retry |
| 동시 요청 (이중 클릭) | 첫 요청 처리 중 두 번째는 무시 (`isLoading` guard) | — |
| 네트워크 단절 | 토스트 sticky "Connection lost. Your password input is preserved locally." (단 비밀번호는 sessionStorage에 저장 금지!) | Retry, 입력은 다시 타이핑 필요 |

### 4.4 로딩 상태

| 단계 | 로딩 표현 |
|---|---|
| `/setup-account/{token}` 진입 시 토큰 검증 | 풀페이지 `<LoadingSpinner />` + "Verifying link..." |
| 비밀번호 Submit 중 | 버튼 → spinner + "Setting up your account..." (1~3초 예상) |
| 자동 로그인 + redirect 중 | "Signing you in..." 짧은 splash |

### 4.5 이메일 존재 여부 노출 방지 UX 정리

| 채널 | 노출 가능? | 처리 |
|---|---|---|
| `/auth/login/request-activation` 응답 | ❌ | 항상 200 + 동일 메시지 |
| `/auth/login/request-activation` 타이밍 | ❌ | p95 차이 < 200ms (PRD O-23, 통합 테스트) |
| 토스트/모달 메시지 | ❌ | 모든 케이스에 "If your email is registered..." 동일 문구 |
| `/auth/login` 응답 코드 | ⚠️ 부분적 허용 | INVALID_CREDENTIALS vs PENDING_ACTIVATION 구분은 비밀번호를 이미 시도한 사용자에게만 노출 — 허용 |
| 네트워크 탭 (devtools) | ❌ | 응답 본문 동일성 유지 |
| 비밀번호 reset 페이지 | ❌ | 동일 정책 (기존 ForgotPasswordPage 재검토 필요) |

### 4.6 접근성

- ActivationLinkPanel은 `role="status"` + `aria-live="polite"`로 동적 노출 시 announce
- 비밀번호 복잡도 체크리스트는 `aria-live="polite"`로 실시간 ✓ 변화 announce
- "Show password" 토글은 `aria-pressed` 사용
- 카운트다운(48h)은 `<time>` 시맨틱 + `aria-label="Link expires in 47 hours and 2 minutes"`

---

## 5. Concierge Manager 대시보드 (MVP)

### 5.1 목적 / 사용자 / 진입점
- **목적**: SLA 24h 내 첫 연락을 절대 놓치지 않게 하고, 요청 → Application 핸드오프를 빠르게.
- **사용자**: Concierge Manager. 동시 처리 요청 수 5~30건 가정. 데스크톱 사용 비중 높음 (전화 + 폼).
- **진입점**: `/concierge-manager/dashboard` (로그인 후 자동 라우팅).

### 5.2 정보 구조 (사이드바 — 기존 AdminSidebar 확장)

```
┌─ AdminSidebar 확장 ─┐
│ Dashboard           │  ← KPI + 최근 요청
│ Requests            │  ← 메인 작업 화면
│ Calendar (Phase 2)  │
│ My Profile          │
└─────────────────────┘
```

### 5.3 Dashboard 와이어프레임

```
┌────────────────────────────────────────────────────────────────────┐
│  KPI 카드 4개 (DashboardCard 재사용)                                │
│  ┌─────────┬─────────┬─────────┬─────────────┐                     │
│  │ Today   │ In prog │ My queue│ ⚠ SLA breach│  (마지막은 빨강)    │
│  │ 12      │ 47      │ 8       │ 2           │                     │
│  └─────────┴─────────┴─────────┴─────────────┘                     │
│                                                                    │
│  ┌─ Recent requests (5건, 클릭 시 상세) ────────────────────────┐  │
│  │ #C-2026-0023  · Tan WM  · SUBMITTED  · ⏱ 23h 12m  [Take it] │  │
│  │ #C-2026-0022  · Lim KH  · CONTACTING · ⏱ 4h 02m   [Open]    │  │
│  │ #C-2026-0021  · Goh PL  · ⚠ BREACHED (26h)        [Open]    │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  [ Go to all requests → ]                                          │
└────────────────────────────────────────────────────────────────────┘
```

### 5.4 Requests 목록 와이어프레임

```
┌────────────────────────────────────────────────────────────────────┐
│  ┌─ Tabs (status filter) ──────────────────────────────────────┐  │
│  │ [All] [Submitted 12] [Assigned 8] [Contacting 15]           │  │
│  │ [Awaiting LOA 5] [Awaiting payment 3] [Completed]           │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  ┌─ Filter bar ──────────────────────────────────────────────┐    │
│  │ [Assignee ▾ All] [Date ▾ Last 7d] [Search by name/email]  │    │
│  │ ▢ Show only PENDING_ACTIVATION (AC-34)                    │    │
│  │ ▢ Show only SLA breaching/breached                        │    │
│  └───────────────────────────────────────────────────────────┘    │
│                                                                    │
│  ┌─ Table ──────────────────────────────────────────────────────┐ │
│  │ #         Submitted   Applicant       Phone        Status    │ │
│  │           Email       Account         SLA          Assignee  │ │
│  │ ──────────────────────────────────────────────────────────── │ │
│  │ C-…0023   10 min ago  Tan Wei Ming    +65 9123…   SUBMITTED │ │
│  │           tan@…       🔒 Pending act. ⏱ 23h 50m  — [Take]   │ │
│  │ C-…0022   3h ago      Lim Kah Hong    +65 8888…  CONTACTING│ │
│  │           lim@…       ✓ Active        ⏱ 21h 02m  @me [Open]│ │
│  │ C-…0021   26h ago     Goh Pei Ling    +65 9000…  ⚠ BREACH  │ │
│  │           goh@…       ✓ Active        🔴 -2h 15m @me [Open]│ │
│  │ ...                                                          │ │
│  │ [pagination]                                                 │ │
│  └──────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

**행 색 코딩 (SlaBadge 신규)**:
- `⏱ >12h` 회색 (안전)
- `⏱ 6~12h` 노랑 (주의 — `bg-warning-50 text-warning-700`)
- `⏱ <6h` 주황 (임박)
- `🔴 BREACH` 빨강 (위반 — `bg-error-50 text-error-700` + 행 좌측에 빨간 보더)

PENDING_ACTIVATION 표시: `🔒 Pending activation` 회색 뱃지. AC-34 필터로 토글 가능.

### 5.5 요청 상세 와이어프레임 (Phase 1)

```
┌─ Header ─────────────────────────────────────────────────────────┐
│  ← Back   Request #C-2026-0023   [SUBMITTED ▾]   ⏱ SLA 23h 50m   │
│           Tan Wei Ming (tan@example.sg, +65 9123 4567)            │
└──────────────────────────────────────────────────────────────────┘

┌─ 좌 1/3 ─────────────────┬─ 우 2/3 ──────────────────────────────┐
│ Applicant                │  ┌─ Action bar ────────────────────┐  │
│ ─────────                │  │ [Add contact note]              │  │
│ Name:   Tan Wei Ming     │  │ [Create Application on behalf →]│  │
│ Email:  tan@example.sg   │  │ [Mark as Cancelled]             │  │
│ Mobile: +65 9123 4567 📞 │  └─────────────────────────────────┘  │
│ Memo:   "Shophouse..."   │                                       │
│                          │  ── Timeline ──                       │
│ Account                  │  • 14:02  Submitted  (system)         │
│ ─────────                │  • 14:03  Auto-assigned to @me        │
│ Status: 🔒 Pending act.  │  • (next) Add contact note            │
│ Created: 10 min ago      │                                       │
│ [Resend setup email]     │                                       │
│ Last sent: never         │                                       │
│                          │  ── LOA Signature Collection ──       │
│ Linked Application       │  (Phase 1: 직접 + 경로 A 활성화)       │
│ ─────────                │  ┌──────────────────────────────────┐ │
│ (none yet)               │  │ [① Direct request] [② Upload ✏]│ │
│                          │  │ [③ Remote link 🚧 Phase 2]      │ │
│ Payment (Phase 2)        │  ├──────────────────────────────────┤ │
│ ─────────                │  │ ① Resend LOA signing email...   │ │
│ ⓘ No upfront payment     │  │ ② Upload signature file +       │ │
│                          │  │   source + memo + confirm       │ │
│ Concierge notes          │  └──────────────────────────────────┘ │
│ ─────────                │                                       │
│ • 14:30 @me              │                                       │
│   "Called, no answer"    │                                       │
│ [+ Add note]             │                                       │
└──────────────────────────┴───────────────────────────────────────┘
```

### 5.6 상태 변경 워크플로우 버튼

상태 dropdown 클릭 시 가능한 다음 상태만 노출 (PRD §5의 상태 머신 준수):

```
SUBMITTED
  ▼ "Take ownership (assign to me)"
ASSIGNED
  ▼ "Log first contact" (자동: CONTACTING)
CONTACTING
  ▼ "Create application on behalf"
AWAITING_APPLICANT_LOA_SIGN
  ▼ (Application 완료 시 자동) → COMPLETED
  ▼ "Cancel request"  → CANCELLED (사유 입력 필수)
```

**위험한 작업** (Cancelled, Reassign)은 ConfirmDialog로 확인:
```
"Are you sure? This will cancel #C-2026-0023 and notify the applicant."
[ Cancel ]  [ Yes, cancel request ]
```

### 5.7 LOA 서명 수집 패널 (Phase 1 = 탭 ①·② 활성)

PRD §2.7 그대로. Phase 1에서는 **탭 ③(원격 링크)을 비활성** 처리하되 자리는 보여준다:

```
[① Direct request]  [② Upload signature]  [③ Remote link 🚧 Phase 2]
                                            ↑ 회색 disabled, 호버 시
                                              "Available in Phase 2"
```

> **이유**: 탭 자리를 미리 보여주면 Phase 2 출시 시 학습비용 0. 단 disabled 상태가 명확해야 함 (`opacity-50 cursor-not-allowed`).

### 5.8 PENDING_ACTIVATION 필터 (AC-34)

상단 필터 바 + KPI 카드 5번째로 추가 (선택):

```
┌─ Filter ─┐
│ ▢ Show only PENDING_ACTIVATION  (12)
└──────────┘
```

체크 시 query string `?activationStatus=PENDING_ACTIVATION` 추가, 서버는 `u.status = 'PENDING_ACTIVATION' AND cr.status NOT IN ('COMPLETED','CANCELLED')` 적용.

### 5.9 24h SLA 위반 임박/초과 시각화

| 잔여 시간 | 표시 |
|---|---|
| > 12h | 회색 `⏱ 14h 23m` |
| 6~12h | 노랑 `⏱ 8h 02m` (warning) |
| 0~6h | 주황 `⏱ 3h 18m` + 깜빡임 X (조용한 경고) |
| 위반 | 빨강 `🔴 -2h 15m` + 행 좌측 보더 + 정렬 시 최상단 강제 |

추가:
- 헤더에 "Critical" 토스트 배너 (대시보드 진입 시 위반 건 ≥1이면): `"⚠ 2 requests have breached SLA. View now"`
- SLA 위반 행을 클릭하지 않고 "Take it" 버튼으로 즉시 배정 가능

### 5.10 요청 상세 → Application 이동 버튼

`Linked Application` 박스에 Application이 생성된 경우:
```
┌─ Linked Application ─┐
│ #A-2026-2131         │
│ Status: PAID         │
│ [ Open Application → ] (새 탭)
└──────────────────────┘
```

새 탭으로 여는 이유: Manager가 두 컨텍스트(Concierge 요청 + Application)를 동시에 보면서 작업하는 패턴이 일반적.

### 5.11 접근성

- 테이블 행 키보드 순회 (Tab/Enter)
- 상태 dropdown은 `<select>` 시맨틱 (네이티브) 또는 ARIA `combobox`
- SlaBadge는 색뿐 아니라 아이콘(⏱/🔴)으로도 상태 전달
- KPI 카드는 `<a>` 또는 클릭 가능한 `<button>` (해당 필터로 jump)

---

## 6. 전체 사용자 여정 맵

### 6.1 Applicant 여정 (C1 케이스, 신규 가입)

```
[Visitor] 랜딩 도착
   │ 감정: 라이선스 절차 막막함
   │ 행동: Hero 읽음 → Concierge 섹션 발견
   │ 마찰: "정말 다 해주는 건가?" 의심
   ▼
[Visitor] CTA 클릭 → 모달 오픈
   │ 감정: 호기심 + 약간의 경계
   │ 마찰: 동의 5개 — 길어 보임
   │ 완화: "Agree to all" 편의 + 약관은 드로어로 즉시 확인
   ▼
[Visitor] 폼 제출
   │ 감정: 완료감 + "잘 보냈나?"
   │ 행동: 성공 페이지 도착
   │ 결정점: 즉시 메일 확인 vs 나중에
   ▼
[Applicant 계정 생성됨, PENDING_ACTIVATION]
   │ 이메일 N1 도착 ────────┐
   │                          │
   ├─ 경로 A: 메일 링크 클릭 ─┘
   │   → /setup-account/{token}
   │   → 비밀번호 설정 → 자동 로그인 → ACTIVE
   │
   └─ 경로 B: 며칠 뒤 /login 직접 접근
       → 활성화 패널 등장
       → 인증 링크 발송 요청
       → 메일 확인 → /setup-account → ACTIVE
   │
   ▼
[ACTIVE Applicant] 대시보드 진입
   │ Manager가 만든 Application 발견
   │ ▼
[Applicant] LOA 서명
   │ → COMPLETED
```

**Key emotion shifts**:
- 가장 큰 불안 지점: **모달 → 성공 페이지** 전환 직후 ("내 정보가 어떻게 처리되는 거지?")
- 안심 지점: **N1 이메일 수신** + Manager 전화 + 진행 상황 가시화

### 6.2 Manager 여정

```
[09:00] 출근, /concierge-manager/dashboard 진입
   │ KPI 확인: SLA breach 2건 → 빨간 카드 클릭
   ▼
[09:01] Requests 페이지, breach 행 상단 정렬
   │ 가장 위 행 [Open] 클릭
   ▼
[09:02] 요청 상세 진입
   │ 전화 번호 클릭 → 전화 발신 (mobile에서 tel: 링크)
   │ 통화 후 [Add contact note] → 상태 자동 CONTACTING
   ▼
[09:30] 통화 완료, [Create Application on behalf] 클릭
   │ 노란 배너 + 신청 폼 → 정보 입력 → 제출
   │ → 상태 AWAITING_APPLICANT_LOA_SIGN
   ▼
[09:45] 신청자에게 LOA 안내 이메일 자동 발송
   │ Manager는 다른 요청으로 이동
   ▼
[다음날] 신청자가 자체 로그인 + LOA 서명 안 함
   │ Manager는 ② Upload signature 탭 → 파일 업로드
   │ → COMPLETED
```

---

## 7. 주요 인터랙션 패턴

### 7.1 모달 vs 풀페이지

| 화면 | 데스크톱 | 모바일 | 이유 |
|---|---|---|---|
| 컨시어지 신청 | Modal | 풀페이지 | 모바일 키보드 + 동의 5개의 컨텍스트 보존 |
| 약관 전문 보기 | Drawer (우측 슬라이드) | Bottom sheet | 부모 폼의 입력 데이터 보존 |
| Cancel 확인 | ConfirmDialog (기존) | ConfirmDialog | 기존 패턴 |
| LOA 업로드 | 인라인 (탭 안) | 인라인 | 결제/서명 같은 거래성 액션은 페이지 변환보다 인라인이 확신감 |
| 비밀번호 설정 | 풀페이지 | 풀페이지 | 보안 컨텍스트 (URL이 토큰) |

### 7.2 토스트 vs 인라인 에러

| 케이스 | 패턴 |
|---|---|
| 폼 필드 검증 실패 | **인라인** (필드 하단) |
| 폼 제출 실패 (서버) | **상단 InfoBox** (스크롤 안 해도 보이게) + 토스트는 부가적으로만 |
| 백그라운드 작업 (자동저장 실패) | **토스트** (sticky까지는 X, 5s 자동 dismiss) |
| 중대 에러 (세션 만료, 권한 없음) | **풀스크린 모달** + 명시적 액션 요구 |
| 성공 피드백 (저장됨, 발송됨) | **토스트 (3s)** + 필요시 인라인 ✓ |
| SLA breach 경고 | **상단 sticky 배너** (대시보드 진입 시) |

### 7.3 로딩 인디케이터

| 시간 | 패턴 |
|---|---|
| < 300ms | 표시 안 함 (깜빡임 방지) |
| 300ms ~ 1s | 버튼 내부 spinner + 텍스트 변경 |
| 1s ~ 3s | 풀 컴포넌트 spinner + "Submitting..." |
| > 3s | progress bar + 단계 안내 ("Step 2 of 3: Sending email...") |

### 7.4 "Confirm 전 미리보기"

위험한 액션 전에는 항상 **무엇이 일어날지** 명시:
- LOA 서명 업로드 confirm: "This will embed the signature and finalize the LOA. The applicant will receive a confirmation email."
- 요청 취소 confirm: "This will cancel #C-2026-0023, send a cancellation email to {email}, and return any refund per policy."

---

## 8. 반응형 고려사항

### 8.1 브레이크포인트 (Tailwind 기본)
- `sm` 640px / `md` 768px / `lg` 1024px / `xl` 1280px

### 8.2 화면별 적응

| 화면 | < md | md ~ lg | ≥ lg |
|---|---|---|---|
| 랜딩 Concierge 섹션 | 1-col 스택, CTA fold-line 위 | 2-col 시작 | 2-col 완성형 |
| 신청 모달 | 풀페이지, 단일 컬럼 | 풀페이지 단일 | 모달 2-col (좌 설명 / 우 폼) |
| 성공 페이지 | 단일 컬럼, max-w-md | 단일, max-w-xl | 단일, max-w-2xl |
| `/login` + ActivationPanel | 단일, AuthLayout 그대로 | 동일 | 동일 |
| Manager 대시보드 | (Phase 1: 데스크톱 전용 — 안내 배너) | 2-col 시도 | 풀 2-col |
| 요청 상세 | 1-col 스택 (Applicant info → Action → Timeline → LOA) | 1-col 또는 2-col 70/30 | 1-col 좌1/3 + 우2/3 |

### 8.3 Manager 대시보드 모바일 정책

Phase 1에서는 **모바일 미지원**으로 결정. 모바일 진입 시 다음 안내:

```
┌──────────────────────────────────┐
│ ⓘ This page is best on desktop. │
│   Open on a laptop for full     │
│   functionality.                │
│   [ Continue anyway ]            │
└──────────────────────────────────┘
```

이유: SLA 위반 처리 + 전화 + 폼 작성을 모바일에서 동시에 하는 시나리오는 위험. Phase 2에서 모바일 알림만이라도 구현 검토.

### 8.4 모바일 QR 스캔 흐름 자리 확보 (Phase 2)

원격 LOA 서명(`/sign/{token}`)은 Phase 2이지만, **사용자 멘탈 모델 준비**를 위해 다음을 미리 준비:
- 요청 상세의 LOA 패널에 ③ Remote link 탭을 disabled로 노출 (앞서 5.7 참조)
- 신청 성공 페이지의 "Next steps" 3번 항목에 "(Phase 2) or sign on mobile via QR" 한 줄 추가하지 **않음** — 혼동 우려, Phase 2 출시 시 추가

---

## 9. 접근성 체크리스트 (WCAG 2.1 AA)

### 9.1 인지 (Perceivable)
- [ ] 색 대비 4.5:1 이상 (텍스트), 3:1 (UI 요소). SLA 빨강은 `text-error-700 on white = 5.9:1` 통과
- [ ] 색에만 의존 금지 — SLA 상태는 색 + 아이콘 (⏱/🔴) + 텍스트 모두 사용
- [ ] 이미지/아이콘 모두 `alt` 또는 `aria-label`
- [ ] 폼 라벨은 모두 시각적으로 노출 (placeholder만으로 라벨 대체 금지)
- [ ] 본문 16px 이상, 작은 글씨 12px 이하 금지 (fine print 13px 권장)

### 9.2 조작 (Operable)
- [ ] 모든 인터랙티브 요소 키보드 접근 가능 (Tab/Enter/Space/ESC)
- [ ] 모달 focus trap + ESC 닫기 + 트리거로 focus 복원
- [ ] 터치 타겟 최소 44×44px (체크박스 라벨 영역 포함)
- [ ] focus ring 명확 (Tailwind `focus-visible:ring-2`)
- [ ] 자동 재생/타임아웃 없음 (resend 카운트다운은 사용자 액션 후에만)

### 9.3 이해 (Understandable)
- [ ] 폼 에러 메시지는 "무엇이 잘못됐고 어떻게 고치는지" 모두 포함
- [ ] 약관 링크는 "Read full text" 명시 (단순 "Read more" 금지)
- [ ] 위험한 액션 직전 confirm + 결과 명시
- [ ] 언어 속성 `lang="en"` (Phase 2 한국어 지원 시 `lang="ko"`)
- [ ] 일관된 네비게이션 — 사이드바 + 헤더 위치 고정

### 9.4 견고성 (Robust)
- [ ] 시맨틱 HTML 우선 (`<button>`, `<input>`, `<dialog>`, `<nav>`)
- [ ] ARIA는 시맨틱이 부족할 때만 보완
- [ ] 스크린리더 테스트: VoiceOver (Mac), NVDA (Win)
- [ ] `prefers-reduced-motion` 존중 — Hero/Concierge 섹션 transition 비활성

---

## 10. 우선순위 매트릭스 (영향도 × 구현 용이성)

| 항목 | 영향도 | 구현 | 우선 |
|---|---|---|---|
| 컨시어지 신청 모달 + ConsentChecklist | 매우 높음 | 중 | P0 |
| 제출 완료 화면 (C1/C2/C3 분기) | 높음 | 낮음 | P0 |
| `/setup-account/{token}` | 매우 높음 | 낮음 | P0 |
| 옵션 B `/login` 활성화 패널 | 높음 | 중 | P0 |
| Manager 대시보드 KPI + 요청 목록 | 높음 | 중 | P0 |
| 요청 상세 + 상태 워크플로우 | 높음 | 중 | P0 |
| LOA 패널 ①·② | 높음 | 중 | P1 |
| SlaBadge 색 코딩 + breach 정렬 | 중간 | 낮음 | P1 |
| AC-34 PENDING_ACTIVATION 필터 | 중간 | 낮음 | P1 |
| 랜딩 Concierge 섹션 | 중간 (마케팅) | 낮음 | P1 |
| 약관 드로어 (vs 새 창) | 낮음 | 낮음 | P2 |
| 성공 페이지 publicCode 클립보드 복사 | 낮음 | 낮음 | P2 |
| 모바일 풀페이지 폼 | 중간 | 중 | P2 |
| 신청 폼 sessionStorage 자동저장 | 낮음 | 낮음 | P2 |

P0 = Phase 1 출시 필수 / P1 = Phase 1 출시 권장 / P2 = Phase 1.5 또는 Phase 2

---

## 11. 디자이너 핸드오프 노트

- **신규 컴포넌트 3종** (`ConsentChecklist`, `SlaBadge`, `ActivationLinkPanel`)에 대한 시각 명세 필요
- 아이콘: `🔒` `⏱` `🔴` `⚠` `✓` `ⓘ` 모두 lucide-react 또는 heroicons로 교체 권장 (이모지는 폰트 일관성 문제)
- ConsentChecklist의 indeterminate 상태 시각 디자인 필요
- SlaBadge 4단계 색 토큰 매핑: 회색/노랑/주황/빨강 — 기존 Tailwind `gray/warning/orange/error` 시리즈 활용
- 약관 드로어의 슬라이드 애니메이션 토큰 필요 (200ms ease-out 권장)

---

**End of Spec.**
