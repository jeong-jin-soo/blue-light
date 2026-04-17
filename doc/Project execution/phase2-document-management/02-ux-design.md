# Phase 2 — 사후 서류 관리 UX 설계 (와이어프레임 & 마이크로카피)

**작성일**: 2026-04-17
**근거 스펙**: `01-spec.md` AC-J1~J6, AC-T1~T4, AC-D1~D6, AC-U1~U4
**설계 원칙**: Just-in-Time Disclosure (Phase 1 연속), 인식 > 회상, 시스템 상태 가시성, 오류 방지

---

## 1. 컴포넌트 A — CompanyInfoModal (법인 JIT)

### 트리거 조건
Step 3 Review에서 `Submit` 클릭 시 `applicantType=CORPORATE && user.companyName==null` → 모달 인터셉트, `POST /api/applications` 아직 미호출 (AC-J1).

### 레이아웃 (Desktop 중앙 모달 480px)
```
┌─────────────────────────────────────────────┐
│ 🏢 Company details needed            [×]    │ ← H2 + ESC close
│ ─────────────────────────────────────────── │
│ We need a few company details to submit     │ ← 1-line why
│ your corporate application.                 │
│                                             │
│ Company Name *                              │
│ [ Acme Pte Ltd                         ]    │
│                                             │
│ UEN *                                       │
│ [ 201812345A                           ]    │
│ Format: 9–10 chars (e.g. 201812345A)        │ ← helper
│                                             │
│ Your Designation *                          │
│ [ Director                             ]    │
│                                             │
│ ┌──────────────────────────────────────┐   │
│ │ ☑ Save to my profile                  │   │ ← default true
│ │   Auto-fills your next application.   │   │
│ └──────────────────────────────────────┘   │
│                                             │
│                 [ Cancel ] [ Save & Submit ]│
└─────────────────────────────────────────────┘
```

### 상태 전이
- **Idle** → 입력 중 → `Save & Submit` 클릭 → **Submitting** (버튼 스피너, 모든 필드 `disabled`, `aria-busy=true`) → 성공 시 모달 close + Step 3 성공 토스트 + `/applications/:id`로 이동.
- **Error**: 400 `INVALID_UEN` → UEN 필드 아래 inline error. 409 `UEN_DUPLICATE`(있다면) → 동일 위치 + 하단 배너. 500/네트워크 → 하단 배너 + 재시도 버튼 활성 유지.

### 마이크로카피 (EN / KO)
| 요소 | EN | KO |
|---|---|---|
| Title | Company details needed | 회사 정보가 필요합니다 |
| Desc | We need a few company details to submit your corporate application. | 법인 신청을 제출하려면 회사 정보가 필요합니다. |
| Company Name | Company Name | 회사명 |
| UEN | UEN (Unique Entity Number) | UEN (고유 사업자번호) |
| UEN helper | Format: 9–10 chars, e.g. 201812345A | 9~10자리, 예: 201812345A |
| Designation | Your Designation | 직책 |
| Persist label | Save to my profile | 내 프로필에 저장 |
| Persist benefit | Auto-fills your next application. | 다음 신청부터 자동 입력됩니다. |
| Cancel | Cancel | 취소 |
| Submit | Save & Submit | 저장하고 제출 |
| Err: UEN format | Invalid UEN format. Check SG UEN rules. | UEN 형식이 올바르지 않습니다. |
| Err: network | Could not submit. Check your connection and try again. | 제출에 실패했습니다. 연결을 확인하고 다시 시도하세요. |
| Err: company required | Company info is required for corporate applications. | 법인 신청에는 회사 정보가 필요합니다. |

