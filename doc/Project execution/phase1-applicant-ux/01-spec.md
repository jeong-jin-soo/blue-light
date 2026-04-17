# Phase 1 — 신청자 UX 간소화 (진입 장벽 제거)

**작성일**: 2026-04-17
**범위**: 회원가입 간소화 + Step 0 파일 업로드 UI 제거 + 프론트/백엔드 검증 정합성
**원칙**: Just-in-Time Disclosure — 정보는 필요한 순간에만 요청

---

## 1. 사용자 스토리 & 비즈니스 목표

### User Stories
- **US-1**: 개인 신청자로서, 회원가입 시 최소 정보(이메일/비밀번호/이름)만 입력하고 싶다. 회사 정보는 나중에 선택적으로 입력할 수 있길 원한다.
- **US-2**: 신청자로서, Step 0에서 파일 업로드 요구 없이 설비 기본 정보만 입력해 빠르게 신청 흐름을 시작하고 싶다.
- **US-3**: 신청자로서, 프론트엔드가 "필수"라고 표시한 항목은 실제로 검증되길 기대한다 (혼란 제거).
- **US-4**: 향후 법인 신청자로서, 내가 법인으로 신청한다는 사실을 시스템이 알고 회사 정보를 적절한 시점에 요청받고 싶다. (Phase 1에서는 플래그만 저장)
- **US-5**: 기존 가입자로서, 이미 입력한 회사 정보가 사라지지 않고 프로필에서 계속 확인/수정되길 원한다.

### 측정 지표 (배포 후 2주)
| 지표 | 현재 베이스라인 | 목표 |
|---|---|---|
| 회원가입 완료율 (시작→완료) | 측정 필요 | +15%p |
| Step 0 완주율 | 측정 필요 | +20%p |
| 신청 Step 0 평균 소요 시간 | 측정 필요 | -40% |
| 가입 후 72h 내 첫 신청 생성률 | 측정 필요 | +10%p |

---

## 2. 수용 기준 (Acceptance Criteria)

### 회원가입
1. **AC-S1** GIVEN 가입 페이지, WHEN 폼을 렌더링, THEN phone/companyName/uen/designation 입력 필드는 DOM에 존재하지 않는다.
2. **AC-S2** GIVEN 가입 폼, WHEN 이메일/비밀번호/이름만 입력 후 제출, THEN 201 Created 응답을 받는다.
3. **AC-S3** GIVEN 구버전 클라이언트가 phone 등을 포함해 `POST /api/auth/signup` 호출, WHEN 서버 처리, THEN 해당 필드는 무시되고 null로 저장되며 성공 응답을 반환한다 (하위호환).
4. **AC-S4** GIVEN 가입 성공, WHEN User 레코드 생성, THEN companyName/uen/designation/phone 컬럼은 NULL이다.
5. **AC-S5** GIVEN 가입 성공, WHEN 리디렉션, THEN 대시보드 또는 ProfilePage로 이동하며 "프로필 완성" 유도는 **의사결정 항목 (b)에 따름**.
6. **AC-S6** GIVEN 백엔드 검증, WHEN SignupRequest DTO 파싱, THEN 제거된 4개 필드에 대한 @NotBlank 등 제약이 남아있지 않다.

### Step 0 (신청 생성)
7. **AC-A1** GIVEN Step 0, WHEN 렌더링, THEN SP Account PDF / LOA Document / Main Breaker Photo / SLD File 업로드 UI 4종은 DOM에 없다.
8. **AC-A2** GIVEN Step 0, WHEN 필수 텍스트/선택 입력만 완료, THEN "다음" 버튼이 활성화된다.
9. **AC-A3** GIVEN Step 0, WHEN 신청자 유형 라디오(Individual/Corporate)를 렌더링, THEN 기본값은 INDIVIDUAL이며 필수 선택이다.
10. **AC-A4** GIVEN `POST /api/applications`, WHEN applicantType 누락, THEN 400 Bad Request (`applicantType is required`).
11. **AC-A5** GIVEN `POST /api/applications`, WHEN applicantType=CORPORATE, THEN Phase 1에서는 **플래그만 저장**, 회사정보 JIT 요청은 하지 않는다.
12. **AC-A6** GIVEN 프론트엔드, WHEN "필수" 표시된 필드, THEN 백엔드 DTO에도 동일한 @NotNull/@NotBlank 제약이 존재한다.

