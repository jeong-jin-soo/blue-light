# Phase 2 테스트 계획서 — 사후 서류 관리 인프라 + 법인 JIT 수집

**작성일**: 2026-04-17
**QA 담당**: tester agent
**대상 스펙**: `01-spec.md` AC-J1~J6, AC-T1~T4, AC-D1~D6, AC-U1~U4, AC-L1~L4 (24개)
**대상 PR**: PR#1 (Document Catalog/API), PR#2 (서류 섹션 UI), PR#3 (JIT 모달), PR#4 (LOA 스냅샷)

---

## 1. 테스트 전략

### 테스트 환경 현황

| 레이어 | 테스트 도구 | 비고 |
|---|---|---|
| 백엔드 | JUnit 5 + Spring Boot Test (MockMvc) | Phase 2에서 일부 추가 예정 |
| 프론트엔드 단위 | 없음 (vitest/jest/playwright 미설치) | package.json 확인 완료 → **수동 검증** |
| E2E | Playwright 미설치 | → **수동 시나리오**로 대체 |
| API 통합 | curl / Postman | 로컬 백엔드(8090) 실행 기준 |
| 파일 업로드 | curl multipart | MIME/크기 경계값 포함 |

프론트엔드는 `npm run build` (TypeScript 컴파일 오류 차단) + 수동 검증 조합으로 진행한다.

### 자동화 / 수동 배분

| 분류 | 자동화 | 수동 |
|---|---|---|
| 백엔드 API 검증 | JUnit MockMvc (TC-CAT, TC-DOC, TC-JIT, TC-LOA 서버 로직) | — |
| 프론트엔드 빌드 | `npm run build` TypeScript 컴파일 | DOM 구조, 드롭다운, 모달, 반응형 |
| 파일 업로드 | curl multipart (MIME/크기 검증) | 브라우저 UI drag-drop |
| E2E 흐름 | — (Playwright 없음) | 수동 시나리오 1~3 |

---

## 2. AC 매트릭스 — 24개 전체

