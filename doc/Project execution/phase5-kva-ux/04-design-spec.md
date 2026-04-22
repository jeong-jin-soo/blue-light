# Phase 5 — 시각 디자인 스펙 (kVA UX)

**작성일**: 2026-04-17
**근거**: `01-spec.md` (AC-U1~U6, S1~S4, A1~A4, P1~P3), `02-ux-design.md`
**원칙**: Phase 1~4 토큰·컴포넌트 100% 재사용. 신규 Tailwind 토큰 0. 신규 `ui/` 원시 컴포넌트 0 — 기존 `Select`, `InfoBox`, `Modal`, `Badge`, `Card`, `Button`, `Textarea`, `Toast`를 조립해 kVA 도메인 컴포넌트만 추가한다. 톤은 "경고가 아니라 안심/대기" — 삼각 경고 아이콘 대신 시계/점 아이콘 사용.

---

## 1. 재사용 컴포넌트 실측

| 컴포넌트 | 경로 | Phase 5 용도 |
|---|---|---|
| `Select` | `components/ui/Select.tsx` | Step 2 kVA 드롭다운. 네이티브 `<select>` 기반이라 `<option>` 내부 커스텀 CSS 불가 → em-dash/italic은 **라벨 문자열**과 **트리거 텍스트 색** 두 곳에서 표현 |
| `InfoBox` | `components/ui/InfoBox.tsx` | Tip 박스, 신청자 "What happens next", 확정 후 배너, LEW 모달 hint. `variant="info"` 단일 재사용 |
| `Badge` | `components/ui/Badge.tsx` | `kVA pending` pill (`variant="warning" dot`), `kVA confirmed` (`variant="success" dot`), source 태그 (`variant="gray"`) |
| `Modal`(+Header/Body/Footer) | `components/ui/Modal.tsx` | `KvaConfirmModal` (size=`md`, `ariaLabelledBy="kva-confirm-title"`) |
| `Card`/`CardHeader` | `components/ui/Card.tsx` | AdminApplicationDetailPage kVA 섹션 컨테이너 |
| `Button` | 〃 | "Confirm kVA" primary, "Override" ghost, "Pay now" disabled |
| `Textarea` | 〃 | note 입력, min 10(ADMIN override 시 20) + 카운터 |
| `Toast` | 〃 | `kVA confirmed ✓ Applicant notified.` |
| `ConfirmDialog` | 〃 | 사용 안 함(모달 내 `Confirm & notify` CTA 라벨로 결과 명시) |

**신규 `ui/` 컴포넌트 0개.** 도메인 컴포넌트는 §14.

---

## 2. 토큰 매핑 (신규 토큰 0)

`index.css` 실측: `--color-warning-*`는 이미 **amber 계열**(50=`#fffbeb`, 500=`#f59e0b`, 700=`#b45309`). Phase 3 pending 배지와 동일 팔레트이므로 Phase 5의 "pending/UNKNOWN"도 `warning-*` 그대로 쓴다. **별도 amber 토큰 추가 금지.**

| 용도 | Tailwind 클래스 |
|---|---|
| UNKNOWN pill ("kVA pending") | `Badge variant="warning" dot` (`bg-warning-50 text-warning-700`) |
| UNKNOWN Card 배경 (LEW 관리자) | `bg-warning-50 border border-warning-500/40` + CardHeader 제목 `text-warning-700` |
| CONFIRMED pill | `Badge variant="success" dot` + inline check SVG `text-success-600` |
| Tip 박스 | `InfoBox variant="info"` (`bg-info-50 border-info-500/30`) — 경고 금지 |
| 가격 카드 (UNKNOWN) | `bg-primary-50 border border-primary/30 rounded-lg` — primary 톤 유지 |
| 가격 분석표 회색화 | 기존 테이블에 `opacity-50 pointer-events-none` + 좌상단 `Badge variant="gray"` `Will activate after LEW confirms` |
| 결제 차단 helper | `text-xs text-warning-700 flex items-start gap-1.5` (info 아이콘 병기) |
| ADMIN override 경고 배너 | `bg-warning-50 border-l-4 border-warning-500 text-warning-700 px-3 py-2 rounded` |
| 확정 후 one-time 배너 | `InfoBox variant="info"` (가격 상승 시 primary 톤 유지, 경고 금지) |

색 단독 전달 금지: 모든 UNKNOWN 표시는 **pill 텍스트 "kVA pending" + 시계 SVG + warning 배경** 3중으로 전달(색각이상 AA).

