# Phase 3 — LEW 서류 요청 워크플로 UX 설계

**작성일**: 2026-04-17
**근거 스펙**: `01-spec.md` AC-R1~R6, S1~S6, LU1~LU4, AU1~AU4, N1~N3, P1~P2
**설계 원칙**: 상태 가시성 · 오류 방지 · 재업로드 마찰 최소화 · Phase 1/2 톤 연속성

---

## 1. 컴포넌트 A — DocumentRequestModal (LEW 체크리스트)

### 트리거
`/admin/applications/:id` 또는 `/lew/applications/:id` 상단 "서류 요청 / Request Documents" 버튼. ADMIN 또는 assigned LEW 이외에는 버튼 자체 미노출(AC-R4).

### 레이아웃 (Desktop 600px, 최대 높이 80vh, 내부 스크롤)
```
┌────────────────────────────────────────────────────┐
│ 📋 Request Documents from Applicant         [×]   │
│ Applicant: Jane Tan · APP-2026-000412              │
│ ────────────────────────────────────────────────── │
│ Select the documents you need. Applicant will be   │
│ notified immediately.                              │
│                                                    │
│ ┌────────────────────────────────────────────┐ ▲  │
│ │ ☑ 📄 SP Account Holder PDF                   │  │
│ │     PDF · up to 10 MB                         │  │
│ │     Note to applicant (optional)              │  │
│ │     [ 명의 변경본으로 부탁드립니다.       ]  │  │
│ ├────────────────────────────────────────────┤    │
│ │ ☑ 📝 Letter of Authorisation (LOA)           │    │
│ │     PDF · up to 10 MB                         │    │
│ │     [ Signed page missing — re-send.      ]  │    │
│ ├────────────────────────────────────────────┤    │
│ │ ☐ 📷 Main Breaker Photo                      │    │
│ ├────────────────────────────────────────────┤    │
│ │ ☐ 📐 Single Line Diagram                     │    │
│ ├────────────────────────────────────────────┤    │
│ │ ☑ 📎 Other                                   │    │
│ │     Label *  [ Tenancy Agreement        ]    │    │
│ │     Note     [ PDF scan, all pages      ]    │    │
│ └────────────────────────────────────────────┘ ▼  │
│                                                    │
│ ⓘ 3 of 10 active requests will be used.            │
│                                                    │
│             [ Cancel ]    [ Send 3 Requests ]      │
└────────────────────────────────────────────────────┘
```

### 인터랙션
- 체크 시 해당 row가 `bg-primary/5`로 하이라이트, memo 입력 필드 fade-in (200ms).
- OTHER 체크 시 customLabel 필드 필수(asterisk + `aria-required`), 비어 있으면 Submit disabled.
- Submit 버튼 라벨은 선택 개수에 따라 동적: `Send 3 Requests` / `Send 1 Request` / `Select at least one`(disabled).
- 이미 active(REQUESTED/UPLOADED) 요청이 존재하는 타입은 체크박스 옆에 `• Already pending` 배지 + 체크 시도 시 disabled + tooltip "이미 요청 중입니다 (#41)"  → 409 사전 차단(AC-R5).
- active 10건 도달 시 전체 체크박스 disabled + 상단 경고 배너 "You have reached the maximum (10) active requests. Approve/reject existing ones first."(AC 소프트 리밋).

### 상태 전이
- Idle → Submitting(버튼 스피너, 모달 전체 `aria-busy`) → 201 성공 시 모달 close + 상세 페이지 요청 섹션 최상단 스크롤 + 토스트 "Requested 3 documents ✓".
- 부분 실패는 없음(배치 트랜잭션, AC-R2/R3). 409 DUPLICATE_ACTIVE_REQUEST → 해당 row inline error + 체크 해제 강제.
- 400 `ITEMS_EMPTY`는 발생 불가(클라 가드).

