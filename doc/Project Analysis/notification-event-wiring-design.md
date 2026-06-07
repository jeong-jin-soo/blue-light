# 알림 템플릿 ↔ 이벤트 연결 설계 (Notification Event Wiring Design)

> **작성일**: 2026-06-04
> **상태**: Design v1.0 — 구현 착수 전 정본 설계
> **선행 문서**:
> - 템플릿 관리 스펙: [`notification-template-manager-spec.md`](./notification-template-manager-spec.md) (PR-T1~T8 배포 완료)
> - 카탈로그(메타 SSOT): [`notification-catalog.md`](./notification-catalog.md)
> - 카피북(본문 정본, 124KB): [`notification-copy-templates.en.md`](./notification-copy-templates.en.md)
> **목적**: 관리자가 편집하는 `notification_templates` 가 실제 발송에 소비되도록, 100종 카탈로그
> 알림을 각 도메인 이벤트와 연결한다. 본 문서가 구현·QA 의 단일 정본.

---

## §1. 배경 — "관리 UI는 만들어졌으나 거의 연결되지 않았다"

알림 발송 인프라는 **두 갈래**로 병존하며, 새 템플릿 엔진은 **현재 1종만** 실제 발송에 쓰인다.

| 경로 | 메커니즘 | 템플릿 소비 | 연결 수 |
|---|---|---|---|
| **신(新) 오케스트레이터** | `NotificationDispatchEvent` → `NotificationOrchestrator`(AFTER_COMMIT) → `templateRegistry.findActive` → outbox → 채널 어댑터 | ✅ `notification_templates` | **1종** (A-20, PR-0E 카나리) |
| **구(舊) 레거시** | `emailService.sendXxxEmail(...)` + `notificationService.createNotification(...)` 직접 호출 | ❌ 본문 자바 하드코딩 | ~40여 종 |
| **미구현** | 트리거(스케줄러/메서드) 자체가 없음 | — | ~30여 종 |

