# Phase 3 — 시각 디자인 스펙

**작성일**: 2026-04-17
**근거**: `01-spec.md` (AC-R1~R6, S1~S6, LU1~LU4, AU1~AU4, N1~N3), `02-ux-design.md`
**원칙**: Phase 1/2 토큰·컴포넌트 100% 재사용. 신규 ui 원시 컴포넌트/토큰 도입 금지. Phase 2에서 skeleton으로만 노출하던 `DocumentRequestCard` 4 variant를 프로덕션 경로로 승격하고, LEW 요청/검토 전용 도메인 컴포넌트만 신규로 조립한다.

---

## 1. 재사용 컴포넌트 실측

| 컴포넌트 | 경로 | Phase 3 용도 |
|---|---|---|
| `Modal`(+Header/Body/Footer) | `components/ui/Modal.tsx` | DocumentRequestModal(size=`md` max-w-lg), RejectReasonModal(size=`sm`). `max-h-[90vh] overflow-y-auto` 내장 — 긴 체크리스트도 내부 스크롤로 수용 (신규 sheet variant 추가 없음) |
| `ConfirmDialog` | `components/ui/ConfirmDialog.tsx` | Cancel Request 확인. `variant="danger"`는 red, 취소는 `primary`로 |
| `Badge` | `components/ui/Badge.tsx` | status 배지 (`warning`/`info`/`success`/`error` 4 variant — 기존 토큰만) |
| `Button` | `components/ui/Button.tsx` | Approve(primary,sm), Reject(outline+text-error-700,sm), Cancel Request(ghost) |
| `Input`/`Textarea` | 〃 | OTHER customLabel, row memo, RejectReason |
| `InfoBox` | 〃 | Phase 2 자발적 업로드 안내 유지(배너 아래) |
| `Toast` | 〃 | "Requested N ✓", "Uploaded · LEW will be notified" 등 |
| `NotificationBell` | `components/common/NotificationBell.tsx` | **실측 확인**: `unreadCount` + `99+` pill 이미 구현. Phase 3 변경은 **폴링 주기만 60s→30s** (색 `bg-red-500` 유지 — 신규 토큰 없음) |

**신규 ui 컴포넌트 0개.** 모두 `components/documents/` 또는 `components/admin/` 도메인에 조립.

---

## 2. 신규 도메인 컴포넌트

| 컴포넌트 | 경로 | 비고 |
|---|---|---|
| `DocumentRequestModal` | `components/admin/DocumentRequestModal.tsx` | 체크리스트 + row memo + OTHER customLabel. Modal 조립 |
| `RejectReasonModal` | `components/admin/RejectReasonModal.tsx` | Textarea(min 10 / max 500) + counter |
| `DocumentRequestBanner` | `components/documents/DocumentRequestBanner.tsx` | 신청자 상단 warning 배너 (스크롤-앵커 CTA) |
| `DocumentRequestList` | `components/admin/DocumentRequestList.tsx` | LEW 상세 "요청 서류" 섹션 (variant별 액션) |

Phase 2의 `DocumentRequestCard`는 **컴포넌트 자체 변경 없음**. `variant` prop 4종에 이미 skeleton 스타일이 있으며, Phase 3에서는 `?devMockups=1` 가드를 제거해 프로덕션 분기에서 그대로 렌더.

---

## 3. 토큰 매핑 (신규 토큰 0)

index.css에 `--color-warning-*`, `--color-info-*`, `--color-success-*`, `--color-error-*` 모두 존재 — 그대로 사용.

| 용도 | Tailwind 클래스 |
|---|---|
| Banner (신청자 상단) | `bg-warning-50 border border-warning-500/40 text-warning-700` |
| requested card | `border-warning-500/40 bg-warning-50` + `Badge variant="warning" dot` |
| uploaded card | `border-info-500/40 bg-info-50` + `Badge variant="info" dot` |
| approved card | `border-success-500/40 bg-success-50` + `Badge variant="success"` + check SVG |
| rejected card | `border-error-500/40 bg-error-50` + `Badge variant="error"` + warning SVG |
| Reject 사유 인용 | `border-l-2 border-error-500 pl-3 text-sm text-gray-700 bg-surface rounded p-2` |
| LEW note 인용 | `border-l-2 border-warning-500 pl-3 text-sm text-gray-700 italic` |
| 벨 배지 숫자(기존 유지) | `bg-red-500 text-white min-w-[18px] h-[18px]` |
| Dashboard row 배지 | `Badge variant="warning" dot` "{n} 대기 / {n} awaiting" |

