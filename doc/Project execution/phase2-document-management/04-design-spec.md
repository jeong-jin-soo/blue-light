# Phase 2 — 시각 디자인 스펙

**작성일**: 2026-04-17
**근거**: `01-spec.md` (AC-J1~J6, AC-T1~T4, AC-D1~D6, AC-U1~U4), `02-ux-design.md`
**원칙**: Phase 1 디자인 토큰·컴포넌트 100% 재사용. 신규 색/토큰 도입 금지. JIT 모달은 `Modal` 컴포넌트 재사용, 서류 섹션은 Phase 1 `Card`/`InfoBox` 패턴 확장.

---

## 1. 재사용 컴포넌트 체크리스트 (기존 코드 실측)

| 컴포넌트 | 경로 | 존재 | Phase 2 용도 |
|---|---|---|---|
| `Modal` + `ModalHeader`/`Body`/`Footer` | `components/ui/Modal.tsx` | ✅ | CompanyInfoModal 컨테이너. focus trap·ESC·overlay close·`bg-black/50` backdrop·`shadow-modal`·`animate-in` 내장. size=`md`(max-w-lg) 사용 |
| `Select` | `components/ui/Select.tsx` | ✅ | Document Type 드롭다운 기반. native `<select>`라 2줄 아이템(icon+meta)은 불가 → **옵션 라벨을 `{emoji} {label_ko} · {mime} · up to {N}MB` 단일 라인으로 구성** (과도한 커스텀 드롭다운 금지) |
| `InfoBox` | `components/ui/InfoBox.tsx` | ✅ | 서류 섹션 상단 안내(AC-U4). variant=`info` 그대로 재사용 |
| `Card` / `CardHeader` | `components/ui/Card.tsx` | ✅ | 서류 섹션 래퍼, 자발적 업로드 서브카드. `CardHeader.action`에 Badge 배치 |
| `Badge` | `components/ui/Badge.tsx` | ✅ | DocumentRequestCard status 배지, Uploaded 목록 카운트 |
| `Button` | `components/ui/Button.tsx` | ✅ | Upload CTA, Modal Cancel/Submit. `loading` 상태 내장 가정 |
| `Input` / `Textarea` | `components/ui/Input.tsx`, `Textarea.tsx` | ✅ | Modal 3필드, OTHER custom_label |
| `ConfirmDialog` | `components/ui/ConfirmDialog.tsx` | ✅ | 파일 삭제 확인 다이얼로그 |
| `Toast` | `components/ui/Toast.tsx` | ✅ | "Uploaded ✓", 업로드 실패 |
| `LoadingSpinner` | `components/ui/LoadingSpinner.tsx` | ✅ | Modal Submit, 목록 초기 로딩 |

**FileInput 컴포넌트 없음** → native `<input type="file" className="sr-only">` + 라벨형 dropzone으로 구현 (shadcn/ui 스타일 드롭존, 신규 컴포넌트 최소화).

---

## 2. 신규 컴포넌트 목록

| 컴포넌트 | 경로 | 비고 |
|---|---|---|
| `CompanyInfoModal` | `components/application/CompanyInfoModal.tsx` | `Modal` 조립, 재사용 1회 이상 예상이지만 도메인 전용 → `ui/` 금지 |
| `DocumentRequestCard` | `components/documents/DocumentRequestCard.tsx` | 공용. 5 variant (`neutral`/`requested`/`uploaded`/`approved`/`rejected`). Phase 2는 `neutral`만 프로덕션 노출 |
| `DocumentTypeSelector` | `components/documents/DocumentTypeSelector.tsx` | `Select`를 감싸는 wrapper. catalog 조회 + 아이템 포맷팅 + OTHER 분기 |
| `DocumentUploadArea` | `components/documents/DocumentUploadArea.tsx` | dropzone + 진행률 바. native `<input type="file">` 기반 |

**신규 ui 원시 컴포넌트 추가 없음.** 모두 `components/documents/` 도메인 디렉토리 또는 `components/application/`에 배치.

---

## 3. 디자인 토큰 매핑 (신규 토큰 추가 없음)

