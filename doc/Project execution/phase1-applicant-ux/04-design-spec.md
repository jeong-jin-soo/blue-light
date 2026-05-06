# Phase 1 — 시각 디자인 스펙

**작성일**: 2026-04-17
**근거**: `01-spec.md`, `02-ux-design.md` §7 Designer 전달 사항
**원칙**: 기존 디자인 토큰·컴포넌트 100% 재사용. 신규 색/컴포넌트 도입 금지.

---

## 1. 재사용 컴포넌트 목록

| 컴포넌트 | 경로 | Phase 1 용도 |
|---|---|---|
| `Button` | `components/ui/Button.tsx` | Signup CTA(primary/fullWidth), Step 0 Next/Back, Profile Save/Cancel |
| `Input` | `components/ui/Input.tsx` | Signup 모든 입력, SP Account, Profile 텍스트 필드 (내장 `label/hint/error/required=*` 사용) |
| `Card` / `CardHeader` | `components/ui/Card.tsx` | Signup 컨테이너, Profile 섹션 래퍼, Step 0 섹션 래퍼 |
| `Badge` variant=`gray` | `components/ui/Badge.tsx` | "Optional" 라벨 (Profile 회사정보 헤더) |
| `EmptyState` | `components/ui/EmptyState.tsx` | (선택) Profile 회사정보 미입력 시 — 단, UX 요구는 "섹션 내부 1줄 hint"이므로 EmptyState 대신 인라인 텍스트 권장 |
| `Toast` | `components/ui/Toast.tsx` | "Company info updated" 성공 토스트 |

**신규 컴포넌트**: `InfoBox`, `ApplicantTypeCard` 2개만 추가 (섹션 3 참조). 재사용성 충분하므로 `components/ui/`에 배치.

---

## 2. 디자인 토큰 매핑 (신규 추가 없음)

| 용도 | 토큰 | 값 |
|---|---|---|
| Info box surface | `bg-info-50` | `#eff6ff` |
| Info box border | `border-info-200` 또는 `border-info-500/30` | `#dbeafe` 계열 |
| Info box 아이콘 | `text-info-600` | `#2563eb` |
| Info box 제목 | `text-info-800` (`#1e40af` 인라인 허용) | — |
| Info box 본문 | `text-info-700` | `#1d4ed8` 계열 |
| Applicant card 선택 테두리 | `border-primary` (2px) | `#1a3a5c` |
| Applicant card 선택 배경 | `bg-primary-50` | `#f0f4f8` |
| Applicant card 미선택 테두리 | `border-gray-200` (1px) | — |
| "Optional" 배지 | Badge `variant="gray"` | 기존 |
| 섹션 카드 | Card (기본) = `bg-surface rounded-xl shadow-card p-6` | 기존 |
| Helper/muted 텍스트 | `text-gray-500` / `text-xs` | 기존 |

**규칙**: `bg-blue-50` 같은 raw Tailwind blue는 기존 코드에 섞여 있으나 **Phase 1 신규 코드는 `bg-info-*` 토큰 사용**으로 통일 (예: `ApplicationDetailPage.tsx` L300과 동일 패턴).

---

## 3. 신규/변형 UI 요소

### A. InfoBox 컴포넌트

**용도**: Step 0 "No documents needed now", Signup 하단 hint(variant=muted)

**구조**:
```tsx
// components/ui/InfoBox.tsx
interface InfoBoxProps {
  title?: string;
  children: ReactNode;
  variant?: 'info' | 'muted';  // muted = 배경 없음, Signup 하단용
  icon?: ReactNode;            // 기본 아이콘은 Info (circle-i) SVG
  className?: string;
}
```

**Tailwind 조합 (variant=info)**:
```
bg-info-50 border border-info-200 rounded-lg p-4 flex items-start gap-3 max-w-prose
```
- 아이콘 래퍼: `flex-shrink-0 text-info-600 mt-0.5`
- 제목: `text-sm font-semibold text-info-800`
- 본문: `text-xs text-info-700 mt-1 leading-relaxed`
- 역할: `role="note"` (경고 아님, UX AC 기준)

**Tailwind 조합 (variant=muted, Signup 하단용)**:
```
flex items-start gap-2 text-xs text-gray-500 mt-6
```
아이콘 없이 텍스트만. CTA와 최소 `mt-6`(24px) 간격.

**아이콘**: 프로젝트는 **lucide-react 미사용 → 인라인 SVG** 사용. `Info` (circle-i) 아이콘 SVG:
```tsx
<svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
  <circle cx="12" cy="12" r="10" />
  <path strokeLinecap="round" d="M12 16v-4M12 8h.01" />
</svg>
```
(Heroicons `information-circle` 동등. 기존 `ApplicationDetailPage`의 이모지 `🔍`/`📝` 방식보다 톤 일관성 ↑ — Phase 1부터 SVG 통일 권장.)