**색 단독 전달 금지**: 각 variant에 `Badge` 텍스트 + SVG 아이콘(check/warn/info/x) + 배경색 3중 동반. 색맹 AA 보장.

---

## 4. 컴포넌트 A — DocumentRequestModal (LEW)

### 구조
```tsx
<Modal isOpen={open} onClose={close} size="md" closeOnEscape={!submitting} closeOnOverlay={!submitting} ariaLabelledBy="dr-modal-title">
  <ModalHeader onClose={close}>
    <div className="flex items-center gap-2">
      <span className="text-xl" aria-hidden>📋</span>
      <div>
        <h3 id="dr-modal-title" className="text-lg font-semibold text-gray-800">신청자에게 서류 요청</h3>
        <p className="text-xs text-gray-500 mt-0.5">Applicant: {name} · {appCode}</p>
      </div>
    </div>
  </ModalHeader>
  <ModalBody className="!py-0">
    {softLimitReached && (
      <div role="alert" className="mt-4 text-sm text-warning-700 bg-warning-50 border border-warning-500/40 rounded-md p-3">
        활성 요청 한도(10건)에 도달했습니다. 기존 요청을 승인/반려 후 다시 시도하세요.
      </div>
    )}
    <p className="text-sm text-gray-600 my-3">필요한 서류를 선택하세요. 신청자에게 즉시 알림이 전송됩니다.</p>
    <ul className="border border-gray-200 rounded-lg divide-y divide-gray-200 max-h-[60vh] overflow-y-auto">
      {catalog.map(dt => <RequestRow key={dt.code} dt={dt} ... />)}
    </ul>
  </ModalBody>
  <ModalFooter>
    <span className="mr-auto text-xs text-gray-500">{`${selected.length} of 10 active requests will be used.`}</span>
    <Button variant="outline" onClick={close} disabled={submitting}>Cancel</Button>
    <Button onClick={submit} loading={submitting} disabled={selected.length === 0 || hasInvalid}>
      {selected.length === 0 ? '최소 1건 선택' : `Send ${selected.length} Request(s)`}
    </Button>
  </ModalFooter>
</Modal>
```

### RequestRow 레이아웃
```tsx
<li className={`px-4 py-3 ${checked ? 'bg-primary-50' : 'bg-surface'} transition-colors`}>
  <label className="flex items-start gap-3 cursor-pointer">
    <input type="checkbox" checked={checked} disabled={alreadyPending} onChange={...} className="mt-0.5 accent-primary" />
    <span className="text-xl flex-shrink-0" aria-hidden>{dt.iconEmoji}</span>
    <div className="flex-1 min-w-0">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium text-gray-900">{dt.labelKo} <span className="text-gray-500 font-normal">· {dt.labelEn}</span></p>
        {alreadyPending && <Badge variant="gray" dot>이미 요청 중 #{existingId}</Badge>}
      </div>
      <p className="text-xs text-gray-500 mt-0.5">{prettyMime(dt.acceptedMime)} · 최대 {dt.maxSizeMb}MB</p>
    </div>
  </label>
  {checked && (
    <div className="ml-8 mt-2 space-y-2 animate-in">
      {dt.code === 'OTHER' && (
        <Input label="Label" required placeholder="Describe the document" value={customLabel} error={customLabelError} />
      )}
      <Textarea label="Note to applicant (optional)" rows={2} placeholder="예: 전체 페이지 고해상도 스캔" value={note} maxLength={300} />
    </div>
  )}
</li>
```

