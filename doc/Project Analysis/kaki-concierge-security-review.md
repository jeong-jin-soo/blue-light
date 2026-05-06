# Kaki Concierge Service Phase 1 — 보안 사전 검토

**검토 대상**: `kaki-concierge-service-prd.md` v1.4 (Phase 1 구현 착수 전)
**검토 기준**: OWASP ASVS 4.0.3, NIST SP 800-63B, PDPA (Singapore), OWASP Top 10 2021
**기존 코드베이스**: develop (2026-04-19)

---

## 1. Executive Summary

PRD v1.4는 보안 요구사항을 폭넓게 수용했지만, **Phase 1 착수 전에 반드시 해결해야 할 High급 이슈 5건**이 확인되었다.

| # | 이슈 | 차단 대상 |
|---|------|----------|
| **H-1** | `/api/auth/login`이 `ACCOUNT_PENDING_ACTIVATION` vs `INVALID_CREDENTIALS`로 분기되어 **이메일 존재 여부를 에러 코드로 노출** (AC-29 설계 모순) | PR#1 로그인 분기 |
| **H-2** | URL path 토큰이 Tomcat/Nginx access log, APM, JVM 덤프에 평문 기록됨 — PRD는 query-string 금지만 명시, **path 로그 마스킹 전무** | PR#1·#2 토큰 발급 |
| **H-3** | AccountSetupToken에 **실패 시도 제한 없음**(LoaSigningToken AC-18과 비대칭). 48h 동안 무제한 재시도 가능 | PR#1 account-setup |
| **H-4** | 기존 `SecurityConfig`가 `/api/admin/**`을 `LEW`에게도 허용 — 경로 A LOA 업로드 API를 LEW가 호출 가능해 **LOA 위조 경로** 발생 | PR#1 착수 첫 커밋 |
| **H-5** | 옵션 A(평문 임시 비밀번호 이메일)는 **PDPA §24 + OWASP ASVS V2.1.6 위반 소지**. O-21 미확정 | PR#3 (O-21 결정 전 보류) |

Medium 5건(§2.3), Low 3건(§2.4) 포함. **O-23 (타이밍 p95 < 200ms)** 는 Spring Boot 환경에서 구조적으로 달성 어려우므로 상수 시간 패딩으로 대체 권고(M-1).

---

## 2. 취약점·리스크 카탈로그

### 2.1 Critical
없음 (H-1~H-5 미해결 배포 시 Critical 승격).

### 2.2 High

#### H-1. 로그인 응답 분기가 이메일 열거(Enumeration) 허용

**위치**: PRD AC-29, AC-33 / `AuthService.java:157`
**설명**: AC-29가 "비밀번호 검증 전에 status 체크 선행 → 401 `ACCOUNT_PENDING_ACTIVATION`"을 명시. 이 분기 자체가 이메일 존재 여부 + status를 응답 코드로 드러냄.

**공격 시나리오**:
```
POST /api/auth/login {email, password:"x"}
 → ACCOUNT_PENDING_ACTIVATION  ⇒ 계정 존재 + 미활성화
 → INVALID_CREDENTIALS + 비동기 발송 지연 차이 ⇒ 존재 + 활성화
 → INVALID_CREDENTIALS (즉시)   ⇒ 계정 없음
```

**영향**: CWE-204. PDPA §13 간접 식별정보 노출 가능성. Concierge 경유 PENDING 계정 전수 식별 → 스피어 피싱 표적화.

**수정 방안**: **비밀번호 검증 선행 + 성공 시에만 status 분기**로 AC-29 재정의.

```java
// AuthService.login — 수정 후 요약
Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
String hashToCheck = userOpt.map(User::getPassword).orElse(DUMMY_BCRYPT_HASH);
boolean passwordOk = passwordEncoder.matches(request.getPassword(), hashToCheck);
if (userOpt.isEmpty() || !passwordOk) {
    throw new BusinessException("Invalid email or password",
        UNAUTHORIZED, "INVALID_CREDENTIALS"); // 동일 응답
}
User user = userOpt.get();
switch (user.getStatus()) {
    case PENDING_ACTIVATION -> throw ...("ACCOUNT_PENDING_ACTIVATION");
    case SUSPENDED          -> throw ...("ACCOUNT_SUSPENDED");
    case DELETED            -> throw ...("INVALID_CREDENTIALS");
    case ACTIVE             -> { /* JWT 발급 */ }
}
```

