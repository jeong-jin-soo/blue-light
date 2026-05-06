# Phase 3 테스트 계획서 — LEW 서류 요청 워크플로

**작성일**: 2026-04-17
**QA 담당**: tester agent
**대상 스펙**: `01-spec.md` AC-R1~R6, AC-S1~S6, AC-LU1~LU4, AC-AU1~AU4, AC-N1~N3, AC-P1~P2 (25개)
**대상 PR**: PR#1 (상태 머신 + API), PR#2 (LEW UI), PR#3 (신청자 UI + 인앱), PR#4 (이메일 알림)

---

## 1. 테스트 전략

### 테스트 환경

| 레이어 | 도구 | 비고 |
|---|---|---|
| 백엔드 | JUnit 5 + Spring Boot Test (MockMvc) | PR#1·PR#4 자동화 |
| 프론트엔드 단위 | 없음 (vitest 미설치) | `npm run build` + 수동 |
| E2E | Playwright 미설치 | 수동 시나리오로 대체 |
| API 통합 | curl / Postman | 로컬 백엔드(8090) 기준 |
| 이메일 | MailPit (로컬), SMTP 수신함 (dev) | PR#4 대상 |

### 자동화 / 수동 배분

| 분류 | 자동화 | 수동 |
|---|---|---|
| 상태 머신 전이 | `@ParameterizedTest` 16케이스 | — |
| 권한 검증 | MockMvc (`@WithMockUser`) | — |
| 알림 발송 | Mockito spy (`@Async` swallow 포함) | SMTP 수신함 |
| 프론트엔드 빌드 | `npm run build` | 모달/카드/배너 DOM |

---

## 2. AC 매트릭스 — 25개

| AC ID | 설명 요약 | TC ID | 자동화 |
|---|---|---|---|
| AC-R1 | 배치 생성 → 201 + id 배열, status=REQUESTED | TC-REQ-01 | MockMvc |
| AC-R2 | 미등록 documentTypeCode → 400 `UNKNOWN_DOCUMENT_TYPE`, 전체 롤백 | TC-REQ-06 | MockMvc |
| AC-R3 | OTHER + customLabel 누락 → 400 `CUSTOM_LABEL_REQUIRED` | TC-REQ-02 | MockMvc |
| AC-R4 | 미할당 LEW → 403; ADMIN → 허용 | TC-REQ-04, TC-REQ-05 | MockMvc |
| AC-R5 | REQUESTED/UPLOADED 동일 타입 중복 → 409 `DUPLICATE_ACTIVE_REQUEST` | TC-REQ-03 | MockMvc |
| AC-R6 | 생성 성공 → requested_by/at, 감사 로그 `DOCUMENT_REQUEST_CREATED` | TC-REQ-01 | MockMvc |
| AC-S1 | REQUESTED→UPLOADED (fulfill) + fulfilled_file_seq/at 기록 | TC-STATE-01 | MockMvc |
| AC-S2 | UPLOADED→APPROVED + reviewed_by/at | TC-STATE-02 | MockMvc |
| AC-S3 | UPLOADED→REJECTED + rejectionReason; 사유 누락 → 400 | TC-STATE-03 | MockMvc |
| AC-S4 | REJECTED→UPLOADED (재업로드): 사유 보존, reviewed_at NULL화 | TC-STATE-04 | MockMvc |
| AC-S5 | REQUESTED→CANCELLED (LEW DELETE); UPLOADED/APPROVED/REJECTED → 409 | TC-STATE-05 | MockMvc |
| AC-S6 | 불법 전이(APPROVED→REJECTED 등) → 409 `INVALID_STATE_TRANSITION` | TC-STATE-06 | MockMvc |
| AC-LU1 | LEW 모달 열기 → document-types 7종 체크박스 렌더 | TC-UI-LEW-01 | 수동 |
| AC-LU2 | 체크 시 lewNote 입력; OTHER → customLabel 필수; Submit 최소 1건 | TC-UI-LEW-02, TC-UI-LEW-03 | 수동 |
| AC-LU3 | 요청 목록 status 배지 + 버튼(UPLOADED: 승인/반려, REQUESTED: 취소) | TC-UI-LEW-04 | 수동 |
| AC-LU4 | 반려 클릭 → 사유 모달(min 10자) → /reject 호출 | TC-UI-LEW-04 | 수동 |
| AC-AU1 | 신청 상세 상단 warning 배너 (REQUESTED/REJECTED ≥1건) | TC-UI-APP-01 | 수동 |
| AC-AU2 | DocumentRequestCard 4 variant 프로덕션 활성화 | TC-UI-APP-02, TC-UI-APP-03 | 수동 |
| AC-AU3 | 대시보드 "요청 대기 N" 배지 | TC-UI-APP-04 | 수동 |
| AC-AU4 | 재업로드 시 이전 파일 보존 + 감사 로그 previousFileSeq | TC-STATE-04 | MockMvc |
| AC-N1 | 요청 배치 생성 → 신청자 인앱 1건 + 이메일 1건 | TC-NOTI-01 | MockMvc+MailPit |
| AC-N2 | fulfill 업로드 → 할당 LEW 인앱 + 이메일 | TC-NOTI-02 | MockMvc+MailPit |
| AC-N3 | 승인/반려 → 신청자 인앱 + 이메일(반려 시 사유 포함) | TC-NOTI-03, TC-NOTI-04 | MockMvc+MailPit |
| AC-P1 | 감사 로그 5종 이벤트 + actor/application/metadata | TC-REQ-01 (DB 확인) | MockMvc |
| AC-P2 | 타 신청의 reqId로 fulfill → 404 `DOCUMENT_REQUEST_NOT_FOUND` | TC-AUTH-01 | MockMvc |

