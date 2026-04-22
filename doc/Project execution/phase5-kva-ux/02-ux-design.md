# Phase 5 — kVA 입력 UX 개선 설계 (옵션 A)

**작성일**: 2026-04-17
**근거 스펙**: `01-spec.md` AC-U1~U6, S1~S4, A1~A4, P1~P3
**설계 원칙**: Just-in-Time Disclosure · Role-based Deferral · Phase 1 InfoBox 톤 / Phase 3 배지·모달 패턴 연속성 · 경고가 아닌 "안심" 톤

---

## 1. 화면 A — NewApplicationPage Step 2 (kVA 선택)

### 1.1 와이어프레임 (Desktop ~720px)

```
┌────────────────────────────────────────────────────────┐
│ Step 2 of 4 · Electrical Load                         │
│ ────────────────────────────────────────────────────── │
│                                                        │
│ Approved Load (kVA) *                                  │
│ ┌──────────────────────────────────────────────┐       │
│ │  — I don't know — let LEW confirm me later ▾ │       │
│ └──────────────────────────────────────────────┘       │
│                                                        │
│ ┌── Tip ─────────────────────────────────────── ⓘ ──┐  │
│ │ Not sure about your kVA?                          │  │
│ │ You can find it on your SP Group bill, or check   │  │
│ │ the rating label on your main circuit breaker.    │  │
│ │ • HDB flats are typically 45 kVA   ← buildingType │  │
│ │   기반 조건부 한 줄                                │  │
│ │ ▸ How to read your SP bill                        │  │
│ │ ▸ Where is the main breaker nameplate?            │  │
│ └───────────────────────────────────────────────────┘  │
│                                                        │
│ ┌── Estimated quote ───────────────────────────────┐   │
│ │ From S$350                                       │   │
│ │ Final price will be set after your LEW confirms  │   │
│ │ the actual kVA. No payment yet.                  │   │
│ └──────────────────────────────────────────────────┘   │
│                                                        │
│ ┌── Price by tier (reference) ─────────── dimmed ──┐   │
│ │  45 kVA  S$350   ·  100 kVA  S$650  · 200 S$950  │   │
│ │  (table is faded — will activate once confirmed) │   │
│ └──────────────────────────────────────────────────┘   │
│                                                        │
│             [ ◀ Back ]              [ Next ▶ ]         │
└────────────────────────────────────────────────────────┘
```

### 1.2 드롭다운 옵션 구성 (AC-U1)

```
┌──────────────────────────────────────────────┐
│ — I don't know — let LEW confirm me later    │  ← italic, text-muted
│ ────────────────────────────────────────── ─ │  ← divider
│   45 kVA   (HDB / small unit)                │
│  100 kVA   (shophouse / small F&B)           │
│  200 kVA                                      │
│  300 kVA                                      │
│  500 kVA   (factory / industrial)            │
└──────────────────────────────────────────────┘
```

- "I don't know" 옵션: `italic`, `text-neutral-500`, 앞에 em-dash 2개로 시각 구분. 선택 후 트리거 버튼에도 동일 스타일 적용해 "tier를 고르지 않았음"을 항상 인지.
- divider 아래 tier 옵션은 기존 포맷 유지(괄호 안 건물 예시는 Phase 5 신규 보조 텍스트 — 선택 Grape, AC-U1 범위 외로 designer 재량).

### 1.3 상태별 UI 전환

| state | 드롭다운 라벨 | 가격 카드 | 가격 분석표 | Next 버튼 |
|---|---|---|---|---|
| initial (unselected) | placeholder "Select kVA tier" | hidden | hidden | disabled |
| tier 선택 (CONFIRMED) | "100 kVA" | `S$650` primary | 전체 tier 표 표시 (현재 tier 강조) | enabled |
| UNKNOWN 선택 | 이탤릭 "I don't know…" | `From S$350` + 보조 문구 | `opacity-50 pointer-events-none` (회색화) | **enabled** |

