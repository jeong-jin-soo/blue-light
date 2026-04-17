# Phase 2 — 사후 서류 관리 인프라 + 법인 JIT 수집

**작성일**: 2026-04-17
**범위**: 법인 회사정보 JIT 모달 + Document Type Catalog + DocumentRequest 엔티티/API + 신청 상세 "서류" 섹션(자발적 업로드) + LoaGenerationService 연계
**원칙**: Phase 1 "진입 장벽 제거"의 후속 인프라. Phase 3(LEW 서류 요청)의 기반을 구축한다.
**선행 배포**: Phase 1 3개 PR (03c706a, 0dc0f30, 32aeb90) 머지 완료.

---

## 1. 사용자 스토리 & 비즈니스 목표

### User Stories
- **US-1 (법인 신청자)**: 신청 제출 시 회사명/UEN/Designation이 없더라도 흐름이 끊기지 않고 모달에서 바로 입력·저장한 뒤 제출이 완료되길 원한다.
- **US-2 (개인/법인 신청자)**: 신청 생성 후 상세 페이지에서 SP Account·LOA·Main Breaker Photo·SLD 파일을 **자발적으로** 업로드할 수 있길 원한다 (Phase 1에서 제거된 Step 0 업로드의 대체 경로).
- **US-3 (LEW, 준비 단계)**: 향후 내가 특정 서류를 신청자에게 요청하기 위해, 시스템이 표준화된 Document Type 목록과 요청 레코드 구조를 갖추고 있어야 한다.
- **US-4 (법인 신청자)**: 모달에서 입력한 회사정보가 "프로필에도 저장" 체크를 통해 User에 반영되어 다음 신청부터는 재입력 없이 진행되길 원한다.
- **US-5 (모든 신청자)**: 업로드 가능한 서류 종류·허용 확장자·크기 제한·예시 이미지가 한 곳에서 명시되어 무엇을 올려야 할지 헷갈리지 않길 원한다.
- **US-6 (감사/법무)**: LOA 생성 시점의 신청자 성명/회사/UEN/직책이 향후 User 프로필 수정과 무관하게 **LOA 레코드 자체에 스냅샷**으로 고정되길 원한다.

### 측정 지표 (배포 후 2주)
| 지표 | 베이스라인 | 목표 |
|---|---|---|
| 법인 신청 제출 성공률 (JIT 모달 도입 후) | 측정 필요 | ≥ 95% |
| 신청 상세 페이지 자발적 업로드율 (신청 건수 대비 최소 1개 업로드) | 0% (기능 없음) | ≥ 40% |
| LOA 생성 시 `INCOMPLETE_PROFILE` 예외 비율 | 측정 필요 | 법인은 0%, 개인은 의사결정 (a) 참조 |
| `POST /api/applications`(법인) 평균 왕복 시간 | 측정 필요 | +300ms 이내 (JIT 모달 왕복 감안) |

---

## 2. 수용 기준 (Acceptance Criteria)

### A. 법인 JIT 회사정보 모달 (6개)
1. **AC-J1** GIVEN `applicantType=CORPORATE` 신청 제출, WHEN User.companyName=null, THEN 모달이 인라인으로 표시되며 신청 API는 아직 호출되지 않는다.
2. **AC-J2** GIVEN 모달, WHEN companyName/uen/designation 입력 후 확인, THEN 단일 트랜잭션으로 User 업데이트 + Application 생성 → 201 Created.
3. **AC-J3** GIVEN 모달의 "프로필에 저장" 체크박스 기본값, THEN true. 체크 해제 시 Application에만 스냅샷되고 User는 업데이트되지 않는다.
4. **AC-J4** GIVEN 모달, WHEN "취소" 클릭, THEN 신청은 저장되지 않고 폼 상태는 보존된다.
5. **AC-J5** GIVEN `applicantType=INDIVIDUAL` 또는 User.companyName 존재, WHEN 제출, THEN 모달이 뜨지 않고 바로 신청 API 호출.
6. **AC-J6** GIVEN 모달 UEN, WHEN 싱가포르 UEN 포맷 위반, THEN 클라이언트/서버 모두 400 대응 (`INVALID_UEN`).