| AC ID | 설명 (요약) | 테스트 ID | 자동화 |
|---|---|---|---|
| AC-J1 | CORPORATE + companyName=null → 모달 표시, API 미호출 | TC-JIT-01 | 수동 |
| AC-J2 | 모달 입력 후 확인 → 단일 트랜잭션 User 업데이트 + Application 생성 201 | TC-JIT-04 | MockMvc |
| AC-J3 | "프로필에 저장" 기본 true, 해제 시 User 미수정 | TC-JIT-03 | MockMvc |
| AC-J4 | 모달 "취소" → 신청 미저장, Step 3 폼 보존 | TC-JIT-05 | 수동 |
| AC-J5 | INDIVIDUAL 또는 companyName 존재 → 모달 없이 즉시 API 호출 | TC-JIT-02, TC-JIT-03 | 수동+MockMvc |
| AC-J6 | UEN 포맷 위반 → 클라이언트 + 서버 400 `INVALID_UEN` | TC-JIT-06 | MockMvc |
| AC-T1 | 배포 후 document_type_catalog 7개 active row 존재 | TC-CAT-01 | MockMvc |
| AC-T2 | GET /api/document-types → active=true, display_order 오름차순 | TC-CAT-01, TC-CAT-02 | MockMvc |
| AC-T3 | catalog row 전체 필드 응답 포함 | TC-CAT-03 | MockMvc |
| AC-T4 | SP_ACCOUNT/LOA: accepted_mime=application/pdf, max_size_mb=10 | TC-CAT-04 | MockMvc |
| AC-D1 | 자발적 업로드 POST → 201 + DocumentRequest(UPLOADED) 생성 | TC-DOC-01 | MockMvc |
| AC-D2 | 없는 type_code 업로드 → 400 `UNKNOWN_DOCUMENT_TYPE` | TC-DOC-02 | MockMvc |
| AC-D3 | MIME 불일치 → 400 `INVALID_FILE_TYPE` / 크기 초과 → 400 `FILE_TOO_LARGE` | TC-DOC-02, TC-DOC-03 | MockMvc |
| AC-D4 | OTHER + custom_label 누락 → 400 `CUSTOM_LABEL_REQUIRED` | TC-DOC-01 | MockMvc |
| AC-D5 | GET document-requests → 소유자/LEW/ADMIN만 조회, 403 방어 | TC-DOC-04 | MockMvc |
| AC-D6 | fulfill 엔드포인트 → REQUESTED→UPLOADED 전이, fulfilled_at 기록 | TC-DOC-05 | MockMvc |
| AC-U1 | DocumentRequest 없는 상태 → 자발적 업로드 카드 + InfoBox 렌더링 | TC-UI-01 | 수동 |
| AC-U2 | 업로드 성공 → 목록 즉시 반영 + 토스트 (리프레시 불필요) | TC-UI-02 | 수동 |
| AC-U3 | 4개 status skeleton (Storybook 또는 devMockups) 확인 가능 | TC-UI-03 | 수동 |
| AC-U4 | 서류 섹션 상단 InfoBox "LEW 검토 후 요청" 문구 재노출 | TC-UI-01 | 수동 |
| AC-L1 | 법인 신청 LOA 생성 시 INCOMPLETE_PROFILE 미발생 | TC-LOA-01 | MockMvc |
| AC-L2 | 개인 신청 LOA 생성 → INCOMPLETE_PROFILE 유지 (의사결정 a) | TC-LOA-02 | MockMvc |
| AC-L3 | LOA 생성 시 스냅샷 4컬럼 저장 | TC-LOA-01, TC-LOA-03 | MockMvc |
| AC-L4 | User 프로필 수정 후 기존 LOA 조회 → 스냅샷 값 불변 | TC-LOA-04 | MockMvc |

---

## 3. 시나리오 기반 테스트 케이스

### JIT 모달 (TC-JIT-01~06)

**TC-JIT-01** (수동 / AC-J1)
- 전제: 로컬 프론트 실행, CORPORATE 신청자이며 User.companyName=null
- 단계: Step 3 Review → Submit 클릭
- 기대: CompanyInfoModal 표시, `POST /api/applications` 미호출 (Network 탭에서 확인)
- FAIL: 모달 없이 바로 API 호출 시 CRITICAL

**TC-JIT-02** (수동 / AC-J5)
- 단계: INDIVIDUAL 신청 → Step 3 Submit 클릭
- 기대: 모달 없이 즉시 `POST /api/applications` 호출 → 201

**TC-JIT-03** (수동+MockMvc / AC-J3, AC-J5)
- 전제: CORPORATE 신청자이며 User.companyName 이미 존재
- 수동: Submit → 모달 뜨지 않음 확인
- MockMvc: `persistToProfile=false` 포함 JIT 요청 → User.companyName 미수정 확인

```
POST /api/applications
Body: { ..., "applicantType": "CORPORATE",
        "companyInfo": { "companyName": "Acme", "uen": "201812345A",
                         "designation": "Director", "persistToProfile": false }}
기대: 201 Created
GET /api/users/me → companyName: (변경 전 값 그대로)
```

**TC-JIT-04** (MockMvc 자동 / AC-J2)
```
POST /api/applications
Body: { ..., "applicantType": "CORPORATE",
        "companyInfo": { "companyName": "Acme Pte Ltd", "uen": "201812345A",
                         "designation": "Director", "persistToProfile": true }}
기대: 201 Created
GET /api/users/me → companyName: "Acme Pte Ltd" (저장 확인)
```

**TC-JIT-05** (수동 / AC-J4)
- 단계: JIT 모달 열린 상태 → "취소(Cancel)" 클릭
- 기대: 모달 닫힘, Step 3 폼 값 그대로 보존, 신청 미생성
- DB 확인: `SELECT COUNT(*) FROM applications` 변화 없음