### 에지 케이스
- **Cancel**: 모달만 닫음, Step 3 상태 보존 → 사용자는 Applicant Type을 Individual로 바꾸거나 다시 Submit 가능 (AC-J4). `beforeunload` 경고는 **두지 않음** — Step 3 폼은 이미 zustand/세션에 유지되어 손실 없음.
- **UEN 검증**: 싱가포르 UEN 포맷(사업장 등록 9자리 숫자+알파벳 or 10자리). 정규식 `/^(\d{8}[A-Z]|\d{9}[A-Z]|[TSR]\d{2}[A-Z]{2}\d{4}[A-Z])$/` 클라 검증, 서버 최종 검증(AC-J6).
- **체크박스 해제 제출**: Application 스냅샷만 저장, User 미수정 — 동일 사용자가 다음 신청 시 모달 재등장.
- **모바일 (<640px)**: **Full-screen bottom sheet**로 전환. 헤더 sticky, 버튼 영역 하단 sticky (`safe-area-inset`). 키보드 가려짐 방지 위해 활성 필드로 `scrollIntoView`.

---

## 2. 컴포넌트 B — ApplicationDetailPage "서류" 섹션

### 배치
상태 카드 ↓ **여기** ↓ 신청 폼 데이터. Phase 2는 **자발적 업로드 카드 1개**만. Phase 3 확장 자리(요청 카드 리스트)는 주석 + 목업으로만 (AC-U3).

### 레이아웃
```
┌─ Status Card (기존) ───────────────────────┐
└───────────────────────────────────────────┘

┌─ 📎 Documents (서류) ──────────────────────┐
│ ┌──────────────────────────────────────┐  │
│ │ ℹ Upload is optional for now          │  │ ← InfoBox (Phase 1 연속)
│ │ Your LEW may request documents during │  │   bg-info/10 border-info/30
│ │ review. You can also upload anything  │  │
│ │ you already have — it speeds things   │  │
│ │ up.                                    │  │
│ └──────────────────────────────────────┘  │
│                                            │
│ ┌── Upload a document ──────────────────┐ │ ← 자발적 업로드 카드
│ │ Document Type *                        │ │
│ │ [ Select type         ▾ ]              │ │ ← §3 참조
│ │                                        │ │
│ │ (OTHER 선택 시)                         │ │
│ │ Label *                                │ │
│ │ [ Describe this document          ]    │ │
│ │                                        │ │
│ │ ┌──────────────────────────────────┐  │ │
│ │ │    ⬆  Drag & drop or click       │  │ │ ← dropzone
│ │ │       PDF · PNG · JPG · up to 10MB│  │ │   (type에 따라 동적)
│ │ └──────────────────────────────────┘  │ │
│ │                                        │ │
│ │                      [ Upload ]        │ │
│ └───────────────────────────────────────┘ │
│                                            │
│ Uploaded (3)                               │
│ ┌───────────────────────────────────────┐ │
│ │ 📄 sp_account.pdf                      │ │
│ │ SP Account · 1.2 MB · 2026-04-17 10:31 │ │
│ │                       [Download] [🗑]  │ │
│ ├───────────────────────────────────────┤ │
│ │ 📷 breaker.jpg   Main Breaker · 2.4 MB │ │
│ │                       [Download] [🗑]  │ │
│ └───────────────────────────────────────┘ │
│                                            │
│ (empty state — 업로드 0건)                  │
│ 🗂 No documents yet. Upload when ready.    │
└────────────────────────────────────────────┘
```

### 상태 전이
1. **Empty** — 업로드 카드 + empty state 메시지.
2. **Uploading** — Upload 버튼 `aria-busy`, 진행률 바 0→100%, dropzone disabled, 목록 상단에 skeleton row.
3. **Success** — skeleton → 실제 row, 토스트 "Uploaded ✓" (AC-U2), 파일 input reset, Document Type 유지(연속 업로드 편의).
4. **Error** — dropzone 하단 inline error, 파일 선택 유지, 재시도 가능.
5. **Delete confirm** — `AlertDialog` "Delete [filename]? This cannot be undone."