---

## 3. 시나리오 기반 테스트 케이스

### LEW 요청 생성 (TC-REQ-01~06)

**TC-REQ-01** (MockMvc / AC-R1, AC-R6)
```
POST /api/admin/applications/{id}/document-requests (LEW 토큰, 할당된 신청)
Body: { "items": [{"documentTypeCode":"LOA","lewNote":"서명 누락"}, {"documentTypeCode":"SP_ACCOUNT"}] }
기대: 201, created[].status = "REQUESTED", created[].id 존재
DB: requested_by=lewUserSeq, requested_at NOT NULL
감사: audit_log.event_type = "DOCUMENT_REQUEST_CREATED", application_seq 포함
```

**TC-REQ-02** (MockMvc / AC-R3)
```
Body: { "items": [{"documentTypeCode":"OTHER"}] }  // customLabel 누락
기대: 400, errorCode: "CUSTOM_LABEL_REQUIRED", 배치 전체 롤백
```

**TC-REQ-03** (MockMvc / AC-R5)
- 전제: LOA status=REQUESTED 레코드 이미 존재
```
Body: { "items": [{"documentTypeCode":"LOA"}] }
기대: 409, errorCode: "DUPLICATE_ACTIVE_REQUEST"
```

**TC-REQ-04** (MockMvc / AC-R4)
```
// 미할당 LEW 토큰으로 타 신청에 요청 생성
기대: 403, errorCode: "FORBIDDEN"
```

**TC-REQ-05** (MockMvc / AC-R4)
```
// ADMIN 토큰으로 임의 신청에 요청 생성
기대: 201 Created (LEW 할당 여부 무관)
```

**TC-REQ-06** (MockMvc / AC-R2)
```
Body: { "items": [{"documentTypeCode":"NONEXISTENT_CODE"}] }
기대: 400, errorCode: "UNKNOWN_DOCUMENT_TYPE"
// 빈 배치도 검증
Body: { "items": [] }
기대: 400, errorCode: "ITEMS_EMPTY"
```

---

### 상태 전이 (TC-STATE-01~06)

**TC-STATE-01** (MockMvc / AC-S1)
```
// 전제: status=REQUESTED인 reqId
POST /api/applications/{id}/document-requests/{reqId}/fulfill (multipart, 신청자 토큰)
Fields: file=(pdf)
기대: 200, status: "UPLOADED", fulfilledAt NOT NULL, fulfilledFileSeq 존재
```

**TC-STATE-02** (MockMvc / AC-S2)
```
// 전제: status=UPLOADED
PATCH /api/admin/document-requests/{reqId}/approve (LEW 토큰)
기대: 200, status: "APPROVED", reviewedBy=lewSeq, reviewedAt NOT NULL
```