| 용도 | 토큰 | 값 |
|---|---|---|
| Modal backdrop | `bg-black/50` (Modal 내장) | — |
| Modal surface | `bg-surface rounded-xl shadow-modal` (Modal 내장) | — |
| Section card | `bg-surface rounded-xl shadow-card p-6` (Card 기본) | — |
| InfoBox | `bg-info-50 border-info-500/30` (InfoBox 내장) | — |
| Dropzone idle | `border-2 border-dashed border-gray-300 bg-surface-secondary` | — |
| Dropzone hover/focus | `border-primary bg-primary-50` | `#1a3a5c` / `#f0f4f8` |
| Dropzone drag-over | `border-primary bg-primary-50 ring-2 ring-primary/20` | — |
| Dropzone disabled | `border-gray-200 bg-surface-tertiary opacity-60` | — |
| Progress bar track | `bg-gray-200 h-1.5 rounded-full` | — |
| Progress bar fill | `bg-primary h-1.5 rounded-full transition-[width] duration-200` | — |
| "Save to profile" 체크박스 | `accent-primary` (Phase 1 동일) | — |
| Error inline | `text-xs text-error-600` (Input 내장과 동일) | — |

### DocumentRequestCard variant별 테두리/배경 (AC-U3 skeleton)

| variant | border | bg | status 배지 | 프로덕션 노출 |
|---|---|---|---|---|
| `neutral` (Phase 2 자발적) | `border-gray-200` | `bg-surface` | — | ✅ |
| `requested` (Phase 3) | `border-warning-500/40` | `bg-warning-50` | Badge `variant="warning"` "Requested" | 🔒 dev only |
| `uploaded` (Phase 3) | `border-info-500/40` | `bg-info-50` | Badge `variant="info"` "Under Review" | 🔒 |
| `approved` (Phase 3) | `border-success-500/40` | `bg-success-50` | Badge `variant="success"` "Approved" ✓ | 🔒 |
| `rejected` (Phase 3) | `border-error-500/40` | `bg-error-50` | Badge `variant="danger"` "Rejected" | 🔒 |

**색 단독 전달 금지**: 모든 variant에 SVG 아이콘(checkmark/warning/info) + 배지 텍스트 동반.

---

## 4. 컴포넌트 A — CompanyInfoModal

### 구조
```tsx
<Modal isOpen={open} onClose={handleCancel} size="md" closeOnEscape={!submitting} closeOnOverlay={!submitting}>
  <ModalHeader onClose={handleCancel}>
    <div className="flex items-center gap-2">
      <span className="text-xl">🏢</span>
      <h3 className="text-lg font-semibold text-gray-800">회사 정보가 필요합니다</h3>
    </div>
  </ModalHeader>
  <ModalBody>
    <p className="text-sm text-gray-600 mb-4">법인 신청을 제출하려면 회사 정보가 필요합니다.</p>
    <div className="space-y-4">
      <Input label="회사명" name="companyName" required autoFocus />
      <Input label="UEN" name="uen" required hint="9~10자리, 예: 201812345A" />
      <Input label="직책" name="designation" required />
      <label className="flex items-start gap-2 text-sm text-gray-700 bg-surface-secondary rounded-md p-3">
        <input type="checkbox" defaultChecked className="mt-0.5 accent-primary" />
        <span>
          <span className="font-medium">내 프로필에 저장</span>
          <span className="block text-xs text-gray-500 mt-0.5">다음 신청부터 자동 입력됩니다.</span>
        </span>
      </label>
    </div>
  </ModalBody>
  <ModalFooter>
    <Button variant="outline" onClick={handleCancel} disabled={submitting}>취소</Button>
    <Button onClick={handleSubmit} loading={submitting}>저장하고 제출</Button>
  </ModalFooter>
</Modal>
```

### 반응형
- **≥640px**: 중앙 정렬, `max-w-lg` (Modal `size="md"`), 여백 `px-6 py-4` (ModalBody 내장).
- **<640px**: Modal 기본 `p-4` overlay 유지. 내부는 세로 스택 그대로. **full-screen bottom sheet 전환은 Phase 2 범위 외** — 현재 Modal은 centered 전용, UX §1의 bottom sheet 요구는 **별도 Modal variant 추가 없이 모바일에서도 centered 유지**로 타협(키보드 열릴 때 `max-h-[90vh] overflow-y-auto` 내장으로 스크롤 가능). 향후 bottom sheet 변형 필요 시 Modal에 `variant="sheet"` prop 추가 — Phase 2 신규 디자인 토큰 금지 원칙으로 이월.