### 마이크로카피 (EN / KO)
| 요소 | EN | KO |
|---|---|---|
| Title | Request Documents from Applicant | 신청자에게 서류 요청 |
| Subhead | Applicant: {name} · {appCode} | 신청자: {name} · {appCode} |
| Intro | Select the documents you need. Applicant will be notified immediately. | 필요한 서류를 선택하세요. 신청자에게 즉시 알림이 전송됩니다. |
| Memo label | Note to applicant (optional) | 신청자에게 전할 메모 (선택) |
| Memo placeholder | e.g. High-res scan, all pages | 예: 전체 페이지 고해상도 스캔 |
| OTHER label | Label * | 라벨 * |
| OTHER placeholder | Describe the document | 서류 설명 |
| Quota hint | {n} of 10 active requests will be used. | 현재 요청 포함 활성 요청 {n}/10건 |
| Already pending badge | Already pending #{id} | 이미 요청 중 #{id} |
| Cancel | Cancel | 취소 |
| Submit (n=0) | Select at least one | 최소 1건 선택 |
| Submit (n≥1) | Send {n} Request(s) | {n}건 요청 보내기 |
| Limit banner | Maximum 10 active requests reached. | 활성 요청 한도(10건)에 도달했습니다. |
| Success toast | Requested {n} documents ✓ | 서류 {n}건 요청 완료 |

---

## 2. 컴포넌트 B — LEW 신청 상세 "요청 서류" 섹션

### 배치
`AdminApplicationDetailPage` Documents 섹션 상단에 신설. 기존 업로드 목록은 하위로 유지. 페이지 헤더 우측에 `[+ 서류 요청]` primary 버튼.

```
┌─ 📋 Document Requests (4) ──────────────── [+ Request Documents] ┐
│                                                                   │
│ ┌─ #42 · LOA ──────────────── [REQUESTED 🟡] ────────────────┐  │
│ │ Requested 2026-04-17 10:02 by you                           │  │
│ │ Note: "Signed page missing — re-send."                      │  │
│ │                                          [ Cancel Request ] │  │
│ └─────────────────────────────────────────────────────────────┘  │
│                                                                   │
│ ┌─ #41 · SP Account Holder PDF ── [UPLOADED 🔵] ──────────────┐  │
│ │ Uploaded 2026-04-17 11:43 · 1.2 MB · [Download] [Preview]   │  │
│ │ Applicant note: "Renamed file."                              │  │
│ │                               [ Reject ]   [ Approve ✓ ]    │  │
│ └─────────────────────────────────────────────────────────────┘  │
│                                                                   │
│ ┌─ #39 · Main Breaker Photo ───── [REJECTED ⚠] ──────────────┐  │
│ │ Rejected by you · "Too blurry, reshoot at 200dpi+"          │  │
│ │ Awaiting applicant re-upload…                                │  │
│ └─────────────────────────────────────────────────────────────┘  │
│                                                                   │
│ ┌─ #37 · SLD File ───────────── [APPROVED ✓] ────────────────┐  │
│ │ Approved 2026-04-16 · [Download]                             │  │
│ └─────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────┘
```

### 액션 & 상태 피드백
- **Cancel Request**: `AlertDialog` "Cancel request #{id}? Applicant will no longer see it."  → 200 후 카드 즉시 제거 + 토스트 "Request cancelled".
- **Approve**: 낙관적 전이 — 배지 즉시 `APPROVED ✓`, 하단 버튼 제거, 실패 시 롤백 + 에러 토스트.
- **Reject**: `RejectReasonModal` 오픈 (§2-1).
- status가 UPLOADED/APPROVED/REJECTED인 카드의 Cancel 버튼은 **렌더링하지 않음**(AC-S5 불법 전이 차단 — UI에서 제거가 409보다 명확).
- 카드 우상단 타임라인 아이콘 hover → 상태 이력 툴팁: "Requested→Uploaded→Rejected→Uploaded".

### 2-1. RejectReasonModal
```
┌──────────────────────────────────────────┐
│ Reject this upload?                  [×] │
│ Document: Main Breaker Photo (#39)       │
│ ──────────────────────────────────────── │
│ Reason (shared with applicant) *         │
│ ┌──────────────────────────────────────┐ │
│ │                                       │ │
│ │                                       │ │
│ └──────────────────────────────────────┘ │
│ 0 / 500 · minimum 10                     │
│                                          │
│         [ Cancel ]  [ Reject & Notify ]  │
└──────────────────────────────────────────┘
```
- 10자 미만이면 Submit disabled + helper "Please explain in at least 10 characters."
- 신청자에게 그대로 노출됨을 헬퍼로 명시(오톤/민감 표현 억제).

