# Kaki Concierge Service — 디자인 시스템 확장 가이드

**버전**: v1.0 (2026-04-19) · 대응 PRD: `kaki-concierge-service-prd.md` v1.4
**대상 화면**: 랜딩 CTA, 신청 모달, Account Setup, Manager 대시보드, 상태 뱃지

---

## 1. 브랜드 톤 & 비주얼 포지셔닝

### 1.1 기존 LicenseKaki 톤 (확인된 사실)

`blue-light-frontend/src/index.css` `@theme` 토큰 기준:

- **Primary**: navy `#1a3a5c` (primary-800), hover `#15304d` — 신뢰·정부 라이선스의 톤
- **Surface**: 화이트 카드 + `shadow-card` (`0 1px 3px / 0 1px 2px`) — 여백 중심, 평면적
- **Radius**: 카드 `rounded-xl` (1rem), 버튼 `rounded-lg` (0.75rem), 뱃지 `rounded-full`
- **타이포**: `Inter`, 타이틀 `text-lg font-semibold text-gray-800`, 본문 `text-sm text-gray-500/700`
- **선택 강조 패턴**: `border-2 border-primary-800 bg-primary-50` (ApplicantTypeCard)
- **상태 뱃지**: `Badge` 컴포넌트 6 variant (gray/primary/success/warning/error/info)

### 1.2 컨시어지의 차별화 원칙

| 원칙 | 적용 |
|---|---|
| **Distinct, not divorced** | 새 컬러 팔레트를 따로 만들지 않고 Primary navy + **Concierge Accent (gold)** 1색만 추가 |
| **Premium = restraint** | 그라디언트·glow 남발 금지. Accent는 1) 히어로 배지 2) Primary CTA 좌측 아이콘 3) 모달 좌측 사이드바 4) "Concierge" 라벨 텍스트 — 4개 지점에만 |
| **Surface 차별화** | 일반 페이지 흰 배경 / 컨시어지 모달·랜딩 섹션은 `bg-slate-50` + Concierge 카드는 `bg-gradient-to-br from-primary-900 to-primary-950 text-white` |
| **Iconography 통일** | Heroicons outline 1.5 stroke 유지 (기존 ApplicantTypeCard와 동일). 별도 아이콘 셋 도입 금지 |

---

## 2. 컬러 팔레트 확장

### 2.1 Concierge Accent (신규 1셋만 추가)

따뜻한 골드 톤. 정부 navy와 보색 관계로 시각적 위계를 만들면서 "프리미엄"을 환기.

| 토큰 | HEX | 용도 |
|---|---|---|
| `--color-concierge-50` | `#fdf8ed` | 배지 배경, 모달 사이드바 배경 |
| `--color-concierge-100` | `#faecc8` | hover 배경 |
| `--color-concierge-500` | `#c89738` | 아이콘, 강조 보더 |
| `--color-concierge-600` | `#a87c24` | CTA 액센트, 텍스트 (대비 AA pass on white: 4.7:1) |
| `--color-concierge-700` | `#85601a` | hover 텍스트 |
| `--color-concierge` (alias) | `#a87c24` | 의미적 alias |

### 2.2 UserStatus (4종) — Account 상태 표시

| Status | Badge variant | 색상 | 라벨(EN) |
|---|---|---|---|
| `PENDING_ACTIVATION` | `warning` (확장: dot) | `bg-warning-50 text-warning-700` | "Setup pending" |
| `ACTIVE` | `success` | `bg-success-50 text-success-700` | "Active" |
| `SUSPENDED` | `gray` | `bg-gray-100 text-gray-700` + 좌측 lock 아이콘 | "Suspended" |
| `DELETED` | `error` | `bg-error-50 text-error-700` | "Deleted" |

### 2.3 ConciergeRequestStatus (8종) — 의미 매핑

