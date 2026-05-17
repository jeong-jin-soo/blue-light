# LicenseKaki 알림 요구사항 명세 (Notification Requirements)

> 작성 기준: `feature/unified-lew-review` 브랜치 / 2026-04-24
> 코드 증거 기반 — 모든 경로·라인 번호는 현재 리포지토리 기준

---

## 1. Executive Summary

### 현황 한 줄 요약
**이메일 + 인앱 2채널**만 구현됨. SMS·WhatsApp은 발송 채널로 **구현되지 않음** (`NoteChannel.WHATSAPP`은 컨시어지 매니저가 **수동 기록**하는 로그용 enum — 발송 기능 아님).
현재 이벤트 대 알림 구현 커버리지는 **Application 메인 플로우 + Document Request + Concierge 접수/견적 + LOA 대리업로드 + 계정 설정/비밀번호 리셋/면허 만료경고**까지이며, 그 외 7개 이상의 비즈니스 도메인에 **알림이 0건**이다.

### 핵심 Gap (P0 · 당장 처리해야 함)

| # | Gap | 증거 |
|---|-----|------|
| G1 | **Application 제출 알림 0건** — 신청자에게 "접수 완료" 확인, 관리자에게 "신규 접수" 통보 모두 없음 | `ApplicationService.createApplication()` (L84~) 에 `emailService`·`notificationService` 호출 없음 |
| G2 | **LEW 가입 승인/반려 알림 0건** — `approveLew`/`rejectLew` 가 이메일·인앱 모두 미발송. 승인 대기자는 "언제 승인됐는지" 알 방법이 없음 | `AdminUserController.approveLew` L120~135, `rejectLew` L144~159 |
| G3 | **SLD Order 전체 알림 0건** — PENDING_QUOTE → QUOTE_PROPOSED → PAID → SLD_UPLOADED → REVISION → COMPLETED 전 상태 전이에서 어떤 채널도 발송 없음 | `SldOrderService.java`, `SldManagerService.java` — `emailService`·`notificationService` 임포트 자체가 없음 |
| G4 | **Expired License Order 전체 알림 0건** (방문형 서비스) — 견적/결제/방문 스케줄/방문 완료/재방문 요청 모두 미알림. 방문 일정은 반드시 양방향 통보가 필요한 채널 | `ExpiredLicenseOrderService.java`, `ExpiredLicenseManagerService.java` 전수 grep — 알림 호출 0건 |
| G5 | **License 자동 EXPIRED 전환 알림 없음** — 스케줄러가 COMPLETED → EXPIRED 로 전환할 때 신청자에게 통보 없음 (만료 *경고*만 있음) | `LicenseExpiryScheduler.expireOverdueLicenses()` L100~116 — `markAsExpired()` 후 `emailService` 호출 없음 |
| G6 | **선언됐지만 사용되지 않는 Concierge NotificationType 7종** — `CONCIERGE_REQUEST_ASSIGNED`, `CONCIERGE_LOA_SIGN_REQUIRED`, `CONCIERGE_LICENCE_PAYMENT_REQUIRED`, `CONCIERGE_COMPLETED`, `CONCIERGE_CANCELLED`, `CONCIERGE_SLA_BREACH_WARNING`, `CONCIERGE_ACCOUNT_SETUP_LINK_SENT`. enum에는 존재하나 `createNotification` 호출부 0건 | `NotificationType.java` L25~30 선언 vs grep 결과 미사용 |
| G7 | **Payment 요청/확정 → 인앱 알림 비일관** — 신청자에겐 이메일만, 할당 LEW에겐 이메일+인앱. 신청자 인앱 누락 | `AdminPaymentService.confirmPayment` L96~120 비교 |
| G8 | **LEW/Admin 대시보드 → 신규 신청 통보 없음** — Admin이 LEW에게 배정해야 첫 통보를 받음. 그 전까지 "새 신청서가 들어온 것"을 능동적으로 알 방법 없음 | `AdminApplicationService` 에 "신청 접수 → Admin 알림" 로직 부재 |

---

## 2. 현재 구현 상태

### 2.1 인프라
| 구성요소 | 파일 | 비고 |
|---------|------|------|
| 인앱 알림 엔티티 | `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/Notification.java` | Soft delete, `is_read`/`read_at` |
| 인앱 알림 서비스 | `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/NotificationService.java` | `createNotification` REQUIRES_NEW — afterCommit 훅 안전 |
| 알림 타입 enum | `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationType.java` | 17종 선언, 실사용 10종 |
| 이메일 인터페이스 | `blue-light-backend/src/main/java/com/bluelight/backend/api/email/EmailService.java` | 23개 메서드 |
| SMTP 구현체 | `blue-light-backend/src/main/java/com/bluelight/backend/api/email/SmtpEmailService.java` | `@Async` · `@ConditionalOnProperty(mail.smtp.enabled=true)` · HTML 템플릿 인라인 빌더 |
| 로그 폴백 | `blue-light-backend/src/main/java/com/bluelight/backend/api/email/LogOnlyEmailService.java` | 개발용 (콘솔 출력만) |
| SMTP 설정 | `blue-light-backend/src/main/resources/application.yaml` L37~45, L127~130 | `MAIL_HOST`/`MAIL_SMTP_ENABLED` 환경변수 |
| SMS/WhatsApp | **없음** | 발송 게이트웨이·DTO·설정 모두 부재 |

### 2.2 현재 실제로 발송되는 알림 (코드 확인)

> 범례: **E** = 이메일, **I** = 인앱 알림(Notification 테이블)