### 상태
- **Idle → Submitting**: Modal `aria-busy="true"`, 모든 체크박스/입력 disabled, Submit `loading`.
- **409 DUPLICATE**: 해당 row inline error `text-xs text-error-600 mt-1` + 체크 해제.
- **모바일 <640px**: Modal centered 유지(신규 sheet variant 없음). 리스트 max-h를 `50vh`로 축소, Footer는 `flex-col sm:flex-row` 줄바꿈.

---

## 5. 컴포넌트 B — LEW "요청 서류" 섹션

`AdminApplicationDetailPage` 상단 Documents 카드에 신설. 우측 상단 `[+ 서류 요청]` primary 버튼.

### Request Row (LEW 관점, Phase 2 Card와 별도 컴포넌트)
```tsx
<div className={`rounded-lg border p-4 ${variantBorder[status]} ${variantBg[status]}`}>
  <div className="flex items-start justify-between mb-2">
    <div className="flex items-center gap-2">
      <span className="text-xl" aria-hidden>{dt.iconEmoji}</span>
      <div>
        <p className="text-sm font-semibold text-gray-900">#{id} · {label}</p>
        <p className="text-xs text-gray-500 mt-0.5">Requested {fmt(requestedAt)} by {requesterName}</p>
      </div>
    </div>
    <Badge variant={badgeVariant[status]} dot>{statusLabel[status]}</Badge>
  </div>
  {lewNote && <blockquote className="border-l-2 border-warning-500 pl-3 text-sm text-gray-700 italic mb-2">"{lewNote}"</blockquote>}
  {status === 'UPLOADED' && <UploadedFileBlock file={...} applicantNote={...} />}
  {status === 'REJECTED' && <RejectedReasonBlock reason={rejectionReason} />}
  <div className="flex justify-end gap-2 mt-3">
    {status === 'REQUESTED' && <Button size="sm" variant="ghost" onClick={confirmCancel}>Cancel Request</Button>}
    {status === 'UPLOADED' && <>
      <Button size="sm" variant="outline" className="text-error-700 border-error-500/40 hover:bg-error-50" onClick={openReject}>Reject</Button>
      <Button size="sm" onClick={approve} loading={approving}>Approve ✓</Button>
    </>}
  </div>
</div>
```

### Approve/Reject 낙관적 업데이트 패턴
```tsx
const approve = async () => {
  const prev = request;
  setRequest({...prev, status: 'APPROVED', reviewedAt: new Date().toISOString()}); // 즉시 반영
  try { await api.approve(id); showToast('Approved'); }
  catch (e) { setRequest(prev); showToast('승인 실패 — 다시 시도하세요', 'error'); }
};
```

- 전이 모션: Badge가 `warning→success` 150ms crossfade (CSS `transition-colors`), 버튼 영역 `height` 200ms 축소. `prefers-reduced-motion` 시 즉시.
- 불법 전이(UPLOADED→CANCELLED 등) 버튼은 **렌더링하지 않음**.

### 5-1. RejectReasonModal
```tsx
<Modal isOpen={open} onClose={close} size="sm" ariaLabelledBy="rr-title">
  <ModalHeader onClose={close}><h3 id="rr-title">Reject this upload?</h3></ModalHeader>
  <ModalBody>
    <p className="text-xs text-gray-500 mb-3">Document: {label} (#{id})</p>
    <Textarea label="Reason (shared with applicant) *" rows={4} maxLength={500} value={reason}
      aria-describedby="rr-hint" error={reason.length > 0 && reason.length < 10 ? '최소 10자 입력' : undefined} />
    <p id="rr-hint" className="text-xs text-gray-500 mt-1">{reason.length} / 500 · minimum 10 · 신청자에게 그대로 전달됩니다.</p>
  </ModalBody>
  <ModalFooter>
    <Button variant="outline" size="sm" onClick={close}>Cancel</Button>
    <Button size="sm" onClick={submit} loading={submitting} disabled={reason.length < 10}
      className="bg-error hover:bg-error/90 text-white">Reject & Notify</Button>
  </ModalFooter>
</Modal>
```

---

## 6. 컴포넌트 C — 신청자 배너 + DocumentRequestCard 4 variant