---

## 3. 컴포넌트 C — 신청자 측 "요청 서류" 섹션 + 상단 배너

### 3-1. 상단 배너 (REQUESTED 또는 REJECTED ≥ 1건)
```
┌──────────────────────────────────────────────────────────┐
│ 🔔 Your LEW requested 2 document(s)                       │
│    1 rejected · please re-upload            [ View ↓ ]   │
└──────────────────────────────────────────────────────────┘
```
- 배경 `bg-warning/10 border-warning/40` — Phase 1/2 InfoBox(`bg-info/10`)보다 한 단계 강함(주의).
- "View" 클릭 → `#doc-requests` 앵커로 smooth scroll, focus-ring 강조.
- 모든 요청이 APPROVED/CANCELLED가 되면 배너 자동 숨김.

### 3-2. DocumentRequestCard — Phase 3 프로덕션 4 variant

**requested (🟡 업로드 대기)**
```
┌─ 📝 Letter of Authorisation ──── [Action needed 🟡] ─┐
│ Requested by LEW · 2026-04-17 10:02                    │
│ Note from LEW: "Signed page missing — re-send."        │
│ 💡 See example · [Download template]                   │
│ ┌──────────────────────────────────────────────────┐  │
│ │   ⬆  Drag & drop or click                         │  │
│ │      PDF · up to 10 MB                            │  │
│ └──────────────────────────────────────────────────┘  │
│                                      [ Upload ]        │
└────────────────────────────────────────────────────────┘
```

**uploaded (🔵 LEW 승인 대기)**
```
┌─ 📝 Letter of Authorisation ──── [Waiting for LEW 🔵] ─┐
│ You uploaded loa_signed.pdf · 820 KB · 12:04            │
│ LEW is reviewing. You will be notified.                 │
│                       [ Replace file ]  [ Download ]    │
└─────────────────────────────────────────────────────────┘
```

**approved (✓ 승인됨)**
```
┌─ 📝 Letter of Authorisation ──── [Approved ✓] ────────┐
│ Approved by LEW · 2026-04-17 13:10                     │
│                                          [ Download ]  │
└────────────────────────────────────────────────────────┘
```

**rejected (⚠ 반려 — 재업로드)**
```
┌─ 📷 Main Breaker Photo ─────── [Needs re-upload ⚠] ───┐
│ LEW rejected your upload.                              │
│ ┌──────────────────────────────────────────────────┐  │
│ │ "Too blurry, reshoot at 200dpi+"                  │  │
│ └──────────────────────────────────────────────────┘  │
│ Previous: breaker_v1.jpg (kept in history)             │
│ ┌──────────────────────────────────────────────────┐  │
│ │   ⬆  Upload a new version                         │  │
│ └──────────────────────────────────────────────────┘  │
│                                  [ Upload new file ]   │
└────────────────────────────────────────────────────────┘
```

### 3-3. 대시보드 배지
- `/applications` 테이블에서 미완료(REQUESTED | REJECTED ≥1) 신청 row 우측에 `🟡 2 awaiting` 배지. 클릭 시 해당 상세 페이지 `#doc-requests` 앵커 이동.

### 3-4. 신청자 카드 마이크로카피
| 요소 | EN | KO |
|---|---|---|
| Banner title | Your LEW requested {n} document(s) | LEW가 서류 {n}건을 요청했습니다 |
| Banner sub (rejected) | {k} rejected · please re-upload | {k}건 반려 · 재업로드가 필요합니다 |
| Banner CTA | View | 보기 |
| Status: requested | Action needed | 업로드 필요 |
| Status: uploaded | Waiting for LEW | LEW 검토 중 |
| Status: approved | Approved | 승인됨 |
| Status: rejected | Needs re-upload | 재업로드 필요 |
| LEW note label | Note from LEW | LEW 메모 |
| Rejection reason label | LEW rejected your upload. | LEW가 업로드를 반려했습니다. |
| Previous file hint | Previous: {filename} (kept in history) | 이전 파일: {filename} (이력 보존) |
| Replace CTA | Replace file | 파일 교체 |
| Upload CTA (requested) | Upload | 업로드 |
| Upload CTA (rejected) | Upload new file | 새 파일 업로드 |
| Success toast (fulfill) | Uploaded · LEW will be notified | 업로드 완료 · LEW에게 알립니다 |