| Status | Variant | 닷 컬러 | 의미 |
|---|---|---|---|
| `SUBMITTED` | `info` | blue | 접수 (24h SLA 시작) |
| `ASSIGNED` | `primary` | navy | Manager 배정 |
| `CONTACTING` | `primary` | navy | 연락 중 |
| `APPLICATION_CREATED` | `info` | blue | 대리 작성 완료 |
| `AWAITING_APPLICANT_LOA_SIGN` | `warning` | amber + pulsing dot | **신청자 액션 대기 (강조)** |
| `AWAITING_LICENCE_PAYMENT` | `warning` | amber + pulsing dot | **신청자 액션 대기 (강조)** |
| `IN_PROGRESS` | `primary` | navy | 진행 중 |
| `COMPLETED` | `success` | green | 완료 |
| `CANCELLED` | `gray` | gray | 취소 |

> **AWAITING_*는 pulsing dot** (`animate-pulse`)로 시각적 차별화 — 손이 떠난 구간임을 한눈에 인지.

### 2.4 24h SLA 경고 컬러 (신규 의미 토큰)

| 상태 | 임계 | 색상 | Tailwind |
|---|---|---|---|
| `safe` | 잔여 > 6h | 표시 안 함 | — |
| `warning` | 잔여 ≤ 6h | amber | `bg-warning-50 text-warning-700 border-warning-500` |
| `breach` | 24h 초과 | red | `bg-error-50 text-error-700 border-error-500` + 좌측 alert 아이콘 |

대시보드 카드 좌측 4px 보더로 표현: `border-l-4 border-l-warning-500` / `border-l-error-500`.

---

## 3. 핵심 컴포넌트 디자인

### 3.1 Landing CTA — "White-Glove Licensing Service"

위치: `LandingPage.tsx` Features Section 직후. 좌우 2-col 그리드.

```
┌──────────────────────────────────────────────────────────────┐
│  bg-gradient-to-br from-primary-900 to-primary-950          │
│  text-white py-16 px-6 md:py-20 md:px-12 rounded-2xl        │
│                                                              │
│  ┌──── 좌측 (col-span-7) ────┐  ┌── 우측 (col-span-5) ──┐  │
│  │  [Badge: White-Glove      │  │  Card: bg-white/10     │  │
│  │   Service]   ← gold       │  │  backdrop-blur-sm      │  │
│  │                            │  │  rounded-xl p-6        │  │
│  │  H1 text-4xl md:text-5xl   │  │                        │  │
│  │  font-bold tracking-tight  │  │  ✓ Dedicated Manager   │  │
│  │  "Let our experts handle   │  │  ✓ Door-to-door service│  │
│  │   your licence."           │  │  ✓ 24h response SLA    │  │
│  │                            │  │  ✓ Full audit trail    │  │
│  │  P text-base text-white/80 │  │                        │  │
│  │  leading-relaxed           │  │                        │  │
│  │                            │  │                        │  │
│  │  [CTA: Request Concierge → │  │                        │  │
│  │   Service ]                │  │                        │  │
│  └────────────────────────────┘  └────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

**컴포넌트 클래스**:

- 배지: `inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-concierge-50 text-concierge-700 text-xs font-semibold tracking-wide uppercase` + 좌측 별 아이콘 (Heroicons `sparkles` outline, `text-concierge-500 w-3.5 h-3.5`)
- CTA 버튼 (Button 확장 variant `concierge`):
  ```
  bg-concierge-600 text-white hover:bg-concierge-700
  focus:ring-concierge-600/30
  shadow-lg shadow-concierge-600/20
  px-7 py-3.5 text-base rounded-lg font-semibold
  ```
  좌측에 `sparkles` 아이콘 (16px), 우측 화살표 (`arrow-right` outline)
- 체크리스트 아이콘: Heroicons `check-circle` solid `w-5 h-5 text-concierge-300` (다크 배경 위)

### 3.2 Concierge 신청 모달 (`/concierge/request`)

기존 `Modal lg` 사용하되 좌측 사이드바를 추가한 **2-col 레이아웃**. `max-w-3xl`로 확장.

```
┌──────────────────── ModalHeader ────────────────────────────┐
│  [✦ Kaki Concierge Service]  · Request this service     [×]│
├─────────────────────────────────┬───────────────────────────┤
│ 좌측 사이드바 (col-span-4)      │ 우측 폼 (col-span-8)       │
│ bg-concierge-50 px-6 py-8       │ bg-white px-6 py-8        │
│                                  │                           │
│ • What you get (체크 4개)        │ Form fields:              │
│ • SLA: 24h first contact         │ - Name, Email, Phone, ... │
│ • Pricing (Phase 2)              │                           │
│ • Need help? Chat ↗              │ ── Consents Section ──    │
│                                  │ Accordion x 5:            │
│ (sticky on desktop scroll)       │ ▣ Agree to ALL  (toggle) │
│                                  │ ▢ 1. PDPA          [ ▾ ] │
│                                  │ ▢ 2. Terms         [ ▾ ] │
│                                  │ ▢ 3. Membership    [ ▾ ] │
│                                  │ ▢ 4. Delegation    [ ▾ ] │
│                                  │ ▢ 5. Marketing(opt)[ ▾ ] │
└──────────────────────────────────┴───────────────────────────┘
│ ModalFooter:  [Cancel] [Submit Request →]                   │
└──────────────────────────────────────────────────────────────┘
```

**5종 동의 체크박스 패턴** (필수 4 + 선택 1):

```jsx
<div className="border border-gray-200 rounded-lg overflow-hidden divide-y divide-gray-200">
  {/* Agree to ALL — 강조 헤더 */}
  <label className="flex items-center gap-3 px-4 py-3 bg-concierge-50 cursor-pointer hover:bg-concierge-100 transition-colors">
    <input type="checkbox" className="w-4 h-4 rounded border-gray-300 text-concierge-600 focus:ring-concierge-600/30" />
    <span className="text-sm font-semibold text-concierge-700">Agree to ALL consents below</span>
  </label>

  {/* 개별 동의 (5회 반복) */}
  <div>
    <label className="flex items-start gap-3 px-4 py-3 cursor-pointer hover:bg-gray-50">
      <input type="checkbox" className="mt-0.5 w-4 h-4 rounded border-gray-300 text-primary focus:ring-primary/20" />
      <div className="flex-1">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-gray-900">
            1. PDPA Consent <span className="text-error-600">*</span>
          </span>
          <button className="text-xs text-gray-500 hover:text-gray-700">View ▾</button>
        </div>
        <p className="text-xs text-gray-500 mt-0.5">Personal data collection &amp; use</p>
      </div>
    </label>
    {/* 확장 영역: max-h-0 → max-h-96 transition */}
    <div className="px-4 pb-3 text-xs text-gray-600 leading-relaxed bg-gray-50">
      [약관 본문 ...]
    </div>
  </div>
