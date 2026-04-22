# Phase 3 — 보안/권한/알림 리스크 리뷰

**작성일**: 2026-04-17
**대상 변경**: LEW 서류 요청 생성/승인/반려/취소 5개 엔드포인트, `DocumentRequestStatus` 상태 머신 확장, `NotificationType` 4종 + `EmailService` 3메서드 + `DocumentRequestNotifier`, Application당 active request ≤ 10 소프트 리밋, 인덱스 `idx_dr_type_status`
**리뷰어**: Security Architect
**결론**: **블로커 4건 해결 후 머지 가능**. 상태 머신·권한 매트릭스 설계는 Phase 2 `OwnershipValidator.validateOwnerOrAdminOrAssignedLew` 패턴을 그대로 확장하면 견고하나, (1) 동시성 가드(@Version) 부재, (2) 이메일 템플릿 XSS, (3) rate limit DB count race, (4) 알림 본문 PDPA class data 포함 우려가 머지 전 차단 항목.

---

## 1. 권한 검증 — 상태 전이별 매트릭스

### 1.1 검증 위치 — 이중 체크 필수
- 컨트롤러: `@PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN','LEW')")` (create/approve/reject/cancel) + `@PreAuthorize("isAuthenticated()")` (fulfill).
- 서비스: `OwnershipValidator.validateOwnerOrAdminOrAssignedLew(applicant, requestor, role, assignedLew)` 재사용. **역할 문자열 추출은 Spring `SecurityContextHolder` 에서 단일 `ROLE_*` 하나만 비교하고 있으므로**(L73-75), 복수 권한 보유 사용자의 경우 우선순위(ADMIN > LEW > USER) 추출 유틸을 `DocumentRequestService` 진입점에 `extractPrimaryRole(Authentication)` 로 중앙화할 것.
- `reqId` 만으로 호출되는 `approve/reject/cancel` 은 path 에 `applicationId` 가 없음 → 서비스 내부에서 `request.application.assignedLew` 를 읽어 검증. **`LEW_A` 가 `LEW_B` 의 request.id 를 알 경우 교차 승인 시도가 가능하므로** 할당 LEW 일치 여부 체크는 반드시 수행해야 하며, 불일치 시 404(path 불일치와 동일 정보 누설 방지 규칙, AC-P2) 반환.

### 1.2 상태 전이 × 역할 매트릭스 (최종)

| 전이 | 허용 | 거부 시 응답 |
|---|---|---|
| (create) → REQUESTED | ADMIN/SYSTEM_ADMIN, assignedLEW only | 403 `FORBIDDEN` |
| REQUESTED → UPLOADED (fulfill) | applicant(owner) only | 404 `DOCUMENT_REQUEST_NOT_FOUND` |
| REJECTED → UPLOADED (re-fulfill) | applicant(owner) only | 동일 |
| UPLOADED → APPROVED/REJECTED | ADMIN, assignedLEW only | 404(LEW-X-LEW) / 409(상태) |
| REQUESTED → CANCELLED | ADMIN, assignedLEW only | 409 if status≠REQUESTED |
| 기타(APPROVED→*, CANCELLED→*) | 없음 | 409 `INVALID_STATE_TRANSITION` |

- `DocumentRequestStatus.canTransitionTo(next)` 는 **final enum method** 로 가드, 서비스에서 호출. 역방향·재승인 모두 차단.

---

## 2. 동시성 & 상태 무결성 — **BLOCKER B-1**

`DocumentRequest` 엔티티(L44~)에 `@Version` 없음. 시나리오:
1. LEW A 가 UPLOADED 요청을 승인 클릭.
2. 동시(수백 ms 차)에 LEW B(동일 assignedLEW seat 공유 또는 ADMIN) 이 반려 클릭.
3. 둘 다 `status == UPLOADED` 가드를 통과하고 마지막 write 가 승.

→ **수정**: `DocumentRequest` 에 `@Version private Long version;` 추가. `document_request` 테이블에 `version BIGINT NOT NULL DEFAULT 0` 컬럼과 마이그레이션 포함. `OptimisticLockException` 은 컨트롤러 advice 에서 409 `CONCURRENT_REVIEW` 로 변환. Phase 2 "LOA 스냅샷 불변" 과 동일한 "한 번 확정된 결정은 덮어쓰지 않는다" 원칙을 `status ∈ {APPROVED, CANCELLED}` 컬럼에 `@Column(updatable=false)` 형태로 거는 것은 JPA state transition 을 막아 부적절 → version lock 이 정답.