---

## 3. 인라인 SVG 아이콘 (lucide-react 미사용)

```tsx
// clock (UNKNOWN pending) — Badge dot 대체 또는 병기
const ClockIcon = (p: {className?: string}) => (
  <svg className={p.className} width="12" height="12" viewBox="0 0 24 24" fill="none"
       stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path strokeLinecap="round" d="M12 7v5l3 2" />
  </svg>
);

// check (CONFIRMED)
const CheckIcon = (p: {className?: string}) => (
  <svg className={p.className} width="14" height="14" viewBox="0 0 24 24" fill="none"
       stroke="currentColor" strokeWidth="2.5" aria-hidden="true">
    <path strokeLinecap="round" strokeLinejoin="round" d="M5 12l5 5L20 7" />
  </svg>
);

// lightbulb (Tip 박스 — InfoBox의 icon prop으로 주입)
const BulbIcon = (p: {className?: string}) => (
  <svg className={p.className} width="20" height="20" viewBox="0 0 24 24" fill="none"
       stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <path strokeLinecap="round" strokeLinejoin="round"
          d="M9 18h6m-5 3h4M12 3a6 6 0 00-4 10.5c.8.8 1.3 1.9 1.5 3h5c.2-1.1.7-2.2 1.5-3A6 6 0 0012 3z" />
  </svg>
);

// warning triangle (ADMIN override 전용 — UNKNOWN에는 사용 금지)
const WarnIcon = (p: {className?: string}) => (
  <svg className={p.className} width="16" height="16" viewBox="0 0 24 24" fill="none"
       stroke="currentColor" strokeWidth="2" aria-hidden="true">
    <path strokeLinecap="round" strokeLinejoin="round"
          d="M12 9v4m0 4h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" />
  </svg>
);
```

---

## 4. 드롭다운 "I don't know" 옵션 (AC-U1, AC-U2)

네이티브 `<select>`는 옵션별 CSS 불가 → **문자열 선두에 em-dash 2개**(`— `)로 시각 구분 + **`disabled` 속성은 절대 붙이지 않음**(선택 가능한 정당한 옵션). "divider"는 옵션 value `''` + 라벨 `──────────` + `disabled` 비주얼 row 1개로 표현.

```tsx
// NewApplicationPage Step 2
const UNKNOWN = '-1'; // sentinel
const options = [
  { value: UNKNOWN, label: "— I don't know — let LEW confirm me later" },
  { value: '__divider', label: '──────────', disabled: true }, // 시각 분리
  { value: '45',  label: '45 kVA  (HDB / small unit)' },
  { value: '100', label: '100 kVA (shophouse / small F&B)' },
  { value: '200', label: '200 kVA' },
  { value: '300', label: '300 kVA' },
  { value: '500', label: '500 kVA (factory / industrial)' },
];

<Select
  label="Approved Load (kVA)"
  required
  value={kva}
  onChange={handleChange}
  options={options}
  placeholder="Select kVA tier"
  aria-describedby="kva-tip-title"
  className={kva === UNKNOWN ? 'italic text-neutral-500' : ''}
/>
```

- 기존 `Select`는 `options` 타입에 `disabled`가 없으므로 **한 줄 확장**(`disabled?: boolean`)을 허용하고 `<option disabled>`로 렌더. Phase 1~4 `SelectOption` 소비자에 영향 없음(optional prop).
- **트리거 텍스트 색**: UNKNOWN 선택 시 `Select`의 `className`에 `italic text-neutral-500`을 전달해 선택 상태도 "확정되지 않음"을 즉시 인지시킴.
- 스크린리더: em-dash 2개는 TTS가 "dash dash"로 읽을 수 있어 `aria-label`은 옵션 렌더에서 쓸 수 없다. 대신 Tip 박스의 첫 줄(`"Not sure? Let your LEW confirm it."`)을 `aria-describedby`로 연결해 맥락을 보충.

---

## 5. Tip 박스 (AC-U4~U6) — `KvaTipBox.tsx`

항상 노출(접이식 컨테이너 아님). 내부 "How to read SP bill / Where is the breaker nameplate?" 2개만 `<details>` 접이식.