- UNKNOWN 가격 카드 톤: 경고 아님. Phase 1 `InfoBox` 의 primary/neutral 톤 그대로. border는 `border-primary/30`, 배경 `bg-primary/5`.
- 가격 분석표는 숨기지 않고 **회색화**만 수행 — 사용자가 "나중에 이 tier 중 하나로 확정된다"는 참조값을 볼 수 있게 유지.

### 1.4 Tip 박스 컴포넌트 (`KvaTipBox.tsx`)

- 위치: 드롭다운 **바로 아래**. 드롭다운과 수직 분리(spacing `gap-3`).
- 디자인: Phase 1 InfoBox 재사용. `border-l-4 border-primary/40`, `bg-primary/5`, `ⓘ` 아이콘 왼쪽.
- **톤**: 경고 느낌 금지 — "Not sure?" 로 시작하는 공감형 오프닝.
- **buildingType 조건부 한 줄** (AC-U4/U5):

| buildingType | 추가 한 줄 문구 |
|---|---|
| HDB | `HDB flats are typically 45 kVA.` |
| CONDO | `Condo units usually fall between 45–100 kVA.` |
| LANDED | `Landed homes vary widely — check the SP bill.` |
| SHOPHOUSE | `Shophouses are commonly 100 kVA.` |
| FACTORY | `Factories often range from 300–500 kVA.` |
| OFFICE | `Offices typically fall in the 100–300 kVA range.` |
| (미선택/기타) | *일반 안내만 표시, 조건부 줄 숨김* |

- **중요**: 이 문구는 **텍스트일 뿐**이며 드롭다운 pre-select 동작을 **일으키지 않음** (AC-U4). 사용자가 직접 선택해야 CONFIRMED.
- 접이식 상세 2개 (AC-U6):
  - ▸ "How to read your SP bill" → 펼치면 2~3줄 텍스트 + SP 고지서 캡처 썸네일(designer 요청 항목).
  - ▸ "Where is the main breaker nameplate?" → 일반 가정 DB 박스 내부 사진 + "Look for '45 kVA' or '100 A' label" 문구.
- 모바일: 기본 접힘(chevron right), 탭으로 확장. Desktop: 기본 접힘이지만 hover 가능.

---

## 2. 화면 B — 결제 차단 UX (kvaStatus=UNKNOWN)

### 2.1 신청 상세의 결제 CTA 버튼

```
┌───────────────────────────────────────────────────┐
│ Application APP-2026-000412 · PENDING REVIEW      │
│                                                   │
│ ┌── kVA ──────────────────────────────────────┐   │
│ │ 🟡 Pending LEW review                       │   │
│ │ Your electrician will confirm the final     │   │
│ │ kVA shortly — typically within 1 business   │   │
│ │ day. You'll be notified here.               │   │
│ └─────────────────────────────────────────────┘   │
│                                                   │
│ Estimated: From S$350                             │
│                                                   │
│ [ Pay now ]  ← disabled, 회색                     │
│  ⓘ Your kVA needs to be confirmed by your LEW     │
│    before payment.                                │
└───────────────────────────────────────────────────┘
```

- "Pay now" 버튼: `disabled` + helper text는 버튼 하단에 항상 노출(hover/tooltip 아님 — 인식 > 회상).
- 결제 페이지에 **직접 URL 접근** 시: redirect to 신청 상세 + info banner `"Payment will open once your LEW confirms the kVA."` (서버가 400 `KVA_NOT_CONFIRMED` 반환하므로 프론트 가드는 UX 보조).
- 대시보드 신청 행: 기존 상태 배지 옆에 `🟡 kVA pending` 작은 pill 추가 (Phase 3 "pending docs" 배지와 같은 크기/위치).

### 2.2 pill / 배지 시각 사양 (designer 전달)