### 마이크로카피
| 요소 | EN | KO |
|---|---|---|
| Section H | Documents | 서류 |
| InfoBox title | Upload is optional for now | 지금은 업로드가 필수가 아니에요 |
| InfoBox body | Your LEW may request documents during review. You can also upload anything you already have — it speeds things up. | LEW가 검토 중 서류를 요청할 수 있습니다. 이미 가진 서류가 있다면 먼저 업로드해 두면 진행이 빨라집니다. |
| Upload card H | Upload a document | 서류 업로드 |
| Type placeholder | Select type | 서류 종류 선택 |
| Custom label | Label (for "Other") | 라벨 (기타 선택 시) |
| Dropzone | Drag & drop or click | 파일을 끌어 놓거나 클릭 |
| Upload CTA | Upload | 업로드 |
| List H | Uploaded ({count}) | 업로드됨 ({count}) |
| Empty state | No documents yet. Upload when ready. | 업로드된 서류가 없습니다. |
| Success toast | Uploaded ✓ | 업로드 완료 |
| Delete confirm | Delete this document? It cannot be undone. | 이 서류를 삭제할까요? 되돌릴 수 없습니다. |
| Err: size | File too large (max {N} MB). | 파일이 너무 큽니다 (최대 {N}MB). |
| Err: mime | File type not allowed for {label}. Accepted: {list}. | {label}에 허용되지 않는 형식입니다. 허용: {list}. |
| Err: unknown | Upload failed. Try again. | 업로드에 실패했습니다. 다시 시도하세요. |

### DocumentRequestCard Props 설계
```ts
interface DocumentRequestCardProps {
  documentType: DocumentType;        // catalog row
  request: DocumentRequest | null;   // null = 자발적(Phase 2) / object = 요청응답(Phase 3)
  onUpload: (file: File, customLabel?: string) => Promise<void>;
  onDelete?: (fileId: number) => Promise<void>;
  disabled?: boolean;
}
```
- `request==null` → "자발적 업로드" variant (Phase 2).
- `request!=null` → status에 따른 variant (Phase 3). 4개 variant skeleton을 Storybook 또는 `?devMockups=1` 쿼리로 확인 가능(AC-U3).

---

## 3. 컴포넌트 C — Document Type 선택 UI

### 드롭다운 아이템 (shadcn/ui Select)
```
┌──────────────────────────────────────┐
│ 📄 SP Account Holder PDF              │
│    PDF · up to 10 MB                  │
├──────────────────────────────────────┤
│ 📝 Letter of Authorisation            │
│    PDF · up to 10 MB                  │
├──────────────────────────────────────┤
│ 📷 Main Breaker Photo                 │
│    PNG/JPG · up to 8 MB               │
├──────────────────────────────────────┤
│ 📐 Single Line Diagram                │
│    PDF/PNG/JPG · up to 20 MB          │
├──────────────────────────────────────┤
│ ✏️ Sketch / Plan                      │
│    PDF/PNG/JPG · up to 10 MB          │
├──────────────────────────────────────┤
│ 🧾 Payment Receipt                    │
│    PDF/PNG/JPG · up to 5 MB           │
├──────────────────────────────────────┤
│ 📎 Other                              │
│    Requires a custom label            │
└──────────────────────────────────────┘
```
- 각 row: `{icon_emoji} {label_ko / label_en}` + 2nd line 허용 형식/용량 (catalog 기반 동적, AC-T3).
- 선택 후 `help_text`/`template_url`/`example_image_url`이 있으면 드롭다운 아래에 힌트 노출:
  ```
  💡 See example · [Download template]
  ```
- **OTHER** 선택 시: `custom_label` 입력 필드 fade-in (200ms), 필수 표기, 빈 값이면 Upload 버튼 disabled (AC-D4).
- 허용 MIME/크기 변동: Dropzone caption이 타입 선택에 따라 즉시 갱신.

### 한국어 라벨 (catalog `label_ko`와 일치 필수)
| code | label_ko |
|---|---|
| SP_ACCOUNT | SP 계정 보유자 PDF |
| LOA | 위임장 (LOA) |
| MAIN_BREAKER_PHOTO | 메인 차단기 사진 |
| SLD_FILE | 단선도 (SLD) |
| SKETCH | 평면 스케치 |
| PAYMENT_RECEIPT | 결제 영수증 |
| OTHER | 기타 |