```tsx
// components/applications/KvaTipBox.tsx
<InfoBox
  variant="info"
  title="Not sure about your kVA?"
  icon={<BulbIcon className="w-5 h-5 flex-shrink-0 text-info-600 mt-0.5" />}
  className="mt-3"
>
  <p id="kva-tip-title">
    You can find it on your SP Group bill, or check the rating label on your main circuit breaker.
  </p>
  {byTypeLine && <p className="mt-1 font-medium">{byTypeLine}</p>}
  <details className="mt-2 group">
    <summary className="cursor-pointer text-[13px] font-medium hover:underline
                        min-h-[44px] flex items-center">
      ▸ How to read your SP bill
    </summary>
    <div className="mt-1 pl-3 text-[12px] leading-relaxed">…</div>
  </details>
  <details className="mt-1 group">
    <summary className="cursor-pointer text-[13px] font-medium hover:underline min-h-[44px] flex items-center">
      ▸ Where is the main breaker nameplate?
    </summary>
    <div className="mt-1 pl-3 text-[12px] leading-relaxed">…</div>
  </details>
</InfoBox>
```

- `byTypeLine`: HDB/CONDO/LANDED/SHOPHOUSE/FACTORY/OFFICE 매핑(02-ux §1.4 표). 미선택 시 줄 자체 숨김.
- **절대 드롭다운을 pre-select하지 않음**(AC-U4) — 문자열만 표기.
- 모바일: `<details>` 기본 접힘 유지 + `summary` `min-h-[44px]`로 터치 타겟 확보.

---

## 6. UNKNOWN Pill — "kVA pending" (§2.2)

Phase 3 `pending docs` 배지와 **동일 규격**(`Badge` 컴포넌트 그대로, `rounded-full text-xs px-2.5 py-0.5`). 색상만 warning, 아이콘은 시계.

```tsx
// 대시보드 row · 신청자 상세 헤더 공용
<Badge variant="warning" dot>
  <ClockIcon className="w-3 h-3" /> kVA pending
</Badge>
```

- 기본 `dot`은 유지(1.5px 원) + 시계 SVG 병기 — dot 색상/아이콘/텍스트 3중으로 상태 전달.
- 확정 배지:

```tsx
<Badge variant="success" dot>
  <CheckIcon className="w-3 h-3" /> kVA confirmed · 100 kVA
</Badge>
```

- 신청자가 스스로 tier 선택한 CONFIRMED 기본 상태는 **배지 생략**(과시각화 방지, AC-S2).
- 관리자 목록 행의 source 구분 태그: `<Badge variant="gray">LEW</Badge>` / `<Badge variant="gray">user</Badge>`.

---

## 7. 가격 표시 — "From S$350" (AC-U2)

UNKNOWN 상태 전용 카드. Phase 1 가격 영역 컴포넌트를 확장하거나 래퍼로 감싼다.

```tsx
<div className="bg-primary-50 border border-primary/30 rounded-lg p-5">
  <div className="flex items-baseline gap-2">
    <span className="text-sm text-gray-500 font-medium opacity-80">From</span>
    <span className="text-3xl font-semibold text-primary
                     border-b border-dashed border-primary/40 pb-0.5">
      S$350
    </span>
  </div>
  <p className="text-sm text-gray-600 mt-2">
    Final price will be set after your LEW confirms the actual kVA. No payment yet.
  </p>
</div>
```

- "From" prefix는 작고 muted(opacity-80 + text-gray-500).
- 금액 하단의 **dashed underline** `border-b border-dashed border-primary/40 pb-0.5`로 "잠정" 의미를 시각 부호화(color-blind safe).
- `aria-live="polite"` 컨테이너 안에서 UNKNOWN↔CONFIRMED 전환 시 읽히도록.

### 가격 분석표 회색화

```tsx
<div className="relative mt-4" aria-disabled={kvaStatus === 'UNKNOWN'}>
  {kvaStatus === 'UNKNOWN' && (
    <Badge variant="gray" className="absolute -top-2 left-3 bg-white shadow-sm">
      Will activate after LEW confirms
    </Badge>
  )}
  <table className={kvaStatus === 'UNKNOWN' ? 'opacity-50 pointer-events-none select-none' : ''}>
    {/* 기존 tier 표 */}
  </table>
</div>
```

- hover 효과 제거(`pointer-events-none`), 선택 방지(`select-none`).
- 모바일(<640px)에서는 `hidden sm:block` — 좁은 화면에서 회색 표는 혼란 가중이므로 숨김(02-ux §8).

---

## 8. 결제 차단 UI (§2.1)

"Pay now" 버튼은 `disabled`, helper text는 **hover 툴팁 금지 · 항상 노출**(인식 우선).

