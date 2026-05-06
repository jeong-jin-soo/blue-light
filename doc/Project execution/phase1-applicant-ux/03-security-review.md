# Phase 1 — 보안/PDPA/마이그레이션 리스크 리뷰

**작성일**: 2026-04-17
**대상 변경**: 회원가입 4필드(phone/companyName/uen/designation) 제거 + Step 0 파일 업로드 UI 제거 + `Application.applicantType` 컬럼 신설
**리뷰어**: Security Architect
**결론**: **블로커 4건 해결 후 머지 가능**. DDL/데이터 측면에서는 낮은 위험. PDPA 고지 문구/동의 범위 재정비는 필수.

---

## 1. PDPA (Singapore Personal Data Protection Act) 영향 분석

### 1.1 데이터 최소화 원칙 측면 — **긍정적**
PDPA §18 "Purpose Limitation" 및 §17 Consent와 함께 자주 인용되는 **data minimisation** 원칙에 부합한다. 가입 시점에 수집 목적이 없는 항목(phone/companyName/uen/designation)을 제거하는 것은 PDPC 가이드라인이 권장하는 방향이다.

### 1.2 기존 Privacy Policy/Consent 문구 정합성 — **블로커(B-1)**
현재 상태:
- `blue-light-frontend/src/pages/legal/PrivacyPolicyPage.tsx` L32: "Account Information: Full name, email address, **phone number**"로 명시. companyName/UEN/Designation은 현재 Privacy Policy에도 누락되어 있으나, Signup UI에서는 받고 있었다 — **현재도 불일치**.
- `blue-light-frontend/src/pages/auth/SignupPage.tsx` L383-395의 consent 문구는 "personal data as described"라는 포괄적 표현 → 문구 자체는 Phase 1 변경으로 바꾸지 않아도 기술적으로 유효하나, Privacy Policy §1 "Personal Data We Collect" 목록은 **현 시점의 실수집 항목과 정확히 일치시켜야** PDPA §20 Notification Obligation을 만족한다.
- `User.pdpaConsentAt` 타임스탬프(User.java L134)만 저장하고 동의한 Privacy Policy 버전/개정일은 저장하지 않음.

### 1.3 기존 가입자 동의 재취득 — **불필요**
수집 범위가 **축소**되고 기존 값은 보존되므로, PDPC 가이드 §"Changes to Purpose"에 해당하지 않는다(새로운 목적/제3자 공유 추가가 아님). 재동의 불필요.

### 1.4 ProfilePage에서의 선택 입력 — **블로커(B-2)**
현재 `UserController.updateProfile` 류 API는 profile 편집 시 별도 PDPA 재동의를 받지 않는다. Phase 1에서 UEN/companyName/designation이 **선택 수집**으로 전환되면, 실제 수집 시점에 "수집 목적(EMA LOA 인쇄/라이선스 등록)"을 고지해야 한다. 최소한 ProfilePage 해당 섹션에 **inline helper text** (예: *"UEN and Company Name are used for Letter of Appointment generation and EMA licence printing only"*)를 추가.

---

## 2. 기존 데이터 보호 검증

### 2.1 User 테이블 — **안전**
`01-spec.md §3`대로 DDL 무변경, 기존 4컬럼 유지. `User.updateProfile(...)`(User.java L239-253) 메서드 시그니처가 7개 인자를 모두 받도록 이미 되어 있어 선택 입력 정상 저장 가능.

### 2.2 Application 마이그레이션 트랜잭션성
```sql
ALTER TABLE application ADD COLUMN applicant_type VARCHAR(20) NULL;
UPDATE application SET applicant_type='INDIVIDUAL' WHERE applicant_type IS NULL;
ALTER TABLE application MODIFY COLUMN applicant_type VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL';
```
- MySQL 8.0에서 **DDL은 암묵 커밋** — 세 문장은 단일 트랜잭션이 아니다. 중간에 실패하면 부분 적용 상태로 남는다.
- **롤백 절차 명시 필요(B-3)**: 실패 시 `ALTER TABLE application DROP COLUMN applicant_type;`로 복원하는 down 스크립트를 릴리즈 노트에 첨부. Flyway undo는 커뮤니티 버전에서 미지원이므로 수동 runbook 필요.

