# Phase 3 — LEW 구조화 서류 요청 워크플로

**작성일**: 2026-04-17
**범위**: LEW가 신청자에게 서류를 플랫폼 내에서 요청·검토·승인/반려하는 정식 흐름. Phase 2 `document_request` 인프라를 활성화하고, 인앱/이메일 알림을 통해 신청자에게 즉시 전달한다.
**원칙**: 외부 채널(전화/이메일) 의존을 제거하고, 요청-응답-검토 전 과정을 **상태 머신 + 감사 로그**로 추적 가능하게 만든다.
**선행 배포**: Phase 1 3개 PR, Phase 2 4개 PR 전부 머지 완료 가정.

---

## 1. 사용자 스토리 & 측정 지표

### User Stories
- **US-1 (LEW)**: 신청 상세 페이지에서 "서류 요청" 버튼 한 번으로 표준 7종 중 필요한 항목을 **체크박스 + 메모**로 일괄 요청하고 싶다. 전화/이메일 대신 플랫폼이 추적해줘야 한다.
- **US-2 (신청자)**: LEW가 요청한 서류가 대시보드·신청 상세에 **배너와 카드**로 명시되어, 무엇을 올려야 하는지 즉시 알고 싶다.
- **US-3 (신청자)**: 반려된 서류는 사유와 함께 보이고, 같은 카드 안에서 바로 재업로드하고 싶다.
- **US-4 (LEW)**: 업로드된 요청 서류를 목록으로 모아 보고, 승인 또는 사유와 함께 반려를 한 번에 처리하고 싶다.
- **US-5 (신청자/LEW)**: 상대방 액션이 발생하는 즉시 인앱 벨 아이콘 + 이메일로 알림을 받고 싶다.
- **US-6 (관리자/감사)**: 누가 언제 어떤 요청을 생성·승인·반려·취소했는지 감사 로그로 추적할 수 있어야 한다.
- **US-7 (LEW)**: 이미 응답이 올라온(UPLOADED 이상) 요청은 취소 불가해야 혼선이 없다.

### 측정 지표 (배포 후 4주)
| 지표 | 베이스라인 | 목표 |
|---|---|---|
| 외부 채널(전화/이메일)로 서류 요청한 비율 | 100% (Phase 2) | ≤ 20% |
| LEW 요청 생성 → 신청자 업로드 평균 소요시간 | 측정 필요 | ≤ 24h (중앙값) |
| 반려 후 재업로드 완료율 | 측정 필요 | ≥ 80% |
| 요청 1건당 평균 반려 횟수 | 측정 필요 | ≤ 1.2회 |
| 알림 수신 후 첫 액션까지 소요시간 (median) | — | ≤ 4h |

---

## 2. 수용 기준 (Acceptance Criteria) — 23개

### A. LEW 요청 생성 (6개)
1. **AC-R1** GIVEN LEW가 신청 상세 모달, WHEN `POST /api/admin/applications/{id}/document-requests` 배치 호출, THEN 각 항목마다 `DocumentRequest`(status=REQUESTED) 레코드가 생성되고 201과 함께 생성된 id 배열을 반환한다.
2. **AC-R2** GIVEN 요청 body의 한 항목의 `documentTypeCode`가 catalog에 없음, THEN 전체 요청이 롤백되고 400 `UNKNOWN_DOCUMENT_TYPE`.
3. **AC-R3** GIVEN `documentTypeCode=OTHER`, WHEN `customLabel` 누락, THEN 400 `CUSTOM_LABEL_REQUIRED` (배치 전체 롤백).
4. **AC-R4** GIVEN 해당 Application에 **할당되지 않은 LEW**가 호출, THEN 403 `FORBIDDEN`. ADMIN은 모든 Application에 허용.
5. **AC-R5** GIVEN 동일 `documentTypeCode`로 status=REQUESTED/UPLOADED인 레코드가 이미 존재, THEN 409 `DUPLICATE_ACTIVE_REQUEST` (OTHER는 `customLabel`까지 비교).
6. **AC-R6** GIVEN 생성 성공, THEN `requested_by=current user`, `requested_at=NOW()`, 감사 로그 `DOCUMENT_REQUEST_CREATED` 기록.

