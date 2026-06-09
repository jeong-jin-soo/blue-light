# LicenseKaki 프론트엔드 디자인 개선안 — "AI 자동생성 티" 해소

**작성**: visual-designer · **대상**: `blue-light-frontend/src` · **기준 브랜치**: `feature/design-improvement`
**전제**: 기존 Tailwind 4 `@theme` 토큰 체계 유지·확장. 신규 팔레트 임의 도입 금지.

---

## 0. 한눈 요약

이 프론트엔드가 "AI로 자동생성한 티"가 나는 근본 이유는 **디자인 시스템이 존재하는데 화면이 그 시스템을 절반만 쓰고 나머지 절반은 즉석에서 지어내기 때문**이다. `index.css`에 54개+ 토큰과 `components/ui/`에 14개 컴포넌트가 잘 만들어져 있으나, 페이지 단에서는 (1) 이모지를 아이콘으로 쓰고, (2) 토큰 밖 색(`teal/blue/emerald/purple` 573회)을 직접 찍고, (3) `<Button>` 대신 raw `<button>`을 다시 스타일링한다. 그 결과 "기본기는 있는데 손맛이 없는" 전형적 생성형 결과물처럼 보인다.

가장 효과 큰 3가지(Phase 1): **① 이모지 → 아이콘 라이브러리(Lucide)**, **② 브랜드 레드 액센트 토큰화(`--color-accent`)**, **③ 토큰 밖 색 일괄 정리**. 이 셋만으로 "AI 티"의 체감 80%가 사라진다.

---

## 1. 현행 진단 (코드 근거)

### 1-A. 이모지를 아이콘으로 사용 — **최대 신호**

아이콘 라이브러리 미설치 확인: `package.json`에 `lucide-react`/`@heroicons`/`react-icons` **없음**(grep 0건). 대신 유니코드 이모지가 UI 구조 요소로 박혀 있다.

- `Layout.tsx:43-103` — **모든 역할의 사이드바 메뉴**가 이모지 아이콘. 예:
  ```
  { path: '/dashboard',  label: 'Dashboard', icon: '📊' },
  { path: '/applications', label: 'My Applications', icon: '📋' },
  { path: '/sld-orders',  label: 'SLD Orders', icon: '📐' },
  { path: '/lighting-orders', label: 'Lighting Layout', icon: '💡' },
  { path: '/lew-service-orders', label: 'LEW Service', icon: '⚡' },
  ```
  → 렌더링은 `<span>{item.icon}</span>` (`Layout.tsx:171`). OS·브라우저별로 이모지 모양·색·크기가 제각각 → 정부 라이선스 톤과 정면 충돌.
- `AdminDashboardPage.tsx:91-152` — **통계 카드 10개 전부** 이모지 아이콘(`📋🔍📝💳✅🔄🏁⏰👥⚡`). `DashboardCard.tsx:34`은 `<span className="text-2xl">{icon}</span>`로 그대로 출력.
- `LandingPage.tsx:12-31` — 서비스 6종(`📋📐💡🔌⚡🔄`), 신뢰배지 3종(`🔒🛡️📝`)이 모두 이모지.
- `EmptyState.tsx:21`도 `icon`을 `text-4xl`로 출력 → 호출부에서 `icon="📊"`(`AdminDashboardPage.tsx:198`).
- 폼 안내에도 잔존: `BeforeYouBeginGuide.tsx:28,106-109` (`🔌💰📋`).

**측정**: `icon: '<emoji>'` 패턴이 6개 파일 57회. 사이드바·대시보드·랜딩이라는 **첫인상 3대 화면**에 집중되어 타격이 가장 큼.

### 1-B. 브랜드 레드 액센트가 토큰에 없음

`index.css @theme`(9-100행)에 빨강 계열은 **시맨틱 에러색만** 존재(`--color-error: #dc2626`, 44-51행). 로고 워드마크의 **레드 슬래시 액센트**를 표현할 브랜드 토큰(`--color-accent` 등)이 **전무**.