### B. Document Type Catalog (4개)
7. **AC-T1** GIVEN 배포 직후 DB, THEN `document_type_catalog`에 7개 active row(SP_ACCOUNT/LOA/MAIN_BREAKER_PHOTO/SLD_FILE/SKETCH/PAYMENT_RECEIPT/OTHER)가 존재한다.
8. **AC-T2** GIVEN `GET /api/document-types`, THEN `active=true` 행만 `display_order` 오름차순으로 반환.
9. **AC-T3** GIVEN catalog row, THEN `accepted_mime`·`max_size_mb`·`label_en/ko`·`icon_emoji`·`template_url(nullable)`·`example_image_url(nullable)` 필드가 응답에 포함된다.
10. **AC-T4** GIVEN seed 데이터의 SP_ACCOUNT/LOA 항목, THEN accepted_mime은 `application/pdf`, max_size_mb는 10이다.

### C. DocumentRequest 엔티티 & 업로드 API (6개)
11. **AC-D1** GIVEN 신청자, WHEN `POST /api/applications/{id}/documents` (multipart: file + document_type_code), THEN 201과 함께 Document 레코드 + (선택적으로) 자발적 업로드용 DocumentRequest(status=UPLOADED)가 생성된다.
12. **AC-D2** GIVEN 업로드, WHEN document_type_code가 catalog에 없음, THEN 400 `UNKNOWN_DOCUMENT_TYPE`.
13. **AC-D3** GIVEN 업로드, WHEN MIME이 accepted_mime 불일치 또는 크기가 max_size_mb 초과, THEN 400 `INVALID_FILE_TYPE` / `FILE_TOO_LARGE`.
14. **AC-D4** GIVEN OTHER 타입 업로드, WHEN `custom_label` 누락, THEN 400 `CUSTOM_LABEL_REQUIRED`.
15. **AC-D5** GIVEN `GET /api/applications/{id}/document-requests`, THEN 신청 소유자 또는 LEW/ADMIN만 조회 가능(403 아니면). status 필터 쿼리 파라미터 지원.
16. **AC-D6** GIVEN `POST .../document-requests/{reqId}/fulfill`, WHEN 기존 REQUESTED 상태의 요청에 파일 첨부, THEN status=UPLOADED, fulfilled_file_id/fulfilled_at 기록.

### D. 신청 상세 "서류" 섹션 UI (4개)
17. **AC-U1** GIVEN 신청 상세 페이지, WHEN DocumentRequest가 없음, THEN "자발적 업로드" 카드만 렌더링(Document Type 드롭다운 + 파일 선택).
18. **AC-U2** GIVEN 업로드 성공, THEN 업로드 목록에 즉시 반영되고 토스트가 표시된다. 페이지 리프레시 불필요.
19. **AC-U3** GIVEN DocumentRequestCard 컴포넌트, THEN 요청 상태(REQUESTED/UPLOADED/APPROVED/REJECTED) 4종 스켈레톤이 Storybook 또는 동일 페이지 내 개발용 목업으로 확인 가능하다(Phase 3 활성화 대비).
20. **AC-U4** GIVEN Phase 1에서 노출된 "LEW 검토 후 요청" 안내, THEN "서류" 섹션 상단에 동일 문구 InfoBox 재노출 (의사결정 (c)).

### E. LoaGenerationService 연계 (4개)
21. **AC-L1** GIVEN 법인 신청, WHEN LOA 생성 시도, THEN JIT 모달 덕분에 User.companyName/designation은 항상 존재하므로 `INCOMPLETE_PROFILE` 예외가 발생하지 않는다.
22. **AC-L2** GIVEN 개인 신청의 LOA 생성, THEN **의사결정 (a)에 따라** 경로 결정.
23. **AC-L3** GIVEN LOA 생성 성공, THEN `loa` 테이블에 `applicant_name_snapshot`, `company_name_snapshot`, `uen_snapshot`, `designation_snapshot` 4개 컬럼이 저장된다.
24. **AC-L4** GIVEN 이후 User의 companyName 수정, WHEN 기존 LOA 조회, THEN 스냅샷 값이 수정 전 값 그대로 유지된다.

---

## 3. 데이터 모델 설계