### 6-1. DocumentRequestBanner (상단)
```tsx
{(requestedCount + rejectedCount) > 0 && (
  <div role="region" aria-live="polite"
       className="flex items-center gap-3 bg-warning-50 border border-warning-500/40 rounded-lg px-4 py-3 mb-4">
    <span className="text-lg" aria-hidden>🔔</span>
    <div className="flex-1 text-sm">
      <p className="font-medium text-warning-700">LEW가 서류 {total}건을 요청했습니다</p>
      {rejectedCount > 0 && <p className="text-xs text-warning-700/80 mt-0.5">{rejectedCount}건 반려 · 재업로드가 필요합니다</p>}
    </div>
    <a href="#doc-requests" className="text-sm font-medium text-warning-700 underline hover:no-underline">보기 →</a>
  </div>
)}
```
- Phase 1/2 `InfoBox`(`bg-info-50`)보다 한 단계 강한 주의 톤 = `bg-warning-50`. 같은 페이지에 공존 시 세로 배치 순서는 **배너(warning) → InfoBox(info) → 카드 섹션**.
- 모든 요청이 APPROVED/CANCELLED가 되면 배너 언마운트.
- 모바일 <640px: `flex-col items-start`, CTA는 아래 별도 줄.

### 6-2. DocumentRequestCard variant 프로덕션 활성화

Phase 2 §6 skeleton을 그대로 렌더. 추가되는 variant별 **액션 영역만** 확정:

| variant | 헤더 Badge | 본문 | Action |
|---|---|---|---|
| `requested` | `warning` dot "Action needed" + ⚠ SVG | LEW note blockquote + `DocumentUploadArea` dropzone | `Button` "Upload" primary |
| `uploaded` | `info` dot "Waiting for LEW" + info SVG | "You uploaded {filename} · {size} · {time}" + "LEW is reviewing. You will be notified." | `Button variant="ghost"` "Replace file" + "Download" |
| `approved` | `success` dot "Approved" + ✓ SVG `text-success-600` | "Approved by LEW · {time}" | `Button variant="ghost"` "Download" |
| `rejected` | `error` dot "Needs re-upload" + ⚠ outline SVG `text-error-600` | 사유 블록 + "Previous: {file} (kept in history)" + `DocumentUploadArea` | `Button` "Upload new file" primary |

**rejected variant 톤 주의**: 헤더 타이틀만 destructive 색, 본문 텍스트는 `text-gray-700` 유지. 아이콘은 **outline** 스타일(filled는 공격적). → "경고가 아니라 가이드" 톤.

```tsx
// rejected variant 스니펫
<div className="rounded-lg border border-error-500/40 bg-error-50 p-5">
  <div className="flex items-start justify-between mb-3">
    <div className="flex items-center gap-2">
      <span className="text-xl" aria-hidden>{dt.iconEmoji}</span>
      <h4 className="text-sm font-semibold text-error-700">{label}</h4>
    </div>
    <Badge variant="error" dot>Needs re-upload</Badge>
  </div>
  <div className="bg-surface border-l-2 border-error-500 rounded p-3 mb-3">
    <p className="text-xs font-medium text-gray-500 mb-1">LEW가 업로드를 반려했습니다</p>
    <p className="text-sm text-gray-700">"{rejectionReason}"</p>
  </div>
  {previousFile && <p className="text-xs text-gray-500 mb-3">이전 파일: {previousFile.name} (이력 보존)</p>}
  <DocumentUploadArea ... />
  <div className="flex justify-end mt-4">
    <Button onClick={reupload} loading={uploading}>Upload new file</Button>
  </div>
</div>
```

### 6-3. 대시보드 row 배지
```tsx
{pendingCount > 0 && <Badge variant="warning" dot>{pendingCount} 대기</Badge>}
```

---

## 7. 컴포넌트 D — 인앱 알림 (벨)

**실측 결과**: `NotificationBell.tsx`에 `unreadCount`, `99+` pill, `aria-label` 이미 구현. 변경 범위는 **폴링 주기 상수 1개** + 드롭다운 아이템 4종 아이콘/색상.