결과: 화면 전반의 브랜드색이 네이비 `--color-primary: #1a3a5c` 단색뿐. 액센트가 없으니 모든 화면이 "파랑+회색"으로 평평하다. 강조가 필요할 때 개발자가 즉석에서 `emerald`/`teal`/`amber`를 꺼내 쓰는 원인(1-C)이 바로 이 토큰 공백이다. 게다가 레드를 브랜드색으로 쓰려 해도 현재는 `error`와 충돌해 의미가 꼬인다.

### 1-C. 토큰 밖 색 직접 사용 — **573회 / 72파일**

`(bg|text|border|ring)-(blue|teal|emerald|green|purple|amber|red|indigo|slate|sky|cyan)-[숫자]` 패턴이 **573회, 72개 파일**. 디자인 시스템에 `primary/success/warning/error/info/concierge` 토큰이 다 있는데도 Tailwind 기본 팔레트를 직접 찍는다.

대표 위반(`AdminNotificationTemplateListPage.tsx`):
- 검색 input 포커스: `focus:border-teal-500` (63행) — primary가 아닌 teal
- 코드 링크: `text-teal-700` (185행)
- severity: `text-purple-700`(205행), `text-amber-700`(203행)
- 모달 버튼: `bg-blue-600 hover:bg-blue-700`(353,408,501행) — `<Button variant="primary">`가 있는데 raw blue
- 에러배너: `bg-red-50 border-red-200 text-red-800`(136행) — `error-50/error-200/error-700` 토큰 있음
- 상태 점: `bg-emerald-500`(177,446행) — `success-500` 있음

또한 `bg-blue-*`(39회) ↔ `bg-primary-*` 혼재 → **같은 네이비를 두 이름으로 부름**. `LandingPage.tsx`는 한 파일 안에서 `text-primary`와 `from-blue-50`/`text-blue-200`/`bg-blue-100`을 섞어 쓴다(268,298행).

### 1-D. raw 엘리먼트 — `<Button>/<Input>/<Select>` 미사용 구간

`ui/` 컴포넌트가 있는데 페이지가 raw 엘리먼트를 다시 스타일링. 브리프 기준 raw `<button>` 73파일·raw `<input>` 29파일. `AdminNotificationTemplateListPage.tsx` 한 파일에서만 raw `<input>`(58,328,390), raw `<select>`×6(68,84,97,118,335,381), raw `<button>` 다수(307,313,345,399…). `Input.tsx`/`Select.tsx`/`Button.tsx`를 안 쓰니 포커스 링·radius·높이·에러 표기가 컴포넌트 버전과 미묘하게 다르다 → "비슷하지만 안 맞는" 인상.

### 1-E. raw 모달이 `<Modal>` 컴포넌트를 우회

`AdminNotificationTemplateListPage.tsx:321,363` — `fixed inset-0 bg-black bg-opacity-30 ...`로 모달을 직접 구성. `ui/Modal.tsx`가 있는데도 백드롭·z-index·shadow가 표준과 다름(`bg-black/50` vs `bg-opacity-30`, `shadow-xl` vs 토큰 `shadow-modal`).

### 1-F. radius/리듬 불일치

- 디자인 시스템 규칙: 카드 `rounded-xl`, 버튼 `rounded-lg`(kaki-concierge-design-system §1.1). 그러나 알림 템플릿 화면은 전부 `rounded`(=0.25rem) 사용(55,63,73,141…) → 카드가 각져 다른 화면과 톤이 다름.
- 간격이 대부분 `p-4`/`gap-4` 단일 리듬. 위계가 폰트 크기로만 표현됨(브리프 지적과 일치). 8pt 스케일·섹션 간 리듬 변화가 없어 "균일하게 펼쳐진 표" 느낌.

### 1-G. 추가 발견 신호