### 3-1. `document_type_catalog` (신규)
```sql
CREATE TABLE document_type_catalog (
  code              VARCHAR(40)  NOT NULL PRIMARY KEY,
  label_en          VARCHAR(120) NOT NULL,
  label_ko          VARCHAR(120) NOT NULL,
  description       VARCHAR(500) NULL,
  help_text         VARCHAR(1000) NULL,
  accepted_mime     VARCHAR(200) NOT NULL,         -- 'application/pdf,image/png,image/jpeg'
  max_size_mb       INT          NOT NULL DEFAULT 10,
  template_url      VARCHAR(500) NULL,
  example_image_url VARCHAR(500) NULL,
  required_fields   JSON         NULL,
  icon_emoji        VARCHAR(16)  NULL,
  display_order     INT          NOT NULL DEFAULT 0,
  active            BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at        DATETIME     NOT NULL,
  updated_at        DATETIME     NOT NULL
);
```

### 3-2. `document_request` (신규)
```sql
CREATE TABLE document_request (
  id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  application_id       BIGINT       NOT NULL,
  document_type_code   VARCHAR(40)  NOT NULL,
  custom_label         VARCHAR(200) NULL,
  lew_note             VARCHAR(1000) NULL,
  status               VARCHAR(20)  NOT NULL,   -- REQUESTED|UPLOADED|APPROVED|REJECTED|CANCELLED
  fulfilled_file_id    BIGINT       NULL,
  requested_by         BIGINT       NULL,
  requested_at         DATETIME     NULL,
  fulfilled_at         DATETIME     NULL,
  reviewed_at          DATETIME     NULL,
  reviewed_by          BIGINT       NULL,
  rejection_reason     VARCHAR(1000) NULL,
  created_at           DATETIME     NOT NULL,
  updated_at           DATETIME     NOT NULL,
  created_by           BIGINT       NULL,
  updated_by           BIGINT       NULL,
  deleted_at           DATETIME     NULL,
  CONSTRAINT fk_dr_application  FOREIGN KEY (application_id)     REFERENCES application(id),
  CONSTRAINT fk_dr_type         FOREIGN KEY (document_type_code) REFERENCES document_type_catalog(code),
  CONSTRAINT fk_dr_file         FOREIGN KEY (fulfilled_file_id)  REFERENCES uploaded_file(id),
  INDEX idx_dr_app_status (application_id, status)
);
```
- `status` enum은 Java 측에서 검증. DB는 VARCHAR(프로젝트 컨벤션).
- Soft delete: `@SQLDelete` + `@SQLRestriction`(`deleted_at IS NULL`).

### 3-3. `loa` 스냅샷 컬럼 추가 (Security R-2)
```sql
ALTER TABLE loa
  ADD COLUMN applicant_name_snapshot VARCHAR(200) NULL AFTER application_id,
  ADD COLUMN company_name_snapshot   VARCHAR(200) NULL,
  ADD COLUMN uen_snapshot            VARCHAR(50)  NULL,
  ADD COLUMN designation_snapshot    VARCHAR(100) NULL;
```
- 기존 LOA 레코드의 스냅샷 백필: 현 User 값으로 1회 복사 (`UPDATE loa l JOIN application a ... JOIN user u ...`).
- 이후 INSERT는 항상 생성 시점 User 값을 스냅샷.

### 3-4. schema.sql 수정 위치
- `blue-light-backend/src/main/resources/schema.sql`
  - `document_type_catalog` 정의 추가(참조 대상이므로 `application`보다 앞)
  - `document_request` 정의 추가(`application`, `uploaded_file` 이후)
  - `loa` 테이블 컬럼 4개 추가
- `data.sql` 또는 별도 `seed-document-types.sql`에 7종 INSERT.

---

## 4. API 변경 스펙

### `GET /api/document-types` (Public 신규)
- Auth: 인증 필요(모든 로그인 사용자).
- Response 200:
```json
[{
  "code":"SP_ACCOUNT","labelEn":"SP Account Holder PDF","labelKo":"SP 계정 보유자 PDF",
  "description":"...", "helpText":"...",
  "acceptedMime":"application/pdf","maxSizeMb":10,
  "templateUrl":null,"exampleImageUrl":null,
  "requiredFields":null,"iconEmoji":"📄","displayOrder":10
}]
```

### `GET /api/applications/{id}/document-requests` (신규)
- Auth: Application 소유자 또는 LEW/ADMIN.
- Query: `?status=REQUESTED,UPLOADED` (optional)
- Response 200: `[{id, documentTypeCode, customLabel, status, lewNote, fulfilledFileId, requestedAt, fulfilledAt, reviewedAt, rejectionReason}]`
- Error: 403 `FORBIDDEN`, 404 `APPLICATION_NOT_FOUND`.