### 2.3 `schema.sql` vs Flyway — **블로커(B-4)**
`application.yaml` L27 `ddl-auto: none` + `resources/schema.sql` 존재 + **`db/migration/` 디렉토리 부재**. 즉 현재 스키마는 `schema.sql`로 관리되고 있고 Flyway는 아직 도입되지 않았다. `01-spec.md §5`는 "Flyway 스크립트"를 전제로 적혀 있으나 실제로는:
- (a) Flyway를 이번 기회에 도입하거나
- (b) `schema.sql`의 `application` 테이블 정의를 수정하고 기존 DB에는 별도 수동 마이그레이션을 돌리거나
중 하나를 선택·문서화해야 한다. 이 결정이 없으면 배포 자체가 불가능하다.

---

## 3. 하위호환성 리스크

### 3.1 Jackson `FAIL_ON_UNKNOWN_PROPERTIES` — **안전**
`application.yaml`에 `spring.jackson.deserialization.fail-on-unknown-properties` 설정 없음. `Jackson2ObjectMapperBuilder` 커스텀 빈도 없음 → **Spring Boot 기본값 `false`** 적용. 구버전 클라이언트가 phone/companyName/uen/designation을 전송해도 조용히 무시되며 201 응답 정상. `AC-S3` 통과 가능.

### 3.2 Bean Validation 제약 제거 필수
`SignupRequest.java` L33, L59-72의 `@Size` 제약은 필드 제거와 함께 삭제. 필드만 지우고 validation annotation이 남으면 컴파일 에러.

### 3.3 `AuthService` signup 로직 — 확인 필요
`AuthService.java`에서 `request.getCompanyName()` 등을 User entity 빌더에 넘기고 있다면 그 부분도 함께 제거. 남겨두면 구 클라이언트가 보낸 값을 그대로 저장해 "조용히 무시"라는 AC-S3와 어긋난다.

---

## 4. 마이그레이션 안전성 (MySQL 8.0)

- `ALTER TABLE application ADD COLUMN ... NULL`: MySQL 8.0.29+에서 `ALGORITHM=INSTANT` 자동 적용(끝에 컬럼 추가, default 변경 없음) → 락 시간 거의 0.
- `UPDATE application SET applicant_type='INDIVIDUAL'`: 전체 row 스캔. 현재 LicenseKaki는 개발/파일럿 단계로 application 레코드 수 수백 건 미만으로 추정 → 수 초 내 완료.
- `MODIFY COLUMN ... NOT NULL DEFAULT ...`: default 값 변경과 NULL→NOT NULL은 **INSTANT 불가, INPLACE로 수행**. 테이블 락은 짧지만 메타데이터 락 대기가 발생할 수 있으므로 배포 윈도우에 수행 권장.
- **권장**: 운영 규모 커지기 전 단계이므로 현 시점 락 리스크는 **L(낮음)**. 단, `01-spec.md §5`에서 3개 문장을 **하나의 Flyway 스크립트**로 묶을 때 중간 실패 시 수동 복구가 필요함을 §2.2 참고.

---

## 5. 감사 로깅 (Audit) 요구사항