`DUMMY_BCRYPT_HASH`는 BCrypt cost=10으로 사전 생성된 상수 (타이밍 일정성).

**참조**: CWE-204, OWASP ASVS V2.2.1, NIST SP 800-63B §5.2.2. **AC-29, AC-31 재작성 필요**.

---

#### H-2. URL path 토큰의 로그 유출

**위치**: PRD §4 (AC-20) / Tomcat `AccessLogValve`, Nginx/ALB, APM
**설명**: PRD는 query-string 금지만 규정. path 토큰은 다음 채널에서 **평문 기록**됨:
- Spring Boot 임베드 Tomcat access log (`%r`)
- 리버스 프록시 (ALB/Nginx access log)
- APM(Datadog/NewRelic) URL 트레이스
- JVM 스레드 덤프, DEBUG 로그

48h TTL 동안 운영 인원이 접근 가능. Log4Shell류 로그 파이프라인 유출 시 외부 노출.

**영향**: CWE-532. LOA 서명 토큰 탈취 → 원격 대리 서명 가능. 이메일 자동 GET 프리뷰로 `usedAt` 선점 리스크.

**수정 방안**:

1. **TokenLogMaskingFilter** (`OncePerRequestFilter`, `HIGHEST_PRECEDENCE`):
```java
private static final Pattern P = Pattern.compile(
    "(/api/public/(?:account-setup|loa-sign)/)[^/?]+");
String masked = P.matcher(req.getRequestURI()).replaceAll("$1***");
MDC.put("maskedUri", masked);
```
`logback-spring.xml` access log pattern을 `%X{maskedUri}` 기반으로 변경.

2. **원자적 usedAt 업데이트**:
```java
@Modifying
@Query("UPDATE AccountSetupToken t SET t.usedAt = :now "
     + "WHERE t.tokenSeq = :seq AND t.usedAt IS NULL")
int markAsUsedIfNotUsed(@Param("seq") Long seq, @Param("now") LocalDateTime now);
// affected == 0 → 410 TOKEN_ALREADY_USED
```

3. **ALB/Nginx 설정 문서화** — 동일 패턴 마스킹(nginx `log_format`). **운영 플레이북 필수**.

**참조**: CWE-532, OWASP ASVS V7.1.1. **PRD §4 보안 기본 원칙에 "로그 마스킹" 추가 필요**.

---

#### H-3. AccountSetupToken 실패 제한 부재

**위치**: PRD §4, AC-28, AC-32
**설명**: LoaSigningToken은 AC-18에 5회 실패 잠금 명시. AccountSetupToken은 **무제한 재시도 허용** — 비밀번호 복잡도 400 반환 시 재사용 가능.

**영향**: CWE-307. 탈취 토큰 48h 동안 복잡도 조합 브루트포스 가능. 이메일 프리뷰 GET으로 선점 전 반복 접근.

**수정 방안**:
```java
@Column(name = "failed_attempts", nullable = false)
private Integer failedAttempts = 0;
@Column(name = "locked_at")
private LocalDateTime lockedAt;

public void recordFailedAttempt() {
    this.failedAttempts++;
    if (failedAttempts >= 5) this.lockedAt = LocalDateTime.now();
}
public boolean isUsable() {
    return usedAt == null && lockedAt == null
        && LocalDateTime.now().isBefore(expiresAt);
}
```
- 잠긴 토큰 조회는 404 (존재 비공개)
- 5회 도달 시 `ACCOUNT_SETUP_TOKEN_LOCKED` 감사 로그 + Manager 대시보드 노출

**참조**: CWE-307, OWASP ASVS V2.2.1. **AC-28b 신설 필요**.

---

#### H-4. SecurityConfig에서 LEW의 LOA 업로드 권한 누수

**위치**: `SecurityConfig.java:83` — `.requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "LEW", "SYSTEM_ADMIN")`
**설명**: PRD는 `POST /api/admin/applications/{id}/loa/upload-signature`를 `CONCIERGE_MANAGER, ADMIN` 권한으로 명시했지만 URL 매처가 LEW도 허용. LEW는 Application 검토자로서 **LOA 서명 업로드 권한이 있어서는 안 됨**.