**TC-STATE-03** (MockMvc / AC-S3)
```
PATCH /api/admin/document-requests/{reqId}/reject
Body: { "rejectionReason": "해상도 200dpi 이상 재스캔 필요" }
기대: 200, status: "REJECTED", rejectionReason 저장
// 사유 누락
Body: {}
기대: 400, errorCode: "REJECTION_REASON_REQUIRED"
```

**TC-STATE-04** (MockMvc / AC-S4, AC-AU4)
```
// 전제: status=REJECTED, fulfilledFileSeq=100
POST .../fulfill (새 파일)
기대: 200, status: "UPLOADED"
DB 확인: rejection_reason 보존, fulfilled_file_seq=새값, reviewed_at IS NULL
감사: previousFileSeq=100 포함
```

**TC-STATE-05** (MockMvc / AC-S5)
```
// REQUESTED → CANCELLED
DELETE /api/admin/document-requests/{reqId} (LEW 토큰)
기대: 200, status: "CANCELLED"
// UPLOADED 상태에서 시도
기대: 409, errorCode: "INVALID_STATE_TRANSITION"
```

**TC-STATE-06** (MockMvc / AC-S6)
```
// APPROVED 상태에서 reject 시도
PATCH /api/admin/document-requests/{reqId}/reject
기대: 409, errorCode: "INVALID_STATE_TRANSITION", currentStatus: "APPROVED"
```

---

### 권한 (TC-AUTH-01~04)

**TC-AUTH-01** (MockMvc / AC-P2)
```
// 신청자 A의 토큰으로 신청 B에 속한 reqId에 fulfill 시도
POST /api/applications/{B.id}/document-requests/{reqId}/fulfill
기대: 404, errorCode: "DOCUMENT_REQUEST_NOT_FOUND"
```

**TC-AUTH-02** (MockMvc)
```
// 미할당 LEW가 approve 시도
PATCH /api/admin/document-requests/{reqId}/approve
기대: 403, errorCode: "FORBIDDEN"
```

**TC-AUTH-03** (MockMvc)
```
// LEW B가 LEW A의 요청 취소 시도
DELETE /api/admin/document-requests/{reqId}
기대: 403
```

**TC-AUTH-04** (MockMvc)
```
// ADMIN 토큰으로 approve/reject/cancel 전부 시도
기대: 각각 200 (정상 처리)
```

---

### 상태 머신 단위 테스트 (파라미터화)

`DocumentRequestStatus.canTransitionTo()` — 모든 조합 `@ParameterizedTest`:

| From | To | 결과 |
|---|---|---|
| REQUESTED | UPLOADED | true |
| REQUESTED | CANCELLED | true |
| REQUESTED | APPROVED | false |
| REQUESTED | REJECTED | false |
| UPLOADED | APPROVED | true |
| UPLOADED | REJECTED | true |
| UPLOADED | CANCELLED | false |
| UPLOADED | REQUESTED | false |
| REJECTED | UPLOADED | true |
| REJECTED | APPROVED | false |
| REJECTED | CANCELLED | false |
| APPROVED | REJECTED | false |
| APPROVED | UPLOADED | false |
| APPROVED | CANCELLED | false |
| CANCELLED | REQUESTED | false |
| CANCELLED | UPLOADED | false |

총 16케이스 `@ParameterizedTest(name="[{0}→{1}]={2}")`.

---

### LEW UI (TC-UI-LEW-01~04)

**TC-UI-LEW-01** (수동 / AC-LU1)
- 할당 LEW로 `/admin/applications/:id` 접근 → "서류 요청" 버튼 확인
- 클릭 → 모달 오픈 → document-types 7종 체크박스 렌더링 확인
- 미할당 LEW → 버튼 미노출 확인

**TC-UI-LEW-02** (수동 / AC-LU2)
- LOA 체크 → lewNote 입력 fade-in (200ms) 확인
- 0건 선택 → Submit "Select at least one" disabled 확인
- 1건 선택 → "Send 1 Request" 활성화

