# Phase 6 — UX Design

**대상 페이지**: `/lew/applications/:id/review` — LEW가 배정된 신청을 리뷰하는 메인 워크스페이스
**목표**: LEW가 한 페이지에서 서류 요청·kVA 확정·SLD 확정·CoF 서명을 완결. finalize 전제조건을 시각적으로 강제해 실수 경로를 차단.

---

## 1. 상단 헤더

```
┌─────────────────────────────────────────────────────────────┐
│  ← Back   LEW Review — Application #5                        │
│           Block 123 Main St                    [Pending…]    │
└─────────────────────────────────────────────────────────────┘
```

- **Back**: `/lew/applications` 목록으로 이동
- **타이틀**: `LEW Review — Application #{seq}` + 주소 subline
- **상태 배지**: 우측 상단 `StatusBadge` (Application status)

## 2. Revision 코멘트 배너 (조건부)

`adminApp.reviewComment != null`인 경우만 노출:

```
┌─────────────────────────────────────────────────────────────┐
│ ⚠ Revision comment from admin                                │
│   <reviewComment 텍스트, whitespace-pre-wrap>                 │
└─────────────────────────────────────────────────────────────┘
```

- 스타일: `border-warning-200 bg-warning-50 text-warning-700`
- 수정 불가 (ADMIN 이전 revision 요청 맥락 열람용)

## 3. 탭 네비게이션

```
┌─────────────────────────────────────────────────────────────┐
│  Documents  │  kVA [Confirmed]  │  SLD [Confirmed]  │       │
│  LOA        │  Certificate of Fitness [Ready]                │
└─────────────────────────────────────────────────────────────┘
```

### 3-1. 탭 정의

| 탭 | 조건부 표시 | 배지 규칙 |
|---|---|---|
| **Documents** | 항상 | pending 개수(≥1) 시 `warning` 배지 |
| **kVA** | 항상 | `CONFIRMED` → `success "Confirmed"`, `UNKNOWN` → `warning "Unknown"` |
| **SLD** | `sldOption == REQUEST_LEW`만 | `CONFIRMED` → `success "Confirmed"`, 그 외 → `warning "<상태명>"` |
| **LOA** | 항상 | 배지 없음 |
| **Certificate of Fitness** | 항상 | `finalized` → `success "Finalized"` / 가드 통과 → `info "Ready"` / 차단 → `gray "Blocked"` |

### 3-2. 디폴트 활성 탭
- 초기 렌더링: **CoF 탭** (가장 많이 사용하는 동작)
- 가드 차단 에러 수신 시: 해당 탭 자동 전환 (예: `KVA_NOT_CONFIRMED` → kVA 탭)

### 3-3. Tabs 컴포넌트
`components/ui/Tabs.tsx` — 비통제 컴포넌트(`activeKey`/`onChange`). `TabPanel`은 비활성 탭을 DOM에서 제거해 하위 컴포넌트의 불필요한 API 호출/렌더링을 방지.

---

## 4. 탭별 콘텐츠

### 4-1. Documents 탭
- `LewDocumentReviewSection` 임베드(Phase 3 컴포넌트 재사용)
- props: `applicationSeq`, `canRequest` (LEW 본인이 assigned일 때 true), `applicantDisplayName`, `applicationCode`
- 서류 요청·승인·반려·취소 모두 여기서 수행

### 4-2. kVA 탭
- `KvaSection` 임베드(Phase 5 컴포넌트 재사용, `AdminApplication` 요구)
- LEW: UNKNOWN 시 "Confirm kVA" 버튼, CONFIRMED 시 읽기 전용 값 표시
- ADMIN (동일 페이지를 공유하는 경우 없음): 이 탭에서는 override 버튼 없음 — override는 `/admin/applications/:id` KvaSection에서만

