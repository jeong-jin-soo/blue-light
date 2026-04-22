# LEW Service 주문 플로우 재설계안 — 방문형 서비스 전환

**작성일**: 2026-04-22
**작성자**: UX Expert
**배경**: LEW Service 주문이 SLD Manager 패턴의 "도면 납품" 플로우를 그대로 복제하고 있어, 실제 서비스 성격(LEW가 현장을 방문해 전기 공사/검사를 수행)과 괴리가 크다. 이 문서는 재설계의 **IA/스펙/PR 분할**을 정의한다. 목업·비주얼 디자인은 별도 작업.

---

## §1. 현재 LEW Service 플로우의 부적합 지점

**(1) 상태 머신이 SLD 도면 납품의 Copy-Paste**
`LewServiceOrderStatus.java:6-33` 9개 상태 중 `SLD_UPLOADED`가 그대로 살아있다(주석도 "Request for LEW Service 업로드 완료"). `LewServiceOrder.java:187-198`의 `uploadSld()` 메서드는 이름부터 도면 업로드를 전제로 하며, 허용 상태 전이가 `IN_PROGRESS/REVISION_REQUESTED/SLD_UPLOADED → SLD_UPLOADED`로만 되어있어 **현장 방문이라는 개념 자체가 상태 머신 어디에도 없다**.

**(2) "완료"의 정의가 파일 업로드와 결합되어 있음**
`LewServiceOrder.java:214-219` `complete()`는 `SLD_UPLOADED` 상태에서만 호출 가능. 즉 **파일을 업로드하지 않으면 주문을 완료시킬 수 없다**. 현장 공사는 "파일 없이 방문 자체가 결과물"인 경우가 존재(고장 수리, 절연 측정 Pass/Fail 통보)하는데 현재 모델로는 불가능하다.

**(3) 신청자 UI에 방문 일정·현장 수행 개념 부재**
`LewServiceOrderDetailPage.tsx:303-361`에서 `PAID`/`IN_PROGRESS` 메시지는 "work will begin shortly", "prepared ... once it is uploaded" 등 **"LEW가 언제 방문하느냐"라는 1순위 질문에 답이 없다**. 작업은 "현장에서 실행"되어야 한다.

**(4) 완료 증빙이 "도면 파일"로 고정**
`LewServiceOrderDetailPage.tsx:394-402`가 `uploadedFileSeq` 단일 파일 iframe PDF 프리뷰. 현장 수행 결과물은 보통 **여러 장의 사진 + 측정치 시트 + 방문 보고서(PDF) + 고객 사인** — 단일 파일 필드로는 표현 불가능.

**(5) Manager 업로드 엔드포인트까지 SLD 전제**
`LewServiceOrderFileController.java:82`에서 `FileType.DRAWING_SLD` hard-coded. 신청자 sketch는 `SKETCH_LEW_SERVICE`로 별도 타입이 있으므로 그 반대편도 `LEW_SERVICE_REPORT` 같은 새 타입이 필요.

**(6) 용어 혼용으로 인한 인지 부하**
`LewServiceOrderListPage.tsx:18`에서 `SLD_UPLOADED` 라벨을 "Deliverable Uploaded"로 덮어쓰는 등 **백엔드 값과 UI 라벨이 불일치**. 휴리스틱 위반: #2(실세계 일치), #4(일관성).

**(7) Manager에 일정 관리 도구 부재**
`LewServiceManagerOrderDetailPage.tsx`·`DashboardPage.tsx` 어디에도 "오늘 방문 예정" / "이번 주 일정" 뷰가 없다. LEW가 여러 주문을 병렬 처리할 때 캘린더/타임라인이 없으면 **물리적 이동 계획이 안 선다**.

---

## §2. To-Be 정보구조 (IA)

### 신청자 여정 (7단계)
```
1. Request LEW Service (폼)
2. Pending Quote ─ "팀이 검토 중"
3. Quote Proposed ─ 금액·예상 소요 시간 확인 → Accept/Reject
4. Pending Payment
5. Paid → VISIT_SCHEDULED   ← 신규: 방문 일시 표시 + 캘린더 추가
6. ON_SITE                   ← LEW 체크인 시 자동, "기술자 방문 중" 배너
7. VISIT_COMPLETED ─ 사진·보고서·측정치 확인 → Confirm / Request Revisit
   └ COMPLETED
```

### LEW Service Manager / LEW 여정
```
1. Order 검토 → Quote 제출
2. Payment 확인
3. 방문 일정 예약 (proposeVisitSchedule → applicant 합의)
4. 현장 도착 시 Check-in (타임스탬프 + GPS 선택)
5. 작업 중: 사진 다건 업로드, 메모
6. 체크아웃 + 방문 보고서 업로드 (PDF 또는 구조화 폼)
7. 신청자 확인 대기
```