**TC-UI-LEW-03** (수동 / AC-LU2)
- OTHER 체크 → customLabel 필드 등장 (asterisk 표시) 확인
- customLabel 비워둔 채 Submit → disabled 유지
- customLabel 입력 후 Submit → 활성화

**TC-UI-LEW-04** (수동 / AC-LU3, AC-LU4)
- 요청 목록에서 상태별 버튼 확인:
  - REQUESTED 카드 → "Cancel Request" 버튼만
  - UPLOADED 카드 → "Approve" + "Reject" 버튼
  - APPROVED/REJECTED/CANCELLED 카드 → 액션 버튼 미노출
- "Reject" 클릭 → RejectReasonModal 오픈, 10자 미만 → Submit disabled

---

### 신청자 UI (TC-UI-APP-01~04)

**TC-UI-APP-01** (수동 / AC-AU1)
- REQUESTED 요청 1건 이상인 신청 상세 → 상단 warning 배너 노출
- 배너에 "LEW가 N건의 서류를 요청했습니다" 문구 확인
- "보기" 클릭 → `#doc-requests` smooth scroll 확인
- 모든 요청 APPROVED/CANCELLED 시 배너 자동 숨김

**TC-UI-APP-02** (수동 / AC-AU2)
- requested variant: dropzone + "Upload" 버튼, lewNote 표시
- uploaded variant: 파일명/크기/날짜 + "Replace file" 버튼, 업로드 버튼 없음
- approved variant: "Approved" 체크 배지 + "Download" 버튼만
- rejected variant: rejectionReason 블록 + dropzone + "Upload new file"

**TC-UI-APP-03** (수동 / AC-AU2, AC-S4)
- rejected 카드에서 파일 선택 → "Upload new file" 클릭 → 업로드 성공
- 카드 variant uploaded로 전환 확인 + "Previous: {filename} (kept in history)" 표시

**TC-UI-APP-04** (수동 / AC-AU3)
- 대시보드 신청 목록에서 미완료(REQUESTED/REJECTED ≥1) row → "요청 대기 N" 배지 확인
- 배지 클릭 → 신청 상세 `#doc-requests` 앵커 이동

---

### 알림 (TC-NOTI-01~04)

**TC-NOTI-01** (MockMvc+수동 / AC-N1)
- LEW 배치 요청 생성 후:
  - DB: `notification` 테이블에 type=`DOCUMENT_REQUESTED`, recipient=신청자 1건 (배치당 1건)
  - 로컬: MailPit에서 신청자 이메일 수신 확인, 제목에 요청 개수 포함
  - 신청자 로그인 → 벨 아이콘 미확인 카운트 +1

**TC-NOTI-02** (MockMvc+수동 / AC-N2)
- 신청자 fulfill 업로드 후:
  - DB: type=`DOCUMENT_REQUEST_FULFILLED`, recipient=할당 LEW
  - LEW 이메일 수신 + 인앱 알림 확인

**TC-NOTI-03** (MockMvc / AC-N3)
- LEW 승인 후:
  - DB: type=`DOCUMENT_REQUEST_APPROVED`, recipient=신청자
  - 신청자 이메일: 제목에 "승인됨" 포함

**TC-NOTI-04** (MockMvc / AC-N3)
- LEW 반려 후:
  - DB: type=`DOCUMENT_REQUEST_REJECTED`, rejection_reason 포함
  - 신청자 이메일: 본문에 사유 텍스트 포함 확인

---

## 4. 동시성 테스트

**TC-CONCURRENCY-01**
- 두 LEW 토큰으로 동일 reqId에 approve 동시 요청 (CompletableFuture 또는 Thread 2개)
- 기대: 한쪽만 200, 나머지 409 `INVALID_STATE_TRANSITION`
- DB: `reviewed_by` 컬럼에 단일 LEW seq만 기록, 이중 감사 로그 없음

---

## 5. 수동 검증 체크리스트 (PR 머지 전)