**영향**: CWE-285. LEW가 Application에 대리 서명 첨부 가능 → LOA 위조. 감사 증적은 남지만 법적 무결성 손상.

**수정 방안**:

```java
// SecurityConfig 수정 — LOA 서명 경로를 별도 매처로 선언 (상단 배치)
.requestMatchers(
    "/api/admin/applications/*/loa/upload-signature",
    "/api/admin/applications/*/loa/request-remote-sign",
    "/api/admin/applications/*/loa/revoke-remote-sign/**",
    "/api/admin/applications/*/loa/remote-sign-tokens")
    .hasAnyRole("CONCIERGE_MANAGER", "ADMIN", "SYSTEM_ADMIN")
.requestMatchers("/api/admin/**")
    .hasAnyRole("ADMIN", "LEW", "SYSTEM_ADMIN", "CONCIERGE_MANAGER")
.requestMatchers("/api/concierge-manager/**")
    .hasAnyRole("CONCIERGE_MANAGER", "ADMIN", "SYSTEM_ADMIN")
```

- 서비스 레이어에 **본인 담당 검증** 이중 방어:
```java
if (!isAdminRole(role)) {
    ConciergeRequest cr = crRepo.findByApplicationApplicationSeq(appSeq)
        .orElseThrow(() -> forbidden("NOT_CONCIERGE_APP"));
    if (!cr.getAssignedManager().getUserSeq().equals(managerSeq))
        throw forbidden("NOT_ASSIGNED_MANAGER");
}
```

**참조**: CWE-285, OWASP Top 10 2021 A01. **AC-15b 추가: LEW/타 Manager 업로드 시도 시 403**.

---

#### H-5. 옵션 A (평문 임시 비밀번호 이메일) PDPA·OWASP 위반

**위치**: PRD O-21, §6.4-1b, AC-31
**설명**: 이메일 평문 임시 비밀번호는:
- 받은편지함 평문 저장(서버 관리자, 백업, 포워딩 규칙 노출)
- DLP/안티멀웨어 스캔 플래그
- 장기 보존 시 탈취 확률 증가

**영향**: PDPA §24 Protection Obligation 위반 소지, OWASP ASVS V2.1.6 직접 위반. 사고 시 PDPA §22A 72시간 PDPC 통지 의무 발동. 피해자의 최초 강제 변경 전 공격자 선점 가능.

**수정 방안**:
- **옵션 B 단독 채택 강력 권고** (PRD도 권장)
- 옵션 A 불가피 시: **6자리 OTP + 첫 로그인 즉시 만료**, SMS/Authenticator 별도 채널 권장
- 이메일 발송 로그 즉시 anonymize

**결정 필요**: O-21 **PR#3 착수 전 보안팀 + PO 서면 결정 필수**.

**참조**: CWE-319, OWASP ASVS V2.1.6, PDPA §24.

---

### 2.3 Medium

#### M-1. 타이밍 동등성 p95 < 200ms 달성 구조적 난점

**위치**: PRD §4.4, AC-31, O-23
**설명**: Spring Boot에서 p95 < 200ms 달성 구조적 난점:
1. ORM L1/L2 캐시 불균형 (10~50ms)
2. JIT 핫스팟 최적화 편차
3. afterCommit 비동기 발송이어도 동기 구간(consentLog 기록)에 경로 차이 존재
4. 감사 로그 JSON 크기 차이 (I/O)

**영향**: 보안 테스트 통과해도 tail latency로 enumeration 가능. False sense of security.

**수정 방안**: **상수 시간 패딩(600ms 타겟)** — `CompletableFuture.get(target, MS)` + 잔여 `Thread.sleep`으로 총 응답 시간 고정. 실제 방어는 rate-limit(IP당 시간당 20회)에 위임. p95 테스트는 평균 보장 목적만.

```java
long target = 600, start = System.currentTimeMillis();
CompletableFuture<Void> work = CompletableFuture.runAsync(() ->
    userRepository.findByEmail(email).ifPresent(user -> {
        if (user.getStatus() == PENDING_ACTIVATION) {
            tokenService.issueOrReissue(user);
            emailService.sendActivation(user);
        }}));
try { work.get(target, MILLISECONDS); } catch (Exception ignored) {}
long rem = target - (System.currentTimeMillis() - start);
if (rem > 0) Thread.sleep(rem);
return LoginRequestActivationResponse.fixed();
```