#### 인증·계정
| # | 이벤트 | 수신자 | 채널 | 호출부 |
|---|--------|--------|------|--------|
| 1 | 회원가입 완료 → 이메일 인증 요청 | 가입자 | E | `AuthService.java:159` |
| 2 | 비밀번호 재설정 요청 | 본인 | E | `AuthService.java:303` |
| 3 | 재이메일 인증 요청 | 본인 | E | `AuthService.java:420` |
| 4 | 비활성 계정 로그인 시도 → 활성화 링크 | 본인 | E | `LoginActivationService.java:126` |
| 5 | 컨시어지 매니저의 계정 활성화 링크 발송 | 대상 사용자 | E | `ConciergeManagerService.java:307` |

#### Application 플로우
| # | 이벤트 | 수신자 | 채널 | 호출부 |
|---|--------|--------|------|--------|
| 6 | LEW 할당 | LEW | E | `AdminLewService.java:73` |
| 7 | 보완 요청(`requestRevision`) | 신청자 | E | `AdminApplicationService.java:303` |
| 8 | 결제 요청(`approveForPayment`) | 신청자 | E | `AdminApplicationService.java:339` |
| 9 | 결제 확정(`confirmPayment`) | 신청자 | E | `AdminPaymentService.java:96` |
| 10 | 결제 확정(`confirmPayment`) | 할당 LEW | **E+I** | `AdminPaymentService.java:106, 114` |
| 11 | 면허 발급 완료(`completeApplication`) | 신청자 | E | `AdminApplicationService.java:274` |
| 12 | LEW kVA 확정(`confirmKva`) | 신청자 | I | `ApplicationKvaService.java:245` |

#### Document Request (LEW ↔ Applicant)
| # | 이벤트 | 수신자 | 채널 | 호출부 |
|---|--------|--------|------|--------|
| 13 | LEW 서류 요청 생성 | 신청자 | **E+I** | `DocumentRequestNotifier.notifyCreated` |
| 14 | 신청자 업로드 완료 | 할당 LEW | **E+I** | `DocumentRequestNotifier.notifyFulfilled` |
| 15 | LEW 승인 | 신청자 | **E+I** | `DocumentRequestNotifier.notifyApproved` |
| 16 | LEW 반려 | 신청자 | **E+I** | `DocumentRequestNotifier.notifyRejected` |

#### Kaki Concierge (v1.5)
| # | 이벤트 | 수신자 | 채널 | 호출부 |
|---|--------|--------|------|--------|
| 17 | 컨시어지 요청 접수 (신규 가입) | 신청자 | **E+I** | `ConciergeNotifier.notifySubmitted` (C1/C3 케이스) |
| 18 | 컨시어지 요청 접수 (기존 계정 연결) | 신청자 | E+I | 〃 (C2 케이스) |
| 19 | 컨시어지 신규 접수 알림 | Admin + Concierge Manager | **E+I** | `ConciergeNotifier.safeNotifyStaff` |
| 20 | 견적 발송 (통화 후) | 신청자 | **E+I** | `ConciergeNotifier.notifyQuoteSent` |
| 21 | LOA 대리 업로드 확인 (7일 이의 창구) | 신청자 | E | `LoaService.java:317` |

#### Schedule 기반
| # | 이벤트 | 수신자 | 채널 | 호출부 |
|---|--------|--------|------|--------|
| 22 | 면허 만료 경고 (기본 D-30) | 신청자 | E | `LicenseExpiryScheduler.java:77` |

### 2.3 현재 **구현되지 않은** 중대 도메인

| 도메인 | 상태 전이 갯수 | 알림 호출 |
|--------|---------------|----------|
| Application 접수 (신청자 제출) | 1 | **0** |
| LEW 가입 승인/반려 | 2 | **0** |
| SLD Order | 9 상태 × 양방향 | **0** |
| Expired License Order (방문형) | 9 상태 × 양방향 | **0** |
| LEW Service Order | - | **0** |
| Lighting Order | - | **0** |
| Power Socket Order | - | **0** |
| License 자동 EXPIRED 전환 | 1 | **0** (경고만 있음) |
| Application `updateStatus` (관리자 강제 전환) | N | **0** |

---

## 3. 역할별 알림 매트릭스

> 범례: **E** = 이메일, **S** = SMS, **W** = WhatsApp, **I** = 인앱 · **✓** = 구현됨 · **✗** = 미구현 · **∆** = 부분 구현

### 3.1 신청자 (APPLICANT)