1. **제네릭 카피**: `AdminDashboardPage.tsx:86` `"Platform overview and key metrics"` — 전형적 플레이스홀더 문구. EmptyState 설명도 일반론(`AdminDashboardPage.tsx:200`).
2. **랜딩 카피 어색함**: `LandingPage.tsx:171,296` `"...Licence Management & more"`, 버튼 라벨 `"more"`(110행, 소문자) — 사람이 다듬지 않은 티.
3. **그라디언트 남발**: 랜딩에 `from-slate-50 to-blue-50`(86), `from-emerald-50 to-green-50`(164), `bg-clip-text ... from-emerald-500 to-green-600`(172) — 섹션마다 다른 그라디언트 + 무지개 텍스트는 생성형 결과물의 전형. design system §1.2 "그라디언트·glow 남발 금지" 자기 규칙 위반.
4. **InfoBox 토큰 공백 우회**: `InfoBox.tsx:60,62`가 `text-[#1e40af]`/`text-[#1d4ed8]` 인라인 hex 사용(info-700/800 토큰 미정의). 주석으로 사유는 남겼으나 토큰화가 정답.
5. **장식 blob 과다**: `LandingPage.tsx:156-157,267-268`의 떠다니는 원 4개 — 의미 없는 장식.
6. **severity/status 색이 컴포넌트 밖에서 즉흥 매핑**: `AdminNotificationTemplateListPage.tsx:200-205`에서 `★ Critical`(red), `● Important`(amber), `○ Info`, `M`(purple) — `Badge` 컴포넌트 미사용 + 텍스트 기호(★●○M)로 표현 → 1-A의 변종.

---

## 2. "AI 티"의 근본 원인 (디자인 원리)

| 원인 | 설명 | 어디서 드러나나 |
|---|---|---|
| **시스템 절반만 채택** | 토큰·컴포넌트는 잘 만들어졌으나 페이지가 안 씀. 도구가 있는데 매번 새로 만든 흔적 = 생성형 코드의 지문 | 1-C·1-D·1-E |
| **일관성 결핍** | 같은 네이비를 `primary`와 `blue`로 부르고, radius가 `rounded`/`rounded-lg`/`rounded-xl` 혼재 | 1-C·1-F |
| **브랜드 부재** | 액센트 토큰이 없어 모든 화면이 네이비+회색. 브랜드가 '기억에 남는 1색'을 못 가짐 | 1-B |
| **디테일 부족** | 이모지 아이콘은 정렬·광학크기·색 통제 불가 → 픽셀 단위 완성도가 안 남. 사람 디자이너가 절대 안 하는 선택 | 1-A·1-G-6 |
| **위계 평탄** | 강조가 폰트 크기뿐. 색/여백/모션으로 만든 입체적 위계가 없음 | 1-F |
| **카피 미완성** | "Platform overview", "more", "& more" 등 다듬어지지 않은 문구 | 1-G-1·2 |

핵심: **"무엇을 추가하느냐"보다 "있는 시스템을 일관되게 강제하느냐"의 문제.** 따라서 개선은 '디자인 추가'가 아니라 '시스템 수렴(convergence)'이 본질이다.

---

## 3. 개선 전략 (영향 큰 순)