- `Application.applicantType`은 INDIVIDUAL↔CORPORATE 전환 이력이 LEW/관리자 판단에 영향을 주므로 **변경 감사 필요**. 현재 `BaseEntity.updatedBy/updatedAt`만으로는 이전 값을 알 수 없다. Phase 2에서 JIT 모달과 함께 `AuditLogService`를 활용한 명시 로깅(`APPLICANT_TYPE_CHANGED` 이벤트) 도입 권장(**권장 R-1**).
- `LoaGenerationService.java` L107-115은 LOA 본문에 `applicant.getCompanyName()`/`getDesignation()`을 직접 인쇄. **LOA 생성 시점의 값 스냅샷**이 Loa 엔티티나 생성 아티팩트에 별도 저장되지 않으면, 사후 프로필 변경 시 LOA 원본과 현재 DB 값이 괴리된다. Phase 2 JIT 모달에서 CORPORATE 신청자가 새로 입력한 회사정보가 LOA로 흘러갈 때 더 두드러진다. **권장 R-2**: LOA 생성 시 applicant_name/company_name/designation/uen snapshot을 `loa` 테이블에 컬럼으로 보관.
- `LoaGenerationService.java` L374-388의 `INCOMPLETE_PROFILE` 가드는 Phase 1에서 유지(`AC-C3`). 현 코드 상태에서 정상 동작하므로 변경 없음.

---

## 6. Phase 1 배포 중 일시적 보안 공백

Step 0 파일 업로드 UI 제거로 LEW가 **외부 채널(이메일/전화)**로 SP Account PDF, LOA, Breaker Photo, SLD를 임시 수신하는 기간이 존재한다.

- **리스크**: SP Account PDF·LOA는 거주지 주소·UEN·서명을 포함한 **PDPA class of "personal data"**. 개인 이메일함(Gmail 등)에 무기한 잔존 시 §24 Protection Obligation 위반 소지.
- **임시 운영 가이드(B-배너에 포함)**:
  1. LEW는 회사 이메일로만 수신, 수신 즉시 관리자 업로드 후 원본 메일·첨부 삭제.
  2. 관리자가 대행 업로드 시 `AuditLogService`로 `ADMIN_UPLOAD_ON_BEHALF` 이벤트 남기기 — 현재 관리자 파일 업로드 API에 감사로그가 누락되어 있는지 확인 필요(**블로커 아님, 권장 R-3**).
  3. 외부 수신 기간을 **4주 이내로 제한** — Phase 2 일정을 이 안에 확정.

---

## 7. 리스크 요약 표

| ID | 리스크 | 심각도 | 발생 가능성 | 완화책 | 머지 전 차단 |
|---|---|---|---|---|---|
| R1 | Privacy Policy §1 목록이 실수집 항목과 불일치 (PDPA §20 위반) | M | H | PrivacyPolicyPage §1 전면 갱신, 개정일 표기 | **예(B-1)** |
| R2 | ProfilePage 선택 수집 시 목적 고지 누락 | M | H | inline helper text 추가, 저장 시 목적 로깅 | **예(B-2)** |
| R3 | Flyway 부재로 마이그레이션 경로 미정 | H | H | schema.sql 직접 수정 vs Flyway 도입 결정·문서화 | **예(B-4)** |
| R4 | DDL 3문장 중간 실패 시 수동 복구 경로 없음 | M | L | rollback SQL runbook 첨부 | **예(B-3)** |
| R5 | `SignupRequest` validation/AuthService에 잔류 참조 | M | M | DTO + 서비스 동시 정리, 단위 테스트 추가 | 아니오(회귀 테스트) |
| R6 | 구 클라이언트 호환 | L | M | Jackson 기본값 OK, 추가 조치 불필요 | 아니오 |
| R7 | 외부 채널 파일 수신 기간 개인정보 유출 | M | M | 운영 가이드 + 기간 제한 + Phase 2 일정 확정 | 아니오(운영) |
| R8 | LOA 스냅샷 부재로 사후 값 괴리 | L | M | Phase 2에서 `loa` 테이블 스냅샷 컬럼화 | 아니오(권장) |
| R9 | `applicantType` 변경 이력 미기록 | L | L | Phase 2 JIT 구현 시 감사로그 도입 | 아니오(권장) |
| R10 | MySQL DDL 락 | L | L | 배포 윈도우 수행 | 아니오 |

---

## 8. 머지 전 필수 수정사항 (Blockers)