| # | 이벤트 | 트리거 조건 | 채널 | 목적 | CTA | 상태 |
|---|--------|-----------|------|------|-----|------|
| A-01 | 회원가입 이메일 인증 | 가입 완료 직후 | E | 액션 유도 | `/verify-email?token=` | ✓ |
| A-02 | 비밀번호 재설정 링크 | `POST /api/auth/forgot-password` | E | 액션 유도 | `/reset-password?token=` | ✓ |
| A-03 | **Application 접수 확인** | `createApplication` 성공 직후 | **E+I** | 정보 전달 | `/applications/{id}` | **✗ P0** |
| A-04 | LEW 배정됨 (나에게 담당자 생김) | `assignLew` | **E+I** | 정보 전달 | `/applications/{id}` | **✗ P1** |
| A-05 | kVA 확정됨 | `confirmKva` | I (E 추가 권장) | 정보 전달 | `/applications/{id}` | ∆ (인앱만) |
| A-06 | 보완 요청(Revision) | `requestRevision` | **E+I** | 액션 유도 | `/applications/{id}/edit` | ∆ (이메일만) |
| A-07 | 서류 요청 생성 | LEW가 서류 요청 | E+I | 액션 유도 | `/applications/{id}#documents` | ✓ |
| A-08 | 서류 승인 | LEW 승인 | E+I | 정보 전달 | `/applications/{id}#documents` | ✓ |
| A-09 | 서류 반려(재업로드) | LEW 반려 | **E+I+S** | 액션 유도(긴급) | `/applications/{id}#documents` | ∆ (이메일·인앱만, SMS 추가 권장) |
| A-10 | 결제 요청 | `approveForPayment` | **E+I** | 액션 유도 | `/applications/{id}/payment` | ∆ (이메일만) |
| A-11 | 결제 확인 완료 | `confirmPayment` | **E+I** | 정보 전달 | `/applications/{id}` | ∆ (이메일만) |
| A-12 | 면허 발급 완료 | `completeApplication` | **E+I+S** | 정보 전달(중요) | `/applications/{id}/licence` | ∆ (이메일만) |
| A-13 | 면허 만료 경고 (D-30/D-14/D-7/D-1) | 스케줄러 | E (+ D-7부터 S/W) | 경고 | `/applications/{id}/renew` | ∆ (D-30 한번만) |
| A-14 | **면허 자동 EXPIRED 전환** | 스케줄러 | **E+I** | 경고 | `/applications/{id}/renew` | **✗ P0** |
| A-15 | 컨시어지 접수 확인 | `ConciergeNotifier.notifySubmitted` | E+I | 정보 전달 | `/concierge/requests/{code}` | ✓ |
| A-16 | 컨시어지 견적 발송 | `notifyQuoteSent` | E+I | 액션 유도 | (이메일 내 PayNow 정보) | ✓ |
| A-17 | 컨시어지 담당자 배정됨 | Manager 배정 시 | **E+I** | 정보 전달 | `/concierge/requests/{code}` | **✗ P1** (enum만 있음) |
| A-18 | 컨시어지 LOA 서명 요청 | Manager LOA 생성 | **E+I+S** | 액션 유도 | `/concierge/loa/{token}` | **✗ P0** (enum만 있음) |
| A-19 | 컨시어지 LOA 대리업로드 확인 | Manager upload | E | 이의 제기 창구 | `mailto:support@` | ✓ |
| A-20 | 컨시어지 라이선스료 결제 요청 | Application `PENDING_PAYMENT` 진입 + viaConcierge | **E+I** | 액션 유도 | `/applications/{id}/payment` | **✗ P0** (enum만 있음) |
| A-21 | 컨시어지 완료 통지 | `COMPLETED` 전이 | **E+I** | 정보 전달 | `/concierge/requests/{code}` | **✗ P1** (enum만 있음) |
| A-22 | 컨시어지 취소 통보 | Manager/Admin 취소 | **E+I** | 정보 전달 | `/concierge/requests/{code}` | **✗ P1** (enum만 있음) |
| A-23 | **SLD Order 견적 제안됨** | `proposeQuote` | **E+I** | 액션 유도 | `/orders/sld/{id}` | **✗ P1** |
| A-24 | **SLD Order 업로드 완료** | `uploadSld` | **E+I** | 액션 유도(확인) | `/orders/sld/{id}` | **✗ P1** |
| A-25 | **SLD Order 완료** | `markComplete` | E+I | 정보 전달 | `/orders/sld/{id}` | **✗ P2** |
| A-26 | **Expired License Order 견적 제안됨** | `proposeQuote` | **E+I** | 액션 유도 | `/orders/expired-licence/{id}` | **✗ P1** |
| A-27 | **Expired License 방문 일정 확정** | `scheduleVisit` | **E+I+S** | 정보 전달(중요) | 캘린더 ICS 첨부 권장 | **✗ P0** (SMS 필요) |
| A-28 | **Expired License 방문 체크인** | `checkIn` | I | 정보 전달 | `/orders/expired-licence/{id}` | **✗ P2** |
| A-29 | **Expired License 방문 완료 (사진 업로드)** | `uploadVisitPhotos` 완료 | **E+I** | 액션 유도(확인) | `/orders/expired-licence/{id}` | **✗ P1** |
| A-30 | **Expired License 완료** | 완료 확인 | E+I | 정보 전달 | 〃 | **✗ P2** |
| A-31 | Admin이 Application 상태 강제 변경 | `updateStatus` | I (선택적 E) | 정보 전달 | `/applications/{id}` | **✗ P1** |
| A-32 | 비밀번호 변경 성공 | `resetPassword` 완료 | **E** | 보안 알림 | (CTA 없음, 본인 확인) | **✗ P0** (보안) |
| A-33 | 새 디바이스 로그인 감지 | 로그인 + 신규 User-Agent/IP | E | 보안 알림 | 〃 | **✗ P2** (옵션) |

### 3.2 LEW (Licensed Electrical Worker)

| # | 이벤트 | 트리거 | 채널 | 상태 |
|---|--------|-------|------|------|
| L-01 | **LEW 가입 승인됨** | `approveLew` | **E+I** | **✗ P0** |
| L-02 | **LEW 가입 반려됨** | `rejectLew` (사유 포함) | **E** | **✗ P0** |
| L-03 | 신청 할당됨 | `assignLew` | E (I 추가 권장) | ∆ |
| L-04 | 신청 배정 해제됨 | `unassignLew` | **E+I** | **✗ P1** |
| L-05 | 서류 업로드 완료 (검토 필요) | 신청자 업로드 | E+I | ✓ |
| L-06 | 결제 확인됨 (작업 개시 가능) | `confirmPayment` | E+I | ✓ |
| L-07 | 보완 재제출(Revision 완료) | `resubmit` | **E+I** | **✗ P1** |
| L-08 | 신청자가 채팅 메시지 보냄 | Chat message (있다면) | I | **✗ P2** (검증 필요) |
| L-09 | 배정 건 SLA 경고 (예: 48h 미처리) | 스케줄러 | **E+I** | **✗ P1** |
| L-10 | LEW Service Order 관련 | LewServiceOrder 상태 전이 | E+I | **✗ P1** |