| state | label | color token | 톤 |
|---|---|---|---|
| UNKNOWN | `kVA pending` | `amber-100 / amber-800` | **info** (경고 아님) |
| CONFIRMED by LEW | `kVA confirmed` | `emerald-100 / emerald-800` | success, 24h만 표시 후 소멸 |
| CONFIRMED by user | (배지 없음 — 기본 상태) | — | — |

- 주의: **amber**를 쓰되 "경고 아이콘"은 피함. 동그란 점(•) 또는 시계 아이콘(⏱)으로 "대기 중" 뉘앙스만 전달.

---

## 3. 화면 C — 신청 상세 (신청자 뷰) kVA 섹션

```
┌── Electrical Load ────────────────────────────────┐
│                                                   │
│  Load (kVA)          • kVA pending LEW review     │
│  ─────────────────────────────────────────────    │
│                                                   │
│  ┌─ ⓘ What happens next ────────────────────┐     │
│  │ 1. Your LEW reviews your application.    │     │
│  │ 2. They may request an SP bill or        │     │
│  │    a breaker photo via this portal.      │     │
│  │ 3. Once confirmed, you'll see the final  │     │
│  │    price and be able to pay.             │     │
│  └──────────────────────────────────────────┘     │
│                                                   │
└───────────────────────────────────────────────────┘
```

- 확정 후(CONFIRMED 전환 시):
  - 배지 → `✓ kVA confirmed · 100 kVA`
  - 회색 info box 제거, 가격 카드 = `S$650` primary
  - 상단에 **one-time** 배너 (24h TTL 또는 dismiss 시 소멸): `"Your LEW confirmed 100 kVA. Price updated to S$650. You can now proceed to payment."`
  - 가격 상승(placeholder 350 → 650 등)인 경우 배너 문구에 **금액 명시 + 취소 가능 안내**: `"If this doesn't match your expectation, you can cancel this application at no cost before paying."`
- 알림 연계 (Phase 3 `NotificationService` 재사용):
  - `NotificationType.KVA_CONFIRMED` → in-app only (이메일 범위 외)
  - title: `kVA confirmed — ready for payment`
  - body: `Your LEW set the load to {kva} kVA. Final price: S${amount}.`
  - deep-link: `/applications/{id}` 해당 섹션으로 anchor scroll.

---

## 4. 화면 D — LEW/ADMIN kVA 확정 UI

### 4.1 AdminApplicationDetailPage 내 kVA 섹션

UNKNOWN 상태:
```
┌── kVA · 🟡 UNKNOWN ───────────────────────────────┐
│ Submitted by applicant: — (deferred to LEW)       │
│ Placeholder tier used for quote: 45 kVA (S$350)   │
│                                                   │
│ Evidence from applicant:                          │
│  • SP Account PDF ..... ✓ uploaded (#dr-41)       │
│  • Main Breaker Photo . ⏳ requested              │
│   [ + Request documents ]  ← Phase 3 모달         │
│                                                   │
│            [  Confirm kVA  ]  ← primary           │
└───────────────────────────────────────────────────┘
```

CONFIRMED 상태:
```
┌── kVA · ✓ CONFIRMED ──────────────────────────────┐
│ 100 kVA · Confirmed by LEW Tan Ah Kow             │
│ on 2026-04-20 11:20 SGT                           │
│ Note: "SP bill shows 100 kVA contract."           │
│                                                   │
│ [ Override ]  ← ADMIN only, ghost 버튼            │
└───────────────────────────────────────────────────┘
```

- "Confirm kVA" 버튼은 UNKNOWN 시에만 primary로 노출. CONFIRMED 상태에서는 ADMIN에게만 `Override`(ghost, text-neutral) 버튼 노출. LEW에게는 아예 표시 안 함(AC-P1).
- UNKNOWN 섹션의 "Evidence from applicant": Phase 3 DocumentRequest의 SP_ACCOUNT_PDF / MAIN_BREAKER_PHOTO 진행 상태를 inline 요약. `+ Request documents` 버튼은 Phase 3 모달을 그대로 호출.

### 4.2 `KvaConfirmModal.tsx`