---

### B. ApplicantTypeCard (카드형 라디오)

**구조**: 일반 `<input type="radio">`을 `sr-only`로 숨기고 `<label>` 전체를 클릭 타겟으로 사용. `<fieldset>`+`<legend>`로 감싸 스크린 리더 발화 보장.

**Tailwind — 카드 개별**:
```
relative flex flex-col items-start gap-1 p-4 rounded-lg border cursor-pointer transition-colors
focus-within:ring-2 focus-within:ring-primary/20
peer-checked:border-primary peer-checked:border-2 peer-checked:bg-primary-50
[&:has(input:not(:checked))]:border-gray-200 [&:has(input:not(:checked))]:hover:border-gray-300 [&:has(input:not(:checked))]:hover:bg-gray-50
```
- 미선택: `border-gray-200` (1px), `bg-surface`
- Hover(미선택): `border-gray-300 bg-gray-50`
- 선택: `border-primary border-2` (테두리 1px→2px 전환 시 레이아웃 시프트 방지를 위해 **미선택에도 `border-2 border-transparent` 유지 후 선택 시 `border-primary`로 교체** 권장)
- 선택 시 우상단 체크 인디케이터: `absolute top-2 right-2 w-5 h-5 rounded-full bg-primary text-white flex items-center justify-center` + checkmark SVG

**아이콘 선택** (인라인 SVG):
- Individual: 사용자 한 명 (Heroicons `user` 경로)
- Corporate: 건물 (Heroicons `building-office-2` 경로)

아이콘 크기: `w-6 h-6 text-primary` (선택 시 채도 유지, 미선택 시 `text-gray-500`)

**카드 내부 타이포**:
- 타이틀: `text-sm font-semibold text-gray-900`
- 설명: `text-xs text-gray-500 mt-0.5 leading-relaxed`

**레이아웃 (그룹)**:
```
grid grid-cols-1 sm:grid-cols-2 gap-3
```
- 모바일 1열 스택, `sm` (≥640px)부터 2열
- 두 카드 높이 균등 (grid가 자동 보정)

**모션**: `transition-colors duration-150`만. 스케일/translate 없음 (prefers-reduced-motion 대응).

---

### C. ProfilePage 회사정보 섹션 강화

**헤더 스타일** (CardHeader 재사용 + description 추가):
```tsx
<CardHeader
  title="Company Information"
  description="Auto-filled on future applications to save you time."
  action={<Badge variant="gray">Optional</Badge>}
/>
```
- 기존 `CardHeader` API 그대로 사용 — **변경 불필요**
- Badge variant=`gray` ("Optional") — 빨강/경고톤 금지

**섹션 시각 분리**:
- Personal Info 카드와 Company Info 카드 사이 `space-y-6` (24px)
- 두 카드 동일 surface (`bg-surface shadow-card`) — 회사정보를 별격 톤으로 다르게 하지 말 것 (UX §7.3)

**빈 상태 (3필드 모두 empty)**:
Card 내부 필드 영역 하단에 인라인 hint — EmptyState 컴포넌트 **사용 안 함** (과함):
```tsx
<p className="text-xs text-gray-500 mt-3 flex items-start gap-1.5">
  <LightbulbIcon className="w-4 h-4 text-warning-500 flex-shrink-0 mt-0.5" />
  Add your company details to save time on future applications.
</p>
```
아이콘: 전구 SVG (Heroicons `light-bulb`). Warning 색이지만 크기 작아 과하지 않음.

---

## 4. 화면별 레이아웃 스펙

### 화면 A — SignupPage

```
<main class="min-h-screen flex items-center justify-center bg-surface-secondary py-12 px-4">
  <Card class="w-full max-w-md" padding="lg">   // max-w-md = 448px
    <Logo />
    <h1 class="text-2xl font-semibold text-gray-900 mt-6">Create your account</h1>
    <p class="text-sm text-gray-500 mt-1">Get started in 30 seconds</p>

    <form class="mt-6 space-y-4">
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Input label="First Name" required />
        <Input label="Last Name" required />
      </div>
      <Input label="Email" type="email" required />
      <Input label="Password" type="password" required
             hint="Min 8 chars, 1 uppercase, 1 number" />
      <Input label="Confirm Password" type="password" required />

      <label class="flex items-start gap-2 pt-2 text-sm text-gray-700">
        <input type="checkbox" class="mt-0.5 accent-primary" required />
        <span>I agree to the <a class="text-primary underline">Terms</a>...</span>
      </label>

      <Button fullWidth size="lg" class="mt-2">Create account</Button>
    </form>

    <p class="text-sm text-gray-600 text-center mt-4">
      Already have an account? <a class="text-primary font-medium">Sign in</a>
    </p>

    <InfoBox variant="muted">
      You can add phone and company details later from your profile — they're optional.
    </InfoBox>
  </Card>
</main>
```