**TC-JIT-06** (MockMvc 자동 / AC-J6)
```
POST /api/applications
Body: { ..., "applicantType": "CORPORATE",
        "companyInfo": { "companyName": "Bad", "uen": "INVALID",
                         "designation": "CEO", "persistToProfile": true }}
기대: 400 Bad Request, errorCode: "INVALID_UEN"
```
- 수동 추가: 프론트 UEN 필드에 "INVALID" 입력 시 inline 에러 메시지 표시 확인

---

### Document Type Catalog (TC-CAT-01~04)

**TC-CAT-01** (MockMvc 자동 / AC-T1, AC-T2)
```
GET /api/document-types (인증 토큰 포함)
기대: 200 OK, 배열 길이=7,
      순서: SP_ACCOUNT(10) < LOA(20) < MAIN_BREAKER_PHOTO(30) < SLD_FILE(40)
            < SKETCH(50) < PAYMENT_RECEIPT(60) < OTHER(999)
      모두 active=true
```

**TC-CAT-02** (MockMvc 자동 / AC-T2)
- 전제: DB에서 SP_ACCOUNT active=false로 직접 변경
- `GET /api/document-types` → 6개 반환, SP_ACCOUNT 없음 확인
- 이후 active=true로 복구

**TC-CAT-03** (MockMvc 자동 / AC-T3)
- TC-CAT-01 응답 각 항목에 아래 필드 존재 여부 확인:
  `code`, `labelEn`, `labelKo`, `description`, `helpText`, `acceptedMime`, `maxSizeMb`, `templateUrl`, `exampleImageUrl`, `requiredFields`, `iconEmoji`, `displayOrder`

**TC-CAT-04** (MockMvc 자동 / AC-T4)
- TC-CAT-01 응답에서 code=SP_ACCOUNT, code=LOA 항목:
  `acceptedMime == "application/pdf"`, `maxSizeMb == 10`
- 멱등성: seed SQL 재실행 시 중복 에러 없음 (`INSERT IGNORE` 또는 `ON DUPLICATE KEY UPDATE` 사용 전제)

---

### DocumentRequest API (TC-DOC-01~06)

**TC-DOC-01** (MockMvc 자동 / AC-D1, AC-D4)
```
POST /api/applications/{id}/documents (multipart)
Fields: file=(pdf 샘플), documentTypeCode=SP_ACCOUNT
기대: 201 Created
Body: { documentId, documentRequestId, status: "UPLOADED", documentTypeCode: "SP_ACCOUNT" }
```
- OTHER 타입 + customLabel 누락 케이스:
```
Fields: file=(pdf), documentTypeCode=OTHER (customLabel 없음)
기대: 400, errorCode: "CUSTOM_LABEL_REQUIRED"
```

**TC-DOC-02** (MockMvc 자동 / AC-D2, AC-D3)
```
# 없는 type
Fields: file=(pdf), documentTypeCode=UNKNOWN_TYPE
기대: 400, errorCode: "UNKNOWN_DOCUMENT_TYPE"

# MIME 불일치 (SP_ACCOUNT는 pdf만 허용)
Fields: file=(png 파일), documentTypeCode=SP_ACCOUNT
기대: 400, errorCode: "INVALID_FILE_TYPE"
```

**TC-DOC-03** (MockMvc 자동 / AC-D3)
```
# PAYMENT_RECEIPT max_size_mb=5 → 6MB 파일 전송
Fields: file=(6MB dummy), documentTypeCode=PAYMENT_RECEIPT
기대: 400, errorCode: "FILE_TOO_LARGE"

# SLD_FILE max_size_mb=20 → 15MB 파일 허용 확인
Fields: file=(15MB dummy), documentTypeCode=SLD_FILE
기대: 201 Created
```