### 3.3 ADMIN

| # | 이벤트 | 트리거 | 채널 | 상태 |
|---|--------|-------|------|------|
| M-01 | **신규 Application 접수** | `createApplication` 성공 | **E+I** | **✗ P0** |
| M-02 | **LEW 신규 가입 신청** | LEW role 회원가입 | **E+I** | **✗ P0** (승인 대기 건 확인 불가) |
| M-03 | 컨시어지 신규 접수 | `notifySubmitted` | E+I | ✓ |
| M-04 | 컨시어지 24h SLA 위반 경고 | 스케줄러 | **E+I** | **✗ P0** (enum만 있음) |
| M-05 | 결제 실패 / 매칭 실패 알림 | PayNow 매칭 실패 | E+I | **✗ P1** |
| M-06 | Invoice 자동발행 실패 | `invoiceGenerationService` 실패 | I (현재 감사 로그만) | ∆ |
| M-07 | 이상 징후(로그인 실패 연속) | AuditLog 패턴 | E | **✗ P2** (선택) |
| M-08 | 데이터 유출 신고 (DataBreachNotification) | breach 발생 | **E+I** | 확인 필요 |
| M-09 | 만료된 LEW 라이센스 자동 감지 | 스케줄러 | E+I | **✗ P1** |

### 3.4 SYSTEM_ADMIN

| # | 이벤트 | 트리거 | 채널 | 상태 |
|---|--------|-------|------|------|
| S-01 | 시스템 장애 (SMTP 실패율 > n%) | Metrics 임계치 | **E** | **✗ P1** |
| S-02 | 파일 암호화 키 관련 경고 | 키 없음/로딩 실패 | **E** | **✗ P1** |
| S-03 | AI Service 연결 실패 (장시간) | Health check | E | **✗ P2** |
| S-04 | DB 백업 실패 | 스케줄러 | **E** | **✗ P1** |
| S-05 | ADMIN M-01~M-09 승계 (권한 백업) | 모든 M-* 이벤트를 공동수신 (옵션) | E+I | **✗ P2** |

### 3.5 SLD_MANAGER

| # | 이벤트 | 트리거 | 채널 | 상태 |
|---|--------|-------|------|------|
| D-01 | **새 SLD Order 접수** | `createOrder` | **E+I** | **✗ P0** |
| D-02 | 매니저로 배정됨 | `assignManager` | **E+I** | **✗ P1** |
| D-03 | 신청자 견적 수락 / 결제 완료 (작업 개시) | `acceptQuote` → `PAID` | **E+I** | **✗ P0** |
| D-04 | 신청자 견적 거절 | `rejectQuote` | E+I | **✗ P1** |
| D-05 | 신청자 수정 요청 | `requestRevision` (SLD) | **E+I** | **✗ P0** |
| D-06 | 신청자 완료 확인 | `confirmCompletion` | I | **✗ P2** |

### 3.6 CONCIERGE_MANAGER

| # | 이벤트 | 트리거 | 채널 | 상태 |
|---|--------|-------|------|------|
| C-01 | 신규 컨시어지 접수 | `notifySubmitted` | E+I | ✓ |
| C-02 | 매니저로 배정됨 | Manager 지정 | **E+I** | **✗ P1** (enum `CONCIERGE_REQUEST_ASSIGNED` 미사용) |
| C-03 | 24h 내 첫 접촉 SLA 임박/위반 | 스케줄러 | **E+I** | **✗ P0** (enum `CONCIERGE_SLA_BREACH_WARNING` 미사용) |
| C-04 | 신청자 LOA 서명 완료 | `LoaService.sign` | **E+I** | **✗ P1** |
| C-05 | **Expired License Order 접수** | `createOrder` (ExpiredLicense) | **E+I** | **✗ P0** (도메인 기본 수신자) |
| C-06 | Expired License 재방문 요청됨 | `requestRevisit` | **E+I** | **✗ P0** |
| C-07 | Expired License 신청자 완료 확인 | `confirmCompletion` (Applicant) | I | **✗ P2** |

---

## 4. 채널 선택 기준

본 섹션은 **"요구사항 관점"**에서 채널 선택 규칙을 정의한다. (UX 여정 관점은 strategist 에이전트가 별도 분석)

### 4.1 채널 특성

| 채널 | 지연 | 비용 | 길이 | 수신율 | 적합 용도 |
|-----|------|------|------|--------|-----------|
| **인앱(I)** | 실시간 | 무료 | 무제한 | 로그인 시에만 확인 | 모든 상태 전이의 **기본** — 로그인 사용자 UX 주 채널 |
| **이메일(E)** | 수초~수분 | 저렴 | 무제한 | 스팸 필터링 ~5~20% 유실 | 기록·증빙·CTA 링크·PDPA 공지 |
| **SMS(S)** | 수초 | 건당 $0.02~0.05 | 160자 | 95%+ 읽힘 | 긴급·시간제약·약속 |
| **WhatsApp(W)** | 수초 | 건당 $0.01~0.03 (비즈니스) | 무제한 + 첨부 | 싱가포르 침투율 매우 높음 | 이미지/PDF 전달, 캐주얼 리마인더 |

### 4.2 선택 규칙 (Decision Matrix)