### B. 상태 전이 (6개)
7. **AC-S1** GIVEN status=REQUESTED, WHEN 신청자 `POST /api/applications/{id}/document-requests/{reqId}/fulfill` 업로드, THEN UPLOADED 전이 + `fulfilled_file_seq/fulfilled_at` 기록.
8. **AC-S2** GIVEN status=UPLOADED, WHEN LEW `PATCH .../approve`, THEN APPROVED 전이 + `reviewed_by/reviewed_at` 기록.
9. **AC-S3** GIVEN status=UPLOADED, WHEN LEW `PATCH .../reject` with `{rejectionReason}`, THEN REJECTED 전이 + 사유 저장. `rejectionReason` 누락 시 400 `REJECTION_REASON_REQUIRED`.
10. **AC-S4** GIVEN status=REJECTED, WHEN 신청자 재업로드(fulfill), THEN UPLOADED로 복귀하고 `rejection_reason`은 보존되지만 `fulfilled_file_seq`는 새 파일로 갱신, `reviewed_at` NULL로 초기화.
11. **AC-S5** GIVEN status=REQUESTED, WHEN LEW `DELETE .../{reqId}`, THEN CANCELLED 전이 (soft delete 아님). status=UPLOADED/APPROVED/REJECTED에서는 409 `INVALID_STATE_TRANSITION`.
12. **AC-S6** GIVEN 위 외 모든 불법 전이(APPROVED→REJECTED 등), THEN 409 `INVALID_STATE_TRANSITION`와 현재 상태·요청 전이 명시 메시지.

### C. LEW UI (4개)
13. **AC-LU1** GIVEN 신청 상세(`/admin/applications/:id`) 페이지에 "서류 요청" 버튼, WHEN 클릭, THEN 모달이 열리고 `GET /api/document-types` 7종을 체크박스로 표시.
14. **AC-LU2** GIVEN 모달, WHEN 각 체크된 행에 `lewNote` 개별 입력 + OTHER 선택 시 `customLabel` 필수 표시, THEN Submit은 최소 1건 체크된 경우에만 활성화.
15. **AC-LU3** GIVEN 신청 상세 "요청 서류" 탭, THEN 요청 목록이 status 배지(REQUESTED/UPLOADED/APPROVED/REJECTED/CANCELLED)와 함께 카드로 렌더링. UPLOADED 카드에는 "승인"/"반려" 버튼, REQUESTED 카드에는 "취소" 버튼.
16. **AC-LU4** GIVEN LEW가 "반려" 클릭, THEN 사유 입력 모달(최소 10자) → 확인 시 `/reject` 호출.

### D. 신청자 UI (4개)
17. **AC-AU1** GIVEN 신청 상세 페이지에 1개 이상 REQUESTED/REJECTED 요청, THEN 상단에 노란색 배너 "LEW가 N건의 서류를 요청했습니다" 노출 + 스크롤 포커스 링크.
18. **AC-AU2** GIVEN "요청 서류" 섹션에서 Phase 2의 `DocumentRequestCard`의 4 variant 활성화, THEN REQUESTED는 업로드 영역, UPLOADED는 파일명+대기 문구, APPROVED는 체크 배지, REJECTED는 사유 + 재업로드 영역을 렌더링.
19. **AC-AU3** GIVEN 대시보드 신청 목록, THEN 미완료(REQUESTED 또는 REJECTED) 요청을 가진 신청 row에 "요청 대기 N" 배지 표시.
20. **AC-AU4** GIVEN 재업로드, WHEN 기존 `fulfilled_file_seq`가 있음, THEN 이전 파일은 soft delete되지 않고 보존되며 감사 로그 `DOCUMENT_REQUEST_FULFILLED`에 previous file seq 포함.