**CWE-362 (Race Condition)**.

---

## 3. 알림 보안 & PDPA — **BLOCKER B-2**

### 3.1 이메일 본문 class 2 data 혼입
스펙 §6 `sendDocumentRequestedEmail(to, userName, appSeq, count, lewName)` 시그니처에는 주소·LOA 상세·UEN 이 없어 OK. 그러나 기존 `SmtpEmailService` 의 `sendRevisionRequestEmail`(L107, `address` 파라미터) · `sendLicenseIssuedEmail`(L158) · `sendLewAssignedEmail`(L176, `applicantName` + `address`) 는 **설치 주소를 이메일 본문에 포함** 한다. Phase 3 신규 `sendDocumentFulfilledEmail(to, lewName, appSeq, documentLabel, applicantName)` 도 **applicantName 을 LEW 이메일로 평문 전송** — PDPA §24 Protection Obligation 상 개인 이메일함 잔존 시 위반 소지. 기존 패턴과 일관성을 위해 용인하되 **Phase 3 신규 템플릿은 `applicantName` 대신 `appSeq` 만 노출** 하고 상세는 "Log in to view" 안내로 제한할 것.

### 3.2 HTML 템플릿 XSS — **BLOCKER B-2**
`SmtpEmailService.buildRevisionRequestHtml` L369 의 `comment` 는 `String.formatted()` 로 **이스케이프 없이 HTML 에 삽입** 된다. LEW 가 작성하는 `rejectionReason`(min 10자, 최대 1000자) 도 동일 경로를 타게 되어 있어, LEW 가 악의적/실수로 `<script>`·`<a href=javascript:>` 를 입력하면 신청자의 메일 클라이언트(Gmail 은 sanitize 하지만 구 Outlook/모바일 네이티브는 렌더) 에서 렌더·클릭될 수 있다. → **수정**: `org.springframework.web.util.HtmlUtils.htmlEscape(String)` 를 `buildPasswordResetHtml` 제외한 모든 사용자 입력 주입 지점(`userName`, `comment`, `rejectionReason`, `applicantName`, `documentLabel`, `customLabel`, `lewNote`) 에 적용. 링크는 URL-encode + `https?://licensekaki\.com/` prefix allowlist.

**CWE-79 (XSS)**, OWASP A03:2021.

### 3.3 In-app Notification `message` 필드
`Notification.message` 는 프론트가 **렌더 시점에 DOM 에 삽입** 하므로 React JSX `{message}` 라면 기본 이스케이프되나, `dangerouslySetInnerHTML` 로 렌더하면 XSS. Phase 3 PR#3 프론트 구현 시 **리치 텍스트 사용 금지**, 플레인 텍스트만 노출할 것을 개발자 handoff 에 명시.

### 3.4 수신자 검증 & 트랜잭션 경계
- `NotificationService.createNotification(recipientSeq, ...)`(L33) 는 recipientSeq 를 파라미터로 신뢰 수용. `DocumentRequestNotifier` 에서 **역할 매핑 실수로 applicant 에게 LEW 용 내용이 전달될 가능성** → Notifier 내부에 `assertApplicantRecipient(app, userSeq)` / `assertLewRecipient(app, userSeq)` 가드 필수.
- `@Async` 이메일 발송이 `@Transactional` 과 같은 스레드에서 호출되면 트랜잭션 커밋 전에 메일이 나갈 수 있음 → `DocumentRequestNotifier.send*` 는 `TransactionSynchronizationManager.registerSynchronization(afterCommit)` 훅에서 트리거할 것. 인앱 알림(DB write) 는 동일 트랜잭션 내 저장 OK (스펙 §6 합의).
- `SmtpEmailService` 전 메서드가 이미 예외를 catch-and-log 하므로(L56-58) 트랜잭션 롤백 없음 — OK.

### 3.5 이메일 재시도/루프
현재 메일 전송 실패 시 **재시도 없음, 로그만**. 스펙 요구사항과 일치(§R4). `@Async` + Spring `@Retryable` 조합 도입 시 무한 루프 방지 위해 `maxAttempts=3, backoff=2s` 상한을 강제할 것(권장 R-3).

---

## 4. Rate Limit / DoS — **BLOCKER B-3**