| 조건 | 권장 채널 |
|------|----------|
| 로그인 사용자가 플랫폼 안에서 확인할 이벤트 | **I (필수)** |
| 외부 증빙(결제·면허·LOA 서명 링크 등) | **E (필수) + I** |
| **시간 제약 < 24h + 법적·재정적 결과** (결제 마감, 방문 약속, 면허 D-1) | **E + S (필수) + I** |
| 사용자가 플랫폼 미로그인 상태로 2+ 일 경과한 **대기 액션** | **E + S(optional)** |
| 첨부 파일 공유(사진/PDF) + 비형식 | **W (optional, 매니저 주도)** |
| 내부 스태프(ADMIN/Manager) 업무 큐 | **I (필수) + E (요약만)** |
| 보안 이벤트(비번 변경, 신규 기기 로그인) | **E (필수)** |

### 4.3 **SMS/WhatsApp 도입 트리거**

아래 5개 이벤트는 이메일/인앱만으로는 수신 보장이 부족 → **SMS 통합 필수**:

1. **Expired License 방문 일정 확정 (A-27)** — 방문 당일 오전 리마인더 필수
2. **면허 만료 D-7 / D-1 경고 (A-13)** — 법적 불이익 예방
3. **컨시어지 LOA 서명 요청 (A-18)** — v1.5 UX flow상 신청자가 플랫폼 로그인하지 않아도 완료돼야 함
4. **서류 반려 재업로드 (A-09)** — 24h SLA 내 응답 필요
5. **컨시어지 라이선스료 결제 요청 (A-20)** — 마감일 임박 시

### 4.4 전송 순서 (Fan-out 규칙)
`E + I + S` 가 모두 지정된 이벤트는 다음 순서로 발송:
1. `I` 생성 (동일 TX, REQUIRES_NEW)
2. `afterCommit` 훅에서 `E` · `S` 병렬 발송
3. 실패 격리 — 한 채널 실패가 다른 채널을 막지 않음

---

## 5. 신규·개선 필요 알림 상세 스펙

> 본 섹션은 **P0 · P1만** 상세화. P2는 백로그.

### 5.1 [P0 · A-03] Application 접수 확인

**요구사항 요약**
신청자가 `POST /api/applications`로 신청서를 제출하면 즉시 접수 확인 이메일 + 인앱 알림을 발송한다. Admin에게도 신규 접수 인앱 알림을 발송한다(M-01과 함께 구현).

**수용 기준**
1. GIVEN 신청자가 모든 필수 필드를 채워 신청서를 제출 WHEN `ApplicationService.createApplication`이 성공 반환 THEN 신청자 이메일로 "Application #X received" 메일이 `afterCommit` 훅으로 발송된다
2. GIVEN 동일 조건 WHEN 트랜잭션 커밋 THEN `Notification` 테이블에 `type=APPLICATION_SUBMITTED`, `recipient=applicant`, `referenceType=APPLICATION`, `referenceId=applicationSeq` 레코드가 생성된다
3. GIVEN 동일 조건 WHEN 트랜잭션 커밋 THEN `role ∈ {ADMIN, SYSTEM_ADMIN}`, `status=ACTIVE`인 모든 사용자에게 인앱 알림이 생성된다 (이메일 발송 여부는 admin에게 선택적 — P1로 분리)
4. GIVEN `createApplication`이 검증 실패로 롤백 WHEN 예외 발생 THEN 이메일·인앱 모두 발송되지 않는다 (`afterCommit` 보장)
5. GIVEN SMTP 발송 실패 WHEN 이메일 발송 실패 THEN 트랜잭션은 이미 커밋되어 Application은 생성 유지, 인앱 알림도 유지, 실패는 WARN 로그만 남긴다

**기술적 고려**
- 새 `NotificationType.APPLICATION_SUBMITTED` 추가 (DB 마이그레이션 불필요 — VARCHAR)
- `EmailService.sendApplicationSubmittedEmail(String to, String userName, Long appSeq, String address, BigDecimal quoteAmount)` 추가
- `ApplicationNotifier` 신규 오케스트레이터 — `DocumentRequestNotifier` 패턴 답습
- `ApplicationService.createApplication`에서 `applicationNotifier.notifySubmitted(application)` 호출

**엣지 케이스**
1. Concierge에서 대리 생성된 application (`viaConciergeRequestSeq != null`) — 신청자에게만 보내고 admin은 이미 `CONCIERGE_REQUEST_SUBMITTED`로 받았으므로 중복 방지
2. RENEWAL 타입 — 제목·본문을 "Renewal application" 으로 구분
3. kVA UNKNOWN 상태 — 이메일 본문에 "LEW will confirm kVA and price" 섹션 포함

---

### 5.2 [P0 · G2 · L-01, L-02] LEW 가입 승인/반려 통보

**요구사항 요약**
`POST /api/admin/users/{id}/approve` 또는 `/reject` 성공 시 해당 LEW에게 이메일을 발송한다. 반려 시엔 사유를 포함한다.

**수용 기준**
1. GIVEN Admin이 LEW를 승인 WHEN `approveLew` 성공 THEN 해당 LEW 이메일로 "LEW account approved" 메일 + 대시보드 CTA 링크 포함하여 발송
2. GIVEN Admin이 LEW를 반려(사유 포함) WHEN `rejectLew` 성공 THEN 해당 LEW 이메일로 "LEW registration needs revision" 메일 + 사유 텍스트 + 재신청 안내 포함
3. GIVEN 해당 LEW가 로그인 WHEN 알림 센터 조회 THEN `NotificationType.LEW_APPROVED` 또는 `LEW_REJECTED` 인앱 알림 1건 노출
4. GIVEN `rejectLew` 의 `reason`이 null/blank WHEN 실행 THEN 400 `REJECTION_REASON_REQUIRED` (현재 엔드포인트는 reason 파라미터 자체가 없으므로 컨트롤러 시그니처 변경 필요)
5. GIVEN 이메일 발송 실패 WHEN afterCommit 훅 실패 THEN 승인/반려 상태는 유지, WARN 로그만