### E. 알림 (4개)
21. **AC-N1** GIVEN LEW가 요청 배치 생성, THEN 신청자에게 인앱 `Notification(type=DOCUMENT_REQUESTED)` 1건(배치당 1건, 요청 개수 메시지) + 이메일 1건 발송.
22. **AC-N2** GIVEN 신청자가 fulfill 업로드, THEN 해당 Application의 assigned LEW에게 인앱 + 이메일 (`DOCUMENT_REQUEST_FULFILLED`) 발송. LEW 미할당 시 인앱만.
23. **AC-N3** GIVEN LEW가 승인/반려, THEN 신청자에게 인앱 + 이메일 (`DOCUMENT_REQUEST_APPROVED` / `DOCUMENT_REQUEST_REJECTED` — 반려는 사유 포함) 발송.

### F. 권한/감사 (3개, 위와 중복 외 별도)
24. **AC-P1** GIVEN 감사 로그, THEN 5종 이벤트(`DOCUMENT_REQUEST_CREATED/FULFILLED/APPROVED/REJECTED/CANCELLED`)가 `actor_user_seq`, `application_seq`, `document_request_id`, `metadata(JSON)`와 함께 기록된다.
25. **AC-P2** GIVEN 신청자가 타 신청의 요청 id에 fulfill 시도, THEN 404 `DOCUMENT_REQUEST_NOT_FOUND` (path 불일치 검증; 정보 누설 방지로 403 대신 404).

---

## 3. 데이터 모델 변경

### 3-1. `document_request` (Phase 2에 이미 존재 — 활용)
- 추가 컬럼 없음. 기존 `status`, `fulfilled_file_seq`, `requested_by/at`, `reviewed_by/at`, `rejection_reason`로 충분.
- **인덱스 추가**: `KEY idx_dr_type_status (document_type_code, status)` — AC-R5 중복 감지 조회 최적화.

### 3-2. `notification` (Phase 3 연계)
- 기존 테이블/엔티티 존재 확인: `com.bluelight.backend.domain.notification.Notification` + `NotificationService.createNotification(...)`.
- **변경 1**: `NotificationType` enum에 4개 값 추가
  - `DOCUMENT_REQUESTED`, `DOCUMENT_REQUEST_FULFILLED`, `DOCUMENT_REQUEST_APPROVED`, `DOCUMENT_REQUEST_REJECTED`
- `reference_type='DOCUMENT_REQUEST'`, `reference_id=document_request_id`로 기록. 알림 클릭 시 프론트가 `/applications/:appId#doc-req-:id`로 라우팅.
- 테이블 DDL 변경 불필요 (enum은 VARCHAR에 저장).

### 3-3. `audit_log` (기존 패턴 재사용)
- Phase 2의 audit 패턴(`APPLICATION_STATUS_CHANGED` 등) 준수. `event_type VARCHAR`에 5종 상수 추가만 수행, 스키마 변경 없음.

### 3-4. schema.sql 수정 위치
- `blue-light-backend/src/main/resources/schema.sql` — `document_request` 테이블 블록에 `idx_dr_type_status` 인덱스 추가.
- 마이그레이션 파일: `doc/Project execution/phase3-lew-document-workflow/migration/V_01_add_dr_type_status_index.sql`.

---

## 4. API 스펙

### `POST /api/admin/applications/{id}/document-requests` (신규, ADMIN/LEW)
Request:
```json
{ "items": [
  { "documentTypeCode":"LOA",   "lewNote":"서명 누락본만 있습니다." },
  { "documentTypeCode":"OTHER", "customLabel":"임대차 계약서", "lewNote":"PDF 스캔본" }
]}
```
Response 201:
```json
{ "created": [{"id":41,"documentTypeCode":"LOA","status":"REQUESTED"}, {"id":42,"documentTypeCode":"OTHER","customLabel":"임대차 계약서","status":"REQUESTED"}] }
```
Errors: 400 `UNKNOWN_DOCUMENT_TYPE` / `CUSTOM_LABEL_REQUIRED` / `ITEMS_EMPTY`, 403 `FORBIDDEN`, 409 `DUPLICATE_ACTIVE_REQUEST`.

### `POST /api/applications/{id}/document-requests/{reqId}/fulfill` (Phase 2 존재, Phase 3에서 REJECTED→UPLOADED 경로 확장)
- multipart: `file` required.
- Response 200: `{ "id":41, "status":"UPLOADED", "fulfilledFileSeq":789, "previousFileSeq":null|<long> }`.

