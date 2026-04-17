# Phase 1 테스트 계획서 — 신청자 UX 간소화

**작성일**: 2026-04-17
**QA 담당**: tester agent
**대상 스펙**: `01-spec.md` AC-S1~AC-C3 (18개 수용 기준)
**대상 PR**: 변경 A (SignupRequest 간소화), 변경 A-2 (Flyway 마이그레이션), 변경 B (Step 0 재설계)

---

## 1. 테스트 전략

### 테스트 환경 현황

| 레이어 | 테스트 도구 | 비고 |
|---|---|---|
| 백엔드 | JUnit 5 + Spring Boot Test (MockMvc) | 기존 사용 중 (`BackendApplicationTests.java`) |
| 프론트엔드 단위 | 없음 (vitest/jest 미설치, playwright 없음) | package.json 확인 완료 → **수동 검증** |
| E2E | Playwright 미설치 | → **수동 시나리오**로 대체 |
| API 통합 | curl / Postman | 로컬 백엔드 실행 기준 |

프론트엔드는 `npm run build` (TypeScript 컴파일 오류 차단) + 수동 검증 조합으로 진행한다.

### 자동화 / 수동 배분

| 분류 | 자동화 | 수동 |
|---|---|---|
| 백엔드 API 검증 | JUnit MockMvc (TC-SU, TC-A0, TC-MG, TC-PR의 서버 로직) | — |
| 프론트엔드 빌드 | `npm run build` TypeScript 컴파일 | DOM 구조, 라벨, 반응형 |
| 데이터 마이그레이션 | Flyway 스크립트 실행 검증 | 마이그레이션 후 DB 직접 조회 |
| E2E 흐름 | — (Playwright 없음) | 수동 시나리오 1~2 |

---

## 2. AC 매트릭스 — 18개 전체

| AC ID | 설명 (요약) | 테스트 ID | 레벨 | 자동화 |
|---|---|---|---|---|
| AC-S1 | 가입 폼에 4필드 DOM 없음 | TC-SU-01 | 수동(DOM) | 수동 |
| AC-S2 | 최소 3필드만으로 201 응답 | TC-SU-02 | 통합(MockMvc) | 자동 |
| AC-S3 | 구버전 클라이언트 phone 포함 → 무시 후 성공 | TC-SU-03 | 통합(MockMvc) | 자동 |
| AC-S4 | 가입 후 User.companyName 등 4컬럼 NULL | TC-SU-04 | 통합(MockMvc) | 자동 |
| AC-S5 | 가입 후 대시보드 이동, "프로필 완성" 배너 없음 | TC-SU-05 | 수동(브라우저) | 수동 |
| AC-S6 | SignupRequest에 4필드 @NotBlank 제약 없음 | TC-SU-06 | 통합(MockMvc) | 자동 |
| AC-A1 | Step 0 파일 업로드 UI 0개 | TC-A0-01 | 수동(DOM) | 수동 |
| AC-A2 | 필수 필드만 입력 시 Next 버튼 활성화 | TC-A0-02 | 수동(브라우저) | 수동 |
| AC-A3 | applicantType 기본값 INDIVIDUAL, 필수 | TC-A0-03 | 통합(MockMvc) + 수동 | 복합 |
| AC-A4 | applicantType 누락 → 400 Bad Request | TC-A0-04 | 통합(MockMvc) | 자동 |
| AC-A5 | applicantType=CORPORATE → 플래그만 저장, JIT 없음 | TC-A0-05 | 통합(MockMvc) + 수동 | 복합 |
| AC-A6 | 프론트 "필수" 표시 = 백엔드 @NotNull 정합 | TC-A0-06 | 코드 리뷰 + MockMvc | 복합 |
| AC-P1 | 프로필에 4필드 모두 선택 입력으로 노출 | TC-PR-01 | 수동(DOM) | 수동 |
| AC-P2 | 기존 사용자 값 프리필 | TC-PR-02 | 통합(MockMvc) + 수동 | 복합 |
| AC-P3 | 빈 문자열 → NULL 정규화 | TC-PR-03 | 통합(MockMvc) | 자동 |
| AC-C1 | 기존 User 데이터 배포 후 무손실 | TC-MG-01 | DB 직접 조회 | 수동 |
| AC-C2 | 기존 Application.applicantType = INDIVIDUAL 백필 | TC-MG-02 | DB 직접 조회 | 수동 |
| AC-C3 | LOA 생성 시 INCOMPLETE_PROFILE 예외 유지 | TC-MG-03 | 통합(MockMvc) | 자동 |