```tsx
<Button disabled className="opacity-60 cursor-not-allowed">Pay now</Button>
<p className="mt-1.5 text-xs text-warning-700 flex items-start gap-1.5 max-w-prose">
  <ClockIcon className="w-3.5 h-3.5 mt-0.5 flex-shrink-0" />
  Your kVA needs to be confirmed by your LEW before payment.
</p>
```

- 톤은 warning(경고 아닌 대기 안내) — Phase 3 helper text와 동일 팔레트.
- 결제 URL 직접 접근 시 신청 상세로 redirect + `InfoBox variant="info"` 배너: `"Payment will open once your LEW confirms the kVA."`

---

## 9. 신청자 뷰 — kVA 섹션 (§3)

`ApplicationDetailPage`의 기존 "Electrical Load" 섹션 확장:

```tsx
<Card>
  <CardHeader
    title="Electrical Load"
    action={kvaStatus === 'UNKNOWN'
      ? <Badge variant="warning" dot><ClockIcon className="w-3 h-3" /> kVA pending LEW review</Badge>
      : <Badge variant="success" dot><CheckIcon className="w-3 h-3" /> kVA confirmed · {kva} kVA</Badge>
    }
  />
  {kvaStatus === 'UNKNOWN' ? (
    <InfoBox variant="info" title="What happens next">
      <ol className="list-decimal pl-4 space-y-1">
        <li>Your LEW reviews your application.</li>
        <li>They may request an SP bill or breaker photo via this portal.</li>
        <li>Once confirmed, you'll see the final price and be able to pay.</li>
      </ol>
    </InfoBox>
  ) : (
    <div className="text-sm text-gray-700">Load: <strong>{kva} kVA</strong></div>
  )}
</Card>
```

확정 직후 one-time 배너(24h TTL, localStorage `kva-banner-dismissed-{appId}`):

```tsx
<InfoBox variant="info" title="Your LEW confirmed 100 kVA">
  Price updated to <strong>S$650</strong>. You can now proceed to payment.
  {priceIncreased && (
    <p className="mt-1">
      If this doesn't match your expectation, you can cancel this application at no cost before paying.
    </p>
  )}
</InfoBox>
```

---

## 10. LEW 확정 섹션 (§4.1) — `KvaSection.tsx`

`AdminApplicationDetailPage` 내 Card. UNKNOWN 시 `bg-warning-50 + border-warning-500/40`, CONFIRMED 시 기본 Card.

```tsx
// UNKNOWN
<Card className="bg-warning-50 border border-warning-500/40" padding="md">
  <CardHeader
    title="kVA confirmation required"
    description="Applicant deferred this to LEW."
    action={<Badge variant="warning" dot><ClockIcon className="w-3 h-3" /> UNKNOWN</Badge>}
  />
  <dl className="text-sm text-gray-700 space-y-1 mb-4">
    <div className="flex gap-2"><dt className="text-gray-500">Placeholder tier:</dt><dd>45 kVA (S$350)</dd></div>
  </dl>

  <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Evidence from applicant</p>
  {/* Phase 3 DocumentRequestCard readonly 재사용 */}
  <DocumentRequestSummary codes={['SP_ACCOUNT_PDF','MAIN_BREAKER_PHOTO']} />

  <div className="flex justify-end gap-2 mt-4">
    <Button variant="outline" size="sm" onClick={openDocRequest}>+ Request documents</Button>
    <Button size="sm" onClick={openConfirmModal}>Confirm kVA</Button>
  </div>
</Card>

// CONFIRMED
<Card padding="md">
  <CardHeader
    title="kVA"
    action={<Badge variant="success" dot><CheckIcon className="w-3 h-3" /> CONFIRMED</Badge>}
  />
  <p className="text-sm text-gray-700">
    <strong>{kva} kVA</strong> · Confirmed by LEW {confirmerName}
    <span className="text-gray-500"> on {fmt(confirmedAt)}</span>
  </p>
  {note && (
    <blockquote className="mt-2 border-l-2 border-warning-500 pl-3 text-sm text-gray-700 italic">
      "{note}"
    </blockquote>
  )}
  {isAdmin && (
    <div className="flex justify-end mt-3">
      <Button variant="ghost" size="sm" onClick={openOverrideModal}>Override</Button>
    </div>
  )}
</Card>
```

- `Override` 버튼은 ADMIN 세션에서만 렌더(서버 403 방어는 AC-P1의 UI 가드).
- LEW에게 CONFIRMED 상태에서는 어떤 액션 버튼도 노출하지 않음.