### `PATCH /api/admin/document-requests/{reqId}/approve` (신규)
- Empty body. Response 200: `{ "id":41, "status":"APPROVED", "reviewedAt":"2026-04-18T03:20:11Z" }`.

### `PATCH /api/admin/document-requests/{reqId}/reject` (신규)
Request: `{ "rejectionReason": "해상도가 낮아 서명 식별 불가. 200dpi 이상 재스캔 부탁드립니다." }` (min 10자)
Response 200: `{ "id":41, "status":"REJECTED", "rejectionReason":"...", "reviewedAt":"..." }`.

### `DELETE /api/admin/document-requests/{reqId}` (신규)
- Response 200: `{ "id":41, "status":"CANCELLED" }`. status≠REQUESTED 시 409.

### 알림 관련
- 알림 읽기는 Phase 2 이전부터 존재하는 `GET /api/notifications`, `PATCH /api/notifications/{id}/read`, `PATCH /api/notifications/read-all` 재사용. Phase 3 신규 엔드포인트 없음.

---

## 5. 상태 머신 & 권한 매트릭스

```
REQUESTED ──fulfill(신청자)──→ UPLOADED ──approve(LEW)──→ APPROVED
    │                              │
    │                              └──reject(LEW)──→ REJECTED ──fulfill(신청자)──→ UPLOADED
    │
    └──cancel(LEW)──→ CANCELLED
```

| 전이 | 허용 역할 | 검증 위치 |
|---|---|---|
| create → REQUESTED | ADMIN 또는 assigned LEW | `DocumentRequestService.createBatch` |
| REQUESTED → UPLOADED | Application 소유자 | `DocumentRequestService.fulfill` |
| REJECTED → UPLOADED | Application 소유자 | 동일 |
| UPLOADED → APPROVED | ADMIN 또는 assigned LEW | `DocumentRequestService.approve` |
| UPLOADED → REJECTED | 동일 | `DocumentRequestService.reject` |
| REQUESTED → CANCELLED | ADMIN 또는 assigned LEW | `DocumentRequestService.cancel` |
| 그 외 | 거부 (409) | 상태 머신 가드 |

- 권한 검증은 `@PreAuthorize` + 서비스 계층 이중 체크. LEW 할당 여부는 `application.assigned_lew_seq` 비교.
- 상태 전이 가드는 `DocumentRequestStatus.canTransitionTo(next)` 메서드로 중앙화 → 테이블 기반 테스트(`@ParameterizedTest`).

---

## 6. 알림 시스템 설계

### 조사 결과 (2026-04-17 기준)
- 인앱: `Notification` 엔티티 + `NotificationService.createNotification()` 이미 존재. 현재 `NotificationType`에는 `PAYMENT_CONFIRMED` 1개만 정의됨 → **enum 4종 추가 필요**.
- 이메일: `EmailService` 인터페이스 + `SmtpEmailService`/`LogOnlyEmailService` 구현 이미 존재. 이미 7종 템플릿 메서드 보유(password reset, license expiry, revision request, payment 등) → **메서드 3종 추가**.

### 추가할 이메일 메서드
```java
void sendDocumentRequestedEmail(String to, String userName, Long appSeq, int count, String lewName);
void sendDocumentRequestDecisionEmail(String to, String userName, Long appSeq, String documentLabel,
                                       boolean approved, String rejectionReason /* nullable */);
void sendDocumentFulfilledEmail(String to, String lewName, Long appSeq, String documentLabel, String applicantName);
```
- SMTP 구현은 기존 템플릿 파일 패턴(`email-templates/*.html`) 재사용.

### 통합 발송 Facade
- `DocumentRequestNotifier` (신규) — 각 상태 전이 훅에서 호출.
- 내부에서 `NotificationService.createNotification()` + `EmailService.send*()` 순차 호출.
- 이메일 실패는 **삼켜서 로그**만 남김 (비동기 `@Async` 적용으로 트랜잭션과 분리). 인앱은 동일 트랜잭션 내 저장.
- 수신자 해석:
  - 신청자 → `application.applicant_user_seq`
  - LEW → `application.assigned_lew_seq` (NULL이면 이메일 스킵, 인앱은 skip하거나 ADMIN 집합으로 fan-out은 **Phase 3 범위 외**)