**참조**: OWASP Cheat Sheet Auth #Timing, CWE-208. **PRD §4.4 재작성 필요**.

---

#### M-2. UserConsentLog PDPA 7년 보존 — Soft delete 충돌

**위치**: PRD §3.11, AC-27 / `User.java:22-23`
**설명**: `User`에 `@SQLDelete + @SQLRestriction("deleted_at IS NULL")` 적용되어, `UserConsentLog.user`를 통한 JPA 조회가 soft-deleted 사용자의 로그를 반환하지 못함. AC-27 "soft-deleted User의 UserConsentLog 조회 가능" 위반.

**영향**: PDPA 감사 시 동의 기록 누락. CWE-501.

**수정 방안**:
- Repository에 **native query** 추가: `SELECT * FROM user_consent_logs WHERE user_seq = :seq` (nativeQuery=true)
- `UserRepository.findByIdIncludingDeleted`: nativeQuery로 `deleted_at` 무시
- **7년 purge 스케줄러**: `@Scheduled(cron="0 0 3 1 * *")` 월 1회

**참조**: PDPA §24, §22A, CWE-501.

---

#### M-3. 경로 A 서명 이미지 EXIF 미제거

**위치**: PRD §4.2 / `MimeTypeValidator.java` (EXIF 처리 없음)
**설명**: JPG EXIF에 GPS 좌표, 카메라 모델, 소유자 이름 포함 가능. LOA PDF 임베드 시 누구나 조회 가능.

**영향**: PDPA §18 Collection Limitation 위반 소지, CWE-200. 촬영 시각 ≠ Manager 업로드 시각 → 증거 신뢰성 저하.

**수정 방안**: `ImageIO.read → write` re-encode로 EXIF 자동 제거.
```java
BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
ByteArrayOutputStream out = new ByteArrayOutputStream();
ImageIO.write(img, "image/png".equals(mime) ? "png" : "jpg", out);
return out.toByteArray(); // FileStorageService.storeBytes() 전에 적용
```

**참조**: CWE-200, PDPA §18.

---

#### M-4. `/api/public/concierge/request` 스팸/DoS 방어 부족

**위치**: PRD §4.2 (5req/hour/email 권장) / `GenericRateLimiter`
**설명**:
- DB 기반 카운터라 대량 DoS 시 커넥션 고갈
- 이메일 변조 우회 가능(IP 기반만 실효)
- CAPTCHA 부재로 봇 자동화 가능

**영향**: CWE-770. Manager 대시보드 오염, SLA 24h 모니터링 방해. User 테이블 부풀림.

**수정 방안**:
- IP당 시간당 10회 (이메일당보다 엄격)
- **Honeypot 필드** (`@Size(max=0) String honeypot`)
- **CAPTCHA**: Phase 1에 hCaptcha/Cloudflare Turnstile 통합 권고(최소 honeypot+rate-limit은 필수)
- 중복 휴리스틱: 같은 IP+이름+모바일 24h 내 2건 이상 → 409

**참조**: OWASP ASVS V11.1.4, CWE-770.

---

#### M-5. Payment.referenceType 분기 권한 혼선 (Phase 2 사전 검토)

**위치**: PRD §8.1
**설명**: `Payment` 공통 엔티티 — 기존 Application 결제 권한 체크가 ConciergeRequest 결제에는 적용 안 됨. `GET /api/payments/{seq}` 존재 시 IDOR 가능성.

**영향**: CWE-639, OWASP A01 (IDOR).

**수정 방안**: `PaymentService`에 referenceType별 분기:
```java
switch (payment.getReferenceType()) {
    case APPLICATION -> validateOwnerOrAdmin(app.getUser().getUserSeq(), userSeq, role);
    case SLD_ORDER   -> validateOwnerOrAdmin(order.getCustomer().getUserSeq(), ...);
    case CONCIERGE_REQUEST -> {
        validateOwnerOrAdmin(cr.getApplicantUser().getUserSeq(), ...);
        // Manager 담당자 bypass 규칙 추가 필요
    }
}
```