### 상태별 스타일
- **Idle**: 전 필드 enabled, Submit 버튼 `variant="primary"`.
- **Submitting**: 모든 Input `disabled`, Submit 버튼 `loading` (내장 스피너), Cancel `disabled`, `closeOnEscape={false}` (혼동 방지 — UX §4).
- **Error**: UEN 형식 오류는 Input `error` prop으로 inline (red + aria-describedby, Input 내장). 네트워크/서버 오류는 ModalBody 최하단 `<div role="alert" className="mt-4 text-sm text-error-700 bg-error-50 border border-error-200 rounded-md p-3">` 배너 + 재시도 버튼 활성 유지. **Toast 사용 금지** — 모달 내 오류는 모달 내에서 처리(focus 유지).

### 접근성
- `Modal`의 focus trap·ESC·backdrop click 내장 활용. ModalHeader 제목 → 자동 `aria-labelledby` 연결을 위해 `<h3 id="co-modal-title">` 부여. Modal wrapper에 `aria-labelledby="co-modal-title"` 추가 권장 (현 Modal에 없음 — 개발자에게 한 줄 개선 요청).
- 체크박스 label 영역 전체가 클릭 타겟.

---

## 5. 컴포넌트 B — Documents 섹션 (ApplicationDetailPage)

### 레이아웃
```tsx
<Card>
  <CardHeader title="서류" description="Documents" />
  <InfoBox title="지금은 업로드가 필수가 아니에요">
    LEW가 검토 중 서류를 요청할 수 있습니다. 이미 가진 서류가 있다면 먼저 업로드해 두면 진행이 빨라집니다.
  </InfoBox>

  <div className="mt-6">
    <DocumentRequestCard variant="neutral" ... />
  </div>

  <div className="mt-8">
    <div className="flex items-center justify-between mb-3">
      <h4 className="text-sm font-semibold text-gray-800">업로드됨 <span className="text-gray-500 font-normal">({count})</span></h4>
    </div>
    <ul className="divide-y divide-gray-200 border border-gray-200 rounded-lg">
      {/* 파일 row */}
    </ul>
  </div>
</Card>
```

### 파일 row 스타일
- `li`: `flex items-center gap-3 px-4 py-3 hover:bg-surface-secondary`
- 아이콘: `text-2xl` (catalog `icon_emoji`)
- 파일명: `text-sm font-medium text-gray-900 truncate`
- 메타: `text-xs text-gray-500` (`{labelKo} · {sizeMb}MB · {date}`)
- 액션: `Button size="sm" variant="ghost"` 2개 (다운로드/삭제). 삭제는 `text-error-600 hover:bg-error-50`.
- Empty state: `<div className="text-center py-8 text-sm text-gray-500"><span className="text-3xl block mb-2">🗂</span>업로드된 서류가 없습니다.</div>`

### 반응형
- 데스크톱·모바일 모두 **1열 유지** (UX §5). 상세 페이지 본문 폭이 이미 좁음(`max-w-3xl`)이라 2열은 과함.
- 파일 row는 `<640px`에서 액션 버튼을 `flex-col sm:flex-row`로 줄바꿈 — 아이콘만 남겨 공간 절약.

---

## 6. 컴포넌트 C — DocumentRequestCard

### 구조 (variant=`neutral`, Phase 2 자발적)
```tsx
<div className="bg-surface border border-gray-200 rounded-lg p-5">
  <div className="flex items-start justify-between mb-4">
    <div className="flex items-center gap-2">
      <span className="text-xl">{dt?.iconEmoji ?? '📎'}</span>
      <div>
        <h4 className="text-sm font-semibold text-gray-900">서류 업로드</h4>
        <p className="text-xs text-gray-500 mt-0.5">원하는 서류를 자발적으로 업로드할 수 있습니다.</p>
      </div>
    </div>
    {/* variant !== neutral 일 때만 Badge */}
  </div>

  <DocumentTypeSelector value={typeCode} onChange={setTypeCode} />

  {typeCode === 'OTHER' && (
    <div className="mt-3 animate-in">
      <Input label="라벨" required placeholder="이 서류를 설명해 주세요" />
    </div>
  )}

  <div className="mt-4">
    <DocumentUploadArea acceptedMime={...} maxSizeMb={...} />
  </div>

  <div className="flex justify-end mt-4">
    <Button onClick={handleUpload} loading={uploading} disabled={!canUpload}>업로드</Button>
  </div>
</div>
```