### `POST /api/applications/{id}/documents` (자발적 업로드, 신규)
- Auth: Application 소유자.
- Content-Type: `multipart/form-data`
- Fields: `file` (required), `documentTypeCode` (required), `customLabel` (OTHER일 때 required)
- Response 201:
```json
{ "documentId":123, "documentRequestId":456, "status":"UPLOADED",
  "documentTypeCode":"SP_ACCOUNT","fileName":"sp.pdf","sizeBytes":524288 }
```
- Error: 400 `UNKNOWN_DOCUMENT_TYPE` / `INVALID_FILE_TYPE` / `FILE_TOO_LARGE` / `CUSTOM_LABEL_REQUIRED`, 403 `FORBIDDEN`.

### `POST /api/applications/{id}/document-requests/{reqId}/fulfill` (신규)
- Auth: Application 소유자.
- 동작: 기존 REQUESTED 상태의 요청에 파일을 첨부. Phase 2에서는 자발적 업로드 경로가 주이며 LEW가 REQUESTED를 만드는 건 Phase 3.
- 내부적으로 Document 생성 후 DocumentRequest.status=UPLOADED 전이.

### `POST /api/applications` (변경: JIT 처리)
- Phase 1에서 추가된 `applicantType` 유지.
- Body 확장(선택적):
```json
{ ..., "companyInfo": {
    "companyName":"Acme Pte Ltd","uen":"201812345A","designation":"Director",
    "persistToProfile": true
  }}
```
- `applicantType=CORPORATE`이고 User에 정보 없으면 `companyInfo`가 **필수**. 누락 시 400 `COMPANY_INFO_REQUIRED`.
- 서버: 단일 `@Transactional`로 User update(조건부) + Application insert.

### `PATCH /api/users/me`
- 변경 없음. JIT 모달의 "프로필에 저장" 경로는 `POST /api/applications`가 내부적으로 처리.

### Phase 3으로 미루는 API (본 스펙에 정의만)
- `POST /api/admin/applications/{id}/document-requests` (LEW 생성)
- `PATCH /api/admin/document-requests/{reqId}/approve|reject` (LEW 검토)

---

## 5. 마이그레이션 전략

**원칙**: Phase 1과 동일. `schema.sql` 수정 + 운영 DB 일회성 SQL. Flyway 미도입.

### 파일 구조
```
doc/Project execution/phase2-document-management/migration/
  V_01_create_document_type_catalog.sql
  V_02_seed_document_types.sql
  V_03_create_document_request.sql
  V_04_add_loa_snapshot_columns.sql
  V_05_backfill_loa_snapshots.sql
```

### V_02_seed_document_types.sql (예시)
```sql
INSERT INTO document_type_catalog(code,label_en,label_ko,accepted_mime,max_size_mb,icon_emoji,display_order,active,created_at,updated_at) VALUES
 ('SP_ACCOUNT','SP Account Holder PDF','SP 계정 보유자 PDF','application/pdf',10,'📄',10,TRUE,NOW(),NOW()),
 ('LOA','Letter of Authorisation','위임장','application/pdf',10,'📝',20,TRUE,NOW(),NOW()),
 ('MAIN_BREAKER_PHOTO','Main Breaker Photo','메인 차단기 사진','image/png,image/jpeg',8,'📷',30,TRUE,NOW(),NOW()),
 ('SLD_FILE','Single Line Diagram','SLD','application/pdf,image/png,image/jpeg',20,'📐',40,TRUE,NOW(),NOW()),
 ('SKETCH','Sketch / Plan','평면 스케치','application/pdf,image/png,image/jpeg',10,'✏️',50,TRUE,NOW(),NOW()),
 ('PAYMENT_RECEIPT','Payment Receipt','결제 영수증','application/pdf,image/png,image/jpeg',5,'🧾',60,TRUE,NOW(),NOW()),
 ('OTHER','Other','기타','application/pdf,image/png,image/jpeg',10,'📎',999,TRUE,NOW(),NOW());
```

### 실행 순서
1. V_01 → V_02 → V_03 (신규 테이블 + seed)
2. V_04 → V_05 (LOA 스냅샷 스키마 + 기존 데이터 백필)
3. 배포 순서: **백엔드 먼저 → 프론트** (API 사전 노출).

### 롤백
```sql
DROP TABLE document_request;
DROP TABLE document_type_catalog;
ALTER TABLE loa
  DROP COLUMN applicant_name_snapshot,
  DROP COLUMN company_name_snapshot,
  DROP COLUMN uen_snapshot,
  DROP COLUMN designation_snapshot;
```