### 전략 1 — 이모지 전면 제거 → Lucide 아이콘 도입 ★최우선
- **무엇을**: `lucide-react` 설치 후, 사이드바·대시보드·랜딩·EmptyState의 모든 이모지를 Lucide 아이콘 컴포넌트로 교체.
- **왜**: 1-A가 단일 최대 신호이고, 첫인상 3대 화면(사이드바/대시보드/랜딩)에 집중되어 ROI 최고. 아이콘은 stroke·크기·색을 `currentColor`로 통제 가능해 즉시 "프로가 만든 화면"으로 보임. Lucide 선택 근거: tree-shakable, 1.5~2 stroke가 기존 InfoBox/Layout의 인라인 SVG(`stroke-width 2`, `Layout.tsx:196`, `InfoBox.tsx:43`)와 시각적으로 동일 계열 → 무리 없이 섞임. B2B SaaS 표준(Vercel/Linear 계열).
- **구체 방법**:
  - 메뉴 정의의 `icon: '📊'`(string)을 `icon: LayoutDashboard`(컴포넌트 참조)로 변경. `Layout.tsx:171`을 `<item.icon className="w-5 h-5" />`로.
  - 매핑 예: Dashboard→`LayoutDashboard`, Applications→`FileText`, New Licence→`FilePlus`, SLD→`PencilRuler`, Lighting→`Lightbulb`, Power Socket→`Plug`, LEW Service→`Zap`, Expired→`RefreshCw`, Profile→`User`, Users→`Users`, Settings→`Settings`, Notification→`Bell`, Concierge→`Sparkles`(이미 design system §4 채택), System→`ServerCog`, Roles→`KeyRound`, Audit→`ScrollText`, Data Breach→`ShieldAlert`.
  - `DashboardCard`/`EmptyState`의 `icon` prop 타입을 `ReactNode` 유지(이미 그러함)하되 호출부에서 `<Bell className="w-6 h-6 text-primary" />` 전달. 카드 아이콘은 `text-2xl` 대신 `w-6 h-6 text-primary/70`.
- **작업범위**: 핵심 6파일(Layout, AdminDashboard, LandingPage, EmptyState 호출부, BeforeYouBeginGuide, Toast) 우선. 나머지 잔존 이모지는 Phase별 점진. **약 1~1.5일.**

### 전략 2 — 브랜드 레드 액센트 토큰화 (`--color-accent`)
- **무엇을**: 로고 슬래시의 레드를 `--color-accent-*` 스케일로 `@theme`에 추가. error와 의미 분리.
- **왜**: 1-B 해소. 브랜드를 '네이비+레드 1액센트'로 정의하면 화면에 기억점이 생기고, 개발자가 강조색이 필요할 때 즉흥 `emerald/amber`(1-C) 대신 `accent`를 집어들 표준 경로가 생긴다. 네이비(신뢰)+레드(긴급·액션)는 정부/전기 도메인에 자연스럽다.
- **구체 방법**: §4 토큰 스니펫 참조. 사용 지점은 **절제**(design system §1.2 정신 계승): ① 로고/브랜드 마크 ② Primary CTA의 보조 강조(예: "New" 배지) ③ 활성 탭/선택 인디케이터 보조 ④ 긴급 알림 도트. 단, **에러 의미에는 계속 `error` 토큰 사용**(accent는 장식·브랜드, error는 시맨틱 — concierge와 동일 원칙).
- **작업범위**: 토큰 추가 0.5일 + 적용 지점 점진. **약 0.5일(토큰) + 후속.**

### 전략 3 — 토큰 밖 색 일괄 정리 (573회)
- **무엇을**: `blue-*`→`primary-*`, `emerald/green-*`→`success-*`, `amber-*`→`warning-*`, `red-*`(시맨틱)→`error-*`, `teal/purple/indigo/sky/cyan`→가장 가까운 토큰으로 치환.
- **왜**: 1-C·1-G-6 해소. "같은 색 두 이름" 문제가 사라지면 화면 간 톤이 즉시 통일됨. 미래에 브랜드색 바꿔도 `@theme` 한 곳만 고치면 되는 시스템 본래 의도 회복.
- **구체 방법**:
  - 1순위 안전 치환(의미 1:1): `blue-600→primary`, `blue-50→primary-50`, `emerald-500→success-500`, `red-50→error-50` 등. `AdminNotificationTemplateListPage.tsx`, `AuditLogPage.tsx`(16회), 각 manager OrderDetail(16~31회)부터.
  - 2순위 판단 필요: `teal`(현재 알림 템플릿의 사실상 액센트)는 → `primary` 또는 신규 `accent`로. `purple-700`(severity MARKETING)은 의미상 `concierge` 또는 `gray`로.
  - 그라디언트 정리(1-G-3): 랜딩의 섹션별 제각각 그라디언트를 `from-primary-50`/단색 surface로 수렴. 무지개 `bg-clip-text` 제거 → `text-primary` 또는 `text-accent`.