### variant별 헤더 조립 (Phase 3 대비 skeleton)
- `requested`: 헤더 우측 `<Badge variant="warning">요청됨</Badge>` + 본문에 `<blockquote className="border-l-2 border-warning-500 pl-3 text-sm text-gray-700 italic my-3">{lewNote}</blockquote>`
- `uploaded`: `<Badge variant="info">검토 대기</Badge>` + 파일 정보 표시
- `approved`: `<Badge variant="success">승인됨</Badge>` + ✓ SVG (`text-success-600`)
- `rejected`: `<Badge variant="danger">반려됨</Badge>` + `rejectionReason` 인용 + "다시 업로드" Button primary

**Phase 2 배포 시 production 렌더 variant는 `neutral`만.** 나머지 4개는 `?devMockups=1` 쿼리 또는 Storybook으로만 확인 (AC-U3).

---

## 7. 컴포넌트 D — DocumentTypeSelector

### 구조
`Select` 래핑. native `<option>` 라벨에 2줄 표시 불가 → **단일 라인 포맷**으로 타협:

```tsx
options = catalog.map(dt => ({
  value: dt.code,
  label: `${dt.iconEmoji} ${dt.labelKo} · ${prettyMime(dt.acceptedMime)} · 최대 ${dt.maxSizeMb}MB`,
}));

<Select
  label="서류 종류"
  required
  placeholder="서류 종류 선택"
  options={options}
  value={value}
  onChange={e => onChange(e.target.value)}
/>
```

- `prettyMime('application/pdf,image/png,image/jpeg')` → `'PDF · PNG · JPG'`
- 선택 후 드롭다운 **아래** (Select 컴포넌트 외부)에서 help_text/template_url 힌트 조건부 노출:
  ```tsx
  {dt?.helpText && <p className="text-xs text-gray-500 mt-2 flex items-start gap-1.5">💡 {dt.helpText}</p>}
  {dt?.templateUrl && <a href={dt.templateUrl} className="text-xs text-primary underline mt-1 inline-block">템플릿 다운로드</a>}
  ```

**커스텀 드롭다운(2줄 아이템) 금지** — 기존 `Select`는 native이므로 재사용. 2줄 UX는 선택 후 아래 힌트 영역으로 보상. Phase 3+에서 풍부한 드롭다운 필요 시 별도 `Combobox` 도입 태스크.

---

## 8. 컴포넌트 E — DocumentUploadArea

### 구조 (라벨 감싸기 패턴)
```tsx
<label
  htmlFor="doc-file-input"
  className={`block border-2 border-dashed rounded-lg p-6 text-center cursor-pointer transition-colors
    ${isDragOver ? 'border-primary bg-primary-50 ring-2 ring-primary/20' : 'border-gray-300 bg-surface-secondary hover:border-primary hover:bg-primary-50'}
    ${disabled ? 'border-gray-200 bg-surface-tertiary opacity-60 cursor-not-allowed' : ''}`}
  onDragOver={...}
  onDrop={...}
>
  <input id="doc-file-input" type="file" className="sr-only" accept={acceptedMime} onChange={...} disabled={disabled} />
  <svg className="w-8 h-8 mx-auto text-gray-400 mb-2" /* upload icon */ />
  {selectedFile ? (
    <>
      <p className="text-sm font-medium text-gray-900">{selectedFile.name}</p>
      <p className="text-xs text-gray-500 mt-0.5">{formatSize(selectedFile.size)} · 클릭하여 변경</p>
    </>
  ) : (
    <>
      <p className="text-sm font-medium text-gray-700">파일을 끌어 놓거나 클릭</p>
      <p className="text-xs text-gray-500 mt-0.5">{prettyMime} · 최대 {maxSizeMb}MB</p>
    </>
  )}
</label>

{uploading && (
  <div className="mt-3">
    <div className="bg-gray-200 h-1.5 rounded-full overflow-hidden">
      <div className="bg-primary h-full transition-[width] duration-200" style={{ width: `${progress}%` }} />
    </div>
    <p className="text-xs text-gray-500 mt-1.5" aria-live="polite">업로드 중… {progress}%</p>
  </div>
)}

{error && <p className="text-xs text-error-600 mt-2" role="alert">⚠ {error}</p>}
```