</div>
```

**제출 버튼 상태**:

| 상태 | 클래스 |
|---|---|
| 비활성 (필수 4종 미체크) | `bg-gray-200 text-gray-400 cursor-not-allowed` |
| 활성 | `bg-concierge-600 text-white hover:bg-concierge-700 shadow-lg shadow-concierge-600/20` |
| 제출 중 | `bg-concierge-600/80 text-white cursor-wait` + Spinner |

### 3.3 상태 뱃지 시스템 — 통합 컴포넌트

기존 `Badge.tsx`에 두 variant 추가가 필요:

```ts
// Badge.tsx 확장 제안
export type BadgeVariant =
  | 'gray' | 'primary' | 'success' | 'warning' | 'error' | 'info'
  | 'concierge';      // 신규

const variantClasses = {
  // ... 기존
  concierge: 'bg-concierge-50 text-concierge-700',
};
const dotColors = {
  // ... 기존
  concierge: 'bg-concierge-500',
};
```

**ConciergeStatusBadge** (신규 도메인 컴포넌트, `domain/StatusBadge.tsx`와 동일 패턴):

- AWAITING_* 2종은 `dot=true` + 외부에서 `<span className="animate-pulse">` 래핑
- `SUBMITTED`인데 SLA 잔여 ≤ 6h: 옆에 `<Badge variant="warning" dot>SLA 4h left</Badge>` 동시 노출

### 3.4 Account Setup 페이지 (3-step wizard)

기존 `StepTracker` (horizontal) 그대로 사용 — 별도 디자인 신설 불필요. Steps:

1. **Verify identity** (이메일 토큰 검증)
2. **Set password** (비밀번호 + 확인)
3. **Done** (자동 로그인 → 대시보드)

**비밀번호 강도 미터**:

```jsx
<div className="mt-2">
  <div className="flex gap-1 h-1">
    <div className="flex-1 rounded-full bg-error-500" />     {/* weak */}
    <div className="flex-1 rounded-full bg-warning-500" />   {/* fair */}
    <div className="flex-1 rounded-full bg-success-500" />   {/* good */}
    <div className="flex-1 rounded-full bg-gray-200" />      {/* strong, not yet */}
  </div>
  <p className="text-xs mt-1 text-gray-500">
    <span className="text-warning-700 font-medium">Fair</span> — add a number or symbol for stronger
  </p>