---

## 6. PR 분리 전략 (총 4개)

| PR | 제목 | 범위 | 의존 | 독립 배포 가능? |
|---|---|---|---|---|
| **PR#1** | `feat: Document Type Catalog + DocumentRequest 엔티티/API` | 백엔드 only. 테이블 2개, seed, `GET /api/document-types`, `GET/POST /api/applications/{id}/document-requests`, `POST /api/applications/{id}/documents`, fulfill. | — | ✅ (프론트 미사용이어도 무해) |
| **PR#2** | `feat: 신청 상세 서류 섹션 UI + 자발적 업로드` | `ApplicationDetailPage.tsx`에 서류 섹션, `DocumentRequestCard.tsx` 공용 컴포넌트, API 클라이언트, InfoBox(의사결정 (c)). | PR#1 | ✅ (PR#1 배포 후) |
| **PR#3** | `feat: 법인 JIT 회사정보 모달` | `NewApplicationPage.tsx` Step 마지막 제출에서 모달 인터셉트, `CompanyInfoModal.tsx`, `POST /api/applications` body 확장, 백엔드 JIT 처리 분기. | — (Phase 1 applicantType 선행) | ✅ |
| **PR#4** | `feat: LoaGenerationService 스냅샷 + INCOMPLETE_PROFILE 정책 반영` | `loa` 스냅샷 컬럼/백필, `LoaGenerationService` 수정, 개인 신청 정책(의사결정 (a)) 반영. | PR#3 (법인 JIT로 사전 차단) | ✅ |

- PR#1과 PR#3은 병렬 개발 가능. PR#2는 PR#1 이후, PR#4는 PR#3 이후.
- 각 PR 단독으로 테스트 가능해야 하며, 이전 PR이 머지되지 않아도 배포된 상태가 깨지지 않아야 한다(기능 플래그 불필요, 누적형).

---

## 7. 리스크 & 완화책

| # | 리스크 | 영향 | 완화 |
|---|---|---|---|
| R1 | 법인 JIT 모달 도중 네트워크 실패 → User만 업데이트되고 Application 미생성 | 중 | 백엔드 단일 `@Transactional`로 묶음. 실패 시 전체 롤백. 프론트는 실패 토스트 + 모달 재시도. |
| R2 | 기존 Application 사용자가 Step 0에서 파일 업로드 UX가 사라진 후 혼란 | 중 | PR#2에서 신청 상세 "서류" 섹션과 InfoBox로 대체 경로 명시(의사결정 (c)). 배포 공지 포함. |
| R3 | Document Type Catalog seed 데이터 오류(잘못된 MIME) | 중 | 스키마 상수 vs seed 교차 검증 단위 테스트. 운영 배포 전 dev DB에서 `GET /api/document-types` 응답 스냅샷 확인. |
| R4 | `OTHER` 타입 남용으로 분류 불가한 파일 폭증 | 저 | `custom_label` 필수화 + Phase 3 LEW 대시보드 필터링. |
| R5 | LOA 스냅샷 백필 중 장애 | 저 | V_05는 idempotent — `WHERE applicant_name_snapshot IS NULL` 조건으로 재실행 안전. |
| R6 | 파일 크기/MIME 검증을 프론트만 하고 서버에서 놓침 | 중 | PR#1 MockMvc 테스트에서 AC-D3 강제. `max_size_mb` 서버 단계 검증 필수. |
| R7 | 한 Application에 동일 DocumentType의 자발적 업로드가 중복 생성 | 저 | Phase 2에서는 허용(재업로드 유스케이스). 최신본 기준 UI는 정렬. Phase 3에서 "대표본" 지정. |

---

## 8. 의사결정 확정 (2026-04-17)

- **(a) 개인 신청의 LOA 생성 정책**: ✅ **INCOMPLETE_PROFILE 유지**
  - 싱가포르 LOA는 법인 대리인 위임 목적이 주. 개인은 본인이 신청자 = LEW 지정자인 경우가 일반적이므로 현 예외 로직 유지. 예외 케이스(개인이 LEW 별도 지정)는 Phase 3에서 처리.

- **(b) LOA 스냅샷 Phase 2 포함**: ✅ **PR#4에 포함**
  - 법적 문서 무결성 요건(Security R-2). Phase 3로 미룰수록 백필 데이터 범위가 커짐.