---

## 11. LEW 확정 모달 — `KvaConfirmModal.tsx` (§4.2)

```tsx
<Modal isOpen={open} onClose={close} size="md" ariaLabelledBy="kva-confirm-title">
  <ModalHeader onClose={close}>
    <h3 id="kva-confirm-title" className="text-lg font-semibold text-gray-800">
      Confirm kVA for {appCode}
    </h3>
  </ModalHeader>
  <ModalBody>
    {isOverride && (
      <div role="alert"
           className="mb-4 flex items-start gap-2 bg-warning-50 border-l-4 border-warning-500 text-warning-700 px-3 py-2 rounded">
        <WarnIcon className="w-4 h-4 mt-0.5 flex-shrink-0" />
        <p className="text-sm">
          <strong>Overriding an existing confirmation</strong> — admin action will be logged.
        </p>
      </div>
    )}
    <Select
      label="Approved Load (kVA)"
      required
      value={tier}
      onChange={(e) => setTier(e.target.value)}
      options={[
        { value: '45',  label: '45 kVA' },
        { value: '100', label: '100 kVA' },
        { value: '200', label: '200 kVA' },
        { value: '300', label: '300 kVA' },
        { value: '500', label: '500 kVA' },
      ]}
      placeholder="Select tier"
    />
    <div className="mt-4">
      <Textarea
        label={`Verification note * (min ${minNote} chars)`}
        rows={4}
        maxLength={500}
        value={note}
        onChange={(e) => setNote(e.target.value)}
        error={note.length > 0 && note.length < minNote ? `최소 ${minNote}자 입력` : undefined}
        aria-describedby="kva-note-hint"
      />
      <p id="kva-note-hint" className="text-xs text-gray-500 mt-1">
        {note.length} / 500 · recorded in audit log
      </p>
    </div>
    <InfoBox variant="info" className="mt-4">
      This will update the quote to <strong>S${newAmount}</strong> and notify the applicant.
    </InfoBox>
  </ModalBody>
  <ModalFooter>
    <Button variant="outline" size="sm" onClick={close} disabled={submitting}>Cancel</Button>
    <Button size="sm" onClick={submit} loading={submitting}
            disabled={!tier || note.length < minNote}>
      Confirm &amp; notify
    </Button>
  </ModalFooter>
</Modal>
```

- `minNote`: 일반 확정 = 10, ADMIN override = 20 (`isOverride`에 따라 분기).
- 성공: `Toast`로 `kVA confirmed ✓ Applicant notified.` + 모달 닫힘 + `KvaSection` 자동 리페치.
- 409 race: inline `text-xs text-error-600` + `This application was just confirmed by another user. Refresh to see the latest.`
- 모달은 `Modal` 기본 `max-h-[90vh] overflow-y-auto` + `ariaLabelledBy`로 a11y 완료.

---

## 12. 목록/대시보드 (§4.3)

`/admin/applications` 필터바에 토글 체크박스 추가:

```tsx
<label className="flex items-center gap-2 text-sm text-gray-700 cursor-pointer">
  <input type="checkbox" className="accent-primary"
         checked={kvaFilter === 'UNKNOWN'}
         onChange={(e) => setKvaFilter(e.target.checked ? 'UNKNOWN' : undefined)} />
  kVA pending only
</label>
```

목록 행의 kVA 컬럼:

```tsx
{row.kvaStatus === 'UNKNOWN'
  ? <span className="italic text-gray-500">— pending</span>
  : <span>{row.selectedKva} kVA {row.kvaSource === 'LEW_VERIFIED'
       ? <Badge variant="gray" className="ml-1">LEW</Badge> : null}</span>}
```

신청자 대시보드 행은 기존 상태 배지 옆에 `Badge variant="warning" dot` "kVA pending"만 추가(Phase 3 `{n} 대기` 배지와 동일 라인/간격).

---

## 13. 반응형

| 요소 | <640px | ≥640px |
|---|---|---|
| Step 2 드롭다운 + Tip | 수직 stack, Tip 박스 full-width, `details` 기본 접힘 | 동일 수직 stack(옆 배치 금지). Tip `max-w-prose` |
| 가격 카드 | full-width, "S$350" 폰트 `text-2xl` | `text-3xl` |
| 가격 분석표 | `hidden` (UNKNOWN 시) | `opacity-50` 회색화 표시 |
| LEW kVA Card | 전폭, "Confirm kVA" full-width sticky bottom | 우측 정렬 inline |
| KvaConfirmModal | `Modal` centered 유지, `ModalFooter` `flex-col sm:flex-row`, Submit full-width | centered `max-w-lg` |
| 신청자 상세 `InfoBox` | full-width | `max-w-prose` |