```
┌───────────────────────────────────────────────┐
│ Confirm kVA for APP-2026-000412         [×]  │
│ ────────────────────────────────────────────  │
│ Approved Load (kVA) *                         │
│ ┌───────────────────────────────────────┐     │
│ │  100 kVA                             ▾│     │
│ └───────────────────────────────────────┘     │
│                                               │
│ Verification note *  (min 10 chars)           │
│ ┌───────────────────────────────────────┐     │
│ │ SP bill dated 2026-03-12 shows 100    │     │
│ │ kVA contract account #…                │     │
│ └───────────────────────────────────────┘     │
│                                               │
│ ⓘ This will update the quote to S$650 and     │
│   notify the applicant.                       │
│                                               │
│        [ Cancel ]    [ Confirm & notify ]     │
└───────────────────────────────────────────────┘
```

- note 최소 10자 — 감사 로그 품질 확보(AC-A4 metadata).
- Submit 직전 확인 dialog(중첩 모달 지양, 대신 inline `ⓘ` 문구 + CTA 라벨로 명시): `Confirm & notify` 라벨 자체가 결과를 설명.
- 성공 시: 토스트 `kVA confirmed ✓ Applicant notified.` + 섹션이 CONFIRMED 상태로 자동 리렌더.
- ADMIN override 시: 동일 모달, 단 헤더에 `⚠ Overriding existing confirmation` 배너 + note 최소 20자로 상향. 결과적으로 PATCH `?force=true` 호출.
- 에러 매핑:
  - 409 `KVA_ALREADY_CONFIRMED` → LEW에게는 "This application was just confirmed by another user. Refresh to see the latest." (race condition 방어 문구).
  - 403 `FORBIDDEN` → 버튼 자체가 가드되지만, 혹시 통과 시 토스트 `You are not assigned to this application.`
  - 400 `INVALID_KVA_TIER` → 발생 불가(클라 select 제한), 방어적으로 inline error.

### 4.3 `/admin/applications` 목록 필터 (AC-P3)

- 기존 상태 필터 옆에 토글 체크박스 `☐ kVA pending only` 추가. 체크 시 `?kvaStatus=UNKNOWN` 쿼리 파라미터.
- 목록 행의 kVA 컬럼:
  - UNKNOWN → `— pending` (회색 이탤릭)
  - CONFIRMED → `100 kVA` + 작은 `LEW` 또는 `user` 태그(source 구분)
- LEW 대시보드 상단 카드 (선택, nice-to-have): `🟡 3 applications awaiting your kVA confirmation` → 클릭 시 위 필터된 목록으로 이동.

---

## 5. 마이크로카피 (EN)