### 4-3. SLD 탭 (조건부)
- `AdminSldSection` 임베드. sldOption = `REQUEST_LEW`이고 `SldRequest` 레코드 존재 시
- 상태별 UI: REQUESTED/AI_GENERATING → 탭 UI(Manual/AI), UPLOADED → 확정 버튼, CONFIRMED → 읽기 전용
- 확정 다이얼로그: `ConfirmDialog` ("Once confirmed, the SLD will be locked...")

### 4-4. LOA 탭 (view-only)
```
┌─────────────────────────────────────────────────────────────┐
│  Letter of Authority                                          │
│  LOA is managed by ADMIN. LEW can view its status here…       │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ Type              New (generated)                        │ │
│  │ Signature status  Not signed yet                         │ │
│  │ Signed at         —                                      │ │
│  │ LOA file          #123                                   │ │
│  └─────────────────────────────────────────────────────────┘ │
│  [⚠ LOA signature is pending. ADMIN or the applicant…]       │
└─────────────────────────────────────────────────────────────┘
```
- 버튼 없음(생성/업로드 권한 無)
- 미서명 시 warning 블록으로 알림

### 4-5. Certificate of Fitness 탭
**내부 3-step** (기존 구조 유지):

| Step | 내용 | 변경 |
|---|---|---|
| 1. Summary | `CofStepApplicationSummary` — 신청자 입력 요약 | 변경 없음 |
| 2. CoF Fields | `CofStepInputs` — CoF 10 필드 입력 | **kVA 필드 read-only** |
| 3. Review & Finalize | `CofStepReviewFinalize` — 검토 + Finalize | **GuardChecklist 추가** |

---

## 5. CoF Step 2 — kVA 읽기 전용

**변경 전** (Phase 1):
```
Approved Load (kVA) *   [Differs from applicant estimate (60 kVA)]
┌─────────────────────────────┐
│  45                    kVA  │   ← 편집 가능
└─────────────────────────────┘
hint: Applicant estimated 45 kVA.
```

**변경 후** (Phase 6):
```
Approved Load (kVA) *   [LEW confirmed]  ← 또는 [kVA not confirmed]
┌─────────────────────────────────────────────────────────┐
│  45 kVA            Synced from Application. Use the     │
│                    kVA tab to override.                  │
└─────────────────────────────────────────────────────────┘
```

- 배지: `CONFIRMED` → success "LEW confirmed", `UNKNOWN` → warning "kVA not confirmed"
- 값: `Application.selectedKva`를 렌더링 (CoF.approvedLoadKva는 Draft 단계에서 Application 값을 미러링)
- hint 문구: kVA UNKNOWN이면 "Open the kVA tab to confirm capacity on site.", CONFIRMED이면 "Synced from Application. Use the kVA tab to override."
- 입력 불가 — kVA 변경은 오직 kVA 탭의 Confirm/Override 플로우로만

---

## 6. CoF Step 3 — Finalize GuardChecklist

```
┌─────────────────────────────────────────────────────────────┐
│  Review & Finalize                                            │
│  Confirm each field one more time. Finalizing moves the…      │
│                                                                │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ Finalize is blocked                                    │    │
│  │  • kVA confirmed                                       │    │
│  │    LEW has confirmed the electrical capacity.          │    │
│  │  • Document requests resolved                          │    │
│  │    2 request(s) still pending — resolve them on the    │    │
│  │    Documents tab.              [Go to tab →]           │    │
│  │  • SLD uploaded and confirmed                          │    │
│  │    Upload and confirm the SLD on the SLD tab…          │    │
│  │                                [Go to tab →]           │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                                │
│  [LEW-visible summary]   [Applicant-visible preview]           │
│  ...                                                           │
│                                                                │
│  ☑ I confirm that the Certificate of Fitness…                  │
│                                                                │
│  [← Previous]              [Save Draft]  [Finalize & Submit]   │
│                                              ↑ disabled        │
└─────────────────────────────────────────────────────────────┘
```