### 변경 사항
- `POLL_INTERVAL = 60_000` → `30_000` (Phase 3 AC-N의 반응성 목표).
- 신규 도착 시 scale pop 애니메이션(150ms, once): `animate-[bellPop_150ms_ease-out]` (keyframe 추가 — `scale(1) → 1.15 → 1`). `@media (prefers-reduced-motion)` 시 스킵.

### 드롭다운 아이템 타입별 시각 구분
| type | 좌측 아이콘 | 아이콘 색 | 배경(읽지 않음) |
|---|---|---|---|
| DOCUMENT_REQUESTED | 📋 / clipboard-list SVG | `text-warning-600` | `bg-warning-50/50` |
| DOCUMENT_REQUEST_FULFILLED | ⬆ / arrow-up-tray | `text-info-600` | `bg-info-50/50` |
| DOCUMENT_REQUEST_APPROVED | ✓ / check-circle | `text-success-600` | `bg-success-50/50` |
| DOCUMENT_REQUEST_REJECTED | ⚠ / exclamation-triangle outline | `text-error-600` | `bg-error-50/50` |

읽음 상태는 `bg-surface` + 좌측 아이콘만 회색으로. 클릭 시 자동 markRead → 라우팅.

---

## 8. 컴포넌트 E — 이메일 HTML 템플릿 3종

경로: `blue-light-backend/src/main/resources/email-templates/document-{requested,decision,fulfilled}.html`

### 공통 레이아웃 (inline CSS, 600px max)
```html
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f3f4f6;padding:24px 0;">
  <tr><td align="center">
    <table width="600" style="background:#ffffff;border-radius:12px;overflow:hidden;font-family:-apple-system,Segoe UI,sans-serif;">
      <!-- 헤더: LicenseKaki 워드마크 -->
      <tr><td style="background:#1a3a5c;padding:20px 32px;">
        <img src="{{baseUrl}}/logo-wordmark-white.png" height="28" alt="LicenseKaki" />
      </td></tr>
      <!-- 본문 -->
      <tr><td style="padding:32px;color:#1f2937;">
        <h1 style="font-size:18px;margin:0 0 16px;">{{coreEn}}</h1>
        <p style="font-size:14px;color:#6b7280;font-style:italic;margin:0 0 20px;">{{coreKo}}</p>
        <table width="100%" style="background:#f9fafb;border-radius:8px;padding:16px;margin-bottom:24px;">
          <tr><td style="font-size:13px;color:#374151;line-height:1.8;">
            <strong>Application:</strong> {{appCode}}<br/>
            <strong>Document:</strong> {{label}}<br/>
            {{#if reason}}<strong>Reason:</strong> "{{reason}}"<br/>{{/if}}
          </td></tr>
        </table>
        <a href="{{deepLink}}" style="display:inline-block;background:#1a3a5c;color:#fff;text-decoration:none;padding:12px 24px;border-radius:8px;font-weight:600;font-size:14px;">Open in LicenseKaki →</a>
      </td></tr>
      <!-- 푸터 -->
      <tr><td style="background:#f9fafb;padding:16px 32px;font-size:11px;color:#9ca3af;line-height:1.6;">
        You are receiving this because you are the {{role}} on this application.<br/>
        LicenseKaki complies with Singapore PDPA. For privacy inquiries: privacy@licensekaki.sg<br/>
        <!-- TODO(Phase 4): 수신 거부 링크 -->
      </td></tr>
    </table>
  </td></tr>
</table>
```

### 제목 라인 (영/한 병기, 한 줄)
| 이벤트 | Subject |
|---|---|
| REQUESTED | `[LicenseKaki] Your LEW requested {n} document(s) · LEW가 서류를 요청했습니다` |
| FULFILLED | `[LicenseKaki] {applicant} uploaded {label} — please review` |
| APPROVED | `[LicenseKaki] {label} approved · {label} 승인됨` |
| REJECTED | `[LicenseKaki] {label} needs re-upload · {label} 재업로드 필요` |

- rejected 템플릿은 Details 박스에 `Reason` 행 추가 블록을 렌더.
- 브랜드 색: 헤더 `#1a3a5c`(primary), CTA 버튼 동일. 상태별 컬러 스트립은 생략(과시각화 방지, 본문 카피가 충분히 명확).