---

## 4. 컴포넌트 D — 인앱 알림 (bell icon)

### 벨 배지
- 미확인 알림 수를 벨 우상단 dot + 숫자로 표시(최대 99+). Phase 3에서 폴링 주기 60초 → **30초**로 단축(스펙 handoff 참조).
- 배지는 색상(빨강) + 숫자 + `aria-label="3 unread notifications"` 3중 전달.

### 드롭다운 아이템 템플릿 (4종)
| 이벤트 | 대상 | 제목 | 본문 | 라우팅 |
|---|---|---|---|---|
| DOCUMENT_REQUESTED | 신청자 | `LEW가 서류 {n}건을 요청했습니다` | `신청 {appCode} · 지금 업로드하세요` | `/applications/{id}#doc-requests` |
| DOCUMENT_REQUEST_FULFILLED | LEW | `{applicantName}님이 {label}을 업로드했습니다` | `신청 {appCode} · 검토가 필요합니다` | `/admin/applications/{id}#doc-req-{reqId}` |
| DOCUMENT_REQUEST_APPROVED | 신청자 | `LEW가 {label}을 승인했습니다 ✓` | `신청 {appCode}` | 동일 |
| DOCUMENT_REQUEST_REJECTED | 신청자 | `LEW가 {label}을 반려했습니다` | `사유: "{reasonTrimmed60}…" · 재업로드해 주세요` | 동일 |

- 알림 row 클릭 시 자동 `markRead` + 라우팅. `aria-live="polite"`로 벨 드롭다운 열렸을 때 신규 알림 선언.

---

## 5. 이메일 알림

### 템플릿 3종 (HTML, 영문 기본 + 한국어 병기 한 블록)
1. **document-requested.html** — 신청자
2. **document-decision.html** — 신청자(approved/rejected 분기, rejected 시 사유 블록)
3. **document-fulfilled.html** — LEW

### 공통 구조
```
┌ LicenseKaki 헤더(워드마크) ──────────────────────┐
│ {{greeting, applicantName / lewName}}             │
│                                                   │
│ {{core sentence EN}}                              │
│ {{core sentence KO, italic, muted}}               │
│                                                   │
│ ─ Details ───────────────────────────────────    │
│ Application: {appCode}                            │
│ Document(s): {labels or count}                    │
│ (rejected only) Reason: "{reason}"                │
│                                                   │
│          [ Open in LicenseKaki → ]  (button)      │
│                                                   │
│ You are receiving this because you are the        │
│ {role} on this application.                       │
└──────────────────────────────────────────────────┘
```

### 제목 라인 (EN / KO 병기, 한 줄)
| 이벤트 | Subject |
|---|---|
| REQUESTED | `[LicenseKaki] Your LEW requested {n} document(s) · LEW가 서류를 요청했습니다` |
| FULFILLED | `[LicenseKaki] {applicant} uploaded {label} — please review` |
| APPROVED | `[LicenseKaki] {label} approved · {label} 승인됨` |
| REJECTED | `[LicenseKaki] {label} needs re-upload · {label} 재업로드 필요` |

- 본문 언어: 영문 1문단 + 한국어 1문단 병기 (Phase 2 이메일 관행 유지).
- CTA 버튼 1개만(딥링크). 푸터에 알림 설정 링크(Phase 4 토의, 지금은 정적 안내).

---

## 6. 상태 전이 사용자 피드백 요약