### PR#1 (상태 머신 + API)
- [ ] `./gradlew compileJava` 오류 없음
- [ ] `DocumentRequestStatus.canTransitionTo()` 파라미터화 16케이스 통과
- [ ] `POST /api/admin/applications/{id}/document-requests` 배치 생성 201 + 롤백 동작
- [ ] UPLOADED/APPROVED/REJECTED에서 DELETE → 409
- [ ] 감사 로그 5종 이벤트 DB 기록 확인
- [ ] `idx_dr_type_status` 인덱스 추가 (`SHOW INDEX FROM document_request`)
- [ ] TC-AUTH-01~04 전체 통과

### PR#2 (LEW UI)
- [ ] `npm run build` TypeScript 오류 없음
- [ ] DocumentRequestModal 7종 체크박스 렌더 + 스크롤
- [ ] OTHER 선택 → customLabel 필드 등장, 비어 있으면 Submit disabled
- [ ] 활성 10건 시 체크박스 전체 disabled + 경고 배너
- [ ] UPLOADED 카드 Approve 낙관적 전이 + 실패 롤백 확인
- [ ] RejectReasonModal 10자 미만 disabled 확인

### PR#3 (신청자 UI + 인앱)
- [ ] `npm run build` 오류 없음
- [ ] warning 배너 노출/숨김 조건 확인 (APPROVED/CANCELLED 전환 시 숨김)
- [ ] 4 variant 카드 각각 DOM 렌더 + 버튼 상태
- [ ] 대시보드 "요청 대기 N" 배지 클릭 → 앵커 이동
- [ ] 벨 폴링 주기 30초 동작 (Network 탭 확인)
- [ ] 신청자가 타 reqId로 fulfill → 404 토스트 "Request not found"

### PR#4 (이메일 알림)
- [ ] `./gradlew compileJava` 오류 없음
- [ ] `NotificationType` 4종 추가 후 기존 `PAYMENT_CONFIRMED` 회귀 없음
- [ ] `@Async` 이메일 실패 시 트랜잭션 롤백 없음 + 로그 확인
- [ ] MailPit에서 3종 이메일 템플릿 렌더 확인 (EN/KO 병기, CTA 버튼)
- [ ] LEW 미할당 신청: fulfill 시 이메일 발송 skip, 인앱도 skip 확인

---

## 6. E2E 수동 시나리오

### 시나리오 1 — Happy Path (전체 플로우)

전제: 로컬 백엔드(8090) + 프론트(5174) 실행, LEW 할당 신청 존재

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | LEW 로그인 → 신청 상세 → "서류 요청" 버튼 클릭 | 모달 오픈, 7종 체크박스 표시 |
| 2 | SP_ACCOUNT + LOA + OTHER(Tenancy Agreement) 체크, lewNote 입력 | Submit "Send 3 Requests" 활성 |
| 3 | Submit 클릭 | 모달 닫힘, 요청 섹션 3건 REQUESTED 카드 확인, 토스트 "서류 3건 요청 완료" |
| 4 | 신청자 로그인 → 신청 상세 | warning 배너 "LEW가 3건의 서류를 요청했습니다" 노출 |
| 5 | 인앱 벨 클릭 | `DOCUMENT_REQUESTED` 알림 1건 확인 |
| 6 | SP_ACCOUNT 카드 → PDF 업로드 | 카드 variant → uploaded, "Uploaded · LEW에게 알립니다" 토스트 |
| 7 | LOA + OTHER 업로드 | 배너 내 숫자 감소 또는 조건 변경 |
| 8 | LEW 로그인 → 요청 목록 | SP_ACCOUNT UPLOADED 카드 → Approve 클릭 → APPROVED |
| 9 | LOA UPLOADED → Reject, 사유 입력(15자 이상) | 카드 REJECTED, 신청자 인앱+이메일 확인 |
| 10 | 신청자 → LOA 재업로드 | 카드 uploaded, 이전 파일 힌트 표시 |
| 11 | LEW → LOA Approve | APPROVED, 전 과정 감사 로그 5종 DB 확인 |

총 예상 소요: 15분

### 시나리오 2 — 소프트 리밋 도달

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | LEW가 동일 신청에 활성 요청 10건 생성 (여러 타입) | 10건 REQUESTED 확인 |
| 2 | 모달 재오픈 | 체크박스 전체 disabled, 경고 배너 노출 |
| 3 | API 직접 호출 시도 | 409 `TOO_MANY_ACTIVE_REQUESTS` |