---

## 9. 반응형 요약

| 요소 | <640px | ≥640px |
|---|---|---|
| DocumentRequestModal | centered, 리스트 `max-h-[50vh]`, Footer `flex-col` stack, Submit full-width | centered max-w-lg, 리스트 `max-h-[60vh]` |
| RejectReasonModal | centered max-w-sm, Textarea rows=5 | max-w-sm rows=4 |
| 신청자 배너 | `flex-col items-start`, CTA 아래 줄 | `flex-row items-center`, CTA 우측 |
| DocumentRequestCard | 1열, Action `flex-col` full-width | 1열, Action `flex-row justify-end` |
| LEW Request Row | 헤더 좌/Badge 우 `flex-wrap`, Action full-width | 동일 layout, Action inline |
| 벨 드롭다운 | 화면 우측 16px 여백, 최대 폭 320px | 360px |

---

## 10. 접근성 체크

- Modal: `ariaLabelledBy` prop 활용(Modal 이미 지원). RejectReason Textarea에 `aria-describedby="rr-hint"`로 카운터·공유 고지 연결.
- 색+아이콘+텍스트 3중 전달: 모든 status Badge의 `dot`는 보조, 실제 전달은 텍스트 라벨("Approved" 등) + SVG 아이콘이 담당.
- `aria-live`:
  - 낙관적 Approve 성공 토스트 영역: `polite` — 정보성.
  - Reject 완료 및 신청자 배너 신규 등장: `assertive` — 주의 필요.
  - 벨 드롭다운 신규 알림 선언: `polite`(드롭다운 열렸을 때만 활성).
- Focus ring: 모든 Button/Checkbox/Link에 `focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2` (Phase 1 규약 계승).
- 키보드: Modal 내부 Tab trap 내장, ESC=Cancel(Submitting 중 무시 — `closeOnEscape={!submitting}`). 체크박스 toggle → memo input이 DOM 순서상 바로 뒤에 나오도록 배치.
- 벨 배지 색 대비: `bg-red-500 #ef4444` + white 텍스트 — contrast ratio 4.52:1 (AA 통과).

---

## 11. 토큰 매핑 표 (status별)

| status | Border | Background | Badge | Icon | Action Button |
|---|---|---|---|---|---|
| REQUESTED | `border-warning-500/40` | `bg-warning-50` | `variant="warning" dot` | `text-warning-600` ⚠ | primary "Upload" |
| UPLOADED | `border-info-500/40` | `bg-info-50` | `variant="info" dot` | `text-info-600` ℹ | ghost "Replace" / Approve+Reject |
| APPROVED | `border-success-500/40` | `bg-success-50` | `variant="success"` | `text-success-600` ✓ | ghost "Download" |
| REJECTED | `border-error-500/40` | `bg-error-50` | `variant="error"` | `text-error-600` ⚠ outline | primary "Upload new" |
| CANCELLED | 렌더되지 않음(섹션에서 제거) | — | — | — | — |

---

## 12. 개발자 복붙 스니펫

### 체크리스트 row (checked 시 memo 펼침)
```tsx
<li className={`px-4 py-3 transition-colors ${checked ? 'bg-primary-50' : 'bg-surface hover:bg-surface-secondary'}`}>
  <label className="flex items-start gap-3 cursor-pointer">
    <input type="checkbox" className="mt-0.5 accent-primary" checked={checked} disabled={alreadyPending} onChange={toggle} />
    <span className="text-xl" aria-hidden>{dt.iconEmoji}</span>
    <div className="flex-1 min-w-0">
      <p className="text-sm font-medium text-gray-900">{dt.labelKo} <span className="text-gray-500 font-normal">· {dt.labelEn}</span></p>
      <p className="text-xs text-gray-500 mt-0.5">{prettyMime(dt.acceptedMime)} · up to {dt.maxSizeMb}MB</p>
    </div>
    {alreadyPending && <Badge variant="gray" dot>이미 요청 중 #{existingId}</Badge>}
  </label>
  {checked && <div className="ml-8 mt-2 space-y-2 animate-in">{/* OTHER label + memo */}</div>}
</li>
```