| 액터 | 액션 | 피드백 |
|---|---|---|
| LEW | 요청 생성 | 모달 close · 토스트 "Requested {n} ✓" · 요청 섹션 최신 항목으로 스크롤 + 2초 하이라이트 |
| 신청자 | 업로드(fulfill) | 카드 즉시 `uploaded` variant + 토스트 "Uploaded · LEW will be notified" + 인앱/이메일 LEW 발송 |
| LEW | 승인 | 카드 `approved` variant(낙관적) + 신청자 인앱+이메일 |
| LEW | 반려 | 모달 close + 카드 `rejected` variant + 신청자 인앱+이메일(사유 포함) + 상단 배너 재등장 |
| LEW | 취소 | 카드 제거 + 토스트 "Cancelled" · 신청자 알림 없음(의사결정: 취소는 조용히 처리) |

---

## 7. 에지 케이스 UX

| 케이스 | 처리 |
|---|---|
| LEW가 동일 타입을 이미 active 상태에서 재요청 | 모달 체크박스 disabled + "Already pending #{id}" 배지(AC-R5 사전 차단) |
| OTHER는 customLabel까지 비교 | 동일 customLabel일 때만 disabled, 다른 라벨이면 허용 |
| 신청자가 UPLOADED 상태에서 파일을 "삭제"하려 함 | 삭제 버튼 미노출. 대신 **Replace file**만 제공 — 명시적 교체만 허용하여 LEW 리뷰 중 공백 방지 |
| REJECTED 후 재업로드 | 같은 카드에서 dropzone 노출, 이전 파일은 "Previous: … (kept in history)"로 힌트만. 업로드 성공 시 variant → `uploaded`, 사유는 이력 툴팁에서 열람 |
| LEW 미할당 Application에 ADMIN이 요청 생성 | 허용. fulfilled 알림은 인앱 없이 skip(스펙), ADMIN 대시보드의 해당 신청 row는 "Uploaded, needs assignment" 라벨 |
| 활성 10건 한도 도달 | 모달 내부 체크박스 전부 disabled, 상단 경고 배너, Submit 비활성, 툴팁 "Approve/reject existing requests first" |
| 이메일 발송 실패 | 사용자 UI에는 표기 없음(비동기, 인앱 알림은 성공). 모니터링 로그만 |
| 신청자가 타 신청의 reqId로 fulfill 시도 | 404. 프런트는 일반 "Request not found" 에러 토스트(정보 누설 방지, AC-P2) |
| 반복 반려 (동일 카드 3회 이상) | UI 제약 없음(의사결정: 무제한). 카드에 `Replaced 3 times` 미세 카운터만 표기 — 사용자 자각 유도 |

---

## 8. 반응형 & 접근성

### 반응형
| Breakpoint | Modal | 요청 섹션 | 배너 |
|---|---|---|---|
| <640px | Full-screen bottom sheet, Submit sticky 하단, row memo 펼침 상태 유지 | 카드 1열, 액션 버튼 full-width 세로 스택 | 2줄, "View" 버튼 아래 단락 |
| 640–1024 | 중앙 모달 560px | 카드 1열 | 1줄 |
| >1024 | 중앙 모달 600px | 카드 1열 (가독성) | 1줄 |

- 모달 내부 리스트는 `max-height: 60vh; overflow-y: auto` — 헤더/Footer sticky.

### 접근성
- 모달 `role="dialog" aria-modal="true" aria-labelledby`. 첫 체크박스로 초기 포커스, ESC=Cancel(단, Submitting 중 무시).
- 체크박스 toggle 시 memo input이 포커스 트랩에 포함되도록 DOM 순서 유지.
- 카드 status 배지: 색상 + 아이콘(🟡🔵✓⚠) + 텍스트 라벨 3중.
- 상태 변화 영역 `aria-live="polite"` (예: 낙관적 Approve → "Document approved"). Reject는 `aria-live="assertive"`(주의 상승).
- Reject 사유 textarea `aria-describedby`로 "공유됨" 힌트 연결.
- 벨 드롭다운: 키보드 ↑/↓ 네비 + Enter 이동, Esc 닫기.
- 색 대비: 🟡 warning 배경 위 텍스트 AA 통과하는 토큰(`text-warning-foreground`) 사용.

---

## 9. Phase 2 연속성 체크