### ProfilePage
13. **AC-P1** GIVEN 프로필 페이지, WHEN 렌더링, THEN phone/companyName/uen/designation 4개 필드 모두 **선택 입력**으로 노출된다.
14. **AC-P2** GIVEN 기존 사용자가 회사 정보를 이미 가짐, WHEN 프로필 페이지 진입, THEN 저장된 값이 프리필된다.
15. **AC-P3** GIVEN 프로필 편집, WHEN `PATCH /api/users/me`로 저장, THEN 빈 문자열은 NULL로 정규화된다.

### 기존 데이터 호환
16. **AC-C1** GIVEN 마이그레이션 전 사용자 레코드, WHEN 배포 후 조회, THEN 기존 phone/companyName/uen/designation 값은 그대로 유지된다 (비파괴).
17. **AC-C2** GIVEN 마이그레이션 전 Application 레코드, WHEN 배포 후 조회, THEN applicantType = 'INDIVIDUAL'로 백필되어 있다.
18. **AC-C3** GIVEN LoaGenerationService, WHEN companyName/designation 없는 사용자의 LOA 생성 시도, THEN 기존 `INCOMPLETE_PROFILE` 예외는 Phase 1에서 유지된다 (Phase 2 JIT 모달로 해결 예정).

---

## 3. 기존 데이터 처리 정책