---

## 4. 접근성 (a11y)

### CompanyInfoModal
- `role="dialog" aria-modal="true" aria-labelledby="co-modal-title"`.
- **Focus trap**: 열릴 때 첫 필드로 포커스, Shift+Tab 순환. 닫힐 때 트리거(Submit 버튼)로 포커스 복귀.
- **ESC**: Cancel과 동일 동작. 단, `Submitting` 중에는 ESC 무시(실행 취소 애매모호 방지).
- Inline error `role="alert"`, 입력 `aria-invalid aria-describedby`.

### 업로드 섹션
- Upload 진행 중 dropzone `aria-busy="true"`, 상태 변경 영역 `aria-live="polite"` ("Uploading sp.pdf…" → "Uploaded sp.pdf").
- 파일 목록: `<ul>` + 각 row `<li>`, 삭제 버튼 `aria-label="Delete {filename}"`.
- Delete 확인 다이얼로그: focus trap + 기본 포커스는 **Cancel** (오동작 방지, Heuristic 5).
- 색 단독 전달 금지: 에러는 빨강 + `⚠` 아이콘 + 텍스트 3중.

---

## 5. 반응형

| Breakpoint | CompanyInfoModal | Documents 섹션 | Type Dropdown |
|---|---|---|---|
| <640px | Full-screen sheet, 버튼 sticky 하단 | 카드 1열, dropzone 세로 확장 | 트리거 풀폭, 패널 풀폭 |
| 640–1024 | 중앙 모달 480px | 카드 1열 (본문이 좁은 상세 페이지) | 트리거 320px |
| >1024 | 중앙 모달 480px | 업로드 카드 + 목록 1열 유지(가독성) | 트리거 320px |

- Dropzone 높이: 모바일 140px, 데스크톱 120px (터치 타겟 여유).

---

## 6. 에지 케이스 & 에러 UX

| Case | 처리 |
|---|---|
| JIT 모달: 409 중복 UEN | UEN 필드 아래 "This UEN is already registered to another account. Contact support." + support 메일 링크. 하단 배너 동시. |
| JIT 모달: 단일 트랜잭션 실패 → User만 업데이트 | 발생 불가(백엔드 `@Transactional`, R1). 프론트는 모든 실패를 재시도 가능 상태로 취급. |
| 업로드 크기 초과 (클라 1차 검증) | 파일 선택 즉시 catalog `max_size_mb`로 거부 — 네트워크 낭비 방지. 서버 400 `FILE_TOO_LARGE`는 최종 가드. |
| MIME 불일치 | `<input accept>`에 catalog `accepted_mime` 반영 + drop 시 타입 검증 + 서버 최종. |
| 동일 Document Type 중복 업로드 | **허용** (AC-R7: 재업로드 유스케이스). 목록에 최신본부터 정렬. Phase 3에서 "대표본" 지정 UI 추가 예정 — 지금은 토스트에서 언급하지 않음. |
| OTHER label 누락 | Upload 버튼 disabled + tooltip "Enter a label first". 서버 400은 방어용. |
| 삭제 중 네트워크 실패 | 목록에서 낙관적 제거하지 **않음** — 서버 200 응답 후에만 DOM 제거. 실패 시 에러 토스트 + 목록 유지. |
| Phase 1에서 Step 0 업로드 사라진 혼란 | 상세 페이지 진입 시 (application.createdAt > Phase2 배포일) 대상 InfoBox가 상단 고정 → LEW 요청 경로 안내(Phase 1 공지 연속). |

---

## 7. Designer 전달 사항