**TC-DOC-04** (MockMvc 자동 / AC-D5)
```
# 타인 신청 접근
GET /api/applications/{타인id}/document-requests (본인 토큰)
기대: 403 FORBIDDEN

# LEW 토큰으로 조회
GET /api/applications/{id}/document-requests (LEW 토큰)
기대: 200 OK

# status 필터
GET /api/applications/{id}/document-requests?status=UPLOADED
기대: status=UPLOADED 항목만 반환
```

**TC-DOC-05** (MockMvc 자동 / AC-D6)
```
# REQUESTED 상태 DocumentRequest에 파일 첨부 (Phase 3 준비)
POST /api/applications/{id}/document-requests/{reqId}/fulfill (multipart)
Fields: file=(pdf)
기대: 200 OK, status: "UPLOADED", fulfilledAt 존재
```

**TC-DOC-06** (수동 / AC-D1 보완)
- 자발적 업로드 후 파일 삭제 확인:
  업로드 목록 행에서 [삭제] 클릭 → AlertDialog 확인 → 목록에서 즉시 제거 → 서버 200 후 반영

---

### 서류 섹션 UI (TC-UI-01~04)

**TC-UI-01** (수동 / AC-U1, AC-U4)
- 전제: DocumentRequest 없는 신청 상세 페이지 접근
- 확인:
  - "서류(Documents)" 섹션 헤더 존재
  - InfoBox "Upload is optional for now" 또는 한국어 동일 문구 상단 노출
  - "Upload a document" 카드 렌더링 (Document Type 드롭다운 + dropzone)
  - "No documents yet" empty state 메시지

**TC-UI-02** (수동 / AC-U2)
- SP_ACCOUNT 타입 선택 → PDF 파일 선택 → Upload 버튼 클릭
- 기대: 업로드 진행바 → 완료 토스트 → 목록에 파일명/크기/날짜 즉시 표시
- 리프레시 없이 목록 갱신 확인

**TC-UI-03** (수동 / AC-U3)
- `?devMockups=1` 쿼리 파라미터 접근 또는 Storybook 실행
- 4개 status (REQUESTED / UPLOADED / APPROVED / REJECTED) skeleton 모두 렌더링 확인
- REJECTED 카드에 rejection_reason 텍스트 표시 확인

**TC-UI-04** (수동 / 반응형)
- 브라우저 375px 너비 설정 (DevTools Responsive)
- 확인:
  - CompanyInfoModal → full-screen bottom sheet 전환
  - Documents 섹션 드롭다운 트리거 풀폭
  - dropzone 세로 140px 확보 (터치 타겟)
  - 업로드 목록 1열 스택 레이아웃

---

### LOA 스냅샷 (TC-LOA-01~04)

**TC-LOA-01** (MockMvc 자동 / AC-L1, AC-L3)
- 전제: companyName/designation 있는 CORPORATE 사용자로 신청 생성 (JIT 완료 상태)
```
POST /api/applications/{id}/loa
기대: 201 Created
DB 확인: SELECT applicant_name_snapshot, company_name_snapshot,
                uen_snapshot, designation_snapshot
         FROM loa WHERE application_id={id}
기대: 4컬럼 모두 NOT NULL, User 값과 일치
```

**TC-LOA-02** (MockMvc 자동 / AC-L2)
- 전제: companyName=null인 INDIVIDUAL 사용자
```
POST /api/applications/{id}/loa
기대: 4xx, errorCode: "INCOMPLETE_PROFILE"
```

**TC-LOA-03** (MockMvc 자동 / AC-L3 보완)
- 전제: JIT 모달로 companyName/uen/designation 저장 후 LOA 생성
- DB: 스냅샷 4컬럼이 JIT 입력 시점의 값과 일치하는지 확인