- **User**: 기존 phone/companyName/uen/designation 값 **보존**. 삭제·마스킹 없음. DDL 무변경 (이미 nullable).
- **Application**: `applicant_type` 컬럼 신규 추가 → nullable로 생성 → 전체 `UPDATE application SET applicant_type='INDIVIDUAL' WHERE applicant_type IS NULL` 백필 → `ALTER ... SET NOT NULL`. 단일 Flyway 스크립트로 수행.
- **API 하위호환**: SignupRequest는 제거된 4개 필드를 수신해도 200/201로 응답 (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false` 유지). ApplicationResponse에 applicantType 신규 필드 추가는 기존 프론트에 영향 없음(무시).

---

## 4. API 변경 스펙

### `POST /api/auth/signup`
```json
// Before
{ "email", "password", "name", "phone", "companyName", "uen", "designation" }
// After (필수)
{ "email", "password", "name" }
```
- Request DTO에서 4개 필드 제거. 서버는 unknown property를 조용히 무시.
- Response 변경 없음.

### `POST /api/applications`
```json
// Added (required)
{ ..., "applicantType": "INDIVIDUAL" | "CORPORATE" }
```
- `@NotNull` 검증. 누락 시 400.

### `GET /api/applications/{id}`
```json
// Response에 applicantType 추가
{ ..., "applicantType": "INDIVIDUAL" }
```

### `PATCH /api/users/me`
- 변경 없음. 기존 UpdateProfileRequest 그대로 재사용.

---

## 5. 마이그레이션 DDL (schema.sql + 일회성 SQL)

**결정 (2026-04-17)**: 프로젝트는 Flyway 미도입 상태 (`ddl-auto: none` + `schema.sql`). Phase 1에서는 기존 구조 그대로 유지하고 **schema.sql 직접 수정 + 운영 DB 일회성 SQL 스크립트** 방식 채택. Flyway 도입은 별도 Phase로 분리.

### schema.sql 수정
`application` 테이블 정의에 컬럼 추가:
```sql
applicant_type VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL' COMMENT 'INDIVIDUAL | CORPORATE'
```

### 운영 DB 일회성 마이그레이션 SQL
`doc/Project execution/phase1-applicant-ux/migration/V_add_applicant_type.sql` 작성:
```sql
ALTER TABLE application
  ADD COLUMN applicant_type VARCHAR(20) NULL COMMENT 'INDIVIDUAL | CORPORATE';

UPDATE application
  SET applicant_type = 'INDIVIDUAL'
  WHERE applicant_type IS NULL;

ALTER TABLE application
  MODIFY COLUMN applicant_type VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL';
```

### 롤백 SQL (`04-deployment-runbook.md` 참조)
```sql
ALTER TABLE application DROP COLUMN applicant_type;
```

- **인덱스**: Phase 1에서는 추가하지 않음.
- **Enum 검증**: Java `ApplicantType` enum + JPA `@Enumerated(EnumType.STRING)`.

---

## 6. 범위 외 재확인 (Phase 1에서 하지 않음)

- 법인 선택 시 회사정보 JIT 모달 (→ Phase 2)
- 신청 상세 페이지의 "서류" 섹션 신설 및 사후 업로드 UI (→ Phase 2)
- LEW의 서류 요청 기능 (→ Phase 3)
- 파일 업로드 컴포넌트 자체의 UX 재설계 (→ Phase 2)
- LoaGenerationService의 INCOMPLETE_PROFILE 처리 변경 (→ Phase 2)
- 가입 후 "프로필 완성" 자동 배너의 설계/트리거 (의사결정 (b) 참조)

---

## 7. 리스크 & 완화책

| 리스크 | 영향 | 완화책 |
|---|---|---|
| R1. Step 0 파일 업로드가 사라져 LEW 검토 시 첨부 서류 부족 | 중 | Phase 1 배포 공지 (의사결정 (c)) + LEW가 전화/이메일로 임시 요청. Phase 2에서 정식 해결. |
| R2. 기존 신청의 applicantType이 잘못 백필됨 (법인인데 INDIVIDUAL) | 중 | 백필 후 관리자 대시보드에서 수동 정정 가능. 향후 Phase 2에서 법인 표식(UEN 존재 등)으로 재판정 스크립트 실행 검토. |
| R3. 구버전 프론트 캐시가 phone/companyName을 계속 전송 | 저 | 서버가 무시. 배포 후 강제 리프레시 공지 불필요. |
| R4. 이미 Step 0에서 파일 업로드 중이던 사용자가 재진입 시 "진행 중" 상태 불일치 | 저 | Step 0은 아직 Application 저장 전이므로 서버 상태 없음. 로컬 상태만 초기화됨 — 문제 없음. |
| R5. applicantType NULL 렌더링 | 저 | Flyway 백필로 NULL 없음. 방어적으로 프론트는 NULL → INDIVIDUAL 표시. |

---

## 8. 의사결정 확정 (2026-04-17)

- **(a) 기존 Application의 applicantType 백필 값**: ✅ **전체 INDIVIDUAL**
  - 99%가 개인 신청이라는 가정. 이후 관리자가 수동 정정 가능.

- **(b) 가입 직후 "프로필 완성하기" 유도 배너**: ✅ **Phase 2로 미룸**
  - Phase 1은 "제거"에만 집중. 유도 UX는 Phase 2의 JIT 흐름과 함께 설계.

- **(c) Phase 1 배포 후 LEW 공지**: ✅ **이메일 + 대시보드 배너 (이중 전달)**
  - 내용: "당분간 신청서에 SP Account/LOA/Main Breaker Photo/SLD 파일이 첨부되지 않을 수 있음. 필요 시 신청자에게 전화/이메일로 요청. Phase 2에서 플랫폼 내 요청 기능 제공 예정."

---

## 개발자 Handoff 체크리스트

- [ ] Backend: `ApplicantType` enum, `Application.applicantType` 필드, Flyway 스크립트
- [ ] Backend: `SignupRequest`에서 4개 필드 제거, 검증 제약 제거
- [ ] Backend: `CreateApplicationRequest`에 applicantType 추가 + `@NotNull`
- [ ] Backend: `ApplicationResponse`에 applicantType 노출
- [ ] Frontend: `SignupPage.tsx` L173-243 제거
- [ ] Frontend: `NewApplicationPage.tsx` Step 0 업로드 블록 4개 제거 (L472-540, L544-604, L647-720, L774-850)
- [ ] Frontend: Step 0에 applicantType 라디오 추가
- [ ] Frontend: "필수" 라벨과 백엔드 검증 정합성 회귀 점검
- [ ] Test: AC-S1~S6, AC-A1~A6, AC-P1~P3, AC-C1~C3 전체 커버
- [ ] Ops: 배포 전 LEW 공지 초안 작성 (의사결정 (c) 확정 후)
