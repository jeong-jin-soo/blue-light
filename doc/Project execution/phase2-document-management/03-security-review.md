# Phase 2 — 보안/PDPA/파일 업로드/데이터 모델 리스크 리뷰

**작성일**: 2026-04-17
**대상 변경**: 법인 JIT 회사정보 모달(PR#3) + Document Type Catalog/DocumentRequest 엔티티·API(PR#1) + 신청 상세 자발적 업로드 UI(PR#2) + LOA 스냅샷 컬럼(PR#4)
**리뷰어**: Security Architect
**결론**: **블로커 5건 해결 후 머지 가능**. Phase 1 연속성(PDPA 고지·감사로깅·마이그레이션 절차)은 대체로 유지되나, 파일 업로드 계층의 MIME 검증·크기 강제·경로 정규화·악성 코드 검사가 `document_type_catalog` 도입 *이전 수준*에서 정체되어 있어 카탈로그의 `accepted_mime` / `max_size_mb`가 **형식적 메타데이터에 그칠 위험**이 크다.

---

## 1. 법인 JIT 모달 — PDPA & 트랜잭션 무결성

### 1.1 고지/동의 일관성 — **LOW (Phase 1 연속성 양호)**
Phase 1 Security Review B-1로 `PrivacyPolicyPage §1`이 "Optional Profile Information (company, UEN, designation — for LOA/licence printing)"으로 재작성되었다는 전제 하에, JIT 모달이 요구하는 3개 필드는 이미 고지된 수집 항목 범위 안에 있다 → **재동의 불필요**. 단 모달에 **수집 목적 inline helper text**(예: "Used for Letter of Authorisation and EMA licence printing only")를 노출해야 PDPA §20 Notification Obligation이 "실제 입력 화면"에서도 충족된다 → **B-1**.

### 1.2 "프로필에 저장" 기본값 `true` — **MEDIUM**
AC-J3의 기본값 `persistToProfile=true`는 PDPA opt-in 원칙에 **기술적 위반은 아니나**(가입 시 포괄 동의 범위 내), UX상 사용자가 인지하지 못한 채 1회성 입력이 영속 프로필이 된다. **권장 변경**: 체크박스 라벨 아래 보조 문구 "Unchecked = save to this application only, profile remains unchanged" + 기본값을 `false`로 하거나, `true` 유지 시 모달에 고지 문구 명시 → **B-2**. 감사 로그에 `persistToProfile` 플래그 값을 함께 기록(아래 §6).

### 1.3 단일 트랜잭션 보장 — **MEDIUM**
AC-J2는 `User.update` + `Application.insert`를 단일 `@Transactional`로 묶는다고 명시. 실제 구현 시 주의점:
- `ApplicationService.createApplication`에 `@Transactional(rollbackFor = Exception.class)` 명시 — 기본 rollback은 unchecked만이므로 `BusinessException` 외 checked exception에서 누락 방지.
- `UserRepository.save` 후 `ApplicationRepository.save`가 같은 persistence context에서 실행되는지 확인 (별도 `@Transactional(REQUIRES_NEW)` 중첩 금지).
- 실패 시 클라이언트는 동일 모달 상태 복원 — **브라우저에 UEN이 XHR 실패 후 메모리 외 다른 곳(sessionStorage 등)에 남지 않도록** 주의.

---

## 2. 파일 업로드 — Document Catalog 검증 계층의 구체화 필수

### 2.1 현재 상태 (`FileService.java` L35-41, L181-193) — **HIGH**
- **확장자 기반 검증만 수행** (`ALLOWED_EXTENSIONS` Set). `MultipartFile.getContentType()`·매직 바이트 검사 **없음**.
- `application.yaml` L49-51: `max-file-size: 10MB`, `max-request-size: 10MB` **전역 상수**. `document_type_catalog.max_size_mb`가 20MB(SLD_FILE)로 선언되어도 Tomcat이 먼저 `MaxUploadSizeExceededException`을 던져 **20MB PDF는 업로드 불가**. 스펙과 인프라 설정 간 모순 → **B-3**.
- `getOriginalFilename()`에서 확장자를 단순 `lastIndexOf(".")`로 추출 → `malware.pdf.exe` 같은 이중 확장자는 `.exe` 차단 가능하나, `image/png` MIME으로 위장한 `.png.svg`(XSS 벡터) 미차단.

### 2.2 Phase 2에서 반드시 추가할 검증
1. **MIME sniffing**: Apache Tika 또는 `java.net.URLConnection.guessContentTypeFromStream(bytes)`로 **실제 파일 헤더 검증** 후 `DocumentTypeCatalog.acceptedMime`과 교차 일치.
2. **크기 검증 2단계**: (a) Tomcat `max-file-size`를 `document_type_catalog`에서 가장 큰 값(현재 20MB = SLD_FILE) 이상으로 **상향**, (b) 서비스 계층에서 `file.getSize() > catalog.maxSizeMb * 1024 * 1024`면 `FILE_TOO_LARGE`로 400 반환 (AC-D3).
3. **파일명 정규화**: 저장 파일명은 이미 `UUID + extension`으로 안전(`LocalFileStorageService` L76) → OK. 단 `originalFilename`은 DB/Content-Disposition에 그대로 반영되므로 **CR/LF·제어문자·경로 구분자(`/`, `\`) 제거** 필요 (HTTP response splitting 방지).
4. **SVG/HTML 업로드 차단**: `SLD_FILE` accepted_mime이 `image/png,image/jpeg,application/pdf`로 한정되어 있어 괜찮지만, seed에 `image/svg+xml`·`text/html`을 **절대 추가하지 말 것** — SVG는 브라우저에서 스크립트 실행이 가능하여 저장형 XSS 벡터.
5. **악성 코드 검사**: 현재 프로젝트에는 AV 게이트웨이가 **없음**. Phase 2에서는 `APPLICANT → LEW` 파일 흐름이 더 공식화되므로 최소한 **ClamAV sidecar 도입 계획**을 Phase 3 블로커로 예약 → 권장 R-1.

**CWE**: CWE-434 (Unrestricted Upload of File with Dangerous Type), CWE-20 (Improper Input Validation), OWASP A03:2021.

### 2.3 저장소 경로·암호화 — **안전(조건부)**
- `LocalFileStorageService` (L50-57): `rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize()`. `subDirectory = "applications/" + applicationSeq`. `applicationSeq`는 `@PathVariable Long`이므로 traversal 입력 불가. **OK**.
- 단 `FileEncryptionUtil.isEnabled()`가 `FILE_ENCRYPTION_KEY` 환경변수 유무로 동작 분기 — **운영 배포 시 키가 누락되면 조용히 평문 저장**된다. 런타임 로그(L84/L87)만으로는 감지 어려움. Phase 2에서 새로 추가되는 `SP_ACCOUNT`·`LOA` 문서(UEN·주소·서명 포함, PDPA class)는 평문 저장 리스크가 더 크므로, **`profile=prod`일 때 키 미설정이면 애플리케이션 기동을 실패시킬 것** → **B-4**.
- 로컬 파일 시스템 권한: `./uploads`가 웹 루트(Spring Boot static) 외부인지 확인 — `application.yaml`에 `static-locations`가 `uploads/`를 포함하지 않는다면 OK (Spring Boot 기본은 `classpath:/static/`이라 외부 경로 안전).

---

## 3. DocumentTypeCatalog — 설계/시드 리뷰

| 항목 | 리스크 | 판정 |
|---|---|---|
| `accepted_mime VARCHAR(200)` 쉼표 구분 | 서비스 계층에서 `split(",")` 후 **trim 필수**, 대소문자 정규화 필요 (`application/PDF` ≠ `application/pdf`) | **M** |
| `template_url`·`example_image_url` | 외부 URL이면 **SSRF 위험**. 현재 seed에는 모두 NULL → 즉시 위험 없음. 추후 `href`로 프론트 렌더 시 `rel="noopener noreferrer"` 강제, `allowed host list`로 제한 | **L (현 시점)** |
| `required_fields JSON` | Hibernate 7.2에서 `@JdbcTypeCode(SqlTypes.JSON)` 사용 시 Jackson 역직렬화 경로 — **polymorphic deserialization 비활성화** (`ObjectMapper`에 `PolymorphicTypeValidator` 미설정 확인) | **L** |
| seed `icon_emoji` | 사용자 입력 아님, 관리자 편집 대상. 현재 seed 문자열은 정적 상수 → 안전 | — |
| seed 커밋 민감정보 | `template_url`이 내부 S3 presigned URL이 되면 Git 이력에 영구 기록됨. **NULL 유지** 또는 **공개 CDN만 허용** | **M** |

→ 서비스에 `MimeValidator` 유틸 신설: `boolean matches(String fileMime, String catalogAcceptedMime)` — 이 유틸의 단위 테스트를 AC-D3 수용 기준에 추가 → **B-3에 포함**.

---

## 4. LOA 스냅샷 (PR#4)

### 4.1 Phase 1 R-2 반영 확인 — **양호**
`loa` 테이블에 `applicant_name_snapshot / company_name_snapshot / uen_snapshot / designation_snapshot` 4컬럼이 신설되고, `LoaGenerationService.java` L107/L144(`applicant.getCompanyName()`/`applicant.getUen()` 직접 인쇄 구간)가 스냅샷 컬럼에서 읽도록 리팩터링된다는 전제. **원칙**: LOA는 서명된 법적 문서 → 스냅샷은 **불변**.

### 4.2 수정 정책 — **MEDIUM**
스펙 §3-3에는 "수정 정책" 명시가 없다. 다음을 규정할 것 → **B-5**:
- 스냅샷 컬럼은 **UPDATE 경로 없음**. JPA 엔티티에서 `@Column(updatable=false)` 강제, Repository에 custom update 쿼리 금지.
- 관리자가 교정이 필요한 경우: 기존 LOA를 **무효화(revoked=true)** 하고 신규 LOA를 재발급 — 스냅샷 자체를 수정하지 않는다.
- 감사 이벤트 `LOA_SNAPSHOT_CREATED`(신규)를 발급 시점 1회만 기록, UPDATE 이벤트는 정의하지 않음.

### 4.3 V_05 백필 전략 — **LOW**
스펙 §3-3은 "현 User 값으로 1회 복사"를 명시. 이는 엄밀히 말해 **과거 발급 시점 값이 아님** — 기존 LOA가 몇 건이든 "현재 User 값"을 소급 반영한다. 현 단계는 파일럿 규모이므로 수용 가능하나:
- 백필 row에는 `snapshot_backfilled_at DATETIME NULL` 플래그 컬럼을 추가하여 **"법적 증거력이 원본 생성 시점이 아닌 백필 시점임"**을 감사 가능하게 할 것 → 권장 R-2.
- V_05는 `WHERE applicant_name_snapshot IS NULL` 조건으로 **idempotent** 작성(이미 §7 R5에 명시됨). OK.

---

## 5. 신규 API 5종 보안 매트릭스

| 엔드포인트 | 인증 | 인가 | Rate Limit | Input 검증 블로커 |
|---|---|---|---|---|
| `GET /api/document-types` | JWT 필수 | 전체 로그인 사용자 | 미정 (권장 60/min) | 없음 (읽기 전용, active 필터) |
| `GET /api/applications/{id}/document-requests` | JWT | **소유자/LEW(assigned)/ADMIN** — `FileService.getFilesByApplication` 패턴 재사용 필수 | 권장 120/min | path `id` Long 타입 → injection 없음 |
| `POST /api/applications/{id}/documents` | JWT | **소유자만** (스펙 §4 명시) | **필수** — `FileController`의 `FILE_UPLOAD` 30회/10분 재사용 | MIME/size/custom_label (§2.2) |
| `POST /api/applications/{id}/document-requests/{reqId}/fulfill` | JWT | 소유자 | 위와 동일 | `reqId` path; **상태 전이 검증**: 오직 `REQUESTED → UPLOADED`만 허용, 이미 `APPROVED/REJECTED`면 409 |
| `POST /api/applications` (JIT 확장) | JWT | 소유자 | 기존 적용 | UEN 정규식(`^\d{9,10}[A-Z]$`) 서버 검증, `COMPANY_INFO_REQUIRED` 분기 |

- **CSRF**: `SecurityConfig.java` L45 `csrf.disable()` + Stateless JWT → OK, 단 브라우저가 JWT를 `localStorage`에 두는 경우 XSS 한 번으로 탈취되므로 CSP(L147-157)에 `script-src 'self'` 유지 — 인라인 스크립트 금지 상태 확인됨. **OK**.
- **CORS**: `allowCredentials=true` + Origin 화이트리스트 유지. `Authorization` 헤더만 허용 — OK.
- **에러 메시지**: `BusinessException`이 `errorCode` 상수 반환 → 스택트레이스 노출 없음 확인. 단 `FILE_STORE_ERROR`(L94) 등 500 계열은 경로·파일명 원문을 메시지에 포함하지 말 것.

---

## 6. 감사 로깅 (Phase 1 연속성)

`AuditAction.java` L52-54에 Phase 1에서 `PROFILE_COMPANY_INFO_UPDATED`가 이미 추가됨을 확인. Phase 2에서 **추가 필수 이벤트**:

```java
// AuditAction.java — Phase 2 추가
DOCUMENT_UPLOADED_VOLUNTARY,       // 자발적 업로드 (AC-D1)
DOCUMENT_REQUEST_FULFILLED,        // REQUESTED → UPLOADED 전이 (AC-D6)
DOCUMENT_DELETED_BY_OWNER,         // 신청자 자발 삭제
LOA_SNAPSHOT_CREATED,              // PR#4, 최초 발급 시 1회
CORPORATE_INFO_CAPTURED_VIA_JIT,   // JIT 모달 경로 표식 (persistToProfile flag 포함)
```

- 기존 `FILE_UPLOADED` 이벤트와 **중복 로깅 회피**: Document 업로드는 `DOCUMENT_UPLOADED_VOLUNTARY` 하나로 통일하고 `metadata`에 `fileSeq` 포함하도록 할 것.
- `@Auditable` 어노테이션이 `FileController`에서만 적용되므로(L48, L67, L129), `ApplicationDocumentController`(신규) 메서드에도 동일 어노테이션 부착 여부를 PR#1 리뷰 체크리스트에 포함.

---

## 7. 마이그레이션 안전성

- V_01~V_05 **실행 순서 의존성**: V_01(catalog) → V_02(seed) → V_03(document_request, FK → catalog) → V_04(loa ALTER) → V_05(백필). V_03을 V_02 전에 돌리면 `fk_dr_type` 위반 아님(seed row 없어도 FK 생성은 가능), 단 애플리케이션 기동 후 `POST /documents`에서 FK 실패 → **V_02를 V_03보다 먼저 실행 순서 강제**.
- **schema.sql 이중 갱신**: Phase 1 리뷰 B-4 결정(schema.sql 직접 수정 + 수동 SQL)과 동일 경로 유지. Flyway 미도입 전제.
- **seed 재실행 안전성**: `INSERT` 문이 `ON DUPLICATE KEY UPDATE` 또는 `INSERT IGNORE` 없이 단순 VALUES → 두 번 실행 시 PK 충돌. V_02를 `INSERT ... ON DUPLICATE KEY UPDATE label_en=VALUES(label_en), ...` 형태로 작성할 것 → **B-3 포함**.
- **롤백**: §5에 DROP 스크립트는 있으나, `document_request`가 운영 데이터 보유 후 롤백 시 데이터 유실. Phase 2는 초기 단계이므로 허용하나, **롤백 전 `mysqldump document_request`** 절차를 runbook에 명시할 것.

---

## 8. Phase 3 대비 보안 고려사항

- **악의적 LEW 시나리오**: Phase 3에서 LEW가 `POST /admin/applications/{id}/document-requests` (스펙 §4)로 요청 생성 시, **assigned LEW만 허용** (임의 LEW의 횡단 접근 차단). 현 `OwnershipValidator.validateOwnerOrAdminOrAssignedLew` 패턴을 관리자 생성 경로에도 적용.
- **신청자가 요청 거부 가능?**: 스펙상 `CANCELLED` 상태가 있으나 전이 주체 미정. 권장: `REQUESTED` 단계에서만 **신청자가 자발적 취소 가능**, `UPLOADED` 이후는 LEW/ADMIN만 `REJECTED/APPROVED` 전이 → Phase 3 AC에 명시.
- **승인/반려 이력**: `reviewed_at`/`reviewed_by`/`rejection_reason` 컬럼은 DocumentRequest 자체에만 존재 → 이력이 1건만 보관된다. 재심사 시 덮어쓰기 방지를 위해 Phase 3에서 `document_request_review_history` 별도 테이블 신설 권장.

---

## 9. 리스크 요약 표

| ID | 리스크 | 심각도 | 가능성 | 완화책 | 머지 전 차단 |
|---|---|---|---|---|---|
| R1 | MIME 위장 업로드 (확장자만 검증) | **H** | H | Tika 매직 바이트 검증 + catalog.acceptedMime 교차 일치 | **예(B-3)** |
| R2 | Tomcat 10MB 전역 vs catalog 20MB 불일치 | **H** | H | `max-file-size: 25MB`로 상향 + 서비스 계층 catalog 기반 검증 | **예(B-3)** |
| R3 | 운영에서 `FILE_ENCRYPTION_KEY` 누락 시 평문 저장 | **H** | M | `@Profile("prod")` 기동 시 키 필수 검증 | **예(B-4)** |
| R4 | JIT 모달 수집 목적 미고지 | M | H | 모달에 helper text + Privacy Policy 교차 링크 | **예(B-1)** |
| R5 | `persistToProfile=true` 기본값 opt-in 모호 | M | M | 라벨 보조 문구 + 감사 로그에 플래그 기록 | **예(B-2)** |
| R6 | LOA 스냅샷 수정 정책 미정의 | M | L | `@Column(updatable=false)` + 재발급 정책 문서화 | **예(B-5)** |
| R7 | seed INSERT idempotency 없음 | M | M | `ON DUPLICATE KEY UPDATE` 적용 | **예(B-3)** |
| R8 | 파일명(original) 제어문자 미정화 → header injection | M | L | `originalFilename` sanitize util | 아니오(권장) |
| R9 | `template_url` SSRF 잠재 | L | L | 현 seed는 NULL. 추후 도입 시 host allowlist | 아니오 |
| R10 | AV 미도입 (악성코드 게이트웨이 부재) | M | M | Phase 3 블로커로 ClamAV sidecar 예약 | 아니오(R-1) |
| R11 | REQUESTED→UPLOADED 외 상태 전이 검증 누락 | M | M | Service 계층 state machine 가드 + 409 반환 | 아니오(PR#1 단위테스트) |
| R12 | V_05 백필이 "현재 값"을 과거 LOA에 소급 | L | M | `snapshot_backfilled_at` 플래그 추가 | 아니오(R-2) |

---

## 10. 머지 전 필수 수정 (Blockers)

- **B-1 · JIT 모달 수집 목적 고지**: `CompanyInfoModal.tsx`에 helper text "Used only for Letter of Authorisation and EMA licence printing. See Privacy Policy." + Privacy Policy 앵커 링크. AC-J1/J2 시나리오 E2E에 포함.
- **B-2 · `persistToProfile` 기본값 UX 보강**: 체크박스 아래 "If unchecked, this company info applies only to this application; your profile stays unchanged." 문구 + 감사 로그 metadata에 `persistToProfile: true|false` 기록.
- **B-3 · 파일 업로드 검증 3종 세트**:
  1. `application.yaml` `spring.servlet.multipart.max-file-size`를 `max(catalog.maxSizeMb) + margin` = **25MB**로 상향.
  2. `DocumentUploadService`에 Apache Tika 기반 MIME sniffing 도입 — `catalog.acceptedMime` 쉼표 분리 후 교차 매칭.
  3. V_02 seed를 `INSERT ... ON DUPLICATE KEY UPDATE`로 재작성하여 재실행 안전성 확보.
- **B-4 · prod 프로필 암호화 키 필수화**: `FileEncryptionUtil.init()`에서 `@Value("${spring.profiles.active:}")`가 `prod`이고 `FILE_ENCRYPTION_KEY`가 비어있으면 `IllegalStateException` throw → 기동 실패. 개발 프로필은 현재 경고 로그 유지.
- **B-5 · LOA 스냅샷 불변 정책 문서화**: `01-spec.md §3-3`에 "스냅샷 컬럼은 UPDATE 금지, 교정은 revoked+재발급 흐름"을 명시. JPA 엔티티에 `@Column(updatable=false)` 부착. `LOA_SNAPSHOT_CREATED` 감사 이벤트 신설.

---

## 11. 권장 보완 (Phase 3 이후)

- **R-1 · ClamAV sidecar 도입**: `FileStorageService.store()` 전단에 스캔 → Phase 3 LEW 요청 서류 흐름 시점에 필수화.
- **R-2 · LOA 백필 플래그**: `loa.snapshot_backfilled_at` 컬럼 추가로 V_05 대상 row 식별. 법적 쟁송 시 원본 시점 vs 백필 시점 구분.
- **R-3 · `document_request_review_history` 테이블**: Phase 3 승인/반려 이력 보존.
- **R-4 · Rate limiting 확대**: 신규 `GET /api/document-types`·`document-requests`에도 `GenericRateLimiter` 적용(사용자당 120/min).
- **R-5 · CSP `connect-src`에 파일 다운로드 origin 명시**: 현재 `'self' + origins` 충분하나, S3 presigned URL 전환 시 `*.s3.ap-southeast-1.amazonaws.com` 추가 필요.

---

## 참고 코드 위치

- `blue-light-backend/src/main/java/com/bluelight/backend/api/file/FileService.java` L35-41 (ALLOWED_EXTENSIONS, 확장자만 검증), L181-193 (validateFileExtension)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/file/LocalFileStorageService.java` L48-57 (path normalize), L80-88 (암호화 분기), L141-145 (복호화)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/file/FileController.java` L40-61 (rate limit, @Auditable 패턴), L98-123 (download MIME probe)
- `blue-light-backend/src/main/java/com/bluelight/backend/common/util/FileEncryptionUtil.java` (AES-256-GCM, `isEnabled()` 키 유무 분기)
- `blue-light-backend/src/main/java/com/bluelight/backend/config/SecurityConfig.java` L44-63 (CSRF disable, stateless, CSP), L83-85 (admin/lew 경로 defense-in-depth)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/loa/LoaGenerationService.java` L107, L144, L374 (스냅샷 대체 대상)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/audit/AuditAction.java` L25-26, L52-54 (Phase 2 추가 위치)
- `blue-light-backend/src/main/resources/application.yaml` L49-51 (multipart 10MB — 상향 대상)
- `blue-light-backend/src/main/java/com/bluelight/backend/common/util/OwnershipValidator.java` (Phase 2 신규 컨트롤러에서 재사용)