---

## 3. 시나리오 기반 테스트 케이스

### 회원가입 시나리오

**TC-SU-01** (수동 / AC-S1)
- 전제: 로컬 프론트엔드 실행 (`npm run dev`, port 5174)
- 단계: 브라우저 개발자도구 열기 → `/signup` 접속 → Elements 탭에서 `name="phone"`, `name="companyName"`, `name="uen"`, `name="designation"` 검색
- 기대: 검색 결과 0건. `input[name="phone"]` DOM 쿼리 결과 null.
- FAIL 조건: 1건이라도 발견 시 CRITICAL

**TC-SU-02** (MockMvc 자동 / AC-S2)
```
POST /api/auth/signup
Body: { "email": "tc02@test.sg", "password": "Pass1234", "firstName": "Test", "lastName": "User", "pdpaConsent": true }
기대: 201 Created, body에 accessToken 존재
```

**TC-SU-03** (MockMvc 자동 / AC-S3)
```
POST /api/auth/signup
Body: { "email": "tc03@test.sg", "password": "Pass1234", "firstName": "Old", "lastName": "Client",
        "pdpaConsent": true, "phone": "91234567", "companyName": "ABC Pte Ltd", "uen": "202312345A", "designation": "Manager" }
기대: 201 Created (4개 필드 무시됨)
```

**TC-SU-04** (MockMvc 자동 / AC-S4)
- TC-SU-02 실행 후 DB 조회: `SELECT phone, company_name, uen, designation FROM users WHERE email='tc02@test.sg'`
- 기대: 4컬럼 모두 NULL

**TC-SU-05** (수동 / AC-S5)
- 단계: TC-SU-02 흐름 실행 → 가입 완료 후 리디렉션 화면 확인
- 기대: 대시보드 또는 ProfilePage 도착. 화면 어디에도 "프로필 완성" 또는 "Complete your profile" 배너/모달 없음
- 확인 포인트: Phase 2 유도 UI가 Phase 1에 유출되지 않았는지

**TC-SU-06** (MockMvc 자동 / AC-S6)
```
POST /api/auth/signup
Body: { "email": "tc06@test.sg", "password": "Pass1234", "firstName": "A", "lastName": "B", "pdpaConsent": true, "companyName": "" }
기대: 201 Created (companyName 빈값도 무시, @NotBlank 제약이 없으므로 400 나오지 않음)
```

**TC-SU-07** (수동 회귀)
- 이메일 중복 가입 → `409 Conflict` 또는 400 응답 + 에러 메시지
- 비밀번호 불일치(프론트) → "비밀번호가 일치하지 않습니다" 인라인 에러
- PDPA 미동의 + 제출 → Create account 버튼 비활성 또는 클라이언트 에러

---

### Step 0 시나리오

**TC-A0-01** (수동 / AC-A1)
- 단계: 로그인 → 신청 생성 진입 → Step 0 화면 → 개발자도구 Elements → `input[type="file"]` 쿼리
- 기대: 0건. `document.querySelectorAll('input[type="file"]').length === 0`
- 확인 포인트: SP Account PDF / LOA Document / Main Breaker Photo / SLD File 4종 모두 없음

**TC-A0-02** (수동 / AC-A2)
- 단계: Step 0 진입 → 아무 것도 선택하지 않은 초기 상태
- 기대: "Next" 버튼 `disabled` 상태 (cursor-not-allowed 또는 pointer-events-none)
- 이후: ApplicationType + LicencePeriod + ApplicantType + SldOption 4종 모두 선택 → Next 버튼 활성화 확인

**TC-A0-03** (수동 + MockMvc / AC-A3)
- 수동: Step 0 렌더링 시 Individual 라디오 기본 선택 상태 확인
- MockMvc:
```
POST /api/applications
Body: { ..., "applicantType": "INDIVIDUAL" }
기대: 201 Created
GET /api/applications/{id}
기대: "applicantType": "INDIVIDUAL" 포함
```

**TC-A0-04** (MockMvc 자동 / AC-A4)
```
POST /api/applications
Body: { "address": "1 Orchard Rd", "postalCode": "238801", "selectedKva": 100 }
(applicantType 누락)
기대: 400 Bad Request, error message에 "applicantType" 언급
```