### 신청자 warning 배너
```tsx
<div role="region" aria-live="assertive"
     className="flex items-center gap-3 bg-warning-50 border border-warning-500/40 rounded-lg px-4 py-3 mb-4">
  <span className="text-lg" aria-hidden>🔔</span>
  <div className="flex-1 text-sm">
    <p className="font-medium text-warning-700">LEW가 서류 {n}건을 요청했습니다</p>
    {rejectedCount > 0 && <p className="text-xs text-warning-700/80 mt-0.5">{rejectedCount}건 반려 · 재업로드 필요</p>}
  </div>
  <a href="#doc-requests" className="text-sm font-medium text-warning-700 underline hover:no-underline">보기 →</a>
</div>
```

### 벨 배지 (기존 유지, 폴링 주기만 변경)
```tsx
const POLL_INTERVAL = 30_000; // Phase 3: 60s → 30s
// 기존 JSX 유지: bg-red-500, min-w-[18px] h-[18px], 99+ clamp
```

### approve 낙관적 업데이트
```tsx
const approve = async () => {
  const prev = req;
  setReq({ ...prev, status: 'APPROVED', reviewedAt: new Date().toISOString() });
  try { await api.approve(prev.id); toast.success('Approved'); }
  catch { setReq(prev); toast.error('승인에 실패했습니다. 다시 시도해 주세요.'); }
};
```

---

## 13. Phase 2 → Phase 3 연속성 체크

| Phase 2 | Phase 3 |
|---|---|
| `DocumentRequestCard` 4 variant skeleton (`?devMockups=1`) | 프로덕션 분기로 승격 — 컴포넌트 자체 변경 0 |
| `InfoBox`(자발적 업로드 안내) | **유지**. 배너가 있을 때 배너 아래 노출 (warning → info 위계) |
| `Modal` centered only | 유지 — 모바일 bottom sheet variant 추가 없음. `max-h-[90vh] overflow-y-auto`로 체크리스트 수용 |
| `Badge` 6 variant | 동일 팔레트 재사용. status → variant 매핑표 §11 |
| Document Type emoji (catalog `icon_emoji`) | 체크리스트/카드 모두 동일 이모지. SVG 대체 금지 |
| 신규 토큰 금지 | ✅ `warning-50`, `info-50`, `success-50`, `error-50` 전부 index.css 기존 값 |

---

## 14. 범위 외 (Phase 4 이월)

- 이메일 수신 거부 링크 (템플릿에 TODO 주석).
- LEW 활성 요청 만료/에스컬레이션 리마인더.
- Modal `variant="sheet"` 모바일 바텀 시트.
- 벨 드롭다운 preview(현재는 아이콘만 구분; 호버 시 메시지 tooltip은 Phase 4).

---

## 15. AC 커버리지 매트릭스

| AC | 위치 |
|---|---|
| R1, R3, R5, R6 | §4 Modal (체크리스트, OTHER customLabel, already-pending disabled) |
| R2 | §4 Submit 전 클라 catalog 검증 |
| R4 | "서류 요청" 버튼 자체 권한 가드로 비노출 |
| S1, S4 | §6-2 requested/rejected variant의 `DocumentUploadArea` |
| S2 | §5 Approve 낙관적 전이 |
| S3 | §5-1 RejectReasonModal (min 10자) |
| S5 | §5 Cancel Request 버튼은 REQUESTED 카드에서만 렌더 |
| S6 | UI에서 불법 전이 버튼 제거 (409 사전 차단) |
| LU1, LU2 | §4 Modal 전체 |
| LU3 | §5 LEW Request Row variant별 액션 |
| LU4 | §5-1 RejectReasonModal |
| AU1 | §6-1 DocumentRequestBanner |
| AU2 | §6-2 4 variant 프로덕션 |
| AU3 | §6-3 대시보드 Badge |
| AU4 | §6-2 rejected "Previous: … (이력 보존)" |
| N1~N3 | §7 벨 4종 아이콘 구분 + §8 이메일 3 템플릿 |