### 상태
- **Idle**: 점선 회색 테두리, `bg-surface-secondary`.
- **Hover**: `border-primary bg-primary-50`.
- **Drag-over**: hover + `ring-2 ring-primary/20`.
- **File selected**: 동일 테두리 유지, 파일명 + "클릭하여 변경" 안내.
- **Uploading**: dropzone `aria-busy="true"`, 하단 progress bar 표시, 파일 input disabled.
- **Error**: 테두리 유지, 하단 inline `text-error-600` (파일 선택 상태는 유지 — 재시도 쉽게).
- **Disabled**: 회색 + opacity-60 + `cursor-not-allowed`.

### 접근성
- `<label>` 전체가 파일 input 트리거 → 키보드 Enter/Space 동작.
- dropzone 내 `aria-live="polite"` 영역에 "파일 선택됨: xxx.pdf" / "업로드 중 60%" / "업로드 완료".
- 드래그 이벤트 미지원 환경도 클릭으로 동일 기능 가능.

---

## 9. Document Type 아이콘

**UX 요구대로 catalog `icon_emoji` 그대로 사용** (MVP). 인라인 SVG 변환 금지 (작업량 대비 가치 낮음 — 02-ux-design §7.4).

| code | emoji | 한글 라벨 |
|---|---|---|
| SP_ACCOUNT | 📄 | SP 계정 보유자 PDF |
| LOA | 📝 | 위임장 (LOA) |
| MAIN_BREAKER_PHOTO | 📷 | 메인 차단기 사진 |
| SLD_FILE | 📐 | 단선도 (SLD) |
| SKETCH | ✏️ | 평면 스케치 |
| PAYMENT_RECEIPT | 🧾 | 결제 영수증 |
| OTHER | 📎 | 기타 |

**상태 아이콘은 인라인 SVG** (Phase 1 방식 계승):
- Check (approved): Heroicons `check` path, `text-success-600 w-4 h-4`.
- Warning (requested): Heroicons `exclamation-triangle`, `text-warning-600 w-4 h-4`.
- X (rejected): Heroicons `x-mark`, `text-error-600 w-4 h-4`.
- Upload (dropzone): `arrow-up-tray`, `text-gray-400 w-8 h-8`.

Phase 3+에서 시각 일관성 강화 필요 시 `<DocumentIcon code={...} />` 래퍼 도입 — 지금은 metric 우선.

---

## 10. 반응형 요약

| 요소 | <640px | ≥640px (sm) | ≥1024px (lg) |
|---|---|---|---|
| CompanyInfoModal | centered, 좌우 `p-4`, 세로 스크롤 허용 | `max-w-lg` centered | 동일 |
| Documents 섹션 컨테이너 | 좌우 `px-4` | `max-w-3xl` 유지 | 동일 |
| DocumentRequestCard | 1열 (내부 stack) | 1열 | 1열 |
| Uploaded 파일 row | 액션 버튼 `flex-col` 줄바꿈 | `flex-row` | `flex-row` |
| Dropzone 높이 | `min-h-[140px]` (터치 여유) | `min-h-[120px]` | `min-h-[120px]` |
| Document Type Select | 풀폭 | 풀폭 | 풀폭 (상세 폭 제한) |

---

## 11. 모션

- Modal: `animate-in` 내장 (fade + slide, 200ms ease-out).
- OTHER custom_label 필드: `animate-in` 재사용 (200ms fade-in).
- Progress bar fill: `transition-[width] duration-200`.
- Dropzone 색상 전환: `transition-colors duration-150`.
- `prefers-reduced-motion`: 모든 전환 `duration-0` 대체 (글로벌 CSS에 미적용 → 개발자에게 `@media (prefers-reduced-motion)` 블록 추가 요청).

---

## 12. 개발자 복붙 스니펫

### CompanyInfoModal 진입 분기 (NewApplicationPage)
```tsx
const needsCompanyInfo = applicantType === 'CORPORATE' && !user.companyName;
const handleSubmit = () => {
  if (needsCompanyInfo) setCompanyModalOpen(true);
  else createApplication(payload);
};
```