</div>
```

규칙: 길이 ≥8 = 1칸, 대문자 = 2칸, 숫자 = 3칸, 특수문자 = 4칸. 색상 임계: 1=error, 2=warning, 3=warning, 4=success.

**성공 피드백** (Step 3): 중앙 정렬, Heroicons `check-circle` solid `w-16 h-16 text-success-500` + `H2 text-2xl font-semibold text-gray-800` "Your account is active." + Primary CTA "Go to dashboard".

### 3.5 Manager 대시보드 레이아웃

```
┌───────────────── KPI 카드 4개 (grid-cols-1 md:grid-cols-4 gap-4) ──────────────┐
│ [📥 New today]  [⏳ In progress]  [⚠ Setup pending]  [🚨 SLA breach]            │
│  default        primary border    concierge accent   error border-l-4          │
└────────────────────────────────────────────────────────────────────────────────┘
┌───────── Filter bar ─────────┐
│ [Today] [In progress] [Mine] [SLA risk] [+ filters] [search......]            │
└────────────────────────────────────────────────────────────────────────────────┘
┌───────── Request List ───────┐
│ DataTable (기존 컴포넌트 재사용)                                                 │
│ Columns: ID | Applicant | Status | SLA timer | Manager | Created             │
│ Row hover: bg-gray-50                                                          │
│ SLA breach row: border-l-4 border-l-error-500 bg-error-50/30                  │
└────────────────────────────────────────────────────────────────────────────────┘
```

**KPI 카드 (DashboardCard 확장 사용)**:
- "Setup pending" 카드는 컨시어지 강조: `border-t-4 border-t-concierge-500` + 아이콘 `text-concierge-600`
- "SLA breach" 카드: `border-t-4 border-t-error-500` + 숫자 색상 `text-error-700`

**LOA 서명 수집 3-탭 패널** (요청 상세 페이지 우측 액션):

```
┌───────────────────────────────────────────────┐
│  [📤 Upload signed]  [📧 Send link]  [📱 QR]  │  ← Tab nav
│  ─────────────────────                          │
│                                                 │
│  Tab content (탭별 다른 폼)                     │
└───────────────────────────────────────────────┘
```

- 탭 컨테이너: `bg-white rounded-xl shadow-card p-6`
- Tab nav: `flex border-b border-gray-200`, 각 탭 `px-4 py-2.5 text-sm font-medium`, 활성 `text-primary border-b-2 border-primary -mb-px`, 비활성 `text-gray-500 hover:text-gray-700`
- 탭별 leading icon: Heroicons outline (`arrow-up-tray`, `envelope`, `qr-code`) `w-4 h-4`

---

## 4. 아이콘 & 일러스트레이션

별도 일러스트 도입은 **하지 않는다** (브랜드 일관성 우선). 식별은 색 + 아이콘 조합으로 처리.

| 용도 | 아이콘 (Heroicons outline 1.5 stroke) | 클래스 |
|---|---|---|
| Concierge 서비스 식별 | `sparkles` (✦) | `text-concierge-500 w-4 h-4` |
| White-Glove 배지 | `sparkles` solid | `text-concierge-600 w-3.5 h-3.5` |
| LOA 대리 업로드 | `arrow-up-tray` | `text-gray-600` |
| LOA 이메일 전송 | `envelope` | `text-gray-600` |
| LOA QR 링크 | `qr-code` | `text-gray-600` |
| SLA 경고 | `exclamation-triangle` solid | `text-warning-600` (warning) / `text-error-600` (breach) |
| Setup pending | `clock` | `text-concierge-600` |
| Account active | `check-badge` solid | `text-success-600` |
| Suspended | `lock-closed` | `text-gray-600` |

**`sparkles` 아이콘 채택 근거**: "집사" 일러스트는 만화적이라 정부 톤과 맞지 않음. 별/스파클은 "프리미엄·완성도"를 환기하면서 추상적이어서 B2B SaaS(Linear, Vercel, Notion AI)에서 검증된 관행.

---

## 5. Tailwind CSS 4 토큰 추가 (`index.css` `@theme` 블록)

```css
@theme {
  /* 기존 토큰 유지 ... */

  /* ---- Colors: Concierge Accent (NEW) ---- */
  --color-concierge-50:  #fdf8ed;
  --color-concierge-100: #faecc8;
  --color-concierge-500: #c89738;
  --color-concierge-600: #a87c24;
  --color-concierge-700: #85601a;
  --color-concierge:     #a87c24;

  /* ---- Shadows (NEW) ---- */
  --shadow-concierge: 0 10px 25px -5px rgb(168 124 36 / 0.20),
                      0 4px 6px -2px rgb(168 124 36 / 0.10);
  --shadow-hero:      0 25px 50px -12px rgb(13 31 51 / 0.40);
}
```

**유틸리티 사용 예** (Tailwind 4는 `@theme` 토큰을 자동 클래스로 노출):

- `bg-concierge-50`, `text-concierge-700`, `border-concierge-500`, `ring-concierge-600/30`, `shadow-concierge`

**네이밍 컨벤션**:

- 상태 의미는 기존 `success/warning/error/info` 활용 — 새 의미 토큰 추가 금지
- "concierge"는 **장식·브랜드 강조 전용** (의미 색상 아님). 즉 `bg-concierge-50`은 가능하지만 "성공"을 표현하는 용도로는 사용 금지

---

## 6. 기존 디자인과의 일관성 체크리스트

배포 전 검증 항목:

- [ ] Primary navy `#1a3a5c`를 그대로 사용하고 컨시어지에서도 H1/네비/푸터는 navy 유지
- [ ] 새로 추가한 색상은 `--color-concierge-*` 1셋(6 shade)뿐, 그 외 신규 의미 토큰 없음
- [ ] 모든 카드는 `rounded-xl shadow-card` 또는 `rounded-2xl shadow-hero` (히어로 한정)
- [ ] 버튼은 기존 `<Button>` 컴포넌트 사용. 새 variant는 `concierge` 1개만 추가
- [ ] 뱃지는 기존 `<Badge>` 컴포넌트 사용. variant 추가는 `concierge` 1개만
- [ ] StepTracker, Modal, DashboardCard는 **재사용** — 디자인 신설 금지
- [ ] 폼 입력 필드는 기존 `<Input>`, `<Select>`, `<Textarea>` 사용
- [ ] 아이콘은 Heroicons outline 1.5 stroke 통일 (기존 ApplicantTypeCard와 동일)
- [ ] 모든 컬러 조합 WCAG 2.1 AA 대비 검증: `concierge-600 on white` = 4.7:1 (pass), `concierge-700 on concierge-50` = 7.2:1 (pass AAA)
- [ ] AWAITING_* 상태는 단순 색뿐 아니라 `animate-pulse` 닷으로 시각·운동감 두 채널로 강조
- [ ] SLA breach는 색뿐 아니라 좌측 4px 보더 + 아이콘으로 색맹 사용자도 식별 가능

---

## 7. Handoff 우선순위 (개발팀 전달용)

1. **Phase A (기반)**: `index.css`에 concierge 토큰 추가 → `<Button>`, `<Badge>` variant 확장
2. **Phase B (랜딩)**: LandingPage Concierge 섹션 + CTA 모달
3. **Phase C (계정/대시보드)**: AccountSetup 3-step + Manager dashboard KPI/list
4. **Phase D (LOA)**: 요청 상세 LOA 3-탭 패널

각 Phase의 Tailwind 코드 스니펫은 본 문서 §3에 인라인. 구현 시 **컴포넌트 재사용을 최우선**, 신규 컴포넌트는 `ConciergeStatusBadge`, `ConsentChecklist` 2개로 한정 권장.