**TC-LOA-04** (MockMvc 자동 / AC-L4)
```
# 기준값 저장
POST /api/applications/{id}/loa → 201 (스냅샷 기록)

# User 프로필 수정
PATCH /api/users/me
Body: { "companyName": "Changed Corp" }

# LOA 재조회
GET /api/applications/{id}/loa
기대: company_name_snapshot = (변경 전 값) — "Changed Corp" 아님
```

---

## 4. 경계 사례 & 회귀 테스트

| ID | 시나리오 | 기대 결과 | 심각도 |
|---|---|---|---|
| TC-REG-01 | Phase 1 회원가입 → 최소 3필드 → 201 정상 | Phase 2 배포 후 회귀 없음 | CRITICAL |
| TC-REG-02 | Phase 1 신청 Step 0~3 완주 | applicantType, 주소, 기존 필드 정상 저장 | CRITICAL |
| TC-REG-03 | Phase 1에서 업로드된 기존 파일 조회 | 신청 상세 첨부 목록 정상 렌더링 | HIGH |
| TC-REG-04 | 관리자 신청 목록 / LEW 신청 상세 화면 | 새 서류 섹션이 admin/LEW 화면에 영향 없음 | HIGH |
| TC-REG-05 | 동일 DocumentType 복수 업로드 | 모두 허용, 최신 순 정렬 (중복 거부 아님) | MEDIUM |
| TC-REG-06 | schema.sql 전체 재적용 후 기존 테이블 무결성 | 외래키 순서(document_type_catalog → document_request) | HIGH |

---

## 5. E2E 수동 시나리오

### 시나리오 1: 법인 신규 사용자 전체 흐름

전제: 로컬 백엔드(8090) + 프론트(5174) 실행, DB 초기화 상태

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | `/signup` 접속, 최소 3필드로 가입 | 201, 대시보드 이동 |
| 2 | 신청 생성 → Step 0에서 CORPORATE 선택 | applicantType=CORPORATE |
| 3 | Step 3 Submit 클릭 | JIT CompanyInfoModal 표시, API 미호출 확인 |
| 4 | 모달에 회사명/UEN/Designation 입력, "프로필에 저장" 체크 | UEN 클라 검증 통과 |
| 5 | "Save & Submit" 클릭 | 201 응답, 신청 상세 페이지로 이동 |
| 6 | 신청 상세 "서류" 섹션 확인 | InfoBox + 업로드 카드 표시 |
| 7 | SP_ACCOUNT PDF 업로드 | 목록 즉시 갱신 + 토스트 |
| 8 | User 프로필 확인 | companyName/uen/designation 반영 확인 |

총 예상 소요: 8분 이내

### 시나리오 2: 기존 개인 신청자 자발적 업로드

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | 기존 INDIVIDUAL 신청 상세 페이지 접근 | 서류 섹션 노출 확인 |
| 2 | Document Type 드롭다운 → 7종 목록 표시 | display_order 순 정렬 확인 |
| 3 | MAIN_BREAKER_PHOTO 선택 | dropzone 허용 형식 "PNG/JPG · 8MB"로 갱신 |
| 4 | JPG 파일 drag-and-drop | 업로드 성공 + 목록 표시 |
| 5 | OTHER 선택 → custom_label 필드 등장 확인 | 빈 상태에서 Upload 버튼 disabled |
| 6 | custom_label 입력 후 PDF 업로드 | 201, 목록에 label 표시 |

### 시나리오 3: 관리자 LOA 생성 + 스냅샷 검증

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | 관리자 로그인, 법인 신청 선택 | 신청 status 확인 |
| 2 | LOA 생성 API 호출 | 201 Created |
| 3 | DB 직접 조회: `SELECT * FROM loa WHERE application_id={id}` | 스냅샷 4컬럼 NOT NULL |
| 4 | `PATCH /api/users/me` companyName 변경 | 200 OK |
| 5 | 동일 LOA 재조회 | 스냅샷 값 불변 확인 |

---

## 6. 파일 업로드 보안 테스트