| 위치 | 키 | 문구 |
|---|---|---|
| Dropdown option | `kva.optionUnknown` | `— I don't know — let LEW confirm me later` |
| Tip header | `kva.tip.header` | `Not sure about your kVA?` |
| Tip body (generic) | `kva.tip.generic` | `You can find it on your SP Group bill, or check the rating label on your main circuit breaker.` |
| Tip body (HDB) | `kva.tip.byType.HDB` | `HDB flats are typically 45 kVA.` |
| Tip body (SHOPHOUSE) | `kva.tip.byType.SHOPHOUSE` | `Shophouses are commonly 100 kVA.` |
| Tip body (FACTORY) | `kva.tip.byType.FACTORY` | `Factories often range from 300–500 kVA.` |
| Collapsible 1 | `kva.help.spBill` | `How to read your SP bill` |
| Collapsible 2 | `kva.help.breaker` | `Where is the main breaker nameplate?` |
| Price (UNKNOWN) primary | `kva.price.fromAmount` | `From S$350` |
| Price (UNKNOWN) caption | `kva.price.pendingCaption` | `Final price will be set after your LEW confirms the actual kVA. No payment yet.` |
| Pay CTA helper | `payment.blocked.kva` | `Your kVA needs to be confirmed by your LEW before payment.` |
| Payment page banner | `payment.redirect.kva` | `Payment will open once your LEW confirms the kVA.` |
| Applicant detail pill | `status.kvaPending` | `kVA pending LEW review` |
| Applicant info box | `kva.nextSteps` | `1. Your LEW reviews your application. 2. They may request an SP bill or breaker photo. 3. Once confirmed, you can pay.` |
| Confirmation banner | `kva.confirmedBanner` | `Your LEW confirmed {kva} kVA. Price updated to S${amount}. You can now proceed to payment.` |
| Confirmation banner (price up) | `kva.confirmedBanner.priceUp` | `If this doesn't match your expectation, you can cancel this application at no cost before paying.` |
| LEW modal title | `lew.kva.modalTitle` | `Confirm kVA for {appCode}` |
| LEW modal note label | `lew.kva.noteLabel` | `Verification note * (min 10 chars)` |
| LEW modal CTA | `lew.kva.cta` | `Confirm & notify` |
| LEW modal hint | `lew.kva.hint` | `This will update the quote to S${amount} and notify the applicant.` |
| LEW override banner | `lew.kva.overrideWarning` | `Overriding an existing confirmation — admin action will be logged.` |
| LEW success toast | `lew.kva.successToast` | `kVA confirmed ✓ Applicant notified.` |
| LEW race-condition error | `lew.kva.409` | `This application was just confirmed by another user. Refresh to see the latest.` |
| Notification title | `notif.kvaConfirmed.title` | `kVA confirmed — ready for payment` |
| Notification body | `notif.kvaConfirmed.body` | `Your LEW set the load to {kva} kVA. Final price: S${amount}.` |

---

## 6. 접근성 (a11y)

- 드롭다운: 네이티브 `<select>` 기반. "I don't know" 옵션은 `<option>` 라벨에 em-dash로 시각 구분하되, 스크린리더는 full text `"I don't know, let LEW confirm me later"`로 읽히게 **콤마 치환** (em-dash는 TTS가 "dash"로 읽는 엔진 있음).
- Tip 박스: `<aside role="note" aria-labelledby="kva-tip-title">`. 드롭다운의 `aria-describedby="kva-tip-title"`로 연결 → 포커스 시 스크린리더가 tip 존재를 안내.
- 접이식 상세: `<button aria-expanded>` + `<div role="region">` 패턴. 키보드 Tab/Enter/Space 모두 동작.
- UNKNOWN 선택 시 가격 카드 변화: 카드 컨테이너에 `aria-live="polite"` 지정 → "From S$350. Final price will be set after your LEW confirms." 읽힘.
- LEW 확정 성공 토스트: `aria-live="polite"`, focus 이동은 없음(작업 흐름 방해 방지).
- 색상: UNKNOWN pill(amber)은 색만으로 정보 전달 금지 — 반드시 `kVA pending` 텍스트 동반. 색각이상 대응.
- 모바일 터치 타겟: 드롭다운·Tip의 접이식 버튼은 최소 44×44px.

---

## 7. 에지 케이스 UX