### Admin 여정
- 대시보드 SLA: "오늘 방문 N건", "지연 방문 N건", "미확인 보고서 N건"
- 리스트 필터: `?status=VISIT_SCHEDULED&visitDate=2026-04-22`

---

## §3. 재설계 권고 (스펙 수준)

### (A) 상태 머신 재정의

| 현재 | 제안 | 설명 |
|---|---|---|
| PENDING_QUOTE | PENDING_QUOTE | 유지 |
| QUOTE_PROPOSED | QUOTE_PROPOSED | 유지 |
| QUOTE_REJECTED | QUOTE_REJECTED | 유지 |
| PENDING_PAYMENT | PENDING_PAYMENT | 유지 |
| PAID | PAID | 유지 |
| IN_PROGRESS | **VISIT_SCHEDULED** | 방문 일시 확정 |
| — | **ON_SITE** | 체크인 ~ 체크아웃 사이 (신규) |
| SLD_UPLOADED | **VISIT_COMPLETED** | 현장 완료 + 증빙 업로드, 신청자 확인 대기 |
| REVISION_REQUESTED | **REVISIT_REQUESTED** | 재방문 요청 (신청자) |
| COMPLETED | COMPLETED | 유지 |

전이: `PAID → VISIT_SCHEDULED (manager: scheduleVisit) → ON_SITE (lew: checkIn) → VISIT_COMPLETED (lew: checkOut+report) → COMPLETED (applicant: confirm) / REVISIT_REQUESTED → VISIT_SCHEDULED (루프)`.

### (B) 백엔드 필드 변경 (`LewServiceOrder.java`)

추가:
```
visitScheduledAt       DATETIME     -- 합의된 방문 예정 일시
visitScheduleNote      TEXT         -- "현관 벨 고장, 전화 주세요" 등
checkInAt              DATETIME     -- LEW 체크인 시각
checkOutAt             DATETIME     -- LEW 체크아웃 시각
visitReportFileSeq     BIGINT FK    -- 방문 보고서 (PDF 1개)
revisitComment         TEXT         -- (revisionComment에서 rename)
```

신규 하위 테이블 `lew_service_visit_photos(photo_seq PK, order_seq FK, file_seq FK, caption, uploaded_at)`.

제거/Deprecate: `uploadedFileSeq`는 legacy로 남기되 마이그레이션에서 `visitReportFileSeq`로 복사.

`FileType` enum 추가: `LEW_SERVICE_VISIT_PHOTO`, `LEW_SERVICE_VISIT_REPORT`. `DRAWING_SLD` 하드코딩(`LewServiceManagerOrderDetailPage.tsx:82`) 교체.

### (C) 신청자 상세 페이지 UI 모듈

- **VisitScheduleCard** (`status=VISIT_SCHEDULED`): 큰 캘린더 아이콘 + "Your LEW will visit on Wed 23 Apr, 14:00–16:00" + "Reschedule" / "Add to Calendar (ICS)"
- **OnSiteBanner** (`status=ON_SITE`): 상단 스티키 배너 "Your LEW is on site" + 체크인 시각 + 연락처
- **VisitReportViewer** (`status=VISIT_COMPLETED|COMPLETED`):
  - 방문 보고서 PDF 인라인
  - 사진 갤러리 (썸네일 그리드)
  - 체크인/아웃 시각 표시
  - "Confirm Completion" / "Request Revisit" 버튼

### (D) LEW Service Manager 상세 페이지 UI 모듈

- **VisitScheduleSection** (`status=PAID`): 날짜/시간 피커 + 메모 → `POST /api/lew-service-manager/orders/{id}/schedule-visit`
- **OnSiteChecklistCard** (`status=VISIT_SCHEDULED`): "Check In Now" 버튼
- **VisitCompletionForm** (`status=ON_SITE`):
  - Multiple photo upload (최대 10장, `FileType=LEW_SERVICE_VISIT_PHOTO`)
  - Visit report upload (PDF 1개, `FileType=LEW_SERVICE_VISIT_REPORT`)
  - Manager note
  - "Check Out & Submit Report" → `checkOutAt` 기록 + `VISIT_COMPLETED`
- **Dashboard 위젯 3종**: "Today's Visits", "Upcoming Visits", "Awaiting Applicant Confirmation"

### (E) 백엔드 신규 엔드포인트

```
POST /api/lew-service-manager/orders/{id}/schedule-visit   body: {visitScheduledAt, note}
POST /api/lew-service-manager/orders/{id}/check-in
POST /api/lew-service-manager/orders/{id}/check-out        body: {visitReportFileSeq, managerNote}
POST /api/lew-service-manager/orders/{id}/visit-photos     multipart file[]
POST /api/lew-service-orders/{id}/reschedule               body: {note}       (신청자)
POST /api/lew-service-orders/{id}/request-revisit          body: {comment}    (신청자, rename)
```

---

## §4. 작업 분할 (PR 단위)

