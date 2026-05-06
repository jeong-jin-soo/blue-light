# Phase 1 — 신청자 UX 설계 (와이어프레임 & 마이크로카피)

**작성일**: 2026-04-17
**근거 스펙**: `01-spec.md` AC-S1~AC-C3 (18개 조항)
**설계 원칙**: Just-in-Time Disclosure, 인식 > 회상, 시스템 상태 가시성

---

## 1. 화면 A — SignupPage (간소화)

### 레이아웃
```
┌─────────────────────────────────────────┐
│  [LicenseKaki logo]                     │
│                                         │
│  Create your account                    │ ← H1
│  Get started in 30 seconds              │ ← subtitle
│                                         │
│  ┌─ Name row (2 col on ≥md, stack<md)─┐│
│  │ First Name *       Last Name *     ││
│  └────────────────────────────────────┘│
│  Email *                                │
│  [you@company.com                    ]  │
│  Password *                             │
│  [••••••••                           ]  │
│  Min 8 chars, 1 uppercase, 1 number     │ ← helper (always visible)
│  Confirm Password *                     │
│  [••••••••                           ]  │
│                                         │
│  ☐ I agree to the Terms and consent to  │ ← PDPA
│    the collection and use of my         │
│    personal data per PDPA. *            │
│                                         │
│  [ Create account ]  ← primary, full-W  │
│                                         │
│  ─ Already have an account? Sign in ─   │
│                                         │
│  ℹ You can add company details later    │ ← soft hint, neutral grey
│    from your profile.                   │
└─────────────────────────────────────────┘
```

### 필수/선택 표시
- 라벨에 `*` (빨강 아님, `text-destructive` 토큰) + `aria-required="true"`
- Phase 1은 모든 필드가 필수이므로 "(optional)"은 등장하지 않음.

### 마이크로카피 (EN / KO)
| 요소 | 영문 | 한글 |
|---|---|---|
| H1 | Create your account | 계정 만들기 |
| Subtitle | Get started in 30 seconds | 30초면 시작할 수 있어요 |
| First Name | First Name | 이름 |
| Last Name | Last Name | 성 |
| Email | Email | 이메일 |
| Email ph | you@company.com | you@company.com |
| Password | Password | 비밀번호 |
| PW helper | Min 8 chars, 1 uppercase, 1 number | 8자 이상, 대문자·숫자 각 1자 이상 |
| Confirm | Confirm Password | 비밀번호 확인 |
| PDPA | I agree to the Terms and consent to the collection and use of my personal data under Singapore PDPA. | 이용약관에 동의하며, 싱가포르 PDPA에 따른 개인정보 수집·이용에 동의합니다. |
| CTA | Create account | 가입하기 |
| Sign-in link | Already have an account? Sign in | 이미 계정이 있나요? 로그인 |
| Bottom hint | You can add phone and company details later from your profile — they're optional. | 전화번호와 회사 정보는 나중에 프로필에서 선택 입력할 수 있어요. |

### AC 매핑
AC-S1 (4필드 DOM 제거) / AC-S2 (최소 입력 제출) / AC-S5 ("프로필 완성" 유도는 Phase 2로 미룸 → 지금은 수동적 hint 한 줄만).

---

## 2. 화면 B — NewApplicationPage Step 0 (재설계)

