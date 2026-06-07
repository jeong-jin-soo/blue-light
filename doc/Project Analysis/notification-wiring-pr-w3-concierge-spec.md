# PR-W3 — 컨시어지 알림 배선 상세 스펙

> 상위 설계: [`notification-event-wiring-design.md`](./notification-event-wiring-design.md) §2.1(이관 패턴), §5.4·§6(PR-W3)
> 카피 정본: [`notification-copy-templates.en.md`](./notification-copy-templates.en.md) §2.4(A-31~42), §7(C-01~09), §4(M-03)
> 작성: 2026-06-04 / 대상 브랜치: develop

## §0. 목표 & 범위

컨시어지 알림을 **레거시 직접발송**(`ConciergeNotifier` + `EmailService.sendConcierge*` + `NotificationService.createNotification`)에서
**오케스트레이터 1경로**(`eventPublisher.publishEvent(NotificationDispatchEvent)` → `NotificationOrchestrator` → outbox → 채널 어댑터 → `notification_templates` 렌더)로 이관한다.

- **범위 (T1, EMAIL+IN_APP만)**: A-31, A-32, A-33, A-34, A-36, C-01, C-02, M-03
  - ⚠️ **A-37(컨시어지 결제요청)은 PR-W1로 이동**(결정 #D) — A-17 일반 결제요청과 같은 `approveForPayment` 지점이라 함께 배선.

### 0.1 실제 상태 (코드 확인으로 설계문서 보정)
| 템플릿 | 실제 상태 | 작업 성격 |
|--------|-----------|-----------|
| A-31 / C-01 / M-03 (`notifySubmitted`) | 🟡 레거시 있음 | **이관** |
| A-33 (`notifyQuoteSent` → `sendConciergeQuoteEmail`) | 🟡 레거시 있음 | **이관** |
| A-36 (`uploadSignedByManager` → `sendConciergeLoaUploadConfirmEmail`) | 🟡 레거시 있음 | **이관** |
| A-32 / C-02 (매니저 배정) | 🔴 미구현(발송 코드 없음) | **신규** |
| A-34 (LOA 서명요청) | 🔴 미구현(`generateLoa` 발송 없음) | **신규** |
| A-37 (컨시어지 결제) | 🔴 일반 A-17로 처리 중 | **PR-W1로 이동** |

> ⚠️ LEW 배정 알림(`ConciergeLewAssignedEvent`/`ConciergeLewAssignmentNotificationListener`)은 **A-32/C-02 와 다른 사건(LEW 대상)**. 본 PR에서 건드리지 않음.

### 0.2 분할 (위험도 기준)
- **PR-W3a (순수 이관, 저위험)**: A-36 → A-33 → A-31/C-01/M-03
- **PR-W3b (신규 발송)**: A-32/C-02(매니저 배정), A-34(LOA 서명요청)
- **제외**:
  - SMS/WhatsApp 채널 (T3, Meta 승인 의존) — A-34의 S, A-38/39/40의 S+W
  - A-38(방문예약)/A-41(방문완료): 트리거 미구현(🔴N) → T2 별도
  - A-35(LOA 리마인더)/A-39(방문 D-1)/C-03(SLA)/C-08(다이제스트): 스케줄러 없음 → T3
  - A-42(최종완료)/A-43(취소): T2

## §1. 표준 이관 패턴 (트리거당 반복)

```java
// Before (레거시)
emailService.sendConciergeRequestReceivedEmail(email, name, setupUrl, expStr);
notificationService.createNotification(userSeq, TYPE, title, body, "CONCIERGE_REQUEST", reqSeq);

// After (오케스트레이터)
eventPublisher.publishEvent(new NotificationDispatchEvent(
        "CONCIERGE_REQUEST_SUBMITTED",   // eventType (NotificationType enum 문자열)
        recipientUserSeq,
        "CONCIERGE_REQUEST", conciergeRequestSeq,
        "A-31",                          // = template_code
        Map.of("applicantName", name, "publicCode", publicCode, "ctaUrl", ctaUrl /* ... */)));
```
- 발송 타이밍: orchestrator 의 `@TransactionalEventListener(AFTER_COMMIT)` 가 커밋 후 발송 보장
  → 기존 `ConciergeNotifier.afterCommit(...)` 수동 훅 **불필요**(제거).
- 채널 선택·locale 폴백(en/ko/zh)·옵트인·재시도·idempotency 는 전부 orchestrator 책임.
- payload 변수명은 **카피북 카드 Variables 와 1:1** (lint L1: 변수 ⊂ catalog.allowed_variables).

## §2. 트리거별 상세

### T1. `ConciergeNotifier.notifySubmitted` → A-31 + C-01 + M-03
- **eventType**: `CONCIERGE_REQUEST_SUBMITTED` (기존)
- **refType/refId**: `CONCIERGE_REQUEST` / conciergeRequestSeq
- **수신자 3종**:
  - A-31 신청자 — payload: `applicantName, publicCode, setupUrl?, expiresAtDisplay?, ctaUrl`
  - C-01 매니저(CONCIERGE_MANAGER, ACTIVE 전원) — payload: `managerName(수신자명), publicCode, applicantPhone?, ctaUrl`
  - M-03 어드민(ADMIN) — payload: `publicCode, applicantName, applicantEmail, ctaUrl`
- **제거 레거시**: `sendConciergeRequestReceivedEmail`, `sendConciergeRequestReceivedExistingUserEmail`, `sendConciergeStaffNewRequestEmail` + applicant/staff `createNotification` 2건
- **⚠️ 결정필요 #A**: C2(이미 활성 계정) 케이스는 현재 별도 메일(`...ExistingUserEmail`, setupUrl 없음). 이관 시 →
  A-31 단일 템플릿에서 `setupUrl`/`expiresAtDisplay` 를 빈값으로 두고 본문이 분기되게 할지(권장: 카피북 `{{setupUrl?}}` optional), 아니면 A-31-alt 신규 카드 추가할지.
- **주입필요**: `ConciergeNotifier` 에 `ApplicationEventPublisher` (현재 미주입). staff 조회(`findByRoleInAndStatus`)는 유지하되 발송만 이벤트로.

### T2. 매니저 배정 → A-32 + C-02
- **eventType**: `CONCIERGE_REQUEST_ASSIGNED` (기존)
- **트리거**: `ConciergeManagerService` 배정 + `ConciergeLewAssignedEvent` / `ConciergeLewAssignmentNotificationListener`
- **수신자**: A-32 신청자(`applicantName, publicCode, managerName, managerContactWindow, ctaUrl`), C-02 매니저(`managerName, publicCode, applicantPhone?, ctaUrl`)
- **✅ 결정 #B**: A-32/C-02 는 **매니저 배정 사건**(LEW 배정과 별개). 현재 **발송 없음 → 신규**.
  - 트리거: `assignManagerTransition()` 내 `cr.assignManager(target)` **성공 직후**.
  - 모델: 매니저 권한자 전원 대상 자동배정 → C-02 는 **CONCIERGE_MANAGER(ACTIVE) 전원**에게, A-32 는 신청자에게.
  - **LEW 배정 리스너(`ConciergeLewAssignmentNotificationListener`)는 손대지 않음** — 별도 카드(추후).

### T3. `ConciergeManagerService.notifyQuoteSent` → A-33
- **eventType**: **🆕 신규 enum `CONCIERGE_QUOTE_SENT`** 추가 필요 (현재 없음)
- **refType/refId**: `CONCIERGE_REQUEST` / conciergeRequestSeq
- **수신자**: 신청자 — payload: `applicantName, publicCode, quotedAmount, verificationPhrase, paynowUen, paynowAccountName, paynowReference, managerNote?, callScheduledAt?`
- **제거 레거시**: `sendConciergeQuoteEmail`
- **PayNow 변수**: `system_settings`(payment_paynow_uen / payment_paynow_name)에서 조회 — 설정 우선 원칙.

### T4. `LoaService.generateLoa` → A-34
- **eventType**: `CONCIERGE_LOA_SIGN_REQUIRED` (기존)
- **refType/refId**: `APPLICATION` / applicationSeq (LOA는 application 기준)
- **수신자**: 신청자 — payload: `applicantName, publicCode, managerName, loaSignUrl, shortUrl, expiresAtDisplay`
- **채널**: EMAIL+IN_APP (SMS는 T3)
- **✅ 결정 #C**: `generateLoa` 는 현재 **발송 없음**(코드 전수 확인) → **신규 발송 추가**. LOA 생성 직후 신청자에게 E+I.
  - 확인필요: `loaSignUrl`/`shortUrl`(서명 URL·단축URL) 생성 로직 존재 여부 — 없으면 그 생성도 본 작업에 포함.

### T5. `LoaService.uploadSignedByManager` → A-36
- **eventType**: `CONCIERGE_LOA_UPLOAD_CONFIRM` (기존)
- **위치**: `LoaService.java:317` `sendConciergeLoaUploadConfirmEmail`
- **수신자**: 신청자(E) — payload: `applicantName, managerName, publicCode, managerNote?, objectionDeadline`
- **제거 레거시**: `sendConciergeLoaUploadConfirmEmail`

### T6. `approveForPayment` → A-37 — **✅ 결정 #D: PR-W1로 이동(본 PR-W3 범위 밖)**
- **이유**: `AdminApplicationService.approveForPayment()`(339행)는 현재 일반/컨시어지 구분 없이 `sendPaymentRequestEmail`(A-17 레거시) 발송. A-17 배선과 같은 지점이라 **PR-W1에서 `viaConciergeRequestSeq != null` 분기로 A-37/A-17 동시 처리**.
- (참고) A-37 payload: `applicantName, publicCode, managerName, kvaLabel, amount, paynowUen, paynowReference, deadline, ctaUrl`, eventType `CONCIERGE_LICENCE_PAYMENT_REQUIRED`(기존 enum).

## §3. 부수 변경

### 3.1 NotificationType enum
- **추가**: `CONCIERGE_QUOTE_SENT` (A-33용)
- 나머지는 기존 값 재사용.

### 3.2 템플릿 활성화 (필수 ⚠️)
직전 작업에서 A-20 외 전부 비활성 처리됨. 배선되는 9개 코드의 모든 채널 row 를 `enabled=TRUE` 로:
```sql
-- A-37 은 PR-W1(결정 #D)에서 함께 enable. 본 PR-W3 대상 8개:
UPDATE notification_templates SET enabled = TRUE
WHERE template_code IN ('A-31','A-32','A-33','A-34','A-36','C-01','C-02','M-03');
```
- 단, 실제 발송 검증(test-send) 후 활성화 권장. 또는 배선 PR 머지 직후 일괄 enable.
- 적용은 `mysql --default-character-set=utf8mb4` 로 (인코딩 사고 재발 방지).

### 3.3 본문/카탈로그
- 9개 템플릿 본문은 dev DB 시드 완료(카피북 기반, mojibake 수정됨). enable 만 하면 됨.
- catalog.allowed_variables 와 payload 변수 set 정합 확인(lint L1). 불일치 시 카탈로그 갱신.

## §4. 변경 파일 목록 (예상)
| 파일 | 변경 |
|------|------|
| `domain/notification/NotificationType.java` | `CONCIERGE_QUOTE_SENT` 추가 |
| `api/concierge/ConciergeNotifier.java` | `ApplicationEventPublisher` 주입, notifySubmitted/notifyQuoteSent 이관, afterCommit 훅 제거 |
| `api/concierge/ConciergeLewAssignmentNotificationListener.java` | 배정 알림 이관(or dispatch 이벤트로 대체) |
| `api/loa/LoaService.java` | generateLoa(A-34)·uploadSignedByManager(A-36) 이관 |
| `api/admin/AdminApplicationService.java`(또는 상태리스너) | A-37 concierge 결제요청 분기 |
| `api/email/EmailService.java` | 컨시어지 5종 제거: `sendConciergeRequestReceivedEmail`, `sendConciergeRequestReceivedExistingUserEmail`, `sendConciergeStaffNewRequestEmail`, `sendConciergeQuoteEmail`, `sendConciergeLoaUploadConfirmEmail`. **LEW 배정 2종(`sendConciergeLewAssignedEmail`/`sendConciergeLewUnassignedEmail`)은 유지**(별도 카드). |
| DB | 9개 template_code enable |
| 테스트 | 트리거별 e2e(이벤트 발행 검증) + orchestrator 통합 |

## §5. 결정 확정 (2026-06-04)
- **#A ✅ 옵션1** — A-31 **단일 템플릿**. C2(기존 활성계정) 케이스는 `setupUrl`/`expiresAtDisplay` 를 **빈 값**으로 넘기고, 본문이 활성계정 연결 문구로 자연스럽게 읽히도록 카피 조정(조건부 문구). 신규 카드 추가 안 함.
- **#B ✅ 매니저 배정 시점 트리거** — **매니저(CONCIERGE_MANAGER) 권한자 전원 대상 자동배정** 모델. 알림은 **배정 시점**(`assignManagerTransition` / `cr.assignManager`)에 트리거. A-32 → 신청자, C-02 → 매니저(전원). LEW 배정 리스너는 불변.
- **#C ✅ 발송한다** — A-34(LOA 서명요청)를 **신규 발송**으로 추가. `generateLoa` 직후 신청자에게 E+I. `loaSignUrl`/`shortUrl`/`expiresAtDisplay` 소스(서명 URL 생성) 확인·필요 시 생성 포함.
- **#D ✅ PR-W1과 묶음** — A-37(컨시어지 결제요청)은 본 PR-W3 범위에서 제외하고 **A-17(일반 결제요청)과 함께 `approveForPayment` 에서 `viaConciergeRequestSeq` 분기로 PR-W1에서 배선**.

### 5.1 공통 리스크
- **이중발송**: 레거시 제거를 같은 PR에서 동시 처리(orchestrator idempotency 가 1차 가드지만 레거시는 별 경로라 안 막힘).
- **롤백 안전**: 발송은 AFTER_COMMIT — 기존 ConciergeNotifier 의 수동 afterCommit 와 의미 동일, 단 이벤트 발행은 트랜잭션 내부에서.
- **신규 발송 영향**: A-32/C-02·A-34 는 "지금까지 안 보내던 알림을 새로 보내기 시작" — 템플릿 enable 전 test-send 로 본문/변수 검증 후 활성화.

## §6. 권장 진행 순서
**PR-W3a (순수 이관, 저위험)**
1. `CONCIERGE_QUOTE_SENT` enum 추가 (A-33용)
2. T5(A-36, 가장 단순: 단일 수신자 E) 먼저 이관 → 패턴·테스트 검증
3. T3(A-33, 견적) 이관
4. T1(notifySubmitted → A-31/C-01/M-03, 3수신자) 이관 — 가장 영향 큼 (#A 옵션1: A-31 단일+조건부 문구)
5. 레거시 5종 제거 + 회귀
6. 대상 5코드(A-31/A-33/A-36/C-01/M-03) enable → dev test-send 검증

**PR-W3b (신규 발송)**
7. T2(매니저 배정 → A-32/C-02): `assignManagerTransition` 에 dispatch 추가
8. T4(A-34 LOA 서명요청): `loaSignUrl` 소스 확인 후 `generateLoa` 에 dispatch 추가
9. 대상 3코드(A-32/A-34/C-02) enable → test-send 검증

> A-37 은 PR-W1(결정 #D)에서 A-17 과 함께 처리.

## §7. 범위 밖 (후속 트랙)
- T2: A-38/41/42/43, C-04~07 (트리거 신규 or 미구현)
- T3: 스케줄러 필요(A-35/39, C-03/08), SMS/WhatsApp 채널