### DocumentRequestCard variant skeleton (dev mockup)
```tsx
{isDev && ['requested','uploaded','approved','rejected'].map(v =>
  <DocumentRequestCard key={v} variant={v} request={mockRequest(v)} />
)}
```

### Uploaded 파일 row
```tsx
<li className="flex items-center gap-3 px-4 py-3 hover:bg-surface-secondary">
  <span className="text-2xl flex-shrink-0" aria-hidden>{dt.iconEmoji}</span>
  <div className="flex-1 min-w-0">
    <p className="text-sm font-medium text-gray-900 truncate">{file.name}</p>
    <p className="text-xs text-gray-500 mt-0.5">{dt.labelKo} · {sizeMb}MB · {date}</p>
  </div>
  <div className="flex gap-1 flex-shrink-0">
    <Button size="sm" variant="ghost" onClick={() => download(file)}>다운로드</Button>
    <Button size="sm" variant="ghost" className="text-error-600 hover:bg-error-50" onClick={() => confirmDelete(file)} aria-label={`${file.name} 삭제`}>
      <svg className="w-4 h-4" /* trash icon */ />
    </Button>
  </div>
</li>
```

---

## 13. Phase 1 연속성 체크

| Phase 1 요소 | Phase 2 반영 |
|---|---|
| `InfoBox` (variant=info, bg-info-50) | 서류 섹션 상단에 **동일 컴포넌트 그대로** 재사용 (AC-U4). 신규 스타일 도입 0. |
| Step 0 "No documents needed now" 문구 | 상세 페이지 Documents InfoBox로 **이관** — 문구는 "지금은 업로드가 필수가 아니에요"로 톤 조정. |
| `ApplicantTypeCard` 카드 스타일 (`rounded-lg border-2`, `bg-primary-50` 선택) | DocumentRequestCard 기본 구조와 **시각 정합**. 단 DocumentRequestCard는 선택형 라디오가 아니므로 `border-2` 대신 `border` 1px 사용 + variant별 색상만 변경. |
| Profile `CardHeader.action`에 Badge | DocumentRequestCard 헤더 우측 status Badge에 **동일 패턴** 적용. |
| `accent-primary` 체크박스 | CompanyInfoModal "프로필에 저장" 체크박스에 동일. |
| 인라인 SVG (Heroicons outline) | 상태 아이콘 전부 인라인 SVG. Document Type만 emoji 예외(UX 결정). |
| 신규 디자인 토큰 금지 | ✅ `bg-info-*`, `border-{status}-500/40`, `bg-{status}-50`만 사용. |

---

## 14. AC 커버리지 매트릭스

| AC | 반영 위치 |
|---|---|
| J1~J6 | §4 CompanyInfoModal 전체 (trigger, submitting 상태, inline error, ESC/cancel) |
| T1~T4 | §7 DocumentTypeSelector 옵션 라벨 포맷이 catalog 필드(acceptedMime/maxSizeMb/iconEmoji/labelKo)를 그대로 렌더 |
| D1~D4 | §6 OTHER 분기, §8 MIME/size 클라 검증 + inline error |
| D5, D6 | 백엔드 — UI 영향 없음 (Phase 3 LEW 요청 시 variant=`requested` skeleton 재사용) |
| U1 | §5 DocumentRequest 없을 때 neutral variant만 렌더 |
| U2 | §8 업로드 성공 시 파일 리스트 즉시 반영 + Toast "업로드 완료" |
| U3 | §6 5 variant + `?devMockups=1` skeleton |
| U4 | §5 상단 InfoBox (Phase 1 연속) |

---

## 15. 명세 범위 외 (Phase 3 이월)

- LEW 요청 생성 UI (관리자 대시보드).
- DocumentRequestCard의 `requested`/`uploaded`/`approved`/`rejected` variant 프로덕션 활성화.
- Modal `variant="sheet"` 모바일 바텀 시트 (현재 centered 유지).
- 2줄 아이템 Combobox 드롭다운 (현재 native Select + 하단 힌트로 대체).
- Document Type 아이콘 SVG화.
- "대표본" 지정 UI (동일 타입 중복 업로드 관리).