**기술적 고려**
- `NotificationType.LEW_APPROVED`, `LEW_REJECTED` 신규 추가
- `EmailService.sendLewApprovedEmail(...)` / `sendLewRejectedEmail(..., String reason)` 신규 메서드
- `AdminUserController.rejectLew` 시그니처에 `@RequestBody RejectLewRequest{reason}` 추가 — **기존 API 브레이킹 체인지이므로 별도 확인 필요**
- 현재 `approveLew`/`rejectLew` 는 Controller 단에 `@Transactional` — afterCommit 훅 주의

**엣지 케이스**
1. LEW가 이미 APPROVED인데 재승인 호출 — 알림 재발송 금지 (idempotent)
2. 반려 후 프로필 수정 → 재검토 요청 사이클 — 재승인 시 본문 문구 분기

---

### 5.3 [P0 · G4 · A-27] Expired License 방문 일정 확정

**요구사항 요약**
컨시어지 매니저가 `scheduleVisit`로 방문 일정을 확정하면 신청자에게 E+I+S 3채널 발송. 방문 전일 오전 9시(SGT)에 리마인더 S 발송 (별도 스케줄러).

**수용 기준**
1. GIVEN 매니저가 방문 일정 + 메모를 저장 WHEN `scheduleVisit` 성공 THEN 신청자 이메일/SMS/인앱 알림이 발송된다
2. 이메일 본문에 `iCal (.ics)` 첨부를 포함하여 iOS/Android 캘린더 원탭 추가 가능
3. SMS 본문은 160자 이내: `[LicenseKaki] Your expired licence visit: 2026-05-10 14:00 (Mgr: {name}). Details: lk.sg/v/{shortCode}`
4. GIVEN 방문 D-1 오전 9시 (SGT) WHEN 스케줄러 실행 THEN 동일 신청자에게 리마인더 SMS만 발송 (이메일·인앱은 D-1 기준 재발송 없음 — 노이즈 방지)
5. GIVEN SMS 게이트웨이 실패 WHEN SMS 발송 실패 THEN 이메일·인앱은 이미 발송됨, 실패는 `audit_log`에 `SMS_SEND_FAILED`로 기록

**기술적 고려**
- **신규 SMS 게이트웨이 통합 필요** — Twilio Programmable SMS 또는 AWS SNS 권장
- `SmsService` 인터페이스 + `TwilioSmsService` / `SnsSmsService` / `LogOnlySmsService` (EmailService 패턴 답습)
- `application.yaml`에 `sms.provider`, `sms.twilio.account-sid`, `sms.twilio.auth-token`, `sms.twilio.from-number` 설정 추가
- 사용자 `phoneNumber` 필드 필수 확인 — 현재 User 엔티티에 phone 있는가? (`AuthService` L141 주석: "phone/companyName/uen/designation은 가입 시 수집하지 않는다") → **Expired License Order 생성 시점에 JIT로 수집 필요**
- iCal 생성: `net.fortuna.ical4j:ical4j` (새 의존성) 또는 수기 VCALENDAR 문자열
- `ExpiredLicenseOrderNotifier` 신규 오케스트레이터

**엣지 케이스**
1. 신청자 phone 미제공 → SMS 스킵, 이메일 본문에 "Please provide phone number for SMS reminders" 배너
2. 방문 일정 재조정 — 기존 알림 취소 불가(SMS는 회수 불가), 본문에 "This replaces the previous schedule" 명시
3. 주말/공휴일 09:00 SGT 발송 시점 — 법적 이슈 없으나 UX 배려로 평일은 09:00, 주말은 10:00 (스케줄러 분기)
4. SMS 국가번호 — 싱가포르 외 번호 시 국제 요율 경고 로그

---

### 5.4 [P0 · A-14] 면허 자동 EXPIRED 전환 통보

**요구사항 요약**
`LicenseExpiryScheduler.expireOverdueLicenses`가 Application을 EXPIRED로 전환할 때 신청자에게 E+I 발송.

**수용 기준**
1. GIVEN COMPLETED 상태 Application의 `licenseExpiryDate < today` WHEN 스케줄러 실행 THEN 신청자에게 "Licence expired" 이메일 + 인앱 알림 발송
2. 본문에 **Expired License Order 경로** 안내: "Our concierge can visit you to renew — [Book a visit]"
3. 이미 `expiredNotifiedAt != null` 인 경우 중복 발송 금지 (idempotency)
4. 동일 application에 대해 `licenseExpiryDate` 전 30일 경고는 별도 플래그(`expiryNotifiedAt`)와 분리된 새 플래그(`expiredNotifiedAt`) 필요

**기술적 고려**
- `Application.expiredNotifiedAt` 컬럼 추가 (DB 마이그레이션)
- `EmailService.sendLicenseExpiredEmail(...)` 신규
- `NotificationType.LICENSE_EXPIRED` 신규

**엣지 케이스**
- 신청자가 이미 RENEWAL을 제출하여 새 Application이 COMPLETED인 경우 — 구 application은 EXPIRED로 전환되지만 신청자에게 알림은 보내지 않음 (최신 COMPLETED가 존재하면 스킵)

---

### 5.5 [P0 · M-04 · C-03] 컨시어지 24h SLA 위반 경고

**요구사항 요약**
ConciergeRequest가 `SUBMITTED` 상태로 24h 경과했는데 Manager의 첫 접촉 로그(`ConciergeNote`)가 없으면 Admin + 해당 Manager에게 경고.