---

## 7. 마이그레이션 전략

### 파일 구조
```
doc/Project execution/phase3-lew-document-workflow/migration/
  V_01_add_dr_type_status_index.sql
```
### V_01
```sql
ALTER TABLE document_request
  ADD INDEX idx_dr_type_status (document_type_code, status);
```
- 알림 테이블/enum은 DDL 변경 없음(VARCHAR enum). 백필 불필요.
- 감사 로그 테이블 변경 없음(event_type은 VARCHAR).
- 롤백: `ALTER TABLE document_request DROP INDEX idx_dr_type_status;`.

---

## 8. PR 분리 전략 (4개)

| PR | 제목 | 범위 | 의존 | 독립 배포 |
|---|---|---|---|---|
| **PR#1** | `feat(backend): DocumentRequest 상태 머신 + LEW 요청/검토 API` | 5개 엔드포인트, `DocumentRequestStatus.canTransitionTo`, 권한 검증, 감사 로그 5종, AC-R1~R6/S1~S6/P1~P2 MockMvc. 알림 호출은 no-op 훅으로. | — | ✅ |
| **PR#2** | `feat(frontend-lew): 서류 요청 모달 + 승인/반려 UI` | `DocumentRequestModal.tsx`, 상세 페이지 "요청 서류" 탭, 승인/반려/취소 버튼, 반려 사유 모달, API 클라이언트. | PR#1 | ✅ |
| **PR#3** | `feat(frontend-applicant): 요청 서류 카드 활성화 + 재업로드 + 인앱 알림 배너` | Phase 2 `DocumentRequestCard` 4 variant 프로덕션 활성화, 대시보드 배지, 신청 상세 상단 배너, 벨 아이콘 미확인 카운트 fetch 주기 단축. | PR#1 | ✅ |
| **PR#4** | `feat(backend): 알림 발송 — 인앱 + 이메일` | `NotificationType` enum 4종, `EmailService` 메서드 3종 + SMTP 템플릿, `DocumentRequestNotifier`, `@Async` 설정, AC-N1~N3. | PR#1 | ✅ (없어도 상태 전이는 정상) |

- PR 순서: PR#1 → (PR#2·PR#3·PR#4 병렬). PR#4는 기능적으로 가장 독립적이나 템플릿 검수 시간 고려 마지막 배포 권장.

---

## 9. 리스크 & 완화

| # | 리스크 | 영향 | 완화 |
|---|---|---|---|
| R1 | 악의/실수로 LEW가 동일 타입 요청 폭증 | 중 | AC-R5 `DUPLICATE_ACTIVE_REQUEST` 가드 + 의사결정 (c)의 rate limiting. |
| R2 | 신청자가 요청을 무기한 무시 | 중 | 만료/에스컬레이션은 Phase 3 범위 외. 대신 대시보드 "대기 N일" 표시만 제공. Phase 4에서 리마인더 job 검토. |
| R3 | 반려-재업로드 무한 루프 | 저 | 의사결정 (b)의 **무제한 허용** 채택. 반복 횟수는 감사 로그로 감지, 이상치는 ADMIN이 강제 CANCELLED. |
| R4 | 이메일 발송 실패로 트랜잭션 롤백 | 중 | `@Async` + 발송 실패를 로그만 남김. 인앱 알림은 동일 트랜잭션 내 저장하여 최소 보장. |
| R5 | 재업로드 시 기존 파일 상실 | 중 | AC-AU4 — previous file을 audit metadata에 기록, 파일 자체는 soft delete로만 처리. |
| R6 | NotificationType enum 신규값 누락 시 기존 직렬화 깨짐 | 저 | enum은 VARCHAR 저장. 기존 값(`PAYMENT_CONFIRMED`)에 영향 없음. 롤백 시 unused enum만 남음. |
| R7 | 권한 우회(ADMIN 아닌 LEW가 타 Application 조작) | 고 | 컨트롤러 `@PreAuthorize("hasAnyRole('ADMIN','LEW')")` + 서비스 계층에서 `assigned_lew_seq` 비교 이중 체크. |