핵심:
- 컨테이너 `max-w-md` (448px), `padding="lg"` (p-8)
- 필드 간격 `space-y-4`
- Name grid: 모바일 스택 (`grid-cols-1`), ≥640px 2열
- CTA `fullWidth size="lg"`, 상단 여백 `mt-2` (체크박스 다음)
- PDPA 체크박스: `accent-primary`로 브랜드 색 적용

### 화면 B — NewApplicationPage Step 0

```
<div class="max-w-2xl mx-auto space-y-6">    // max-w-2xl = 672px
  <StepProgress current={0} total={5} />

  <Card padding="md">
    <CardHeader title="What are you applying for?" />
    <RadioGroup>... New / Renewal / Amendment</RadioGroup>
  </Card>

  <Card padding="md">
    <CardHeader title="Licence Period" />
    <RadioGroup>... 1y / 2y / 3y (inline)</RadioGroup>
  </Card>

  <Card padding="md">
    <CardHeader title="Who is applying?" description="Default: Individual" />
    <fieldset>
      <legend class="sr-only">Applicant Type</legend>
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <ApplicantTypeCard value="INDIVIDUAL" ... />
        <ApplicantTypeCard value="CORPORATE" ... />
      </div>
    </fieldset>
    <p class="text-xs text-gray-500 mt-3">
      Corporate 선택 시, 이후 단계에서 회사 정보가 필요할 수 있습니다.
    </p>
  </Card>

  <Card padding="md">
    <CardHeader title="Single-Line Diagram (SLD)" />
    <RadioGroup>... own / request</RadioGroup>
  </Card>

  <Card padding="md">
    <Input label="SP Account Number (optional)"
           hint="If you have it on hand. Otherwise your LEW will ask later." />
  </Card>

  <InfoBox title="No documents needed now">
    Your assigned Licensed Electrical Worker (LEW) will review your application
    and request any required documents — SP account, LOA, main breaker photo,
    SLD — through the platform. This keeps your first step fast.
  </InfoBox>

  <div class="flex justify-between pt-2">
    <Button variant="outline">Back</Button>
    <Button disabled={!canProceed}>Next</Button>
  </div>
</div>
```

핵심:
- 섹션(카드) 간격 `space-y-6`
- Info box 위치: **모든 섹션의 맨 아래, Next 버튼 바로 위** — UX 의도("No documents needed"는 페이지 전체를 요약하는 안심 메시지)
- 필수 섹션에는 CardHeader 제목에 `*` 포함 (Input과 동일 규칙)

### 화면 C — ProfilePage

```
<div class="max-w-3xl mx-auto space-y-6">
  <Card>
    <CardHeader title="Personal Information" />
    ... Name / Email(readOnly) / Phone(optional) ...
  </Card>

  <Card>
    <CardHeader
      title="Company Information"
      description="Auto-filled on future applications to save you time."
      action={<Badge variant="gray">Optional</Badge>}
    />
    <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
      <Input label="Company Name" placeholder="e.g. Blue Light Pte Ltd" />
      <Input label="UEN" placeholder="e.g. 202312345A" />
      <Input label="Designation" placeholder="e.g. Project Manager"
             class="sm:col-span-2" />
    </div>
    {isEmpty && <EmptyHint />}
    <div class="flex justify-end gap-2 mt-6">
      <Button variant="outline">Cancel</Button>
      <Button>Save changes</Button>
    </div>
  </Card>
</div>
```

핵심:
- 두 Card 시각 동등 (Company만 튀지 않게)
- 필드 그리드: 모바일 1열, ≥640px 2열. Designation만 `col-span-2`
- 액션 버튼 우측 정렬 (`justify-end`)

---

## 5. 반응형 요약

| 요소 | <640px | ≥640px (sm) | ≥1024px (lg) |
|---|---|---|---|
| Signup Card | `max-w-md`, padding 유지 | 동일 | 동일 |
| Signup Name 필드 | 1열 스택 | 2열 | 2열 |
| Step 0 컨테이너 | 좌우 `px-4` | `max-w-2xl` 중앙 | 동일 |
| Applicant Type 카드 | 1열 스택 | 2열 | 2열 |
| Profile 컨테이너 | `px-4` | `max-w-3xl` | 동일 |
| Profile 회사 필드 | 1열 | 2열 | 2열 |
| InfoBox | `max-w-prose` (항상) | 동일 | 동일 (와이드에서 과도한 확장 방지) |

---

## 6. 아이콘 (인라인 SVG, Heroicons outline 24 기준)