**수용 기준**
1. GIVEN `ConciergeRequest.status = SUBMITTED` AND `createdAt < now - 24h` AND `ConciergeNote.count = 0` WHEN 스케줄러(매 시간) 실행 THEN Admin + 배정 Manager에게 E+I 발송
2. 동일 request에 대해 2회 이상 경고 금지 (escalation 플래그)
3. `CONCIERGE_SLA_BREACH_WARNING` enum 사용 (이미 선언됨)
4. Admin 이메일 본문에 escalation 테이블 포함: Request Code, Manager name, Hours elapsed, Last contact

**기술적 고려**
- `ConciergeSlaScheduler` 신규 `@Scheduled` 컴포넌트
- 기존 `ShedLock` 패턴 답습

---

### 5.6 [P0 · A-20] 컨시어지 라이선스료 결제 요청

**요구사항 요약**
`viaConciergeRequestSeq != null`인 Application이 `PENDING_PAYMENT`로 진입하면 신청자에게 별도 본문의 결제 요청 E+I. (일반 A-10과 분리 — 컨시어지 경로임을 본문에 명시)

**수용 기준**
1. GIVEN Application이 PENDING_REVIEW → PENDING_PAYMENT AND `viaConciergeRequestSeq != null` WHEN `approveForPayment` 성공 THEN 일반 `sendPaymentRequestEmail` 대신 `sendConciergePaymentRequiredEmail` 발송
2. 본문에 Manager 담당자 이름 + "Your concierge manager is {name}" 포함
3. `NotificationType.CONCIERGE_LICENCE_PAYMENT_REQUIRED` 사용 (이미 선언됨)

---

### 5.7 [P0 · A-18] 컨시어지 LOA 서명 요청

**요구사항 요약**
Manager가 LOA PDF 생성 (`POST /api/admin/applications/{id}/loa/generate`) 성공 시 신청자에게 E+I+S 발송.

**수용 기준**
1. Manager가 LOA 생성 성공 → 신청자 E+I+S로 서명 링크 (`/loa/{token}`) 발송
2. `NotificationType.CONCIERGE_LOA_SIGN_REQUIRED` 사용 (이미 선언됨)
3. 48h 미서명 시 리마인더 S+E 1회 발송
4. 72h 미서명 시 Manager에게 경고 I+E
5. SMS 본문: 160자 이내, `/loa/{token}` 단축 URL 포함

---

### 5.8 [P0 · M-01/M-02] Admin 신규 이벤트 수신

**요구사항 요약**
Admin/SystemAdmin에게 Application 신규 접수, LEW 가입 신청 2종에 대해 인앱 알림(이메일은 옵션, 대량 발생 시 noise) 발송.

**수용 기준**
1. GIVEN 신청자가 Application 제출 WHEN 저장 성공 THEN `role ∈ {ADMIN, SYSTEM_ADMIN}`, `status=ACTIVE` 모든 사용자에게 I 생성
2. GIVEN LEW role로 회원가입 WHEN `status=ACTIVE` 또는 관계없이 LEW 가입자 생성 THEN 〃 인앱 알림 + E 1건 (일반 가입은 E 없고 LEW 만 E — 승인 업무 트리거)
3. 이메일은 Admin 개별 설정으로 수신 여부 토글 가능 (system_settings.admin_notify_email_* 키)

---

### 5.9 [P1] 그 외 P1 이벤트 일괄 표 (요약 AC만)

| ID | 이벤트 | 핵심 AC |
|----|--------|--------|
| A-04 | LEW 배정됨 | Assign 성공 → 신청자 I + E; 재배정 시 이전 알림에 "(Updated)" 배지 |
| A-06 | 보완 요청 인앱 추가 | 기존 이메일 + 인앱 I 생성 |
| A-11 | 결제 확인 인앱 추가 | 〃 |
| A-12 | 면허 발급 E+I+S | 발급 완료 시 면허번호 포함 SMS (법적 중요 정보) |
| A-23~26 | SLD/ExpiredLicense 견적 제안 | 각 Order 도메인별 Notifier 추가 |
| C-05, C-06 | Expired License 매니저 수신 | 접수/재방문 시 담당 매니저 전체에게 I+E |
| L-07 | 보완 재제출 → LEW 통보 | `resubmit` 성공 시 할당 LEW에 I+E |
| L-09 | LEW SLA 경고 | 할당 후 48h 미처리 스케줄러 |
| D-01~D-05 | SLD Manager 알림군 | Order 도메인별 Notifier |

---

## 6. 우선순위 로드맵

### P0 (즉시 · 법적/결제/SLA 필수)
1. **A-03 / M-01** Application 접수 확인 + Admin 신규 접수 인앱
2. **L-01 / L-02 / M-02** LEW 가입 승인·반려 + Admin LEW 신규 가입 통보
3. **A-14** 면허 자동 EXPIRED 전환 통보
4. **A-18** 컨시어지 LOA 서명 요청 (E+I+S 중 E+I 먼저, SMS는 5.11 단계)
5. **A-20** 컨시어지 라이선스료 결제 요청
6. **C-03 / M-04** 컨시어지 24h SLA 위반 경고
7. **A-32** 비밀번호 변경 성공 통보 (보안)
8. **D-01 / D-03 / D-05** SLD Order 관리자 필수 3종 (신규 접수, 결제 완료, 수정 요청)
9. **C-05 / C-06** Expired License 매니저 기본 수신 (신규 접수, 재방문 요청)
10. **A-27** Expired License 방문 일정 확정 (E+I 먼저, SMS는 5.11)