소프트 리밋 `active request ≤ 10` 의 race:
```
LEW T1: SELECT COUNT(*) WHERE app=X AND status IN (REQUESTED,UPLOADED,REJECTED) → 9
LEW T2: 동일 쿼리 → 9
T1 INSERT (10), T2 INSERT (11) — 둘 다 통과
```
Batch 엔드포인트 한 번에 10건 POST 면 count=0 에서 시작하나, **배치 내부 루프** 에서도 `count + i` 로 미리 검사해야 하고 동시 배치 2개가 붙으면 20건까지 통과. → 수정:
1. `DocumentRequestService.createBatch` 를 `@Transactional(isolation = Isolation.SERIALIZABLE)` 로 지정 **하지 말고** (MySQL InnoDB 에서 gap lock 과부하), 대신 `SELECT ... FOR UPDATE` 를 `application_seq` row(=`application` 테이블) 에 걸어 **Application 단위 write 직렬화**. 이 패턴은 payment 도 추후 활용 가능.
2. 배치 본문은 `LEAST(items.size, 10 - currentActiveCount)` 가 아닌 **초과 시 전체 409 `TOO_MANY_ACTIVE_REQUESTS`** (부분 성공 금지 — AC-R2 롤백 원칙과 일관).
3. 10건 초과 시도를 `AuditAction.DOCUMENT_REQUEST_RATE_LIMITED`(신규) 로 기록하여 악의적 LEW 탐지 (Phase 2 R-4 권장 반영).

**ADMIN 우회**: ADMIN 은 이 10건 리밋을 우회할 수 있어야 악성 LEW 의 DoS(요청 10건으로 신청자 업로드 막기) 복구 경로가 확보됨 — 스펙 명문화 필요.

---

## 5. 감사 로깅 — 확장 5종

`AuditAction.java` L28-30 에 Phase 2 `DOCUMENT_UPLOADED_VOLUNTARY`, `DOCUMENT_DELETED_VOLUNTARY` 만 존재. **추가 필수 7종**:
```java
DOCUMENT_REQUEST_CREATED,        // metadata: {items:[{type, customLabel}], batchSize}
DOCUMENT_REQUEST_FULFILLED,      // metadata: {type, previousFileSeq, newFileSeq, isReFulfill}
DOCUMENT_REQUEST_APPROVED,
DOCUMENT_REQUEST_REJECTED,       // metadata: {rejectionReasonHash, reasonLength}  ← 본문 저장은 §5.1 참고
DOCUMENT_REQUEST_CANCELLED,
DOCUMENT_REQUEST_RATE_LIMITED,   // §4
DOCUMENT_REQUEST_ACCESS_DENIED   // LEW-X-LEW 교차 시도 탐지
```

### 5.1 반려 사유 장기 보존 — **LOW→MEDIUM**
`DocumentRequest.rejection_reason VARCHAR(1000)` 는 DB 에 평문 잔존. LEW 가 개인정보(예: "신청자 김XX 주민번호 오기") 를 반려 사유에 써 넣으면 PDPA retention 대상. **수정 방향**: (a) UI 에 "Do not include personal data such as NRIC/UEN in the reason" helper text, (b) 감사 로그 metadata 에는 **reason full text 대신 sha256 hash + length** 만 저장(원본은 엔티티에 있으므로 soft-delete 유효 기간만큼 보존), (c) retention policy 문서에 반려 사유 수명 = DocumentRequest 수명과 동일함을 명시.

---

## 6. 기존 NotificationController 재검증 — 안전

L29-36 `getMyNotifications(auth)` → `notificationRepository.findByRecipientUserSeqOrderByCreatedAtDesc(userSeq, ...)` 로 recipient 필터가 JPA 쿼리에 박혀 있어 역할 무관 본인 알림만 조회 가능. `markAsRead` 도 L76-78 recipient 비교 가드 존재. **OK**. 단 `NotificationService.markAsRead` 의 예외 메시지 `"Access denied"` 는 알림 존재 여부를 드러내므로 `"Notification not found"` 로 통일 권장(L74 와 일치). — Phase 3 범위 외, 권장 R-2.

---

## 7. SMTP 설정 / 자격증명 — MEDIUM

`SmtpEmailService` L28 `@ConditionalOnProperty("mail.smtp.enabled")`. 자격증명은 `spring.mail.*` → **application.yaml 에 하드코딩 금지**, `${SMTP_USERNAME}` / `${SMTP_PASSWORD}` 환경변수 필수. 운영 전 확인:
- `.env.example` 갱신 + `.gitignore` 로 `.env` 제외 유지
- `fromAddress` default `noreply@licensekaki.com` 의 **SPF/DKIM/DMARC** 레코드 운영 DNS 에 등록(배포 runbook)
- `helper.setTo(to)` 에 `to` 는 User 엔티티의 `email` 이므로 신뢰 가능하나, `userName` 이 `helper.setFrom(fromAddress, fromName)` 의 두 번째 인자로 들어간다면 CRLF injection 위험. 현재는 정적 `"LicenseKaki"` 이므로 OK.