**TC-A0-05** (MockMvc + 수동 / AC-A5)
```
POST /api/applications
Body: { ..., "applicantType": "CORPORATE" }
기대: 201 Created
GET /api/applications/{id}
기대: "applicantType": "CORPORATE"
추가 수동: 제출 완료 후 회사정보 입력 모달/팝업이 뜨지 않음 확인
```

**TC-A0-06** (코드 리뷰 + MockMvc / AC-A6)
- 코드 리뷰: `CreateApplicationRequest.java`의 `@NotNull` 어노테이션 vs 프론트 `*` 필수 표시 필드 목록 대조
- MockMvc: `address` 누락 → 400, `postalCode` 누락 → 400, `selectedKva` 누락 → 400 각각 확인

**TC-A0-07** (수동)
- SP Account Number 필드 공백 제출 → 신청 생성 성공 확인 (선택 필드이므로 400 발생하면 FAIL)

---

### ProfilePage 시나리오

**TC-PR-01** (수동 / AC-P1)
- 단계: 로그인 → `/profile` 접속 → "Company Information" 섹션 확인
- 기대: companyName, UEN, designation, phone 4개 필드 모두 렌더링. 라벨에 `(optional)` 또는 `*` 없음(선택 표시)
- FAIL 조건: 4필드 중 1개라도 없거나, 필수(`*`) 표시 시

**TC-PR-02** (MockMvc + 수동 / AC-P2)
- 전제: DB에 companyName='Blue Light Pte Ltd', uen='202312345A' 값 있는 사용자
- `GET /api/users/me` → companyName, uen 포함 응답 확인
- 수동: ProfilePage 진입 시 해당 값이 입력칸에 프리필 확인

**TC-PR-03** (MockMvc 자동 / AC-P3)
```
PATCH /api/users/me
Body: { "companyName": "", "uen": "  ", "designation": "" }
기대: 200 OK
GET /api/users/me
기대: companyName: null, uen: null, designation: null (빈 문자열 아닌 null)
```

**TC-PR-04** (수동)
- 신청 흐름 도중 Step 0에서 ProfilePage 링크 접근 가능 여부 확인 (뒤로가기 또는 헤더 네비)

---

### 기존 데이터 호환 시나리오

**TC-MG-01** (수동 DB 조회 / AC-C1)
- 전제: Flyway 마이그레이션 실행 전 users 테이블 스냅샷 저장
  ```sql
  SELECT user_seq, phone, company_name, uen, designation FROM users LIMIT 100;
  ```
- Flyway 실행 후 동일 쿼리 재실행
- 기대: phone/company_name/uen/designation 값 변경 없음 (DDL 변경 없음이므로 당연하지만, 서비스 레이어 save()가 null로 덮어쓰지 않는지 확인)

**TC-MG-02** (수동 DB 조회 / AC-C2)
- Flyway V{next}__add_applicant_type_to_application.sql 실행 후:
  ```sql
  SELECT COUNT(*) FROM applications WHERE applicant_type IS NULL;
  SELECT COUNT(*) FROM applications WHERE applicant_type != 'INDIVIDUAL';
  ```
- 기대: 첫 번째 쿼리 = 0 (NULL 없음), 두 번째 쿼리 = 0 (백필 완료)
- 컬럼 NOT NULL 제약 확인: `SHOW CREATE TABLE applications;`

**TC-MG-03** (MockMvc 자동 / AC-C3)
- 전제: companyName/designation이 NULL인 사용자로 인증
- `POST /api/applications/{id}/loa` (LOA 생성 엔드포인트 호출)
- 기대: `INCOMPLETE_PROFILE` 에러 코드 포함 4xx 응답

---

## 4. 경계 사례 & 회귀 테스트

| ID | 시나리오 | 기대 결과 | 레벨 |
|---|---|---|---|
| TC-REG-01 | 관리자 화면(`/admin/users`)에서 기존 사용자 조회 | phone/companyName 값 정상 표시 | HIGH |
| TC-REG-02 | LEW 화면 신청 목록에서 applicantType 필드 렌더링 | "Individual" 또는 "Corporate" 문자열 표시 | MEDIUM |
| TC-REG-03 | 구버전 앱이 phone 포함 signup 요청 | 400 없이 201 응답 (AC-S3 회귀) | HIGH |
| TC-REG-04 | 마이그레이션 중 applications 테이블 레코드 10만 건 가정 | UPDATE 쿼리 타임아웃 없음 (배치 처리 or 인덱스 확인) | MEDIUM |
| TC-REG-05 | applicantType = null인 ApplicationResponse를 프론트가 수신 | 프론트가 null → "INDIVIDUAL"로 방어 렌더링 | LOW |