### 시나리오 3 — 이메일 알림 수신 확인 (MailPit 환경)

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | LEW 요청 생성 | MailPit inbox → document-requested.html 수신, 제목 포맷 확인 |
| 2 | 신청자 fulfill | LEW 수신함 → document-fulfilled.html |
| 3 | LEW 승인 | 신청자 수신함 → document-decision.html (승인 버전) |
| 4 | LEW 반려 | 신청자 수신함 → document-decision.html (반려+사유 블록 포함) |

---

## 7. 성능/부하

| 항목 | 측정 방법 | 목표 |
|---|---|---|
| 요청 10건 배치 생성 | `curl -w "%{time_total}"` 3회 평균 | 500ms 이내 |
| 알림 폴링 30초 주기 부하 | `GET /api/notifications/unread-count` 10분 연속 | 응답 100ms 이내, 오류 없음 |
| fulfill 업로드 (5MB PDF) | curl multipart 3회 평균 | 2000ms 이내 |

---

## 8. PR별 실행 순서

### PR#1 머지 전 — 상태 머신 + API
1. `./gradlew compileJava` 컴파일 확인
2. `DocumentRequestStatus.canTransitionTo()` 파라미터화 16케이스
3. TC-REQ-01~06 (MockMvc)
4. TC-STATE-01~06 (MockMvc)
5. TC-AUTH-01~04 (MockMvc)
6. TC-CONCURRENCY-01
7. 수동 체크리스트 PR#1 전체

### PR#2 머지 전 — LEW UI
1. `npm run build` TypeScript 빌드 확인
2. TC-UI-LEW-01~04 (수동)
3. Phase 2 회귀: 기존 문서 섹션 정상 렌더 확인

### PR#3 머지 전 — 신청자 UI + 인앱
1. `npm run build` 확인
2. TC-UI-APP-01~04 (수동)
3. TC-NOTI-01~02 (인앱 파트)
4. 시나리오 1 전체 실행

### PR#4 머지 전 — 이메일 알림
1. `./gradlew compileJava` 확인
2. TC-NOTI-01~04 (MockMvc + MailPit)
3. `PAYMENT_CONFIRMED` 기존 알림 회귀 확인
4. 시나리오 3 전체 실행

---

## 9. 배포 후 스모크 테스트 (dev.licensekaki.com, 10분 이내)

1. LEW 계정(`lew@bluelight.sg`) 로그인 → 할당 신청 상세 접근
2. "서류 요청" 버튼 클릭 → 모달 오픈, 7종 체크박스 확인
3. SP_ACCOUNT 1건 요청 생성 → 201 응답, 요청 카드 REQUESTED 확인
4. 신청자 계정 전환 → warning 배너 노출 확인
5. 벨 아이콘 → `DOCUMENT_REQUESTED` 알림 수신 확인
6. SP_ACCOUNT PDF 업로드 → 카드 UPLOADED 전환 확인
7. LEW 전환 → UPLOADED 카드 "Approve" 클릭 → APPROVED 전환
8. 신청자 → APPROVED 카드 렌더 + 벨 알림 `DOCUMENT_REQUEST_APPROVED` 확인
9. 감사 로그: `SELECT event_type FROM audit_log WHERE application_seq={id} ORDER BY created_at DESC LIMIT 5` — 3종 이상 이벤트 확인
10. Phase 2 회귀: 기존 자발적 업로드(SP_ACCOUNT 자발 업로드) 정상 동작 확인

---

## 10. 버그 발견 시 재현 절차 템플릿

```
[BUG] {버그 제목}

## 환경
- OS / 브라우저
- 백엔드: localhost:8090 (branch: ...)
- 프론트엔드: localhost:5174 (branch: ...)

## 심각도
CRITICAL / HIGH / MEDIUM / LOW

## 관련 AC
AC-{ID}

## 재현 단계
1. {정확한 액션}
2. {정확한 액션}

## 기대 결과
{스펙 기준 동작}

## 실제 결과
{실제 발생, 스크린샷 첨부}

## 요청/응답 로그
curl -X ... -H 'Authorization: Bearer ...' -d '...'
Response: {상태코드, body}
```