| # | 상황 | 동작 |
|---|---|---|
| E1 | "I don't know" 선택 후 Back → 다시 Step 2 | 선택 유지(`kvaStatus='UNKNOWN'`). 드래프트 재방문 시 드롭다운도 UNKNOWN 유지. |
| E2 | tier 선택 후 UNKNOWN으로 변경 | 이전 tier 값은 버리고 `selectedKva=45`(placeholder), 가격 분석표 회색화. 이전 선택 복원은 제공하지 않음(혼란 방지). |
| E3 | 신청 제출 후 kVA 확정 전 사용자가 신청 내용 수정 시도 | 기존 Phase 1 정책 그대로: 관리자 assign 이후 edit 차단. kVA 관련 별도 편집 UI는 Phase 5 범위 외. |
| E4 | LEW 확정 직후 가격 상승 | 배너에 new 가격 + "cancel at no cost before paying" 명시(취소는 기존 soft delete 흐름). 환불 로직은 결제 전이라 불필요. |
| E5 | 동시에 두 명의 LEW가 확정 시도 | 뒤 요청은 409 `KVA_ALREADY_CONFIRMED`. UI에 race-condition 토스트 + 섹션 자동 refetch. |
| E6 | Corporate + UNKNOWN 조합 (Phase 2 법인 JIT) | 두 플로우 독립. LEW는 Phase 3 DocumentRequest 배치로 회사정보·kVA 근거를 **한 번에** 요청. UI 상호작용 충돌 없음. |
| E7 | 신청자가 Phase 3 DocumentRequest로 SP bill 업로드 완료했는데 LEW가 확정 지연 | 신청자 상세의 info box 3단계 중 2단계 `✓` 처리 → "Waiting for your LEW to confirm"으로 문구 미세 조정. |
| E8 | placeholder quote 350이 실제 확정가보다 낮아 사용자가 오인 | Tip 박스에 항상 "From" 표기 + 확정 시 배너 금액 변경 사실 명시. |

---

## 8. 반응형 (Mobile < 640px)

- Step 2 드롭다운: full-width, Tip 박스는 드롭다운 **아래** 수직 배치(옆 배치 금지).
- Tip 박스 접이식 상세는 기본 접힘, tap으로 full-width 확장. 썸네일 이미지 있을 경우 max 120px 높이.
- 가격 카드: full-width 스택, "From S$350" 폰트는 desktop 대비 한 단계 작게.
- 가격 분석표(회색화): 모바일에선 hidden 처리(좁은 화면에서 회색 표가 더 혼란) — AC 외 결정, 컴포넌트 prop `showReferenceTable`.
- LEW `AdminApplicationDetailPage`: kVA 섹션은 카드 stack, `[ Confirm kVA ]` 버튼 full-width sticky(하단). 모달은 full-screen sheet로 전환.
- KvaConfirmModal 모바일: full-screen, note textarea 자동 focus, 키보드 올라올 때 submit 버튼 sticky.

---

## 9. Phase 1~4 연속성 체크

- **Phase 1 InfoBox 톤**: Tip 박스·info box·confirmation banner 모두 Phase 1 `InfoBox` 컴포넌트 재사용 (border-l-4, primary/5 배경). 신규 시각 언어 도입 없음.
- **Phase 2 법인 JIT 모달**: Corporate 신청자 Step 2에서 UNKNOWN 선택 → Phase 2 JIT 모달 트리거는 **별도 Step**에서 발생하므로 상호 간섭 없음. 두 deferral을 모두 가진 신청은 LEW 대시보드에서 `🟡 kVA pending` + `📄 company docs needed` 두 배지 동시 노출.
- **Phase 3 DocumentRequestCard**: UNKNOWN 신청 상세의 "Evidence from applicant" 블록은 Phase 3 카드 컴포넌트의 축약형(readonly)을 재사용. 새 카드 스타일 도입 금지.
- **Phase 3 배지 시스템**: `kVA pending` pill은 Phase 3 `pending docs` pill과 동일 크기·패딩·radius. 색상만 amber로 차별화.
- **Phase 4 영어화**: Phase 5 모든 마이크로카피는 영어 only. ko 번역 키는 Phase 4 i18n 리소스 파일에 추가하되 현재 노출은 EN.

---

## 10. Designer 전달 — 시각 요청 사항