**참조**: CWE-639, OWASP A01. **PRD §8에 권한 분기 추가 필요**.

---

#### M-6. SLA 미준수 100% 환불 악용 가능성 (Phase 2)

**위치**: PRD §8.5
**설명**: 의도적 연락 회피(전화 차단, 스팸 필터)로 24h 경과 → 전액 환불 획득 가능. 동일 사용자 다수 이메일 A/B 남용 가능.

**수정 방안**:
- "24h 미준수" 정의를 **최소 2회 시도 기록 + Manager 사유 보고서**로 강화
- 자동 100% 환불 비활성화 → **Admin 수동 승인**
- 동일 IP/이메일 도메인 연속 환불 모니터링

**결정 필요**: 법무·재무 (O-8) 확정 전 환불 자동화 보류.

---

### 2.4 Low

- **L-1**: HSTS 헤더 미설정. 운영에서 origin 최소화 + `Strict-Transport-Security` 추가.
- **L-2**: `frame-ancestors 'none'`이 Phase 2 PG 결제창 iframe과 충돌 가능. PG 도메인만 예외.
- **L-3**: N1 피싱 모방 용이. DKIM/SPF/DMARC 강제 + "비밀번호를 이메일로 요청하지 않음" 면책.

---

## 3. PDPA 준수 체크리스트

| # | 요구사항 | PRD 반영 | 상태 |
|---|---------|---------|------|
| 1 | §13 Consent Obligation — 명시적 동의 | ✅ AC-22 (4종 AssertTrue) | **Phase 1 필수** |
| 2 | §14 Purpose Limitation — 목적 명시 | ⚠️ ConsentSourceContext 기록되나 UI 문구 확인 필요 | **Phase 1 필수** |
| 3 | §18 Collection Limitation — 최소 수집 | ⚠️ mobileNumber 정당화 + M-3 EXIF 제거 | **Phase 1 필수** |
| 4 | §24 Protection Obligation — 합리적 보안 | ✅ AES-256-GCM, JWT HS512 / ⚠️ H-5 옵션 A 선택 시 위반 | **결정 대기** |
| 5 | §25 Retention Limitation — 불필요 시 삭제 | ⚠️ O-22 180일 정리 + M-2 UserConsentLog 7년 purge | **Phase 1 설계** |
| 6 | §22A Notification — 72h 내 PDPC 통지 | ❌ PRD 언급 없음 | **Phase 1 문서화 필요** |
| 7 | §21 Access/Correction — 주체 권리 | ⚠️ "내 데이터 다운로드" API 미설계 | **Phase 3** |
| 8 | §13 Withdrawal — 철회 용이성 | ⚠️ WITHDRAWN 기록은 명시, UI 플로우 미설계 | **Phase 2** |
| 9 | 마케팅 opt-out (O-20) | ⚠️ 이메일 footer + 프로필 토글 둘 다 필요 | **Phase 2** |
| 10 | 감사 로그 보존 (최소 7년) | ✅ §7.6 명시 / ⚠️ 자동 purge 스케줄러 필요 | **Phase 1** |
| 11 | 이메일 enumeration 방어 | ⚠️ H-1, M-1 미해결 | **Phase 1 차단** |
| 12 | 토큰 보호 | ⚠️ H-2, H-3 미해결 | **Phase 1 차단** |
| 13 | 국경 간 데이터 이전 (§26) | ✅ SG 서버 (확인 필요) | **Phase 1 문서** |

### 고위험 PDPA 항목
- **§13**: DELEGATION 동의의 법적 효력 범위 미문서화 → O-1 법무 결정 필수
- **§22A**: 사고 대응 플레이북 부재 → `doc/SecurityIncidentPlaybook.md` 신규 작성 권고
- **§25**: PENDING_ACTIVATION 180일 정리(O-22) + UserConsentLog 7년 purge + soft-deleted 실삭제 정책 3종 명확화 필요

---

## 4. 구현 가이드 — 보안 패턴 (Java/Spring Boot)

### 4.1 Constant-Time Comparison

```java
public static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) return false;
    return MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8));
}
// OTP/토큰 비교: String.equals는 조기 리턴으로 타이밍 분석 가능 → MessageDigest.isEqual만 사용.
```

### 4.2 Rate Limit — 기존 `GenericRateLimiter` 재사용 (Bucket4j 불필요)