오케스트레이터·채널 어댑터·outbox·재시도·렌더러는 **완성·정상 동작**한다
([`EmailChannelAdapter.java:84`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/notification/channel/EmailChannelAdapter.java#L84) 에서 템플릿 렌더 후 `sendGenericEmail` 위임).
막혀 있는 것은 **(A) 발송 본문 데이터**와 **(B) 이벤트 발행 호출부** 둘 뿐이다.

### 1.1 세 가지 구조적 갭

| 갭 | 내용 | 영향 |
|---|---|---|
| **① 본문 미적재** | [`data.sql:187`](../../blue-light-backend/src/main/resources/data.sql#L187) 에 발송용 템플릿은 A-20(`PAYMENT_CONFIRMED_APPLICANT`) **1종**뿐. [`import_notification_copy.py`](../../scripts/import_notification_copy.py) 는 `notification_catalog`(메타)만 생성하고 `notification_templates`(subject/body)는 **생성하지 않음**. 카피북 100종 정본이 **DB 적재 경로가 없음** | 이벤트를 연결해도 `findActive` 빈 결과 → 채널 SKIP |
| **② 코드 매핑 부재** | 카탈로그/카피북은 `A-17` 체계, 오케스트레이터는 `PAYMENT_CONFIRMED_APPLICANT` 시맨틱 코드. 잇는 것은 `catalog_meta_key` 컬럼뿐, A-NN↔시맨틱↔호출부 정본표 없음 | 어떤 코드로 발행해야 하는지 불명확 |
| **③ 레거시 우회** | 관리자가 UI 에서 96종을 편집해도 실제 발송은 `EmailService` 하드코딩 HTML 을 탐 | 편집 효과 0. CLAUDE.md "설정 우선(SSOT)" 정면 위반 |

---

## §2. 목표 아키텍처 — 모든 알림은 오케스트레이터 1경로

```
도메인 서비스 (트랜잭션 내부)
   │  eventPublisher.publishEvent(new NotificationDispatchEvent(
   │      eventType,         // NotificationType enum 문자열 (감사·라우팅용)
   │      recipientUserSeq,  // 수신자
   │      referenceType,     // "APPLICATION" 등 (idempotency·deep-link)
   │      referenceId,
   │      templateCode,      // = 카탈로그 코드 (A-17). §4 결정
   │      payload))          // 템플릿 변수 Map<String,String> (PII 최소화)
   ▼ @TransactionalEventListener(AFTER_COMMIT)
NotificationOrchestrator
   │  1) 수신자 조회 → preferredLanguage(locale)
   │  2) PreferenceResolver.resolveEnabledChannels(recipient, eventType)  ← 채널·옵트인 결정
   │  3) 채널별 templateRegistry.findActive(templateCode, channel, locale)
   │  4) outbox 적재(REQUIRES_NEW) + dispatchAsync
   ▼
채널 어댑터 (EMAIL / IN_APP / WHATSAPP) → 외부 발송
```

**호출부의 책임은 payload 채우기뿐.** 채널 선택·언어·옵트인·재시도·idempotency 는 전부 오케스트레이터가
결정한다. 기준 구현은 카나리 [`AdminPaymentService.java:103`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/admin/AdminPaymentService.java#L103).

### 2.1 레거시 → 신경로 이관 패턴 (Before/After)

**Before** (A-15 수정 요청, 현재):
```java
// AdminApplicationService.requestRevision()
emailService.sendRevisionRequestEmail(applicant.getEmail(), applicant.getFullName(),
        publicCode, revisionReason);          // 하드코딩 HTML, 인앱 없음, 옵트인 무시
```

**After**:
```java
eventPublisher.publishEvent(new NotificationDispatchEvent(
        "APPLICATION_REVISION_REQUESTED",     // NotificationType (신규 enum 값)
        applicant.getUserSeq(),
        "APPLICATION", applicationSeq,
        "A-15",                                // 카탈로그 코드 = template_code
        Map.of("applicantName", applicant.getFullName(),
               "publicCode",    publicCode,
               "revisionReason", revisionReason,
               "ctaUrl",        "/applications/" + publicCode)));
```
- `EmailService.sendRevisionRequestEmail` 및 대응 인앱 `createNotification` 은 **삭제**(또는 deprecated).
- 채널 E+I 동시 발송, ko/zh 자동 폴백, 옵트인 가드는 오케스트레이터가 자동 처리.

---

## §3. 본문 적재 설계 (갭 ① 해소 — 모든 연결의 선행 조건)

### 3.1 신규 스크립트 `scripts/seed_notification_templates.py`

기존 `import_notification_copy.py`(카탈로그 메타 전용)와 **별도**로 신설. 카피북 카드 → `notification_templates` INSERT 생성.

| 입력 | 출력 |
|---|---|
| [`notification-copy-templates.en.md`](./notification-copy-templates.en.md) 카드별 메타표 + Email/In-app/SMS 섹션 | `INSERT INTO notification_templates (template_code, channel, locale, subject, body_text, variables_json, catalog_meta_key, category, severity, recipient_roles, enabled, ...)` + `ON DUPLICATE KEY UPDATE` |

**파싱 규칙** (카피북 카드 구조는 §0.i18n 컨벤션 + 카드별 일관 포맷):
- 카드 헤더 `#### A-17 — Payment required` → `template_code='A-17'`, `catalog_meta_key='A-17'`
- 메타표 `Category/Severity/Recipient/Channels/Variables` → 컬럼 매핑
- `**Email**` 블록 → `channel='EMAIL'`: Subject + Body(+ Headline/CTA) 를 §1.1/1.2 헤더·푸터 블록으로 감싼 HTML 생성 (`{{footerBlock}}` 토큰 포함 — lint L7)
- `**In-app**` 블록 → `channel='IN_APP'`: Title + Body + deep-link(action)
- `**SMS**` 블록 → `channel='SMS'`: prefix `[LicenseKaki] ` + 160자 검증 (lint L2)
- WhatsApp 은 Meta 승인 의존 → MVP 제외 (provider_template_name 만 비워둠)
- `Channels` 표기(`E + I`, `E + S` 등)에 명시된 채널만 INSERT (없는 채널 row 미생성 → `findActive` 자연 SKIP)

**검증**: 생성 직후 각 row 를 `TemplateLinter`(L1 변수 화이트리스트 ⊂ catalog.allowed_variables) 로 검증.
실패 시 스크립트 비정상 종료 (CI drift gate).

### 3.2 적재 절차 (운영/CI)
```bash
python3 scripts/seed_notification_templates.py > /tmp/templates_seed.sql
# 로컬: data.sql 에 병합 (PR-W1) / 운영: mysql 직접 실행
mysql -h <host> -u <user> -p bluelight < /tmp/templates_seed.sql
```
- `data.sql` 의 기존 카나리 A-20 시드는 §4.2 처리에 따라 정리.
- CI 단계에 `seed_notification_templates.py --check` 추가 → markdown↔생성 row 일치 검증(drift 방지).

---

## §4. 코드 명명 결정 (갭 ② 해소)

### 4.1 결정: `template_code = 카탈로그 코드(A-NN)` 채택

| 옵션 | 장점 | 단점 |
|---|---|---|
| **A. A-NN 직접** (권장) | 카탈로그/카피북/DB/UI 1:1, 매핑표 불필요, drift 검증 단순 | 코드만으로 의미 불명(단, `catalog.description` 보유) |
| B. 시맨틱 코드 + catalog_meta_key | 코드 가독성 | 100종 시맨틱명 별도 관리 = 새 매핑표 = 갭 ② 재발 |

- `template_code` = `A-17` (카탈로그 코드). `catalog_meta_key` = 동일값.
- `eventType`(= `NotificationType` enum) 은 시맨틱 유지 (`APPLICATION_REVISION_REQUESTED`) — 감사 로그·`PreferenceResolver` 라우팅·옵트인 카테고리 매핑에 사용. **templateCode 와 eventType 은 별개 축.**
- 따라서 매핑은 `NotificationType(eventType) → A-NN(templateCode)` 1:1, §5 표가 정본.

### 4.2 카나리(A-20) 정합화
- 현재 카나리는 `template_code='PAYMENT_CONFIRMED_APPLICANT'`, `eventType="PAYMENT_CONFIRMED"`.
- **이관**: `notification_templates` 의 code 를 `A-20` 으로 변경(또는 A-20 row 신설 후 구 row disable).
  `AdminPaymentService` 의 `templateCode` 인자를 `"A-20"` 으로 교체. `eventType` 은 enum 그대로 유지.
- PR-W2(결제 묶음)에서 함께 처리.

---

## §5. 이벤트별 연결 매트릭스 (전수 100종)

**상태 범례**: ✅ 연결됨(오케스트레이터) · 🟡L 레거시 이메일/인앱 직접발송(이관 대상) · 🔴N 미구현(트리거/스케줄러 없음)
**Tier**: T1 = 기존 트리거+레거시발송 존재(스왑만) · T2 = 기존 트리거, 발송 신규 · T3 = 트리거/스케줄러 신설 필요

### 5.1 APPLICANT (A-01 ~ A-54)

| 코드 | 알림 | Trigger (카피북) | 채널 | 현재 | Tier |
|---|---|---|---|---|---|
| A-01 | 이메일 인증 | `AuthService.signup` | E | 🟡L `sendEmailVerificationEmail` | T1 |
| A-02 | 환영/온보딩 | `AuthService.verifyEmail` | E+I | 🔴N | T2 |
| A-03 | 비밀번호 재설정 링크 | `POST /forgot-password` | E | 🟡L `sendPasswordResetEmail` | T1 |
| A-04 | 비밀번호 변경 완료 | `AuthService.resetPassword` | E | 🔴N (발송 코드 없음) | T2 |
| A-05 | 신규 기기/IP 로그인 | novel UA fingerprint | E | 🔴N (탐지 로직 없음) | T3 |
| A-06 | 비활성 계정 활성화 링크 | `LoginActivationService` | E | 🟡L `sendAccountSetupLinkEmail` | T1 |
| A-07 | 인증 메일 재발송 | resend 클릭 | E | 🟡L `sendEmailVerificationEmail` | T1 |
| A-08 | 신청 접수증 | `ApplicationService.createApplication` | E+I | 🔴N | T2 |
| A-09 | DRAFT 방치 리마인더 | Scheduler D+1/D+3 | E | 🔴N (스케줄러 없음) | T3 |
| A-10 | LEW 배정 | `AdminLewService.assignLew` | E+I | 🟡L `sendLewAssignedEmail` | T1 |
| A-11 | kVA 확정 | `ApplicationKvaService.confirmKva` | E+I | 🟡L 인앱(KVA_CONFIRMED) | T1 |
| A-12 | 서류 요청 생성 | `DocumentRequestNotifier.notifyCreated` | E+I | 🟡L | T1 |
| A-13 | 서류 승인 | `DocumentRequestNotifier.notifyApproved` | E+I | 🟡L | T1 |
| A-14 | 서류 반려 | `DocumentRequestNotifier.notifyRejected` | E+I+S | 🟡L | T1 |
| A-15 | 수정 요청 | `AdminApplicationService.requestRevision` | E+I(+W) | 🟡L `sendRevisionRequestEmail` | T1 |
| A-16 | 수정 미제출 리마인더 | RevisionReminderScheduler D+2/D+5 | E(+W) | 🔴N (스케줄러 없음) | T3 |
| A-17 | 결제 요청 | `AdminApplicationService.approveForPayment` | E+I | 🟡L `sendPaymentRequestEmail` | T1 |
| A-18 | 결제 D-3 리마인더 | PaymentDueScheduler | E | 🔴N (스케줄러 없음) | T3 |
| A-19 | 결제 D-1 리마인더 | PaymentDueScheduler | E+S(+W) | 🔴N (스케줄러 없음) | T3 |
| **A-20** | **결제 확인** | `AdminPaymentService.confirmPayment` | E+I | ✅ **연결됨(카나리)** | — |
| A-21 | 진행중 안심 메시지 | Scheduler IN_PROGRESS | E | 🔴N (스케줄러 없음) | T3 |
| A-22 | 면허 발급(완료) | `AdminApplicationService.completeApplication` | E+I+S(+W) | 🟡L `sendLicenseIssuedEmail` | T1 |
| A-23 | 관리자 수동 상태 변경 | `updateStatus` (수동) | I(+E) | 🔴N | T2 |
| A-24 | 만료 D-90 | LicenseExpiryScheduler | E | 🔴N (90일 레벨 없음) | T3 |
| A-25 | 만료 D-60 | LicenseExpiryScheduler | E | 🔴N (60일 레벨 없음) | T3 |
| A-26 | 만료 D-30 | LicenseExpiryScheduler | E+I | 🟡L `sendLicenseExpiryWarningEmail`(30일 기본) | T2 |
| A-27 | 만료 D-7 | LicenseExpiryScheduler | E+W | 🔴N (7일 레벨 없음) | T3 |
| A-28 | 만료 D-1 | LicenseExpiryScheduler | E+S | 🔴N (1일 레벨 없음) | T3 |
| A-29 | 자동 EXPIRED 전이 | `expireOverdueLicenses` | E+I | 🔴N | T2 |
| A-30 | 만료 후 D+1 세일즈 | Scheduler(MARKETING) | E | 🔴N (스케줄러 없음) | T3 |
| A-31 | 컨시어지 접수 | `ConciergeNotifier.notifySubmitted` | E+I | 🟡L | T1 |
| A-32 | 컨시어지 매니저 배정 | manager assigned | E+I | 🟡L (Concierge 리스너) | T1 |
| A-33 | 컨시어지 견적 발송 | `ConciergeNotifier.notifyQuoteSent` | E+I | 🟡L `sendConciergeQuoteEmail` | T1 |
| A-34 | 컨시어지 LOA 서명 요청 | `LoaService.generateLoa` | E+I+S(+W) | 🟡L | T1 |
| A-35 | LOA 48h 미서명 리마인더 | LoaReminderScheduler | E+S | 🔴N (스케줄러 없음) | T3 |
| A-36 | LOA 대리 업로드 확인 | `LoaService.uploadSignedByManager` | E | 🟡L `sendConciergeLoaUploadConfirmEmail` | T1 |
| A-37 | 컨시어지 라이선스 결제 요청 | `approveForPayment`(concierge 분기) | E+I | 🟡L | T1 |
| A-38 | 컨시어지 방문 예약 | `ConciergeService.scheduleVisit` | E+I+S | 🔴N (scheduleVisit 미발송) | T2 |
| A-39 | 방문 D-1 리마인더 | VisitReminderScheduler | S(+W) | 🔴N (스케줄러 없음) | T3 |
| A-40 | 방문 30분 전 도착 알림 | "On the way" 탭 | S+W | 🔴N | T3 |
| A-41 | 컨시어지 방문 완료(사진) | `uploadVisitPhotos` | E+I | 🔴N | T2 |
| A-42 | 컨시어지 최종 완료 | COMPLETED 전이 | E+I(+W) | 🔴N | T2 |
| A-43 | 컨시어지 취소 | 취소 | E+I | 🟡L `sendConciergeLewUnassignedEmail`(유사) | T2 |
| A-44 | SLD 견적 제안 | `SldOrderService.proposeQuote` | E+I | 🔴N (SLD 알림 미구현) | T2 |
| A-45 | SLD 견적 리마인더 | Scheduler D-3/D-1 | E(+W) | 🔴N (스케줄러 없음) | T3 |
| A-46 | SLD 도면 업로드 | `SldManagerService.uploadSld` | E+I(+W) | 🔴N | T2 |
| A-47 | SLD 주문 완료 | `SldManagerService.markComplete` | E+I | 🔴N | T2 |
| A-48 | 만료면허 견적 제안 | `ExpiredLicenseOrderService.proposeQuote` | E+I | 🔴N | T2 |
| A-49 | 만료면허 방문 예약 | `ExpiredLicenseManagerService.scheduleVisit` | E+I+S | 🔴N | T2 |
| A-50 | 만료면허 방문 D-1 | VisitReminderScheduler | S(+W) | 🔴N (스케줄러 없음) | T3 |
| A-51 | 방문 체크인 | "Check in" 탭 | I | 🔴N | T2 |
| A-52 | 만료면허 방문 완료 | `uploadVisitPhotos` | E+I | 🔴N | T2 |
| A-53 | 만료면허 최종 완료 | 신청자 확인 | E+I | 🔴N | T2 |
| A-54 | NPS 설문 | Scheduler D+3 완료 후 | E | 🔴N (스케줄러 없음) | T3 |

### 5.2 LEW (L-01 ~ L-12)

| 코드 | 알림 | Trigger | 채널 | 현재 | Tier |
|---|---|---|---|---|---|
| L-01 | LEW 가입 승인 | `AdminUserController.approveLew` | E+I | 🔴N (승인 시 발송 없음) | T2 |
| L-02 | LEW 가입 거부 | `rejectLew` | E | 🔴N | T2 |
| L-03 | 신청 배정 | `AdminLewService.assignLew` | I+E(digest) | 🟡L | T1 |
| L-04 | 배정 해제 | `AdminLewService.unassignLew` | E+I | 🟡L | T1 |
| L-05 | 서류 업로드(검토 필요) | `DocumentRequestNotifier.notifyFulfilled` | I+E(digest) | 🟡L | T1 |
| L-06 | 결제 확인(작업 시작) | `AdminPaymentService.confirmPayment` | E+I | 🟡L `sendPaymentConfirmedToLewEmail`+인앱(`LewPaymentNotificationListener`) | T1 |
| L-07 | 수정 재제출 접수 | 신청자 resubmit | I+E(digest) | 🔴N | T2 |
| L-08 | SLA 경고(24h) | LewSlaScheduler | E+I | 🔴N (스케줄러 없음) | T3 |
| L-09 | SLA 위반(48h+) | LewSlaScheduler | E+I | 🔴N (스케줄러 없음) | T3 |
| L-10 | LEW 서비스 오더 이벤트 | Service Order 전이 | E+I(digest) | 🔴N | T2 |
| L-11 | 현장작업 예약(교차) | (cross-ref) | — | 🔴N | T3 |
| L-12 | 일일 마감 요약 | LewDailySummaryScheduler | E | 🔴N (스케줄러 없음) | T3 |

추가 레거시(카탈로그 외 kVA 사후조정 + CoF 알림 — `notification-template-manager-spec` 이후 도입):
`KvaOverrideNotificationListener`, `KvaSettlementNotificationListener`, `LewKvaAdjustmentRequest/ResolvedNotificationListener`,
`ApplicationKvaService` — 모두 🟡L (인앱+이메일 직접). **신규 코드 부여 확정(결정 #2)** — PR-W2 에서 처리:

| 신규 코드 | 알림 | NotificationType | 수신 |
|---|---|---|---|
| A-55 | CoF 서명 완료 → 결제 안내 | `CERTIFICATE_OF_FITNESS_FINALIZED` | 신청자 |
| A-56 | kVA override → CoF 재서명 필요 | `COF_REISSUED_BY_KVA_OVERRIDE` | 신청자 |
| L-13 | 결제 후 ADMIN kVA 변경 통지 | `KVA_ADJUSTED_BY_ADMIN_LEW` | LEW |
| L-14 | settlement 마킹 통지 | `KVA_ADJUSTMENT_SETTLED_LEW` | LEW |
| L-15 | kVA override CoF 재서명 필요 | `COF_REISSUED_BY_KVA_OVERRIDE` | LEW |
| M-11 | LEW 결제 후 kVA 변경 요청 | `KVA_ADJUSTMENT_REQUESTED_ADMIN` | ADMIN |

### 5.3 ADMIN / SYSTEM_ADMIN (M-01 ~ M-10, S-01 ~ S-05)

| 코드 | 알림 | Trigger | 채널 | 현재 | Tier |
|---|---|---|---|---|---|
| M-01 | 신규 신청 접수 | `ApplicationService.createApplication` | I(+E) | 🔴N | T2 |
| M-02 | 신규 LEW 가입 | LEW signup | E+I | 🔴N | T2 |
| M-03 | 신규 컨시어지 요청 | `ConciergeNotifier.notifySubmitted` | E+I | 🟡L (`ConciergeNotifier` staff 알림) | T1 |
| M-04 | 컨시어지 24h SLA 위반 | ConciergeSlaScheduler | E+I | 🔴N (스케줄러 없음) | T3 |
| M-05 | LEW SLA 위반 CC | (cross-ref L-09) | — | 🔴N | T3 |
| M-06 | PayNow 매칭 실패 | PayNow 매칭 스케줄러 | E+I | 🔴N (스케줄러 없음) | T3 |
| M-07 | 영수증 자동발행 실패 | `invoiceGenerationService` 실패 | I(+E) | 🟡L (감사로그만, 알림 부분) | T2 |
| M-08 | 데이터 유출 경보 | `DataBreachService` | E+I | 🔴N | T3 |
| M-09 | LEW 면허 자동만료 감지 | Scheduler daily | E+I | 🔴N | T3 |
| M-10 | 일일 운영 다이제스트 | AdminDailyDigestScheduler | E | 🔴N (스케줄러 없음) | T3 |
| S-01 | 시스템 장애(SMTP 실패율) | Metrics threshold | E(+Slack) | 🔴N | T3 |
| S-02 | 암호화 키 로드 실패 | App startup | E | 🔴N | T3 |
| S-03 | AI 서비스 장기 다운 | Health-check | E | 🔴N | T3 |
| S-04 | DB 백업 실패 | Backup scheduler | E | 🔴N | T3 |
| S-05 | M-* 카본카피 | Each M-* event | E+I | 🔴N | T3 |

### 5.4 SLD_MANAGER (D-01 ~ D-07) · CONCIERGE_MANAGER (C-01 ~ C-09)

| 코드 | 알림 | Trigger | 채널 | 현재 | Tier |
|---|---|---|---|---|---|
| D-01 | 신규 SLD 오더 | `SldOrderService.createOrder` | I+E(digest) | 🔴N | T2 |
| D-02 | 매니저 배정 | `SldOrderService.assignManager` | E+I | 🔴N | T2 |
| D-03 | 결제 완료(작업 시작) | `acceptQuote → PAID` | E+I | 🔴N | T2 |
| D-04 | 견적 거부 | `rejectQuote` | E+I(digest) | 🔴N | T2 |
| D-05 | 수정 요청 | `SldOrderService.requestRevision` | E+I | 🔴N | T2 |
| D-06 | 신청자 완료 확인 | `confirmCompletion` | I(digest) | 🔴N | T2 |
| D-07 | 일일 큐 요약 | digest scheduler | — | 🔴N (스케줄러 없음) | T3 |
| C-01 | 신규 컨시어지 요청 | `ConciergeNotifier.notifySubmitted` | E+I | 🟡L | T1 |
| C-02 | 매니저 배정 | manager assigned | E+I | 🟡L (`ConciergeLewAssignmentNotificationListener`) | T1 |
| C-03 | 24h 첫 응대 SLA | (cross-ref M-04) | — | 🔴N | T3 |
| C-04 | 신청자 LOA 서명 완료 | `LoaService.sign` | E+I | 🔴N | T2 |
| C-05 | 만료면허 오더 접수 | `ExpiredLicenseOrderService.createOrder` | E+I | 🔴N | T2 |
| C-06 | 만료면허 재방문 요청 | `requestRevisit` | E+I | 🔴N | T2 |
| C-07 | 만료면허 신청자 확인 | `confirmCompletion` | I(digest) | 🔴N | T2 |
| C-08 | 일일 방문 요약 | digest scheduler | — | 🔴N (스케줄러 없음) | T3 |
| C-09 | 방문 30분 전 트리거 | (cross-ref A-40) | — | 🔴N | T3 |

> **참고**: 카피북 §"Digest" 섹션의 D-01~D-05(2차 등장)는 위 D-NN(SLD)과 코드 충돌 — **`DIG-01~05`
> 로 재명명 확정(결정 #3)**. 카탈로그/카피북 갱신은 다이제스트 구현(T3, PR-W11)에서 동반.

### 5.5 집계

| Tier | 정의 | 건수(대략) | 작업 성격 |
|---|---|---|---|
| ✅ 연결 | — | 1 | (A-20) |
| T1 | 기존 트리거 + 레거시 발송 | ~20 | **이벤트 스왑** (저위험, 효과 즉시) |
| T2 | 기존 트리거, 발송 신규 | ~35 | 이벤트 신규 발행 |
| T3 | 트리거/스케줄러 신설 | ~45 | 스케줄러·탐지로직 신규 구현 (대형) |

---

## §6. PR 분할 계획

> **MVP 범위 = PR-W0 ~ W7 (T1+T2, EMAIL+IN_APP 만).** T3(PR-W8~W12, 결정 #4)는 **별도 후속 트랙**으로 분리 — 아래 §6.2 참조.

### 6.1 본 트랙 (MVP)

| # | PR | 산출물 | 의존 | 위험 |
|---|---|---|---|---|
| **PR-W0** ✅ | 본문 적재 + 카나리 정합화 | `seed_notification_templates.py`, 카나리 hard-replace(`PAYMENT_CONFIRMED_APPLICANT`→`A-20` + 구 row `DELETE`), A-20 payload 정렬(`AdminPaymentService`), W1 대상(A-10/15/17/22) 본문 선시드. **구현·검증 완료** — §6.3 참조 | — | 中 (데이터) |
| **PR-W1** | T1 신청자 결제·상태 묶음 | A-17/A-10/A-15/A-22 이관 + 레거시 `EmailService` 메서드 즉시 제거(결정 #5) + e2e | W0 | 低 |
| **PR-W2** | T1 서류·kVA·LEW 결제 | A-12/13/14, L-03/04/05/06 이관 + kVA/CoF 신규코드 부여(결정 #2: A-55/56, L-13/14/15, M-11) + 카탈로그·카피북 역반영 + kVA 리스너 4종 이관 | W0 | 中 |
| **PR-W3** | T1 컨시어지 | A-31/32/33/34/36/37, C-01/02, M-03 이관 | W0 | 低 |
| **PR-W4** | T1 인증 | A-01/03/06/07 이관 (호출부 전수 이관 후 EmailService 메서드 제거) | W0 | 低 |
| **PR-W5** | T2 신청 라이프사이클 신규발행 | A-02/04/08/23/29, M-01/02, L-01/02/07 | W0 | 中 |
| **PR-W6** | T2 SLD 오더 알림 | A-44/46/47, D-01~06, C-04 | W0 | 中 |
| **PR-W7** | T2 컨시어지/만료면허 방문 | A-38/41/42/43/48/49/51/52/53, C-05/06/07, M-07 | W0 | 中 |

**원칙**: PR-W0 이 모든 후속의 선행. T1(W1~W4)은 즉시 효과·저위험이므로 우선. 한국어 커밋, stacked.
레거시 제거(결정 #5)는 해당 `sendXxx`/`createNotification` 의 **모든 호출부 이관 완료 시점** PR 에서 수행.

### 6.3 PR-W0 구현 기록 (2026-06-04 완료)

**산출물**
- `scripts/seed_notification_templates.py` — 카피북 카드 → `notification_templates` INSERT 생성기. 자기완결형 HTML(헤더+본문+인라인 푸터, PAYMENT/SECURITY 는 anti-phishing), blockquote/`**bold**` 변환, `{{var}}` 슬롯 보존, `ON DUPLICATE KEY UPDATE`(+`deleted_at=NULL` 재활성). `--codes`/`--all`, `EXCLUDE_CODES={A-20}`.
- `data.sql` — 구 canary 블록 → (a) `DELETE ... PAYMENT_CONFIRMED_APPLICANT` (b) 수기 A-20(EMAIL+IN_APP) (c) 스크립트 생성 A-10/15/17/22.
- `AdminPaymentService.confirmPayment` — templateCode `A-20`, payload 키를 카피북 A-20 변수명(`applicantName/publicCode/amount/paidAtDisplay/lewName/ctaUrl`)에 정렬. `applicationSeq`→`publicCode` 슬롯, `paidAtDisplay`=결제일 포맷, `lewName`=배정 LEW(없으면 "your assigned LEW").
- `AdminPaymentServiceEventTest` — 검증 갱신(templateCode `A-20`, 새 payload 키).

**런타임 제약 반영(구현 중 확정)**
- `TemplateRenderer` 는 단순 `{{var}}` 치환 — footer/system 변수 자동주입 **없음**. 본문은 자기완결형 HTML 이어야 함. (스펙 L7 `{{footerBlock}}` 자동주입은 미구현 → 후속 개선 후보)
- Application 엔티티에 `publicCode` 없음(Concierge 전용) → 호출부가 `applicationSeq` 를 `publicCode` 슬롯에 매핑하는 컨벤션 확립.
- `sendGenericEmail` 첨부 미지원 → A-22 "PDF attached"→"available in your dashboard" 로 수기 조정(W1 에서 첨부 지원 여부 결정). **카피북 A-22 원문은 "attached" 유지** → 스크립트 재생성 시 이 한 문장 차이 주의.

**검증**
- 백엔드 `compileJava`/`compileTestJava` 성공.
- `AdminPaymentServiceEventTest` + `api.notification.*` 전체 그린.
- 실 MySQL(docker) 에 W0 시드 블록 로드 성공 — 10 row(A-20/10/15/17/22 × EMAIL/IN_APP) 생성, 구 canary 0건 확인.
- A-20 렌더 검증: 본문 사용 변수 ⊂ AdminPaymentService payload — **미치환 `{{}}` 0**.
- ⚠ 로컬 docker DB 는 컬럼 추가 전 생성분이라 PR-T1 ALTER(catalog_meta_key/category/severity/recipient_roles)를 1회 수동 적용해야 했음. **신규 DB·CI 는 schema.sql CREATE 에 포함, dev RDS 는 적용 완료**. 운영 RDS 에 미적용 시 동일 ALTER 선행 필요.

### 6.2 후속 트랙 — T3 (별도, 결정 #4 로 분리)

스케줄러·탐지로직 신설이 필요해 본 MVP 에서 제외. 카탈로그 코드는 부여하되 발화 인프라는 후속.

| # | PR | 산출물 | 비고 |
|---|---|---|---|
| PR-W8 | T3 인프라 | `NotificationScheduler` 추상 + ShedLock + 멱등 outbox 가드 | 선행 |
| PR-W9 | 결제·수정·면허 리마인더 | `PaymentDueScheduler`(A-18/19), `RevisionReminderScheduler`(A-16), 만료 D-90/60/7/1(A-24/25/27/28), A-26 레벨 확장 | |
| PR-W10 | 방문·LOA·NPS·세일즈 | `VisitReminderScheduler`(A-39/50), `LoaReminderScheduler`(A-35), A-21/30/45/54, A-09 | |
| PR-W11 | SLA·다이제스트 | `LewSlaScheduler`(L-08/09), 다이제스트(L-12/M-10/D-07/C-08/`DIG-01~05`), `ConciergeSlaScheduler`(M-04) | DIG-* 재명명(결정 #3) |
| PR-W12 | 운영/보안 경보 | A-05(신규기기), M-06/08/09, S-01~05 | 탐지로직 |

---

## §7. 결정 필요 사항

1. ~~**template_code 체계**: §4.1 A-NN 직접 채택안 확정? (권장) — 확정 시 카나리 A-20 정합화 동반.~~
   ✅ **확정 (2026-06-04)**: A-NN 직접 채택. 카나리는 **hard-replace** (구 `PAYMENT_CONFIRMED_APPLICANT` → `A-20` 완전 교체 + 구 row 삭제), **PR-W0 에 포함**.
   `data.sql` 은 매 부팅 재실행(`SQL_INIT_MODE=always`)이므로 dev RDS 는 다음 배포 시 자동 반영 — 수동 SQL 불필요.
   영향 코드 2곳: `data.sql:190/195`(시드), `AdminPaymentService.java:108`(발행 인자). 기존 outbox 이력(구 코드)은 불변 로그로 보존.
2. ~~**카탈로그 외 알림 코드 부여**~~ ✅ **확정 (2026-06-04)**: **부여**. kVA 사후조정 + CoF 알림(현재 레거시·코드 없음)에 신규 코드 부여 + 카탈로그/카피북 역반영. 제안 매핑(PR-W2 에서 확정):
   - `A-55` CoF 서명 완료 → 결제 단계 안내 (신청자, `CERTIFICATE_OF_FITNESS_FINALIZED`)
   - `A-56` kVA override 로 CoF 재서명 필요 (신청자 측, `COF_REISSUED_BY_KVA_OVERRIDE`)
   - `L-13` 결제 후 ADMIN kVA 변경 통지 (LEW, `KVA_ADJUSTED_BY_ADMIN_LEW`)
   - `L-14` settlement 마킹 통지 (LEW, `KVA_ADJUSTMENT_SETTLED_LEW`)
   - `L-15` kVA override CoF 재서명 필요 (LEW 측, `COF_REISSUED_BY_KVA_OVERRIDE`)
   - `M-11` LEW 가 결제 후 kVA 변경 요청 (ADMIN, `KVA_ADJUSTMENT_REQUESTED_ADMIN`)
   - (A-11 `KVA_CONFIRMED` 은 기존 코드 유지)
3. ~~**다이제스트 코드 충돌**~~ ✅ **확정 (2026-06-04)**: **재명명**. 카피북 §Digest 의 D-01~05(2차 등장)를 `DIG-01`~`DIG-05` 로 재명명하고 `notification-catalog.md` 갱신. SLD 의 D-01~07 과 충돌 해소. (다이제스트는 T3 이므로 실제 구현은 후속 트랙)
4. ~~**T3 범위·우선순위**~~ ✅ **확정 (2026-06-04)**: **MVP = T1+T2 만**. 리마인더/SLA/다이제스트/보안경보(T3, PR-W8~W12)는 **별도 후속 트랙으로 분리**. 본 트랙은 실제 사용자 트랜잭션 알림(기존 트리거 존재)에 집중.
5. ~~**레거시 제거 시점**~~ ✅ **확정 (2026-06-04)**: **즉시 삭제**. 한 알림이 오케스트레이터로 이관되면 같은 PR 에서 대응 `EmailService.sendXxx` + 레거시 `createNotification` 을 **즉시 제거**. 단 가드: 해당 `sendXxx` 의 **모든 호출부가 이관 완료된 경우에만** 삭제(미이관 호출부 잔존 시 컴파일 깨짐 → 그 메서드는 마지막 호출부 이관 PR 에서 제거).
6. ~~**SMS/WhatsApp**~~ ✅ **확정 (2026-06-04)**: **다음 트랙**. MVP 는 **EMAIL + IN_APP 만** 연결. SMS/WhatsApp 은 채널 row 시드·어댑터 연결 모두 후속(WhatsApp 은 Meta 승인 의존).

---

## §8. 검증 전략

- **단위**: 각 이관 호출부에 "트랜잭션 커밋 후 `NotificationDispatchEvent` 1건 발행" ApplicationEvents 테스트.
- **통합**: `findActive` 가 시드된 (code, channel, locale) 를 반환하는지 + outbox 적재 검증.
- **e2e**: PR-W1 에서 A-17 결제요청 → 실제 인박스/메일 수신 1회(스펙 §15 DoD #6 충족).
- **회귀 가드**: 레거시 메서드 제거 후 해당 호출부 컴파일 + 기존 알림 테스트 갱신.
- **drift**: CI 에서 `seed_notification_templates.py --check` (markdown↔DB row 코드셋 일치).
- **누락 가시화**: `findActive` SKIP 시 WARN 로그 → admin `/metrics` render_warnings 노출(PR-T7 기 구현).

---

## §9. 참조

- 오케스트레이터: [`NotificationOrchestrator.java`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/notification/orchestrator/NotificationOrchestrator.java)
- 이벤트 레코드: [`NotificationDispatchEvent.java`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/notification/orchestrator/NotificationDispatchEvent.java)
- 카나리 기준 구현: [`AdminPaymentService.java:103`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/admin/AdminPaymentService.java#L103)
- 채널 어댑터: [`EmailChannelAdapter.java`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/notification/channel/EmailChannelAdapter.java), [`InAppChannelAdapter.java`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/notification/channel/InAppChannelAdapter.java)
- 본문 정본: [`notification-copy-templates.en.md`](./notification-copy-templates.en.md)
- 카탈로그 메타: [`notification-catalog.md`](./notification-catalog.md)