### P0.5 (인프라 · P0 중 SMS 수반 항목의 선행)
5.11 **SMS 게이트웨이 통합** (Twilio 또는 AWS SNS) + `SmsService` 추상화
5.12 **User.phoneNumber JIT 수집** — ExpiredLicense/LOA 플로우에서 요청

### P1 (운영 개선)
- A-04, A-06(인앱), A-09(SMS), A-11(인앱), A-12(SMS) — 기존 이메일 이벤트의 채널 보강
- A-17, A-21, A-22 — 컨시어지 배정·완료·취소
- A-23~30 — SLD/ExpiredLicense Order 신청자 수신
- L-03~L-09 — LEW 알림 보강
- M-05, M-09 — Admin 운영 알림
- D-02, D-04, D-06 — SLD Manager 부가

### P2 (UX 추가·선택)
- A-28, A-30, A-33
- L-08
- M-07
- S-03, S-05
- 수신 설정 UI (사용자별 채널 on/off)

---

## 7. 공통 요구사항

### 7.1 i18n
- **현재**: 모든 이메일 본문이 영어 단일(`en-SG`). 한국어·중국어·말레이어 미지원.
- **요구**: `User.preferredLocale` 필드 추가. 템플릿을 `MessageSource` 기반으로 전환 (HTML은 Thymeleaf 또는 Freemarker 권장).
- **우선순위**: P2 — 싱가포르 공용어는 영어라 급하지 않음. 단, 본문 내 **사용자가 입력한 데이터**(이름, 주소, 사유 등)는 이미 UTF-8로 안전.

### 7.2 재시도 (Retry)
- **현재**: 이메일 `@Async` + 실패 WARN 로그만. 재시도 없음.
- **요구**: `Retryable` 아웃박스 패턴 권장. `notification_outbox` 테이블 → 스케줄러가 주기적으로 실패 건 재시도 (최대 3회, 지수 백오프).
- **P0/P1** 이벤트만 아웃박스 적용. 단순 정보성 P2는 best-effort.

### 7.3 수신거부 / Preference
- **현재**: 전체 사용자 공통, opt-out 불가.
- **요구**:
  - `user_notification_preferences(user_seq, notification_type, email_enabled, sms_enabled, in_app_enabled)` 테이블
  - **법적 고정 알림**(결제·면허·LOA 서명·PDPA·데이터 유출)은 opt-out 불가로 강제
  - 정보성(면허 발급 축하, 컨시어지 완료 등)은 사용자 토글 가능
  - 이메일 하단에 `unsubscribe` 링크(법적 고정은 제외)

### 7.4 로깅 · 감사
- **현재**: 각 `EmailService` 구현에서 INFO 로그 1줄.
- **요구**:
  - `notification_log(notification_seq, channel, recipient, status, sent_at, error_code)` 테이블로 모든 채널의 발송 결과 기록
  - SMS는 Twilio Message-SID 저장하여 delivery report 추적
  - Audit log에는 민감 본문 제외, 메타데이터만

### 7.5 PDPA 준수
- **현재**: 이메일 제목에 민감정보 제외 규칙 일관 (예: Concierge Quote 이메일 제목은 `publicCode`만, 금액·주소 제외 — `SmtpEmailService.java:1130` 주석 참조)
- **요구**: 전 신규 알림도 동일 원칙 유지:
  - 제목에 실명·주소·면허번호·금액 **포함 금지**
  - 본문에도 필수 최소화
  - SMS는 민감정보 **완전 금지** — 단축 링크로 플랫폼 이동 유도

### 7.6 템플릿 관리
- **현재**: `SmtpEmailService.java`에 1,200줄 규모의 HTML 템플릿 인라인 — 수정·다국어 전환이 어려움
- **요구**: Phase별 리팩토링:
  1. Phase 1: 현재 인라인 유지 — 신규 템플릿만 `resources/templates/email/*.html` 로 분리
  2. Phase 2: 기존 템플릿도 점진적 추출
  3. 파일 분리 이후에만 i18n 도입

### 7.7 테스트 전략
- **현재**: SMTP 실제 발송 테스트 부재. `LogOnlyEmailService`가 기본값.
- **요구**:
  - `EmailService` · `SmsService`는 테스트에서 **항상 mock 또는 spy**
  - `Notifier` 통합 테스트에서 `afterCommit` 훅이 정확히 1회 호출되는지 검증 (롤백 시 미호출)
  - E2E는 [MailHog](https://github.com/mailhog/MailHog) 또는 [GreenMail](https://greenmail-mail-test.github.io/greenmail/) 로컬 SMTP 캡처

### 7.8 장애 격리
- **원칙** (현재 코드에서도 준수됨): 알림 실패는 비즈니스 트랜잭션을 절대 롤백시키지 않는다. `afterCommit` 훅 + catch-and-log-WARN 패턴 유지.

---

## 8. 범위 외 (Out of Scope)

- 푸시 알림 (모바일 앱 없음)
- 음성 통화 알림
- Slack/Teams 내부 스태프 채널 통합 (선택 기능, P2 이후)
- 마케팅·뉴스레터 (법적·운영 알림 전용)
- 사용자별 채널 preference UI (P2에서 별도 PRD 필요)

---

## 9. 참고

- `CLAUDE.md` — Application Status Flow
- `doc/Project Analysis/kaki-concierge-service-prd.md` §6 알림 명세 (N1~N9 원본)
- `doc/Project Analysis/lew-service-visit-redesign-spec.md`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/email/EmailService.java` — 현행 23개 메서드
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationType.java` — 현행 17종 enum
- `blue-light-backend/src/main/java/com/bluelight/backend/api/document/DocumentRequestNotifier.java` — **권장 Notifier 패턴 참조 구현**