```java
// /api/public/concierge/request
rateLimiter.checkAndRecord("CONCIERGE_SUBMIT_IP", ip, 10, 60);
rateLimiter.checkAndRecord("CONCIERGE_SUBMIT_EMAIL",
    req.email().toLowerCase(), 5, 60);

// /api/auth/login/request-activation — §4.4 고정 응답
try {
    rateLimiter.checkAndRecord("ACTIVATION_REQUEST_IP", ip, 20, 60);
    rateLimiter.checkAndRecord("ACTIVATION_REQUEST_EMAIL",
        req.email().toLowerCase(), 1, 5);
    authService.requestActivation(req.email(), ip, ua);
} catch (BusinessException ignored) { /* 레이트 초과여도 고정 응답 */ }
return LoginRequestActivationResponse.fixed();
```

`LoginRateLimiter`와 기능 중복 → `GenericRateLimiter`로 통합 권고.

### 4.3 토큰 생성·검증

```java
@Transactional
public AccountSetupToken issueOrReissue(User user) {
    tokenRepository.findActiveByUser(user.getUserSeq())
        .forEach(t -> t.revoke("Superseded"));   // O-17: 1개만 유지
    AccountSetupToken t = AccountSetupToken.builder()
        .user(user).tokenUuid(UUID.randomUUID().toString()) // 122-bit
        .expiresAt(LocalDateTime.now().plusHours(48)).build();
    auditLogService.log(ACCOUNT_SETUP_TOKEN_ISSUED, ...);
    return tokenRepository.save(t);
}

public AccountSetupToken findUsable(String uuid) {
    AccountSetupToken t = tokenRepository.findByTokenUuid(uuid)
        .orElseThrow(() -> gone("TOKEN_INVALID"));
    if (!t.isUsable()) throw gone("TOKEN_INVALID"); // 만료·잠김·사용됨 동일 응답
    return t;
}

@Transactional
public void markAsUsed(Long seq) {
    if (tokenRepository.markAsUsedIfNotUsed(seq, LocalDateTime.now()) == 0)
        throw gone("TOKEN_ALREADY_USED"); // 원자적 업데이트로 race 제거
}
```

**핵심**: `markAsUsedIfNotUsed`로 race condition 제거. 모든 실패 응답 동일 메시지(내부 구분은 감사 로그만). TTL 48h 적정.

### 4.4 파일 업로드 검증 (경로 A)

```java
if (!req.managerConfirmation()) throw badRequest("MANAGER_CONFIRMATION_REQUIRED"); // AC-15
// 본인담당 검증 (H-4)
if (!isAdminRole(role)) {
    ConciergeRequest cr = crRepo.findByApplicationApplicationSeq(appSeq)
        .orElseThrow(() -> forbidden("NOT_CONCIERGE_APP"));
    if (!cr.getAssignedManager().getUserSeq().equals(managerSeq))
        throw forbidden("NOT_ASSIGNED_MANAGER");
}
if (application.getLoaSignatureUrl() != null) throw badRequest("LOA_ALREADY_SIGNED");
MimeTypeValidator.validateSize(file, 2);                          // ≤2MB
MimeTypeValidator.validate(file, "image/png,image/jpeg");         // 확장자+매직+선언 교차
byte[] sanitized = ImageSanitizer.stripExif(file.getBytes(), file.getContentType()); // M-3
String path = fileStorageService.storeBytes(sanitized, "signature.png",
    "applications/" + appSeq + "/loa-signatures");                // AES-256-GCM 자동
application.registerLoaSignatureFromManagerUpload(path, managerSeq, req.receivedVia(), req.memo());
auditLogService.log(managerSeq, LOA_SIGNATURE_UPLOADED_BY_MANAGER, ip, ua, ...);
registerAfterCommit(() -> emailService.sendUploadConfirm(application.getUser(), ...));
```

**방어 계층**: ① Manager JWT ② 본인담당/Admin ③ managerConfirmation ④ 크기·MIME·매직바이트 ⑤ EXIF 제거 ⑥ AES-256-GCM 저장 ⑦ 감사 로그 ⑧ 신청자 N5-UploadConfirm 통지.

---

## 5. Phase별 분할 권고