- **B-1 · Privacy Policy 업데이트**: `PrivacyPolicyPage.tsx` §1 "Personal Data We Collect"를 `name, email` 필수 + `phone, company, UEN, designation` **optional (provided via profile page for licence generation)**으로 재작성. 페이지 하단 "Last Updated" 날짜 갱신. 동의 시점 DB에 Privacy Policy 버전 저장 필드 추가 권장(최소한 `pdpaConsentAt`과 함께 `pdpa_consent_version` 컬럼 신설).
- **B-2 · ProfilePage 수집 목적 고지**: UEN/companyName/designation 입력 섹션에 "Used only for Letter of Appointment and EMA licence printing" helper text. 저장 API 호출 시점에 `AuditLogService`로 `PROFILE_COMPANY_INFO_UPDATED` 기록.
- **B-3 · 마이그레이션 롤백 Runbook**: `doc/Project execution/phase1-applicant-ux/` 하위에 `04-deployment-runbook.md` 신설 — forward SQL 3문장 + rollback `ALTER TABLE application DROP COLUMN applicant_type` + 실패 지점별 복구 절차.
- **B-4 · 마이그레이션 실행 경로 확정**: 현재 프로젝트에 `db/migration/` 디렉토리가 없고 `ddl-auto: none` + `schema.sql` 기반이다. `01-spec.md §5`의 "Flyway 스크립트" 전제가 실제 인프라와 불일치. 다음 중 택일하여 문서화:
  - (a) 이번 Phase에 Flyway 도입 — `build.gradle` 의존성, `application.yaml` `spring.flyway.enabled=true`, `V1__baseline.sql` 생성(기존 schema.sql 내용)
  - (b) `schema.sql`에 `application.applicant_type` 컬럼 추가 + 운영 DB는 별도 SQL 스크립트 수동 실행

---

## 9. 권장 보완 (선택, Phase 2 이후)

- **R-1 · applicantType 변경 감사 이벤트**: `AuditLogService.log("APPLICATION_TYPE_CHANGED", old→new)`.
- **R-2 · LOA 스냅샷 테이블**: `loa` 엔티티에 `applicant_name_snapshot`, `company_name_snapshot`, `uen_snapshot`, `designation_snapshot`, `generated_at` 컬럼 추가 — LOA는 서명된 법적 문서이므로 생성 당시 상태 보존이 필수.
- **R-3 · 관리자 대행 업로드 감사**: Admin 파일 업로드 경로에 `ADMIN_UPLOAD_ON_BEHALF { targetUserId, applicationId, fileType }` 이벤트 기록 — 외부 채널 수신 파일 체인 오브 커스터디 확보.
- **R-4 · Privacy Policy 버전 관리**: `User.pdpaConsentVersion` 필드 + Privacy Policy 문서 버전 상수화. 다음 개정 시 재동의 트리거 자동화 가능.
- **R-5 · 가입 직후 프로필 완성 배너**(의사결정 (b)): Phase 2에서 "Complete profile to generate LOA"만 유도, 모든 사용자에게 일괄 요구하지 않음 — PDPA 데이터 최소화 원칙 유지.

---

## 참고 코드 위치

- `blue-light-backend/src/main/java/com/bluelight/backend/api/auth/dto/SignupRequest.java` L33, L59-72 (제거 대상 필드)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/user/User.java` L58, L91-105, L134, L333-347 (보존 대상 + anonymize)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/loa/LoaGenerationService.java` L107-115, L374-388 (LOA 인쇄 + INCOMPLETE_PROFILE 가드)
- `blue-light-backend/src/main/resources/application.yaml` L27 (`ddl-auto: none`)
- `blue-light-backend/src/main/resources/schema.sql` (현 스키마 정의원)
- `blue-light-frontend/src/pages/legal/PrivacyPolicyPage.tsx` L27-38 (갱신 대상)
- `blue-light-frontend/src/pages/auth/SignupPage.tsx` L16-23, L134-141, L204-243, L374-395 (제거 대상 + consent 체크박스)