### 레이아웃
```
┌────────────────────────────────────────────────────┐
│ Step 0 of 5 — Application Details                  │
│ ─────●────○────○────○────○                         │
│                                                    │
│ ┌── Application Type * ───────────────────────────┐│
│ │ ( ) New Licence       첫 면허 신청                ││
│ │ ( ) Renewal           기존 면허 갱신              ││
│ │ ( ) Amendment         설비 변경 신청              ││
│ └─────────────────────────────────────────────────┘│
│                                                    │
│ ┌── Licence Period * ─────────────────────────────┐│
│ │ ( ) 1 year   ( ) 2 years   ( ) 3 years          ││
│ └─────────────────────────────────────────────────┘│
│                                                    │
│ ┌── Applicant Type *  (default: Individual) ─────┐│
│ │ ┌──────────────────┐ ┌──────────────────────┐  ││
│ │ │ ● Individual     │ │ ○ Corporate           │ ││
│ │ │ 개인 자격 신청      │ │ 법인(회사) 명의 신청     │ ││
│ │ │ 본인 명의로 전기     │ │ UEN 보유 법인·사업자     │ ││
│ │ │ 설비를 운영          │ │                      │ ││
│ │ └──────────────────┘ └──────────────────────┘  ││
│ │ ℹ Corporate 선택 시, 다음 단계에서 회사 정보가      ││
│ │   필요할 수 있습니다. (Phase 2 예고)              ││
│ └─────────────────────────────────────────────────┘│
│                                                    │
│ ┌── SLD Option * ─────────────────────────────────┐│
│ │ ( ) I have my own SLD                            ││
│ │ ( ) Request LicenseKaki to generate one          ││
│ └─────────────────────────────────────────────────┘│
│                                                    │
│ ┌── SP Account Number (optional) ─────────────────┐│
│ │ [________________________]                      ││
│ │ e.g. 1234567890. 나중에 LEW 검토 시 요청할 수도 있어요.│
│ └─────────────────────────────────────────────────┘│
│                                                    │
│ ┌──────────────────────────────────────────────┐   │
│ │ ℹ No documents needed now                    │   │ ← info box
│ │ Our Licensed Electrical Worker (LEW) will    │   │   (blue-tinted,
│ │ review your application and request any       │   │    info not warn)
│ │ documents — SP bill, LOA, photos, SLD —       │   │
│ │ through the platform when needed.             │   │
│ └──────────────────────────────────────────────┘   │
│                                                    │
│           [ Back ]        [ Next → ]  ← disabled   │
│                                         until all  │
│                                         required   │
└────────────────────────────────────────────────────┘
```

### 파일 업로드 UI
**0개.** (AC-A1)

### 마이크로카피 (EN / KO)
| 요소 | EN | KO |
|---|---|---|
| Step title | Application Details | 신청 정보 |
| App Type header | What are you applying for? | 어떤 신청인가요? |
| NEW | New Licence — First-time application | 신규 — 첫 면허 신청 |
| RENEWAL | Renewal — Extend your existing licence | 갱신 — 기존 면허 연장 |
| AMENDMENT | Amendment — Modify installation details | 변경 — 설비 변경 신고 |
| Licence Period | Licence Period | 면허 유효 기간 |
| Applicant Type header | Who is applying? | 누가 신청하나요? |
| Individual desc | I am applying as an individual. | 개인 자격으로 신청합니다. |
| Corporate desc | I am applying on behalf of a registered company (UEN). | 회사(UEN 보유 법인) 명의로 신청합니다. |
| Corporate heads-up | You may be asked for company details in a later step. | 이후 단계에서 회사 정보가 필요할 수 있습니다. |
| SLD header | Single-Line Diagram (SLD) | 단선도 (SLD) |
| SLD own | I'll provide my own SLD | 제가 보유한 SLD를 사용 |
| SLD request | Have LicenseKaki generate one for me | LicenseKaki가 생성 |
| SP Account label | SP Account Number (optional) | SP 계정 번호 (선택) |
| SP Account helper | If you have it on hand. Otherwise your LEW will ask later. | 지금 알고 있다면 입력하세요. 없으면 LEW가 이후에 요청합니다. |
| Info box H | No documents needed now | 지금은 서류 제출이 필요 없어요 |
| Info box body | Your assigned Licensed Electrical Worker (LEW) will review your application and request any required documents — SP account, LOA, main breaker photo, SLD — through the platform. This keeps your first step fast. | 배정된 LEW가 신청서를 검토한 뒤 필요한 서류(SP 계정, LOA, 메인 차단기 사진, SLD 등)를 플랫폼을 통해 요청드립니다. 첫 단계는 최대한 빠르게 진행하세요. |
| Next CTA | Next | 다음 |