- **작업범위**: 파일 多이나 기계적. 정규식 일괄 + 육안 검수. **2~3일, Phase 2~3 분산.**

### 전략 4 — raw 엘리먼트 → ui 컴포넌트 마이그레이션
- **무엇을**: raw `<button>/<input>/<select>/<textarea>/모달`을 `Button`/`Input`/`Select`/`Textarea`/`Modal`로 교체.
- **왜**: 1-D·1-E 해소. 포커스 링·높이·radius·에러 표기가 전 화면 통일 → "비슷하지만 안 맞는" 인상 제거. 접근성(focus-ring, aria)도 컴포넌트가 보장.
- **구체 방법**: 화면 단위로. `AdminNotificationTemplateListPage`(필터바·툴바·2모달·페이지네이션)를 레퍼런스 케이스로 먼저 완전 전환 → 패턴 확립 후 확산. 페이지네이션은 기존 `components/data/Pagination.tsx` 재사용.
- **작업범위**: 화면당 0.5~1일. 전수는 길지만 **고빈도 화면(admin/manager 목록·상세)부터 80/20.**

### 전략 5 — 통계 카드 위계 강화
- **무엇을**: `DashboardCard`에 강조 variant 추가(상단 보더 컬러 + 아이콘 색). 모든 카드가 동일 흰 박스인 현 상태(1-F) 개선.
- **왜**: design system §3.5가 이미 "Setup pending=concierge border-t-4, SLA breach=error border-t-4"를 명세. 같은 패턴으로 "Pending Review/Pending Payment" 같은 **액션 필요** 카드를 시각 구분 → 대시보드가 평면 그리드에서 '읽히는 우선순위'로.
- **구체 방법**: `DashboardCard`에 `accent?: 'primary'|'warning'|'error'|'accent'` prop 추가 → `border-t-4 border-t-{accent} ` + 아이콘색 연동. `AdminDashboardPage`에서 Pending류엔 `warning`, Unassigned엔 `accent`.
- **작업범위**: **0.5일.**

### 전략 6 — 8pt 스페이싱·타이포 위계 표준화
- **무엇을**: 페이지 헤더/섹션/카드 내부 간격을 8pt 스케일로 정리(`space-y-6` 섹션 / `gap-4` 카드그리드 / `mb-4` 카드헤더 — 이미 일부 일관). 타이포 위계를 토큰화된 단계로.
- **왜**: 1-F 해소. 위계가 폰트 크기뿐인 문제를 여백+굵기+색 3채널로.
- **구체 방법**: §4의 타이포 토큰 사용. 페이지 타이틀 `text-2xl font-bold text-gray-900`, 섹션 `text-lg font-semibold text-gray-800`, 카드라벨 `text-sm font-medium text-gray-500`로 **고정 3단계** 강제(현재 `text-xl sm:text-2xl`/`text-2xl` 혼재 통일).
- **작업범위**: Phase 4. **1일.**

### 전략 7 — 모션/디테일 + 카피 정리
- **무엇을**: 기존 `.animate-in`(index.css:117) 외에 hover transition·skeleton 로딩 통일. 제네릭 카피 교체.
- **왜**: 1-G-1·2 해소. "Platform overview and key metrics"→실제 맥락 문구, "more"→"Learn more" 등. 디테일이 사람 손길을 증명.
- **작업범위**: Phase 5. **0.5일.**

---

## 4. 디자인 토큰 보강안 (`index.css @theme`)