| ID | 케이스 | 테스트 방법 | 기대 결과 |
|---|---|---|---|
| TC-SEC-01 | MIME spoofing: .jpg 확장자에 PDF 내용 삽입 | curl `Content-Type: image/jpeg` + PDF 바이너리 | 400 `INVALID_FILE_TYPE` |
| TC-SEC-02 | 용량 강제 초과: max_size_mb+1 파일 | `dd if=/dev/zero bs=1M count=11` 생성 후 SP_ACCOUNT 업로드 | 400 `FILE_TOO_LARGE` |
| TC-SEC-03 | Path traversal 파일명: `../../etc/passwd` | multipart filename 파라미터 조작 | 저장 경로에 traversal 없음, 정상 저장 또는 400 |
| TC-SEC-04 | 타인 파일 접근: 타인 application_id로 document-requests 조회 | 본인 JWT로 타인 id 요청 | 403 `FORBIDDEN` |

---

## 7. 성능/부하 검증

| 항목 | 측정 방법 | 목표 |
|---|---|---|
| JIT 모달 트랜잭션 (User 업데이트 + Application 생성) | `curl -w "%{time_total}"` 5회 평균 | 500ms 이내 (스펙 목표 +300ms 감안) |
| `GET /api/document-types` 첫 조회 | curl 3회 평균 | 200ms 이내 |
| `POST /api/applications/{id}/documents` 파일 5MB | curl multipart 3회 평균 | 2000ms 이내 |
| V_04+V_05 마이그레이션 (loa 스냅샷 백필) | 실행 시간 측정 | 기존 loa 전체 rows 기준 10초 이내 |

---

## 8. 수동 검증 체크리스트 (PR 머지 전)

### PR#1 (Document Catalog + API)
- [ ] `./gradlew compileJava` 오류 없음
- [ ] `document_type_catalog` 테이블 생성 및 7종 seed 데이터 존재 확인
- [ ] `document_request` 테이블 외래키 무결성 (fk_dr_application, fk_dr_type, fk_dr_file)
- [ ] `GET /api/document-types` → 7개 active, display_order 오름차순
- [ ] SP_ACCOUNT/LOA: acceptedMime=application/pdf, maxSizeMb=10
- [ ] MIME 불일치 → 400, 크기 초과 → 400 서버 단계 검증 동작

### PR#2 (서류 섹션 UI)
- [ ] `npm run build` TypeScript 오류 없음
- [ ] 신청 상세 페이지 "서류" 섹션 상단 InfoBox 노출 (Phase 1 연속)
- [ ] Document Type 드롭다운 7종 목록, 2행(MIME/크기 힌트) 표시
- [ ] OTHER 선택 시 custom_label 필드 fade-in
- [ ] custom_label 빈 상태 → Upload 버튼 disabled
- [ ] 업로드 성공 → 리프레시 없이 목록 즉시 갱신
- [ ] 삭제 → AlertDialog 확인 후 서버 200 후 제거
- [ ] 모바일 375px: bottom sheet, 드롭다운 풀폭 레이아웃

### PR#3 (JIT 모달)
- [ ] `npm run build` + `./gradlew compileJava` 오류 없음
- [ ] CORPORATE + companyName null → 모달 표시, API 미호출 확인
- [ ] INDIVIDUAL → 모달 없이 즉시 API 호출 확인
- [ ] UEN 클라이언트 정규식 검증 (`/^(\d{8}[A-Z]|\d{9}[A-Z]|[TSR]\d{2}[A-Z]{2}\d{4}[A-Z])$/`) 동작
- [ ] "Cancel" 클릭 → Step 3 폼 보존, 신청 미생성

### PR#4 (LOA 스냅샷)
- [ ] `./gradlew compileJava` 오류 없음
- [ ] loa 테이블 스냅샷 4컬럼 추가 (`SHOW CREATE TABLE loa` 확인)
- [ ] V_05 백필 실행 후 `SELECT COUNT(*) FROM loa WHERE applicant_name_snapshot IS NULL` = 0
- [ ] LOA 생성 API → 스냅샷 4컬럼 NOT NULL
- [ ] User companyName 수정 후 기존 LOA 스냅샷 불변 확인