| Phase 2 요소 | Phase 3 변화 |
|---|---|
| InfoBox "Upload is optional for now" | **유지**. 문구 변경 없음. Phase 3는 그 위에 요청 카드 섹션을 얹는 구조. 단, 요청이 1건 이상이면 상단 **warning 배너**가 InfoBox보다 우선(배너 아래 InfoBox 그대로 노출) |
| DocumentRequestCard 4 variant skeleton | 프로덕션 활성화. Storybook 문서와 프로덕션 렌더 일치 검증 |
| DocumentRequestCardProps | 변경 없음(`request: DocumentRequest \| null` 이미 설계됨) |
| 자발적 업로드 카드 | 변경 없음. 요청 섹션과 **별도 리스트**로 공존 |
| Document Type 드롭다운 | Phase 2 그대로 재사용. LEW 모달 체크박스도 동일 catalog 렌더 |
| 색/간격 토큰 | 신규 없음. warning/success/destructive 기존 토큰만 사용 |

---

## 10. Designer 전달 사항

1. **체크리스트 모달 스크롤**: 리스트만 내부 스크롤, 헤더/Footer sticky. 체크된 row는 `bg-primary/5`로 시각 고정 — 스크롤해도 "무엇을 선택했는지" 파악 가능. 스크롤바는 thin variant.
2. **Rejected variant 강조 톤**: `border-destructive/40 bg-destructive/5`로 주의 환기하되, 상단 타이틀만 destructive 색, 본문은 neutral — "경고가 아니라 가이드" 톤. `⚠` 아이콘은 outline 스타일(filled는 공격적).
3. **신청자 배너 vs InfoBox**: 배너는 `bg-warning/10 border-warning/40` + 벨 이모지(🔔)로 Phase 1/2의 info 톤보다 한 단계 강함. 같은 페이지에서 두 박스가 공존할 때 **warning → info 순서**로 세로 배치, 시각 위계 명확히.
4. **벨 배지 숫자**: 1자리는 원형 8px dot, 2자리 이상은 알약(pill) 14px 높이. 99 초과는 `99+`. 색은 destructive(주목) + `text-destructive-foreground`. 모션은 신규 도착 시 150ms scale pop(once), `prefers-reduced-motion` 시 즉시.
5. **상태 배지 4색**: warning(🟡 Action needed) / info(🔵 Waiting) / success(✓ Approved) / destructive(⚠ Needs re-upload). Phase 2에서 공유한 토큰 그대로.
6. **낙관적 전이 모션**: Approve 클릭 → 카드 배지가 warning→success로 150ms crossfade, 버튼 영역 height 축소 200ms. 실패 롤백 시 shake 없이 즉시 원상복구 + 토스트.
7. **신규 컴포넌트 최소화**: `Dialog`, `Checkbox`, `Textarea`, `Button`, `Alert`, `Badge`, `AlertDialog` 재사용. 신규 컴포넌트 추가 금지.

---

## 11. AC 커버리지 매트릭스

| AC | 반영 위치 |
|---|---|
| R1, R2, R3, R5, R6 | §1 모달, 사전 disabled + inline error |
| R4 | 버튼 자체 비노출(권한 가드) |
| S1, S4 | §3 requested/rejected variant의 재업로드 경로 |
| S2 | §2 Approve 낙관적 전이 |
| S3 | §2-1 RejectReasonModal (min 10자) |
| S5 | §2 Cancel 버튼은 REQUESTED에서만 렌더 |
| S6 | UI에서 불법 전이 버튼 제거로 사전 차단 |
| LU1, LU2 | §1 모달 구성 |
| LU3 | §2 status 배지 + 액션 버튼 |
| LU4 | §2-1 반려 사유 모달 |
| AU1 | §3-1 warning 배너 |
| AU2 | §3-2 4 variant |
| AU3 | §3-3 대시보드 배지 |
| AU4 | §3-2 rejected "Previous: … (kept in history)" + 성공 시 `previousFileSeq` 보존 |
| N1, N2, N3 | §4 인앱 4종 + §5 이메일 3템플릿 |
| P1, P2 | 404 토스트 처리 §7 |