```css
@theme {
  /* === 기존 primary/success/warning/error/info/concierge 유지 === */

  /* ---- Colors: Brand Accent (NEW) — 로고 레드 슬래시 ----
     ⚠️ 장식·브랜드 강조 전용. "에러" 의미에는 --color-error 사용 (concierge와 동일 원칙).
     WCAG: accent-600 on white = 4.5:1 이상 확보 목표(대비 검증 후 확정). */
  --color-accent-50:  #fef2f2;
  --color-accent-100: #fde4e4;
  --color-accent-200: #fbcccc;
  --color-accent-300: #f5a3a3;
  --color-accent-400: #ed6f6f;
  --color-accent-500: #e03e3e;   /* 로고 슬래시 기준색(실측 후 보정) */
  --color-accent-600: #c62828;   /* 텍스트/CTA 강조 — 대비 AA */
  --color-accent-700: #a31f1f;   /* hover */
  --color-accent:        #c62828;  /* 의미적 alias */
  --color-accent-hover:  #a31f1f;

  /* ---- Colors: Info 확장 (NEW) — InfoBox 인라인 hex 제거용 ---- */
  --color-info-700: #1d4ed8;   /* InfoBox.tsx:62 text-[#1d4ed8] 대체 */
  --color-info-800: #1e40af;   /* InfoBox.tsx:60 text-[#1e40af] 대체 */

  /* ---- Neutral 명시화 (NEW) — gray 사용을 토큰으로 의미화 (선택) ----
     Tailwind 기본 gray를 그대로 쓰되, 본문/보조/플레이스홀더 단계를 문서화.
     text-gray-900=본문강조, 700=본문, 500=보조, 400=placeholder/disabled */

  /* ---- Typography Scale (문서화 — 위계 3단계 강제) ----
     page-title : text-2xl(1.5rem) font-bold   text-gray-900
     section    : text-lg(1.125rem) font-semibold text-gray-800
     card-label : text-sm(0.875rem) font-medium  text-gray-500
     body       : text-sm leading-relaxed text-gray-700
     caption    : text-xs text-gray-400 */

  /* ---- Spacing 리듬 (문서화 — 8pt) ----
     섹션 간 space-y-6(24px) / 카드 그리드 gap-4(16px) / 카드 내부 p-5~6 / 헤더 mb-4 */

  /* ---- Shadows (NEW) ---- */
  --shadow-hero: 0 25px 50px -12px rgb(13 31 51 / 0.40);
}
```

> **레드 액센트 hex는 로고 PNG 실측 후 확정 필요.** 위 값은 `#c62828`(Material Red 800 근방)을 placeholder로 둔 것 — 로고에서 스포이드로 추출해 `accent-500/600`을 보정하고, `accent-600 on white` ≥ 4.5:1 (AA) 검증 후 픽스. 검증 미달 시 텍스트용은 `accent-700`로 한 단계 진하게.

**네이밍 원칙**(concierge 토큰 규칙 계승):
- `accent`는 **브랜드·장식 전용**. "성공/경고/오류" 의미 표현 금지(그건 success/warning/error).
- 새 시맨틱 토큰은 추가하지 않음. 추가는 `accent`(브랜드) + `info-700/800`(기존 인라인 hex의 토큰화)뿐.

---

## 5. 컴포넌트 가이드

### 5-A. 아이콘 사용 규칙
1. **라이브러리 단일화**: Lucide만 사용. 이모지 아이콘 신규 도입 **금지**. 인라인 SVG는 기존 것 유지하되 신규는 Lucide.
2. **크기 표준**: 메뉴/버튼 `w-5 h-5`, 카드/헤더 `w-6 h-6`, 인라인 텍스트 `w-4 h-4`, EmptyState `w-10 h-10`.
3. **색**: `currentColor` 원칙(`text-*`로 제어). 단독 강조 아이콘만 `text-primary`/`text-accent`/`text-{semantic}-600`.
4. **stroke**: Lucide 기본(2). 기존 `stroke-width 2` 인라인 SVG와 일치.
5. **이모지 허용 예외**: 사용자 생성 콘텐츠·국기 등 의미상 이모지가 맞는 곳만(UI 구조 요소엔 금지).