---

## 9. 버그 발견 시 재현 절차 템플릿

```
[BUG] {버그 제목}

## 환경
- OS: macOS / Windows 11
- 브라우저: Chrome 124 / Safari 17
- 백엔드: localhost:8090 (branch: ...)
- 프론트엔드: localhost:5174 (branch: ...)
- DB: Docker MySQL 8.0 (port 3307)

## 심각도
CRITICAL / HIGH / MEDIUM / LOW

## 관련 AC
AC-{ID}

## 재현 단계
1. {정확한 액션}
2. {정확한 액션}

## 기대 결과
{스펙 기준 정상 동작}

## 실제 결과
{실제 발생한 동작, 스크린샷 첨부}

## 요청/응답 로그 (API 이슈)
curl -X ... -d '...'
Response: {상태코드, body}

## 추가 정보
{DB 쿼리 결과, 콘솔 에러}
```

---

## 10. 테스트 실행 순서 (PR별)

### PR#1 머지 전 — Document Catalog + API
1. `./gradlew compileJava` 컴파일 오류 확인
2. TC-CAT-01~04 (MockMvc 자동)
3. TC-DOC-01~05 (MockMvc 자동)
4. TC-SEC-01~04 (curl 보안 검증)
5. TC-REG-06 (스키마 무결성)

### PR#2 머지 전 — 서류 섹션 UI
1. `npm run build` TypeScript 빌드 확인
2. TC-UI-01~04 (수동 브라우저)
3. TC-DOC-06 (삭제 UI 수동)
4. TC-REG-03, TC-REG-04 (Phase 1 회귀)
5. 시나리오 2 전체 실행

### PR#3 머지 전 — JIT 모달
1. `npm run build` + `./gradlew compileJava` 확인
2. TC-JIT-01~06 (수동 + MockMvc)
3. TC-REG-01, TC-REG-02 (Phase 1 E2E 회귀)
4. 시나리오 1 전체 실행

### PR#4 머지 전 — LOA 스냅샷
1. `./gradlew compileJava` 확인
2. TC-LOA-01~04 (MockMvc 자동)
3. TC-MG-01: V_04+V_05 마이그레이션 실행 → loa 백필 확인
4. 시나리오 3 전체 실행 (DB 검증 포함)

---

## 11. 배포 후 스모크 테스트 (개발서버, 5분 이내)

배포 완료 직후 아래 항목을 순서대로 확인:

1. **Catalog API**: `GET https://43.210.92.190:8090/api/document-types` → 200, 7개 항목 반환
2. **SP_ACCOUNT MIME**: 응답에서 SP_ACCOUNT.acceptedMime = "application/pdf" 확인
3. **자발적 업로드**: 로그인 후 신청 상세 → "서류" 섹션 렌더링 확인
4. **InfoBox 노출**: 서류 섹션 상단 InfoBox "Upload is optional" 문구 존재
5. **Document Type 드롭다운**: 7종 목록, display_order 오름차순 표시
6. **업로드 성공**: PDF 파일 업로드 → 201 + 목록 갱신 확인
7. **MIME 거부**: PNG를 SP_ACCOUNT로 업로드 → 400 `INVALID_FILE_TYPE` 확인
8. **JIT 모달**: CORPORATE + companyName null 신청 제출 → 모달 표시 확인
9. **LOA 스냅샷**: LOA 생성 후 DB `SELECT applicant_name_snapshot FROM loa LIMIT 1` → NOT NULL
10. **Phase 1 회귀**: 기존 신청 Step 0~3 완주 → 정상 201 응답 (신규 기능이 기존 흐름을 깨지 않음)