### PR 1 — 용어/라벨 정리 (가장 안전)
- DB·상태 enum 변경 없음. 순수 UI 문자열만.
- `LewServiceOrderListPage.tsx` / `DetailPage.tsx` 의 `STATUS_CONFIG` 라벨·메시지에서 "uploaded"·"deliverable"·"SLD" → "visit"·"report" 교체
- Manager 측 `LewServiceManagerSection.tsx` 헤더 교체
- Dashboard 라벨 "Uploaded" → "Report Submitted"
- 테스트: 스냅샷 텍스트 업데이트
- **배포 리스크 0** — 라벨만 교체, 백엔드 영향 없음

### PR 2 — 일정 예약 기능 (데이터 추가, 전이 미변경)
- 백엔드: `visitScheduledAt`, `visitScheduleNote` 컬럼 + `scheduleVisit()` 도메인 메서드(상태 전이 없음). 신규 엔드포인트 `schedule-visit`
- 프론트: Manager `PAID` 카드에 일정 피커, Applicant 상세에 "Scheduled visit" 카드
- 기존 `IN_PROGRESS` 상태는 그대로, 데이터만 얹음. 기존 주문은 `visitScheduledAt=null`로 무해
- 테스트: CRUD, 권한, nullable 렌더링

### PR 3 — 현장 체크인/아웃 + 사진 + 상태 리네이밍
- DB 마이그레이션: `checkInAt`, `checkOutAt`, `visit_photos` 테이블, `FileType.LEW_SERVICE_VISIT_PHOTO/REPORT`
- 상태 enum: `IN_PROGRESS → VISIT_SCHEDULED`, `SLD_UPLOADED → VISIT_COMPLETED`, `REVISION_REQUESTED → REVISIT_REQUESTED`
- `uploadSld()` 제거, `checkIn()`/`checkOut(report, photos)` 도메인 메서드 추가
- 프론트: Manager On-site Panel, Applicant OnSiteBanner + VisitReportViewer
- 테스트: 상태 전이 전수, 사진 업로드 제한, 체크인 없이 체크아웃 차단

### PR 4 — Dashboard "Today's Visits" + Calendar View (선택)
- 대시보드 위젯 3종
- FullCalendar 또는 간단 주간 뷰
- 알림(이메일): "방문 24시간 전 리마인더"

---

## §5. 위험·전제

### (1) 방금 머지된 `f4a90ad` Manager 페이지 재사용 가능
카드 단위로 깨끗이 분리됐으므로 PR 1~2는 증분 수정 가능. PR 3에서 `LewServiceManagerSection` → `VisitCompletionSection` 교체(~150 LOC).

### (2) 데이터 마이그레이션
- `SLD_UPLOADED`/`COMPLETED` row: `uploadedFileSeq → visitReportFileSeq` 복사 (Flyway)
- `IN_PROGRESS` row: `VISIT_SCHEDULED`로 매핑, `visitScheduledAt=null` (UI에서 "TBD")
- 롤백 가능하도록 순수 RENAME + ADD COLUMN. 컬럼 DROP은 별도 PR

### (3) 신청자 교육
"도면 받는" 기대를 가진 신청자 위험. 완화:
- `NewLewServiceOrderPage.tsx` 헤더 아래 **"An on-site visit by a licensed electrical worker — not a drawing delivery"** 명시
- sketch 업로드 필드 라벨을 "참고용 현장 자료"로

### (4) SLD 주문·LEW Service 주문 분리 유지
도메인은 이미 별개(`lewserviceorder`, `sldorder` 패키지). 상태 분기 시 **SLD 엔진/AI 파이프라인 절대 호출 금지** — LEW Service는 AI 생성과 무관.

### (5) LEW "리뷰/CoF 작성"과의 구분
`lew-review-form-spec.md`·`phase3-lew-document-workflow`는 **면허 신청 리뷰**(이미 구현). LEW Service 방문과 별개. LEW 계정 네비게이션 분리 필요(PR 3에서):
- "Application Reviews" (기존)
- "On-site Service Orders" (재설계)

---

## 참조 파일

- `blue-light-frontend/src/pages/applicant/NewLewServiceOrderPage.tsx`
- `blue-light-frontend/src/pages/applicant/LewServiceOrderListPage.tsx`
- `blue-light-frontend/src/pages/applicant/LewServiceOrderDetailPage.tsx`
- `blue-light-frontend/src/pages/lew-service-manager/LewServiceManagerDashboardPage.tsx`
- `blue-light-frontend/src/pages/lew-service-manager/LewServiceManagerOrderDetailPage.tsx`
- `blue-light-frontend/src/pages/lew-service-manager/sections/LewServiceManagerSection.tsx`
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/lewserviceorder/LewServiceOrder.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/lewserviceorder/LewServiceOrderStatus.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/lewserviceorder/LewServiceOrderController.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/lewserviceorder/LewServiceOrderService.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/lewserviceorder/LewServiceOrderFileController.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/file/FileType.java`