### AC 매핑
AC-A1 (업로드 4종 제거) / AC-A2 (필수 입력만으로 Next 활성) / AC-A3 (applicantType 기본 INDIVIDUAL, 필수) / AC-A6 (프론트 "필수" = 백엔드 @NotNull 정합).

---

## 3. 화면 C — ProfilePage 회사정보 섹션

### 레이아웃 (해당 섹션만)
```
┌─ 👤 Personal Information ──────────────────────────┐
│  Name, Email (read-only), Phone (optional)         │
└────────────────────────────────────────────────────┘

┌─ 🏢 Company Information  (Optional) ───────────────┐
│  Auto-filled on future applications to save time.  │ ← benefit copy
│                                                    │
│  Company Name            UEN                       │
│  [___________________]   [______________]          │
│  Designation                                        │
│  [___________________]                              │
│                                                    │
│  [ Save changes ]   [ Cancel ]                     │
│                                                    │
│  (empty state, when all 3 empty:)                  │
│  💡 Add your company details to save time on       │
│     future applications.                           │
└────────────────────────────────────────────────────┘
```

### 마이크로카피
| 요소 | EN | KO |
|---|---|---|
| Section H | Company Information (Optional) | 회사 정보 (선택) |
| Section desc | Auto-filled on future applications to save you time. | 이후 신청에서 자동 입력되어 시간을 아낄 수 있습니다. |
| Company Name ph | e.g. Blue Light Pte Ltd | 예: Blue Light Pte Ltd |
| UEN ph | e.g. 202312345A | 예: 202312345A |
| Designation ph | e.g. Project Manager | 예: Project Manager |
| Empty state | Add your company details to save time on future applications. | 회사 정보를 추가하면 다음 신청부터 자동으로 채워집니다. |
| Save | Save changes | 변경사항 저장 |
| Saved toast | Company info updated | 회사 정보가 저장되었습니다 |

### AC 매핑
AC-P1 (4필드 모두 선택) / AC-P2 (기존 값 프리필) / AC-P3 (빈 문자열 NULL 정규화 — 클라이언트는 trim 후 전송).

---

## 4. 접근성 체크리스트

### 공통
- **키보드 순서**: 논리적 DOM 순서 = 시각 순서. Signup: Name→Name→Email→PW→Confirm→PDPA→CTA. Step 0: AppType→Period→ApplicantType→SLD→SP#→Next.
- **Radio group**: `<fieldset>` + `<legend>` (스크린 리더가 "Applicant Type, radio group, 2 options, Individual selected" 발화).
- **필수 표시**: `*`는 시각, `aria-required="true"`는 보조기기, 라벨 텍스트에도 "(required)" 또는 기본 required(미표기) + 선택 필드에만 "(optional)" 명시.
- **색 대비**: 필수 `*` 색 단독 의존 금지 — 위치(라벨 뒤) + 텍스트로 이중 전달. WCAG AA 4.5:1 보장 (`text-destructive` 토큰 검증).
- **Focus ring**: Tailwind `focus-visible:ring-2 ring-offset-2` 유지. 라디오 카드는 카드 전체 outline.
- **에러 발화**: 필드 아래 `<p id="err-email" role="alert">` + input `aria-describedby="err-email" aria-invalid="true"`.

### 화면별
- **Signup** PDPA 체크박스: 링크는 `<a>` 로 키보드 포커스 가능, 체크박스 라벨 클릭 영역 확장.
- **Step 0** Info box: `role="note"` (경고 아님). 타이틀 `<h3>`로 보조기기 스캔 가능.
- **Profile** 섹션: `<section aria-labelledby="company-info-h">`.

---

## 5. 반응형

| Breakpoint | Signup Name | Step 0 ApplicantType | Profile Company |
|---|---|---|---|
| <640px (mobile) | 1열 스택 | 카드 1열 스택 | 1열 |
| 640–1024 | 2열 | 2열 가로 | 2열 (Name/UEN) |
| >1024 | 2열 (max-w 480px) | 2열 | 2열 |