---

## 10. 의사결정 확정 (2026-04-18)

- **(a) 이메일 알림 Phase 3 포함**: ✅ **포함 (PR#4)** — EmailService/SMTP 인프라 재사용
- **(b) 반려 재업로드 횟수 제한**: ✅ **무제한 허용** — 감사 로그 모니터링으로 이상 탐지
- **(c) LEW 요청 Rate Limiting**: ✅ **Application당 active request ≤ 10건 소프트 리밋** — 초과 시 409 `TOO_MANY_ACTIVE_REQUESTS`

---

## 11. 개발자 Handoff 체크리스트

### Backend (PR#1)
- [ ] `DocumentRequestStatus` enum + `canTransitionTo(next)` + 단위 테스트(파라미터화)
- [ ] `DocumentRequestService`: `createBatch`, `fulfill`(확장), `approve`, `reject`, `cancel`
- [ ] `DocumentRequestController` (admin) + `ApplicationDocumentController`(기존 fulfill 확장)
- [ ] DTO: `CreateDocumentRequestsRequest`, `DocumentRequestItemRequest`, `RejectDocumentRequest`, 기존 `DocumentRequestResponse`에 `previousFileSeq` 추가
- [ ] 권한 가드: `@PreAuthorize` + assigned LEW 비교 유틸
- [ ] AuditLog 5종 이벤트 타입 상수 추가
- [ ] schema.sql 인덱스 + migration/V_01
- [ ] MockMvc: AC-R1~R6, AC-S1~S6, AC-P1~P2

### Backend (PR#4)
- [ ] `NotificationType` 4종 추가 + 기존 `PAYMENT_CONFIRMED` 회귀 테스트
- [ ] `EmailService` 메서드 3종 + SMTP 템플릿 3개(`email-templates/document-*.html`)
- [ ] `DocumentRequestNotifier` — 상태 전이 훅 연결
- [ ] `@EnableAsync` 확인, `@Async` 발송 메서드에 적용
- [ ] AC-N1~N3 통합 테스트 (MailPit 또는 Mockito spy)

### Frontend (PR#2, LEW)
- [ ] `DocumentRequestModal.tsx` — Document Type 체크 + row memo + OTHER customLabel
- [ ] `AdminApplicationDetailPage.tsx`에 "요청 서류" 탭 + 승인/반려/취소 버튼
- [ ] `RejectReasonModal.tsx` (min 10자)
- [ ] API 클라이언트: `createDocumentRequests`, `approveDocumentRequest`, `rejectDocumentRequest`, `cancelDocumentRequest`

### Frontend (PR#3, Applicant)
- [ ] `DocumentRequestCard` 4 variant 활성화 (Phase 2에서 스켈레톤으로 존재)
- [ ] `ApplicationDetailPage.tsx` 상단 배너 + 섹션 앵커
- [ ] `DashboardPage.tsx` 신청 row 배지
- [ ] 벨 아이콘 폴링 주기 단축(예: 30초) — 의사결정 범위 외이나 UX 연동 필요시 토의

### Tests
- [ ] Backend: 상태 머신 16케이스, 권한 매트릭스 회귀
- [ ] Backend: 알림 발송 성공/실패 분기(@Async exception swallow)
- [ ] Frontend: DocumentRequestModal, DocumentRequestCard 각 variant 렌더링
- [ ] E2E: "LEW 요청 → 신청자 업로드 → LEW 반려 → 재업로드 → 승인" 골든 경로

### Ops
- [ ] 배포 순서: PR#1 → PR#4 → PR#2/PR#3
- [ ] LEW 공지: "Phase 3 배포 완료 — 이제 플랫폼에서 서류 요청 가능. 전화/이메일 요청 중단 권장."
- [ ] 이메일 템플릿 QA (스테이징 MailPit)
- [ ] 배포 D-day에 지표 베이스라인 snapshot 쿼리 실행