- **(c) 서류 섹션 InfoBox 문구 반영**: ✅ **반영 (Phase 1 연속성)**
  - Phase 1 Step 0에 있던 "LEW 검토 후 요청" 문구를 상세 페이지 서류 섹션 상단 InfoBox로 그대로 노출. Phase 3에서 "LEW가 요청함" 카드 등장 시 문구 교체.

---

## 9. 배포 후 LEW/관리자 공지

Phase 1 공지("LEW가 전화/이메일로 요청 대체")를 **Phase 2 자발적 업로드 전환 안내**로 업데이트.

- **대상**: LEW, ADMIN, SYSTEM_ADMIN 전원.
- **채널**: 이메일 + 관리자 대시보드 배너 (Phase 1과 동일 이중 전달).
- **문구 요지**:
  1. 신청자는 이제 상세 페이지에서 SP Account/LOA/Main Breaker Photo/SLD/Sketch/Receipt/Other를 자발적으로 업로드 가능.
  2. LEW의 플랫폼 내 서류 요청 기능은 **Phase 3**에서 제공 예정.
  3. Phase 2 기간에도 긴급 시 전화/이메일 요청은 계속 허용(과도기).
  4. 관리자 대시보드 상단에 Phase 3 런칭 예상일 공지.

---

## 10. 개발자 Handoff 체크리스트

### Backend (PR#1)
- [ ] Entity: `DocumentTypeCatalog`, `DocumentRequest`, `DocumentRequestStatus` enum
- [ ] Repository: `DocumentTypeCatalogRepository`, `DocumentRequestRepository` (status 필터 쿼리)
- [ ] Service: `DocumentTypeService`, `DocumentRequestService` (업로드/fulfill 트랜잭션)
- [ ] Controller: `DocumentTypeController`, `ApplicationDocumentController`
- [ ] DTO: `DocumentTypeResponse`, `DocumentRequestResponse`, `DocumentUploadRequest`, `DocumentUploadResponse`
- [ ] Validation: MIME/size/custom_label 서버 검증
- [ ] schema.sql + migration/V_01~V_03 작성, seed data.sql

### Backend (PR#3)
- [ ] `CreateApplicationRequest`에 `companyInfo` 선택 필드 + 조건부 검증 (`@AssertTrue`)
- [ ] `ApplicationService.createApplication`에 JIT User 업데이트 분기 (단일 트랜잭션)
- [ ] 에러 코드: `COMPANY_INFO_REQUIRED`, `INVALID_UEN`

### Backend (PR#4)
- [ ] `loa` 엔티티 스냅샷 필드 4개 추가
- [ ] `LoaGenerationService`에서 User 값 복사
- [ ] migration/V_04 + V_05
- [ ] 의사결정 (a) 반영: 개인 신청 경로 분기 로직

### Frontend (PR#2)
- [ ] `DocumentRequestCard.tsx` (공용, 4개 status 스켈레톤)
- [ ] `ApplicationDetailPage.tsx`: "서류" 섹션 + 자발적 업로드 카드 + InfoBox
- [ ] `api/documents.ts`: `getDocumentTypes`, `uploadDocument`, `getDocumentRequests`, `fulfillDocumentRequest`
- [ ] Types: `DocumentType`, `DocumentRequest`, `DocumentRequestStatus`

### Frontend (PR#3)
- [ ] `CompanyInfoModal.tsx` (companyName/uen/designation + persistToProfile 체크박스)
- [ ] `NewApplicationPage.tsx` 제출 핸들러에서 JIT 분기
- [ ] UEN 클라이언트 검증 util

### Tests
- [ ] Backend MockMvc: AC-T1~T4, AC-D1~D6, AC-J1~J6, AC-L1~L4
- [ ] Backend 단위: MIME/size 검증, JIT 트랜잭션 롤백, LOA 스냅샷 고정성
- [ ] Frontend 컴포넌트: CompanyInfoModal, DocumentRequestCard 4 state 렌더링
- [ ] 회귀: Phase 1 AC (applicantType 저장, Step 0 업로드 부재) 재실행

### Ops
- [ ] Dev DB seed 검증 후 staging 배포
- [ ] 배포 순서 준수: 백엔드 → 프론트
- [ ] Phase 2 전환 공지 초안 (§9) 검토·발송
- [ ] 측정 지표 베이스라인 수집 시작 (배포 D-Day)