1. **"I don't know" 드롭다운 옵션**: 이탤릭 + `text-neutral-500`, 앞에 em-dash 2개. divider로 실제 tier와 분리. 과하지 않게 — 디세이블처럼 보이면 안 됨(선택 가능한 정당한 옵션).
2. **UNKNOWN pill 색상**: `amber-100 / amber-800` + 앞에 원형 점(●) 또는 시계(⏱) 아이콘. 삼각 경고 아이콘(⚠) 금지 — "대기 중"이지 "문제"가 아님.
3. **가격 카드 (UNKNOWN)**: primary 톤 유지. `From S$350`의 "From"은 ~80% opacity로 살짝 작게, 금액은 기존 폰트 크기. 보조 캡션은 `text-sm text-neutral-600`.
4. **가격 분석표 회색화**: `opacity-50`, `pointer-events-none`, 좌상단 작은 배지 `"Will activate after LEW confirms"`.
5. **LEW "Confirm kVA" 버튼**: primary 색. 상세 페이지에서 눈에 잘 띄는 위치(섹션 하단 오른쪽). UNKNOWN 상태에서만 등장.
6. **`Override` 버튼 (ADMIN)**: ghost/text 버튼, 작게. 일반 LEW에겐 아예 렌더되지 않음.
7. **Tip 박스 썸네일**: SP 고지서 샘플 / 메인 브레이커 사진 2장. 저작권 문제없는 자체 촬영 이미지 필요(asset 요청 대상).
8. **Confirmation banner (신청자 뷰)**: dismiss 가능한 primary/emerald 톤. 24h TTL, dismiss 시 즉시 소멸 + `localStorage`로 재노출 방지.

---

## 11. 개발자 Handoff 요약 (UX 관점)

- `KvaTipBox.tsx`: props `{ buildingType?: BuildingType }`. buildingType 없으면 generic만, 있으면 generic + byType 한 줄. 접이식 상세 2개는 상수.
- `NewApplicationPage` Step 2: `kvaStatus` state 추가(`'UNKNOWN' | 'CONFIRMED'`). 드롭다운 value가 `-1`(또는 별도 sentinel)일 때 UNKNOWN, 그 외 숫자 tier면 CONFIRMED. payload에 항상 `kvaStatus` 포함.
- `ApplicationDetailPage` (신청자): kvaStatus에 따라 pill + info box + 배너 분기. 확정 알림 수신 시 SWR/React Query invalidate.
- `AdminApplicationDetailPage`: kVA 섹션 컴포넌트 분리(`KvaSection.tsx`). UNKNOWN/CONFIRMED 두 상태 + ADMIN/LEW role 분기.
- `KvaConfirmModal.tsx`: Phase 3 모달 베이스 재사용(동일 너비/패딩/close 동작).
- `/admin/applications` 리스트: 기존 필터바에 토글 1개 추가. 목록 행의 kVA 컬럼 렌더러 교체.
- 알림: 기존 `NotificationBell` 드롭다운에서 `KVA_CONFIRMED` 타입 아이콘/라우팅만 추가.

---

## 12. 테스트 체크리스트 (UX 검증)

- [ ] "I don't know" 선택 → 드롭다운 트리거도 이탤릭으로 표시
- [ ] buildingType 변경 시 Tip 박스 byType 한 줄이 즉시 갱신
- [ ] UNKNOWN 선택 시 가격 분석표 opacity-50, pointer-events-none
- [ ] UNKNOWN 상태로 제출 → 신청 상세 pill `kVA pending LEW review`
- [ ] 결제 버튼 disabled + helper text 항상 표시 (tooltip 아님)
- [ ] 결제 URL 직접 접근 시 신청 상세로 redirect + banner
- [ ] LEW 확정 모달 note 10자 미만이면 submit disabled
- [ ] 확정 성공 → 토스트 + 섹션 자동 전환 + 배지 변경
- [ ] 신청자 in-app notification 수신 → 클릭 시 해당 섹션 anchor scroll
- [ ] 가격 상승 시 배너에 금액 + cancel 안내
- [ ] ADMIN Override 버튼은 ADMIN 세션에서만 렌더
- [ ] 모바일: Tip 박스 기본 접힘, 드롭다운 아래 수직 배치
- [ ] 스크린리더: 드롭다운에 aria-describedby로 Tip 연결
- [ ] 색각이상 시뮬레이터: amber pill을 텍스트만으로도 식별 가능

---

**다음 산출물**: `03-implementation-plan.md` (개발자 태스크 분해 + 일정).