---

## 8. 마이그레이션 — LOW

`ALTER TABLE document_request ADD INDEX idx_dr_type_status (document_type_code, status);` — MySQL 8.0 `ALGORITHM=INPLACE, LOCK=NONE` 자동, 파일럿 규모 수백 row 에서 즉시 완료. `@Version` 컬럼 추가(B-1) 시에는 `ADD COLUMN version BIGINT NOT NULL DEFAULT 0` 로 동일하게 INSTANT 가능. 롤백 SQL 을 `migration/V_01_*.sql` 파일 하단 주석으로 병기할 것.

---

## 9. Phase 2 R-1~R-5 반영 상태

| ID | 원제목 | Phase 3 반영 |
|---|---|---|
| R-1 | ClamAV sidecar | **Phase 3 범위 외** 확정. Phase 3 는 fulfill 경로에 **기존 `FileService` 상위 검증**(MIME/size/magic bytes, Phase 2 B-3)만 재사용 |
| R-2 | LOA snapshot 백필 플래그 | Phase 2 에서 적용됨 ✅ |
| R-3 | `document_request_review_history` 테이블 | **Phase 3 에서 도입 권장(권장, 블로커 아님)** — 단일 행에 마지막 review 만 저장하면 반복 반려-재업로드 감사 추적 불가. 현재는 감사 로그 `DOCUMENT_REQUEST_APPROVED/REJECTED` 이벤트가 actor/시점/metadata 를 보존하므로 **초기엔 AuditLog 만으로 대체 가능**, 스케일·분쟁 증가 시점에 별도 테이블로 분리 |
| R-4 | Rate limit | 본 리뷰 §4 에서 소프트 리밋 + race 차단 + `DOCUMENT_REQUEST_RATE_LIMITED` 로 반영 |
| R-5 | CSP connect-src S3 | S3 전환 시점, Phase 3 범위 외 |

---

## 10. 리스크 요약 표