`Modal`은 Phase 3와 동일하게 centered 유지 — 모바일 sheet variant **추가하지 않음**(Phase 4 이월과 동일 정책).

---

## 14. 신규 도메인 컴포넌트 (ui/ 원시 컴포넌트 아님)

| 컴포넌트 | 경로 | 비고 |
|---|---|---|
| `KvaTipBox` | `components/applications/KvaTipBox.tsx` | InfoBox 조립, props `{ buildingType?: BuildingType }` |
| `KvaPendingBadge` | `components/applications/KvaPendingBadge.tsx` | `Badge variant="warning" dot` + ClockIcon 래퍼 (대시보드·상세 공용) |
| `KvaSection` | `components/admin/KvaSection.tsx` | LEW/ADMIN 상세 페이지 Card (UNKNOWN/CONFIRMED 분기) |
| `KvaConfirmModal` | `components/admin/KvaConfirmModal.tsx` | Modal + Select + Textarea 조립. `isOverride` prop으로 min 10/20 분기 |
| `KvaPriceCard` | `components/applications/KvaPriceCard.tsx` | UNKNOWN "From S$350" + CONFIRMED `S${amount}` 분기 렌더 |

※ `DocumentRequestSummary`(UNKNOWN 섹션의 readonly 리스트)는 Phase 3 `DocumentRequestCard`를 `readonly` prop으로 재사용 — 신규 컴포넌트 아님.

---

## 15. 접근성 체크

- `Select` 트리거에 `aria-describedby="kva-tip-title"` — 포커스 시 Tip 존재 안내.
- UNKNOWN 선택 시 가격 카드 컨테이너 `aria-live="polite"` → "From S$350. Final price will be set after your LEW confirms." 자동 낭독.
- Pill: 색(warning) + 텍스트("kVA pending") + SVG(clock) 3중 → 색각이상 AA.
- `KvaConfirmModal`: `ariaLabelledBy` 사용, Textarea `aria-describedby="kva-note-hint"`로 카운터·감사 고지 연결.
- 버튼 focus ring: `focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2` (Phase 1 규약).
- 모바일 터치 타겟: `<details>` summary `min-h-[44px]`, 체크박스 toggle `min-h-[44px]`.

---

## 16. Phase 1~4 연속성 체크

| Phase 1~4 | Phase 5 |
|---|---|
| `InfoBox` (bg-info-50, border-l-4 톤) | Tip 박스·신청자 안내·확정 배너·모달 hint **동일 컴포넌트 재사용**. 신규 시각 언어 0. |
| Phase 2 법인 JIT 모달 | 두 deferral이 동시 발생하면 LEW 대시보드에 `kVA pending` + `company docs needed` 배지를 **같은 badge 규격**으로 병기. |
| Phase 3 `pending docs` pill (`warning` variant + dot) | `kVA pending` pill을 **동일 `Badge variant="warning" dot`**로 구현 — 크기/패딩/radius 완전 동일, 아이콘만 clock으로 차별화. |
| Phase 3 `DocumentRequestCard` | UNKNOWN 섹션의 "Evidence from applicant"가 readonly 분기로 재사용 — 신규 카드 0. |
| Phase 4 영어화 | 모든 마이크로카피 EN only (02-ux §5), i18n 키만 ko 리소스에 추가. |

---

## 17. AC 커버리지

| AC | 위치 |
|---|---|
| U1 | §4 드롭다운 옵션 구성(em-dash + divider + tier) |
| U2 | §7 `From S$350` + 보조 문구, `aria-live` |
| U3 | §4 tier 선택 시 `italic` 미적용 + §7 `S$650` primary |
| U4, U5 | §5 KvaTipBox `byTypeLine` 분기 (pre-select 금지) |
| U6 | §5 `<details>` 2개, 모바일 기본 접힘 |
| S1~S4 | §8 결제 차단 helper (UI 가드) |
| A1~A4 | §10 KvaSection + §11 KvaConfirmModal |
| P1 | §10 ADMIN `Override` ghost 버튼 + §11 `isOverride` min 20자 + 경고 배너 |
| P2 | §10 `+ Request documents`에서 Phase 3 모달 그대로 호출 |
| P3 | §12 목록 필터 체크박스 + row 렌더러 |