### Phase 1 (MVP, 2~3주) — 보안 필수
- H-1: 로그인 응답 동일성 (dummy bcrypt + 분기 순서 교정)
- H-2: TokenLogMaskingFilter + `markAsUsedIfNotUsed` 원자성
- H-3: AccountSetupToken 5회 실패 잠금
- H-4: SecurityConfig LOA 경로 분리 + 서비스 레이어 본인담당 검증
- H-5: **옵션 A vs B 결정 (PR#3 착수 전 완료)** — 옵션 B 권장
- M-3: ImageSanitizer (EXIF 제거)
- M-4: Honeypot + IP 시간당 10회 (CAPTCHA 권고, 최소 honeypot 필수)
- PDPA §22A 사고 대응 플레이북 (`doc/SecurityIncidentPlaybook.md`)
- PDPA §25 UserConsentLog 7년 purge 스케줄러 스텁
- O-22 PENDING_ACTIVATION 180일 스케줄러 스텁 (Flyway + @Scheduled)

### Phase 2 — 결제 + 경로 B + check-email
- M-5: Payment referenceType별 권한 분기
- M-6: 환불 자동화 보류(Admin 수동)
- 경로 B LoaSigningToken 5회 실패 잠금 (AC-18 이미 명시)
- check-email CAPTCHA + Cloudflare Turnstile

### Phase 3 — OTP + 강화
- 경로 B OTP 전환 (O-13)
- PDPA §21 데이터 주체 권리 자가 API
- 마케팅 opt-out 완전 구현 (O-20)

---

## 6. 미결 사항 (결정 필요)

| 이슈 | 보안 쟁점 | 의견 | 주체 | 긴급도 |
|------|---------|------|------|--------|
| **O-21** (옵션 A vs B) | H-5: PDPA §24 + ASVS V2.1.6 위반 | **옵션 B 단독 권고**. A 채택 시 OTP/SMS 필수 | 보안팀 + PO | **PR#3 착수 전** |
| **O-22** (PENDING 정리) | M-2, PDPA §25 | 90/180일 2단계 A안 + 사전 이메일 통지 | 법무·운영 | Phase 1 설계 |
| **O-23** (enumeration 검증) | M-1 p95 달성 곤란 | 상수 시간 패딩(600ms) + rate-limit로 방어, p95는 평균 보장만 | 보안팀 | Phase 1 구현 |
| **O-17** (토큰 다중 활성) | H-2 탈취 토큰 무효화 | **A안 (1개만 유지)** | 보안 | Phase 1 |
| **O-18** (soft-deleted 재신청) | PDPA §25 "잊혀질 권리" | **A안 (신규 재생성)** + 감사 플래그 | 법무·보안 | Phase 1 |
| **O-1 / O-15** (경로 A 묵시동의) | 법적 무결성 | **법무 확정 전 경로 A feature flag 비활성** | 법무 | **Phase 1 배포 전** |

---

## 7. 결론

PRD v1.4는 UserConsentLog, PENDING_ACTIVATION, Account Setup 토큰 인프라, 경로 A 메타데이터를 잘 설계했다. 그러나 (1) **H-1~H-4는 PR#1~#3 머지 전 필수 수정**이며, 특히 **H-4(SecurityConfig)는 PR#1 첫 커밋**으로 선행, (2) **H-5 (O-21)는 보안팀·PO 문서 승인 후 PR#3 착수** — 옵션 A 강행 시 본 리뷰 승인 보류, (3) **PDPA §22A 사고 대응 플레이북, §25 보존 정책** 문서화 필요 (`doc/SecurityIncidentPlaybook.md`, `doc/PdpaRetentionPolicy.md`).

**Phase 1 보안 승인 조건**: H-1~H-5 모두 해소 + PDPA 체크리스트 #1·#3·#5·#10·#11·#12 초록.

---

### 부록 A — 참조

- CWE: 200, 204, 208, 285, 307, 319, 501, 532, 639, 770
- OWASP ASVS 4.0.3 — V2/V7/V11/V13; Cheat Sheet — Auth/Password/File Upload/REST
- NIST SP 800-63B §5.2.2
- Singapore PDPA §13/§14/§18/§21/§22A/§24/§25/§26
- OWASP Top 10 2021 — A01/A02/A04/A05/A07