### 5-B. Button / Card / Badge 일관 사용
- **모든 클릭 액션은 `<Button>`**. raw `<button>`은 (a) 토글 스위치(`AdminDashboardPage.tsx:167`)·(b) 테이블 행 전체 클릭·(c) 아이콘 전용 버튼 등 Button이 못 담는 경우만, 이때도 `focus:ring-2 focus:ring-primary/20` 등 토큰 클래스 사용.
- **모든 카드는 `<Card>`** (`rounded-xl shadow-card`). raw `bg-white rounded shadow-xl`(알림 템플릿 모달) 금지 → `<Modal>` 또는 `<Card>`.
- **모든 상태 표시는 `<Badge>`/도메인 StatusBadge**. severity의 `★●○M` 텍스트 기호(`AdminNotificationTemplateListPage.tsx:200-205`)는 `<Badge variant>` 6종으로 교체:
  - CRITICAL→`error`, IMPORTANT→`warning`, INFORMATIONAL→`info`, MARKETING→`gray`(또는 `concierge`).
- **Badge variant 확장 검토**: 필요 시 `accent` 1종 추가(`bg-accent-50 text-accent-700`) — concierge 추가와 동일 패턴(`Badge.tsx:3`).

### 5-C. raw 엘리먼트 마이그레이션 방침
- **신규 코드**: raw `<input>/<select>/<textarea>/<button>` 작성 금지. PR 리뷰 체크 항목.
- **기존 코드**: 화면 단위로 점진. 우선순위 = 사용 빈도(admin/manager 목록·상세 → 신청 플로우 → 기타).
- **변환 패턴 레퍼런스**: `AdminNotificationTemplateListPage`를 1번 케이스로 완전 변환해 PR 본보기 제시.
- **lint 가드(선택)**: ESLint custom rule 또는 grep CI로 `<input`/`<select` 신규 유입 차단 고려.

---

## 6. 단계별 로드맵 (`feature/design-improvement`)

### Phase 1 — 첫인상 즉효 (이모지+액센트) · **2~3일** ★최대 효과
1. `lucide-react` 설치.
2. `Layout.tsx` 사이드바 전 메뉴 이모지 → Lucide(전 역할). 헤더 로그아웃/햄버거도 Lucide 정리.
3. `AdminDashboardPage` 카드 10개 이모지 → Lucide + `DashboardCard`에 `accent` prop 추가(전략 5).
4. `index.css`에 `--color-accent-*` + `info-700/800` 토큰 추가(§4). 로고 레드 실측 보정.
5. `EmptyState` 호출부·`LandingPage` 서비스/신뢰 아이콘 → Lucide.
- **검증(Before/After)**: 로그인 직후 보이는 사이드바·대시보드·랜딩에 **이모지 0개**. 사이드바 아이콘이 색·크기 균일. 대시보드에 액션 카드(Pending류) 상단 보더 강조 생김. `@theme`에 `accent` 토큰 존재. "AI 티" 체감 대폭 감소.

### Phase 2 — 색 토큰 수렴 (고빈도 화면) · **2일**
1. `AdminNotificationTemplateListPage` + `AuditLogPage` + admin/manager OrderDetail의 `teal/blue/emerald/red/purple` → 토큰 치환(전략 3 1순위).
2. `LandingPage` 그라디언트·무지개 텍스트·`blue-*` 정리(1-G-3).
3. `InfoBox.tsx`의 인라인 hex → `text-info-800/700` 토큰(§4로 가능해짐).
- **검증**: 상위 10개 고빈도 파일에서 `(bg|text|border)-(blue|teal|emerald|purple)-` grep **0건**. `bg-primary`와 `bg-blue` 혼용 사라짐. 랜딩에 무지개 텍스트·섹션별 제각각 그라디언트 없음.

### Phase 3 — raw 엘리먼트 → 컴포넌트 (고빈도) · **3일**
1. `AdminNotificationTemplateListPage` 완전 변환(필터바·툴바·2모달·페이지네이션) = 본보기 PR.
2. admin/manager 목록·상세의 raw input/select/button → ui 컴포넌트.
3. raw 모달 → `<Modal>`.
- **검증**: 변환 화면에서 raw `<input>/<select>` 0건, raw 모달 0건. 포커스 링·radius가 전 화면 동일. 신규 lint 가드 통과.