1. **JIT 모달 톤**: 긴급성 없이 친절하게. 빨강/느낌표 금지. `🏢` 아이콘 + neutral 헤더. "왜 지금 필요한가"를 1문장으로만 설명(Heuristic 1). 기존 `Dialog` 컴포넌트 재사용, 신규 스타일 0.
2. **InfoBox 연속성**: Phase 1 Step 0에서 사용한 `bg-info/10 border-info/30` 토큰을 **동일하게** 사용. 타이틀/본문 타이포 동일. 사용자가 "같은 플랫폼의 같은 안내"로 인식하게.
3. **Document 카드 상태별 색상 (Phase 3 선공지)**:
   - 자발적 업로드(Phase 2): neutral card, 테두리 강조 없음.
   - REQUESTED: `border-warning/40` + `bg-warning/5` (주의 환기).
   - UPLOADED: neutral + 체크 아이콘.
   - APPROVED: `border-success/40` + ✓.
   - REJECTED: `border-destructive/40` + ✗ + rejection_reason 인용.
   - 4개 skeleton을 Storybook에 동봉(AC-U3). Phase 2에서는 neutral만 프로덕션 노출.
4. **Document Type 아이콘**: MVP는 `icon_emoji`(catalog) 그대로. Phase 3+에서 "세밀한 시각 일관성" 필요해지면 인라인 SVG로 교체 가능하도록 `<Icon code={dt.code} />` 래퍼 도입. 지금 SVG화는 **금지** (작업량 대비 가치 낮음).
5. **재사용 원칙**: `Dialog`, `Select`, `Alert(info)`, `Card`, `AlertDialog`, `Button` 기존 토큰만 사용. 신규 색/간격 토큰 추가 금지.
6. **모션**: 모달 fade-in 120ms, bottom sheet slide-up 200ms. `prefers-reduced-motion` 시 모두 0ms. 업로드 진행률은 부드러운 transition(`ease-out`).

---

## 8. Phase 1 연속성 체크

| Phase 1 요소 | Phase 2 반영 |
|---|---|
| Step 0 InfoBox "No documents needed now" | 상세 페이지 Documents InfoBox로 **이관 + 재사용** (의사결정 (c), AC-U4). 문구는 "Upload is optional for now"로 톤 일치하게 조정. |
| ApplicantTypeCard 선택형 카드 | JIT 모달은 "Corporate 선택 시 나중에 회사 정보를 받겠다"는 Phase 1 예고(Step 0 heads-up)의 **이행**. 같은 메시지 세계관 유지. |
| ProfilePage 회사정보 섹션 (Optional) | JIT 모달 "Save to my profile" = ProfilePage 동일 필드를 **역방향으로 채우는 동일 데이터 모델**. 모달 하단 힌트로 "Manage anytime in Profile" 링크 고려 가능(모달 간결성 위해 Phase 2에선 생략). |
| PDPA/민감 정보 톤 | JIT 모달은 PDPA 재동의 불필요(기존 동의 범위 내). 별도 체크박스 추가 **금지**. |

---

## 9. AC 커버리지 매트릭스

| AC | 반영 위치 |
|---|---|
| J1, J4, J5 | §1 트리거 조건, Cancel 동작 |
| J2 | §1 Submitting → 단일 트랜잭션 호출 (백엔드 책임) |
| J3 | §1 "Save to my profile" 기본 true |
| J6 | §1 UEN 클라 정규식 + 서버 400 대응 |
| T1~T4 | §3 드롭다운이 `GET /api/document-types` 응답 7종·MIME·크기·label 그대로 렌더 |
| D1, D2, D3, D4 | §2 업로드 플로우 + §3 OTHER label 필수 + §6 에러 처리 |
| D5, D6 | 백엔드 — UI 영향은 Phase 3에서 |
| U1 | §2 자발적 카드만 렌더 (request 없음) |
| U2 | §2 상태 전이 Success — 목록 즉시 반영 + 토스트 |
| U3 | §7(3) 4-state skeleton Storybook |
| U4 | §2 InfoBox + §8 Phase 1 연속성 |
| L1~L4 | 백엔드 — UX 영향 없음(JIT로 사전 차단) |