| 위치 | 아이콘 | Heroicons 이름 |
|---|---|---|
| InfoBox (info variant) | `information-circle` | `<circle r=10/> + i` |
| Individual 카드 | `user` | single user outline |
| Corporate 카드 | `building-office-2` | building outline |
| Profile Company 헤더 (선택) | `building-office-2` | 헤더 제목 앞 `w-5 h-5 text-gray-400` |
| Profile 빈 상태 | `light-bulb` | `text-warning-500 w-4 h-4` |
| Check 인디케이터 (선택 카드) | `check` | 흰색 on primary 원 |

**lucide-react 미설치**: Phase 1에서 새로 추가하지 말 것. 인라인 SVG로 충분. 향후 아이콘 수요가 늘면 별도 태스크에서 도입.

---

## 7. 개발자 복붙 스니펫

### InfoBox (info)
```tsx
<div role="note" className="bg-info-50 border border-info-200 rounded-lg p-4 flex items-start gap-3 max-w-prose">
  <svg className="w-5 h-5 flex-shrink-0 text-info-600 mt-0.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
    <circle cx="12" cy="12" r="10" />
    <path strokeLinecap="round" d="M12 16v-4M12 8h.01" />
  </svg>
  <div>
    <h3 className="text-sm font-semibold text-info-800">No documents needed now</h3>
    <p className="text-xs text-info-700 mt-1 leading-relaxed">
      Your assigned Licensed Electrical Worker (LEW) will review your application...
    </p>
  </div>
</div>
```

### ApplicantTypeCard (단일)
```tsx
<label className="relative flex flex-col gap-2 p-4 rounded-lg border-2 cursor-pointer transition-colors
  focus-within:ring-2 focus-within:ring-primary/20
  has-[:checked]:border-primary has-[:checked]:bg-primary-50
  has-[:not(:checked)]:border-gray-200 has-[:not(:checked)]:hover:border-gray-300 has-[:not(:checked)]:hover:bg-gray-50">
  <input type="radio" name="applicantType" value="INDIVIDUAL" className="sr-only peer" defaultChecked />
  <span className="absolute top-2 right-2 w-5 h-5 rounded-full bg-primary text-white items-center justify-center hidden peer-checked:flex">
    <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth={3} viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
    </svg>
  </span>
  <svg className="w-6 h-6 text-primary" /* user icon */ />
  <div>
    <p className="text-sm font-semibold text-gray-900">Individual</p>
    <p className="text-xs text-gray-500 mt-0.5 leading-relaxed">
      I am applying as an individual.
    </p>
  </div>
</label>
```

### Profile Company Header (Optional 배지)
```tsx
<CardHeader
  title="Company Information"
  description="Auto-filled on future applications to save you time."
  action={<Badge variant="gray">Optional</Badge>}
/>
```

### Signup 하단 muted hint
```tsx
<p className="text-xs text-gray-500 mt-6 text-center">
  You can add phone and company details later from your profile — they're optional.
</p>
```

---

## 8. 접근성 체크리스트 (디자인 책임 범위)

- [x] 필수 표시: `*` 위치 + `text-error-500` + `aria-required` (Input 컴포넌트 내장)
- [x] 색 단독 의존 금지: `*` 외 위치/라벨로 이중 전달
- [x] Focus ring: `focus:ring-2 focus:ring-primary/20` 기존 패턴 유지 (Button/Input 내장)
- [x] 라디오 그룹: `<fieldset><legend>` 사용, 카드 전체 `focus-within:ring-2`
- [x] InfoBox `role="note"` (경고 아님)
- [x] 대비율: `text-info-700` on `bg-info-50` ≈ WCAG AA 4.5:1 충족 (`#1d4ed8` on `#eff6ff`)
- [x] `prefers-reduced-motion`: 전환은 `transition-colors`만, 스케일 없음

---

## 9. UX 전달 사항 매핑 (02-ux-design.md §7)

| 요구 | 반영 |
|---|---|
| Info box 파란톤, `Info` 아이콘, `bg-info/10` 토큰 | §3A — `bg-info-50 border-info-200`, SVG Info 아이콘 |
| Applicant Type 카드형 라디오, 선택 시 border 2px primary | §3B — `border-2 has-[:checked]:border-primary` |
| Profile 회사정보 과강조 금지, neutral Optional 배지 | §3C — Badge `variant="gray"` |
| Signup 하단 hint `text-muted-foreground` 1줄 | §7 스니펫 — `text-xs text-gray-500 mt-6` |
| shadcn/ui 그대로 사용, 신규 토큰 금지 | 본 문서 전체 — 기존 토큰만 사용 |
| 모션 80ms fade, 스케일 없음 | §3B — `transition-colors duration-150` |

---

## 10. 명세 범위 외 (Phase 2 이월)

- 가입 후 "프로필 완성하기" 유도 배너 디자인
- Corporate 선택 시 JIT 회사정보 모달
- lucide-react 도입 (현재 인라인 SVG로 충분)
- 신청 상세 페이지 "서류" 섹션 디자인