### Phase 4 — 위계·리듬·잔여 색 · **2일**
1. 타이포 3단계 강제(전략 6) — 페이지 타이틀/섹션/라벨 통일.
2. 8pt 스페이싱 점검.
3. 남은 색 토큰 위반(나머지 60여 파일) 일괄 정리.
4. 잔존 이모지(폼 가이드 등) → Lucide.
- **검증**: 전 화면 `(blue|teal|emerald|purple|amber|indigo|sky|cyan)-` grep 0건(시맨틱 red 제외). 이모지 아이콘 0건. 페이지 타이틀 폰트 사이즈 단일화.

### Phase 5 — 디테일·카피·모션 · **1일**
1. 제네릭 카피 교체("Platform overview and key metrics" 등).
2. hover transition·로딩 스켈레톤 통일. `.animate-in` 적용 확대.
3. 랜딩 장식 blob 정리.
- **검증**: 플레이스홀더 문구 없음. 인터랙션 전환 일관. 사람이 다듬은 화면으로 보임.

> **권장 PR 분할**: Phase 1을 2개 PR(아이콘 / 토큰+카드)로 쪼개 빠르게 머지 → 체감 효과를 조기 확보. 이후 Phase는 화면 묶음 단위 PR.

---

## 7. Before / After 기준 (체크리스트)

| 항목 | Before(현재) | After(목표) | 검증 방법 |
|---|---|---|---|
| 이모지 아이콘 | 57회/6파일(사이드바·대시보드·랜딩) | UI 구조 요소 0건 | grep `icon: '<emoji>'` = 0 |
| 아이콘 라이브러리 | 미설치 | Lucide 단일 | `package.json`에 lucide-react |
| 브랜드 액센트 토큰 | 없음(error만) | `--color-accent-*` 존재·적용 | `index.css @theme` 확인 |
| 토큰 밖 색 | 573회/72파일 | 시맨틱 red 외 0건 | grep `(blue\|teal\|emerald\|purple\|amber\|indigo\|sky\|cyan)-[0-9]` |
| primary/blue 혼용 | `bg-blue` 39 ↔ `bg-primary` 39 | primary로 단일화 | grep `bg-blue-` = 0 |
| raw 엘리먼트 | `<button>` 73·`<input>` 29파일 | 고빈도 화면 0건 | grep + lint 가드 |
| raw 모달 | `bg-black bg-opacity-30` 직접 | `<Modal>` 사용 | grep `bg-opacity-30` 모달 = 0 |
| radius 일관성 | `rounded`/`lg`/`xl` 혼재 | 카드 xl·버튼 lg 통일 | 육안 + 컴포넌트 강제 |
| 통계카드 위계 | 동일 흰 박스 균일 그리드 | 액션 카드 상단 보더 강조 | DashboardCard `accent` prop |
| 카피 | "Platform overview…", "more" | 맥락 문구 | 육안 |
| 그라디언트/무지개 | 섹션별 제각각 + bg-clip-text | 절제·단색 수렴 | 랜딩 육안 |

---

## 8. 리스크·주의

- **설정 우선 원칙 비충돌**: 본 개선은 시각 토큰·컴포넌트만 다룸. master_prices/role_metadata 등 데이터 소스는 무관(CLAUDE.md §설계 원칙 영향 없음).
- **accent vs error 혼동 방지**: 레드 액센트는 **브랜드·장식 전용**. 폼 검증 에러·삭제 위험 액션은 계속 `error`/`danger`. PR 리뷰에서 의미 오용 점검.
- **대비(AA) 미검증 시 텍스트 사용 보류**: accent hex 실측·검증 전엔 배경/보더에만 쓰고 텍스트엔 `accent-700` 확정 후.
- **점진 적용 중 혼재 구간**: Phase 진행 중 한 화면에 신·구 혼재 불가피 → 화면 단위로 끊어 머지해 시각 일관성 유지.
- **Lucide stroke 톤**: 기존 인라인 SVG와 stroke-width(2) 일치 확인했으나, 일부 16px 이하 아이콘은 광학 보정 위해 stroke 1.5 고려.