### 6-1. 상태별 스타일
- **모두 통과**: 패널 배경 `bg-success-50`, 타이틀 "All prerequisites satisfied", 체크 마크(`✓`)
- **1개 이상 미통과**: 패널 배경 `bg-warning-50`, 타이틀 "Finalize is blocked", 미통과 항목은 `•`

### 6-2. Go to tab 링크
- 미통과 항목 옆에 `text-primary-600 underline` 링크
- 클릭 시 `onJumpToTab(key)` 호출 → 해당 탭 활성화

### 6-3. Finalize 버튼 disabled 조건
```
finalizeDisabled = !confirmed || readOnly || finalizing || !guardsSatisfied
```

---

## 7. 상태 전이 시나리오 (UX 관점)

### 7-1. 정상 경로
1. LEW 진입 → CoF 탭 active (Summary)
2. 탭 상단 배지 확인: Documents 0, kVA Unknown, CoF Blocked
3. kVA 탭 클릭 → "Confirm kVA" 모달 → 확정
4. Documents 탭에서 필요 시 서류 요청 → 신청자 업로드 → 승인
5. (sldOption=REQUEST_LEW인 경우) SLD 탭에서 업로드·확정
6. CoF 탭 Step 1 → 2 → 3 이동, GuardChecklist 모두 ✓ → Finalize 활성화
7. "Finalize & Submit" 클릭 → 토스트 "CoF finalized. Moved to payment stage." → 목록으로 이동

### 7-2. 가드 차단 후 복구
1. LEW가 GuardChecklist 무시하고 Finalize 시도 (버튼은 disabled지만 서버 재검증에 의존)
2. 서버 400 응답 (예: `KVA_NOT_CONFIRMED`)
3. UI: 토스트 표시 + 자동으로 kVA 탭 전환 + 데이터 재로드
4. LEW가 kVA 확정 후 다시 CoF 탭 복귀 → GuardChecklist 업데이트 → Finalize 활성

### 7-3. kVA override 후 재발급 (LEW 관점)
1. ADMIN이 `/admin/applications/:id` KvaSection에서 override 실행
2. LEW에게 인앱 알림: "CoF re-signature required — kVA was updated to X."
3. LEW가 알림 클릭 → `/lew/applications/:id/review` 진입
4. CoF 탭 배지가 `Ready`로 표시(finalized 상태 해제, 가드 여전히 모두 통과)
5. LEW가 Step 3에서 다시 "Finalize & Submit" 클릭 → 재서명 완료

---

## 8. 접근성 & 반응형

- Tabs: `role="tablist"` + `role="tab"` + `aria-selected`
- Disabled 탭: `aria-disabled` + `cursor-not-allowed` + 회색 텍스트
- Finalize 버튼: `aria-disabled` 동기화
- 배지: `Badge` 컴포넌트 재사용 — text 색상 대비 WCAG AA 통과
- 모바일: 탭이 `overflow-x-auto`로 가로 스크롤 가능. GuardChecklist는 세로 스택

---

## 9. 컴포넌트 재사용 매트릭스

| 기존 컴포넌트 | 재사용 | 수정 필요 |
|---|---|---|
| `LewDocumentReviewSection` | ✅ 그대로 | — |
| `KvaSection` | ✅ 그대로 | — |
| `KvaConfirmModal` | ✅ 그대로 | — |
| `AdminSldSection` | ✅ 그대로 | — |
| `AdminLoaSection` | ❌ 대체 | LEW는 view-only 필요 → 인라인 `LoaReadOnlyView`로 대체 |
| `CofStepApplicationSummary` | ✅ 그대로 | — |
| `CofStepInputs` | ⚠ 일부 | kVA 필드 입력 → 읽기 전용 |
| `CofStepReviewFinalize` | ⚠ 일부 | GuardChecklist 추가, props 2개 추가 |
| `Tabs` | 🆕 신규 | `components/ui/Tabs.tsx` |

---

## 10. Figma 링크

(프로젝트에 Figma 파일이 아직 없으므로 텍스트 스펙 기준. 필요 시 후속 PR에서 추가.)