### TC-REG-04 상세 (대용량 마이그레이션 시뮬레이션)
- 개발서버 기준 현재 레코드 수 확인: `SELECT COUNT(*) FROM applications;`
- Flyway 실행 시 슬로우 쿼리 로그 활성화 후 실행 시간 측정
- 목표: 10만 건 기준 30초 이내. 초과 시 `WHERE applicant_type IS NULL LIMIT 1000` 배치 분할 권고

---

## 5. E2E 수동 시나리오

### 시나리오 1: 신규 사용자 최소 정보로 가입 → 첫 신청 생성 완주

전제: 로컬 백엔드(8090) + 프론트(5174) 모두 실행 중

| 단계 | 액션 | 체크포인트 | 목표 시간 |
|---|---|---|---|
| 1 | `/signup` 접속 | 4필드(phone/company/uen/designation) DOM 없음 (TC-SU-01) | — |
| 2 | First Name, Last Name, Email, Password, Confirm PW 입력 + PDPA 체크 | "Create account" 버튼 활성화 | 30초 |
| 3 | 제출 | 201 응답 + 대시보드 또는 홈 이동 | 5초 |
| 4 | "새 신청" 버튼 → Step 0 진입 | 파일 업로드 input 0개 (TC-A0-01) | — |
| 5 | ApplicationType 선택 (New Licence) | 선택 후 카드 강조 | 5초 |
| 6 | LicencePeriod 선택 (1 year) | 선택 후 카드 강조 | 5초 |
| 7 | ApplicantType: Individual (기본 선택 확인) | INDIVIDUAL 기본 선택 (TC-A0-03) | — |
| 8 | SLD Option 선택 | 선택 후 Next 버튼 활성화 (TC-A0-02) | 5초 |
| 9 | Next 클릭 → Step 1 진입 | Step 1 화면 정상 전환 | 3초 |
| 10 | 최종 제출 완료 | 신청 상태 PENDING_REVIEW 확인 | 5초 이내 |

총 예상 소요: 5분 이내

### 시나리오 2: 기존 사용자가 ProfilePage에서 회사정보 추가 → Phase 1에서는 신청 시 자동 활용 안 됨

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | 기존 사용자 로그인 → ProfilePage 진입 | 저장된 companyName 프리필 확인 (TC-PR-02) |
| 2 | companyName 수정 후 Save | 200 응답 + "회사 정보가 저장되었습니다" 토스트 |
| 3 | 새 신청 → Step 0 진입 | Step 0에 회사명 자동입력 없음 확인 (Phase 1 범위 외) |
| 4 | 신청 완료 후 ApplicationResponse 확인 | applicantType 필드 포함 여부 확인 |

**Phase 1 명시 사항**: Step 0에서 ProfilePage의 companyName을 prefill하는 동작은 Phase 2 구현 예정. Phase 1에서 해당 동작이 있으면 범위 초과로 MEDIUM 이슈 등록.

---

## 6. 성능/부하 검증

| 항목 | 측정 방법 | 목표 |
|---|---|---|
| Flyway 마이그레이션 실행 시간 | `SHOW PROCESSLIST` + 슬로우 쿼리 로그 | applications 전체 rows 기준 10초 이내 (개발서버) |
| UPDATE 배치 테이블 락 | `information_schema.INNODB_LOCKS` 조회 | 락 보유 시간 1초 미만 (소규모 환경) |
| `POST /api/applications` 응답 시간 | curl `-w "%{time_total}"` 3회 평균 | 500ms 이내 |
| `GET /api/users/me` 응답 시간 | curl `-w "%{time_total}"` 3회 평균 | 200ms 이내 |

---

## 7. 수동 검증 체크리스트 (PR 올리기 전 로컬 확인)

개발자가 각 PR 머지 전 반드시 로컬에서 확인:

- [ ] `SignupPage.tsx`에 `name="phone"`, `name="companyName"`, `name="uen"`, `name="designation"` 4개 input DOM이 없는가
- [ ] Step 0 화면에 `input[type="file"]`이 0개인가 (DevTools Elements 탭 확인)
- [ ] Step 0에 ApplicantType 라디오 그룹이 존재하고 Individual이 기본 선택인가
- [ ] "No documents needed now" info box가 상시 표시되는가 (숨김 없음)
- [ ] `npm run build`가 TypeScript 에러 없이 완료되는가
- [ ] `./gradlew compileJava`가 에러 없이 완료되는가
- [ ] `SignupRequest.java`에 companyName/uen/designation의 `@NotBlank` 제약이 없는가 (코드 확인)
- [ ] `CreateApplicationRequest.java`에 `applicantType` 필드 + `@NotNull`이 추가되었는가
- [ ] `Application.java` 엔티티에 `applicantType` 컬럼이 추가되었는가
- [ ] Flyway 스크립트 번호가 기존 버전과 충돌하지 않는가 (`V{N}__` 연번 확인)
- [ ] ProfilePage에서 4개 회사정보 필드가 모두 선택 입력으로 렌더링되는가
- [ ] 모바일 뷰(375px)에서 Step 0 ApplicantType 카드 1열 스택, 레이아웃 깨짐 없는가
- [ ] 영문/한글 전환 시 Step 0 info box 텍스트가 정상 표시되는가
- [ ] 기존 사용자(companyName 보유)가 프로필 페이지 진입 시 값이 프리필되는가

---

## 8. 버그 발견 시 재현 절차 템플릿

```
[BUG] {버그 제목}

## 환경
- OS: macOS 15.3 / Windows 11
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
3. ...

## 기대 결과
{스펙 기준 정상 동작}

## 실제 결과
{실제 발생한 동작, 스크린샷 첨부}

## 요청/응답 로그 (API 이슈인 경우)
curl -X ... -d '...'
Response: {상태코드, body}

## 추가 정보
{DB 쿼리 결과, 콘솔 에러 등}
```

---

## 9. 테스트 실행 순서

### PR #1 — 변경 A (SignupRequest 간소화, Step 0 파일 업로드 제거)
머지 전 실행:
1. `./gradlew compileJava` — 컴파일 오류 없음 확인
2. TC-SU-02, TC-SU-03, TC-SU-04, TC-SU-06 (MockMvc 자동)
3. TC-SU-01, TC-SU-05, TC-SU-07 (수동 브라우저)
4. TC-A0-01, TC-A0-02 (수동 DOM)
5. TC-REG-03 (하위호환 회귀)

### PR #2 — 변경 A-2 (Flyway 마이그레이션 + Application 엔티티 변경)
머지 전 실행:
1. `./gradlew compileJava` — 컴파일 오류 없음 확인
2. DB 스냅샷 저장 → Flyway 스크립트 실행 → TC-MG-01, TC-MG-02 (DB 직접 조회)
3. TC-A0-04, TC-A0-05, TC-A0-03 (MockMvc 자동)
4. TC-MG-03 (LOA 회귀, MockMvc)
5. 성능 검증: UPDATE 실행 시간 측정

### PR #3 — 변경 B (Step 0 UI 재설계 — ApplicantType 라디오 추가)
머지 전 실행:
1. `npm run build` — TypeScript 빌드 오류 없음 확인
2. TC-A0-01, TC-A0-02, TC-A0-03, TC-A0-06, TC-A0-07 (수동)
3. TC-PR-01, TC-PR-02, TC-PR-03, TC-PR-04 (수동 + MockMvc)
4. E2E 시나리오 1, 시나리오 2 전체 실행
5. TC-REG-01, TC-REG-02 (관리자/LEW 화면 회귀)
6. 모바일 375px 반응형 확인

---

## 10. 배포 후 스모크 테스트 (개발서버, 5분 이내)

배포 완료 직후 아래 항목을 순서대로 확인:

1. **가입 성공**: `POST https://43.210.92.190:8090/api/auth/signup` → 최소 3필드만 전송 → 201 확인
2. **4필드 DOM 없음**: 개발서버 프론트 `/signup` 접속 → DevTools `input[name="phone"]` 없음 확인
3. **신청 생성**: 로그인 후 Step 0 진입 → `input[type="file"]` 0개 확인
4. **applicantType 저장**: `POST /api/applications` 요청 → 201 + `GET /api/applications/{id}` 응답에 `applicantType` 포함 확인
5. **applicantType 누락 → 400**: `applicantType` 없이 신청 생성 → 400 Bad Request 확인
6. **Flyway 완료**: DB 접속 → `SELECT COUNT(*) FROM applications WHERE applicant_type IS NULL` → 0 확인
7. **ProfilePage 4필드**: 로그인 후 `/profile` → companyName/uen/designation/phone 4필드 선택 입력으로 노출 확인