| ID | 리스크 | 심각도 | 가능성 | 완화 | 머지 전 차단 |
|---|---|---|---|---|---|
| P3-R1 | 동시 승인/반려 race(@Version 부재) | **H** | M | @Version + 409 `CONCURRENT_REVIEW` | **B-1** |
| P3-R2 | 이메일 템플릿 XSS(`rejectionReason`, `customLabel` 등 미이스케이프) | **H** | M | `HtmlUtils.htmlEscape` 전면 적용 | **B-2** |
| P3-R3 | Rate limit count race → 10건 초과 | M | M | Application row `SELECT...FOR UPDATE` + 전체 롤백 + ADMIN 우회 | **B-3** |
| P3-R4 | LEW-X-LEW 교차 승인/반려/취소 | **H** | L | 서비스에서 `assignedLew` 재확인 + 404 | **B-4** |
| P3-R5 | 이메일에 주소/applicantName 노출 | M | H | Phase 3 신규 템플릿은 appSeq 만, 상세는 링크 | 아니오 (권장) |
| P3-R6 | @Async 이메일이 트랜잭션 커밋 전 발송 | M | L | `afterCommit` hook | 아니오 (PR#4 구현 가이드) |
| P3-R7 | 반려 사유 PDPA class data 포함 | M | M | UI helper text + hash 감사 로그 | 아니오 (R-1) |
| P3-R8 | NotificationType enum 누락 시 직렬화 | L | L | VARCHAR 저장, 추가만 수행 | 아니오 |
| P3-R9 | SMTP credentials 하드코딩 | M | L | `${SMTP_*}` env + SPF/DKIM/DMARC runbook | 아니오 |
| P3-R10 | 악성 LEW 가 10건으로 업로드 차단(DoS) | M | L | ADMIN 강제 CANCELLED + 감사 로그 이상 탐지 | 아니오 |
| P3-R11 | CANCELLED→fulfill 같은 역전이 허용 | M | L | `canTransitionTo` 중앙 가드 + @ParameterizedTest 16케이스 | 아니오 (PR#1 테스트) |
| P3-R12 | review-history 단일 행 덮어쓰기 | L | M | AuditLog 로 대체, 장기 R-3 | 아니오 |

---

## 11. 머지 전 필수 수정 (Blockers)

- **B-1 · `DocumentRequest` 동시성 가드**: `@Version private Long version;` 추가 + schema/migration 컬럼 `version BIGINT NOT NULL DEFAULT 0`. `OptimisticLockException` → 409 `CONCURRENT_REVIEW` 로 변환하는 `ControllerAdvice` 핸들러 추가. 단위 테스트로 두 스레드 approve/reject 동시 호출 시 한 건만 성공 확인.
- **B-2 · 이메일 템플릿 HTML escape 전면 적용**: `SmtpEmailService` 전 빌더에서 사용자 입력 주입 지점(`userName`, `applicantName`, `lewName`, `comment`, `rejectionReason`, `customLabel`, `documentLabel`, `lewNote`) 에 `HtmlUtils.htmlEscape` 적용. Phase 3 신규 3 메서드는 처음부터 적용. 회귀 테스트: `comment="<script>alert(1)</script>"` 입력 시 메일 본문에 `&lt;script&gt;` 로 나오는지 검증.
- **B-3 · 10건 소프트 리밋 race 차단**: `DocumentRequestService.createBatch` 시작부에 `applicationRepository.findByIdForUpdate(appSeq)` 로 row lock 획득 → count 재검사 → 초과 시 전체 409 `TOO_MANY_ACTIVE_REQUESTS`. ADMIN 은 리밋 우회 분기 명시. 초과 시도는 `AuditAction.DOCUMENT_REQUEST_RATE_LIMITED` 로그.
- **B-4 · LEW-X-LEW 교차 접근 서비스 이중 체크**: `approve/reject/cancel` 는 path 에 `applicationId` 가 없으므로 `DocumentRequest` 를 fetch 한 후 **반드시** `OwnershipValidator.validateOwnerOrAdminOrAssignedLew(applicant=null, requestor, role, dr.application.assignedLew)` 호출. 불일치 시 404 (AC-P2 정보 누설 방지 규칙과 동일). `LEW_A/LEW_B` 교차 승인 MockMvc 회귀 테스트 필수.

---

## 12. 권장 보완 (Phase 4 이후)

- **R-1 · 반려 사유 PDPA 보조**: `RejectReasonModal.tsx` 에 helper "Do not include NRIC, UEN, or other personal identifiers. Reference document attributes only." + 감사 로그 metadata 에 `reasonSha256` 저장.
- **R-2 · `NotificationService.markAsRead` 오류 메시지 통일**: 404 와 403 을 `NOTIFICATION_NOT_FOUND` 로 통합, enumeration 공격 차단.
- **R-3 · `@Retryable` 이메일 발송 상한**: `maxAttempts=3`, exponential backoff 도입 — Phase 3 현재는 fire-and-log 유지.
- **R-4 · `document_request_review_history` 테이블**: 반복 반려 이력이 4건 이상 발생하는 application 비율이 5% 초과하면 별도 테이블로 분리. 기준 미달 시 AuditLog 만으로 유지.
- **R-5 · 알림 폴링 → SSE/WebSocket**: 30초 폴링은 LEW/신청자 동시 접속 시 DB 부하. 차회 인프라 정비 시 SSE 로 전환.

---

## 참고 코드 위치

- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/NotificationService.java` L33-51 (createNotification), L72-83 (markAsRead recipient 가드)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/NotificationController.java` L28-47 (본인 알림만 조회 — 안전)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/email/SmtpEmailService.java` L107-120 (revision 이메일 comment 이스케이프 누락), L338-370 (`buildRevisionRequestHtml` XSS sink), L41-59 (fire-and-log 패턴)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/email/EmailService.java` L10-113 (인터페이스 — 3메서드 추가 대상)
- `blue-light-backend/src/main/java/com/bluelight/backend/common/util/OwnershipValidator.java` L56-68 (assigned LEW 검증 재사용)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/document/DocumentRequest.java` L44 (@Version 부재), L171-173 (cancel 메서드 — 역전이 가드 추가 필요)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/document/DocumentRequestStatus.java` L15-21 (canTransitionTo 메서드 신설 대상)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/audit/AuditAction.java` L28-30 (Phase 3 7종 추가 위치)
- `blue-light-backend/src/main/java/com/bluelight/backend/BackendApplication.java` (@EnableAsync 확인됨)
- `blue-light-backend/src/main/java/com/bluelight/backend/security/GenericRateLimiter.java` (§4 소프트 리밋 구현 시 재사용 검토)