- Info box는 항상 `max-w-prose` (65ch) — 와이드 모니터에서 과도하게 늘어나지 않게.
- Step 0 info box는 `<details open>` 대신 상시 표시(첫 방문자 대상이므로 숨김 위험).

---

## 6. 에지 케이스 & 에러 UX

| Case | 처리 |
|---|---|
| 구버전 클라이언트가 phone 등을 보냄 (AC-S3) | 서버는 200, 프론트는 해당 필드를 로컬 상태에서 조용히 제거. 사용자 경고 불필요. |
| 기존 사용자가 이미 회사 정보 보유 | ProfilePage 프리필 + 섹션 상단에 "마지막 수정: YYYY-MM-DD" (선택). 삭제 유도 문구 없음. |
| Individual ↔ Corporate 전환 | Phase 1은 플래그만 저장 → 재선택 시 confirm 불필요. 값 손실 없음(다른 필드 비움 아님). Phase 2 JIT 모달 도입 시 "회사 정보 유지?" 확인 추가 예정. |
| SP Account 형식 | 싱가포르 SP Group 계정은 보통 10자리 숫자. Phase 1은 형식 강제 없음, helper에 `e.g. 1234567890`만 제시. 실검증은 Phase 2 LEW 요청 단계에서. |
| Corporate 선택 + 프로필에 companyName 없음 | Phase 1에서는 경고 없이 진행 (AC-A5). Phase 2에서 JIT 모달. |
| LOA 생성 시도 시 INCOMPLETE_PROFILE (AC-C3) | 기존 예외 유지. 사용자에게는 "회사 정보를 먼저 입력해 주세요 → 프로필로 이동" 딥링크 토스트. |

---

## 7. Designer 전달 사항

1. **Info box 톤**: 파란색 계열(신뢰/안내), 노랑·빨강 금지. 아이콘 `Info` (circle-i), 제목 14-15px semibold, 본문 13-14px regular. 기존 `bg-info/10 border-info/30` 토큰 재사용.
2. **Applicant Type 라디오**: 일반 라디오가 아닌 **선택형 카드** 권장 — 넓은 터치 타겟, 설명 포함. 선택 시 border 2px primary + 좌상단 체크 아이콘. 미선택 시 border 1px neutral. 높이는 두 카드 균등.
3. **Profile 회사정보 섹션**: 과도한 강조 금지 — Personal Info와 동일한 card surface, 헤더 아이콘만 추가(🏢 Building). "(Optional)" 뱃지는 neutral 회색, 빨강 아님. 빈 상태 힌트는 섹션 내부 아래쪽 `text-muted-foreground` 1줄.
4. **Signup 하단 hint**: `text-xs text-muted-foreground`, 아이콘 없이 텍스트만. CTA와 충분한 간격(최소 24px).
5. **디자인 토큰 재사용**: 신규 색/간격 추가 금지. `shadcn/ui` Radio, Checkbox, Card, Alert(info variant) 컴포넌트 그대로 사용.
6. **모션**: Applicant Type 전환 시 80ms fade만. 카드 스케일 변화 없음(접근성 `prefers-reduced-motion` 대응).

---

## 8. AC 커버리지 매트릭스

| AC | 반영 위치 |
|---|---|
| S1, S6 | 화면 A 레이아웃에서 4필드 제거 |
| S2 | 화면 A 필수 5필드만 |
| S3 | §6 에지케이스 (무시 처리) |
| S4 | 백엔드 — 디자인엔 영향 없음 |
| S5 | §1 bottom hint (유도 배너는 Phase 2) |
| A1 | 화면 B 업로드 0개 |
| A2 | 화면 B Next 조건 |
| A3 | 화면 B Applicant Type default Individual |
| A4, A6 | §7 "필수" 표시 정합 |
| A5 | §6 Corporate 전환 플래그만 저장 |
| P1, P2, P3 | 화면 C 전체 |
| C1, C2 | 백엔드 — 프론트 영향 없음 |
| C3 | §6 LOA INCOMPLETE_PROFILE 토스트 |
