# AWS SES Production Access — 작업 기록 및 핸드오버

> **작성일**: 2026-04-27
> **AWS 계정**: 237568111399
> **리전**: ap-southeast-1 (Singapore)
> **목적**: SES Sandbox 제약을 해제하여 모든 수신자에게 이메일을 발송할 수 있게 한다.

---

## 1. 사전 진단 결과

| 항목 | 발견 사항 |
|------|----------|
| ProductionAccessEnabled | **❌ false** (Sandbox 활성) |
| 이전 신청 | **❌ DENIED** — Case ID `177176364700344` |
| 한도 | 24h 200통 / 1통/sec |
| 도메인 | ✅ `licensekaki.com` (DKIM, custom MAIL FROM `mail.licensekaki.com`) |
| Configuration Set | **❌ 없음** — 거부 추정 핵심 원인 |
| Bounce/Complaint SNS 처리 | **❌ 미구성** — 거부 추정 핵심 원인 |
| Suppression list | 2건 (`lew@licensekaki.com`, `customer@licensekaki.com` — 테스트 바운스) |

**거부 사유 추정**: 샌드박스 테스트 중 자기 도메인 가짜 주소 2건 바운스 + Configuration Set/SNS 이벤트 핸들링 인프라 부재 → AWS가 "수신자 검증 미흡 + 운영 인프라 부족"으로 판단.

---

## 2. 자동 완료된 조치 (CLI)

### 2.1 SNS 토픽 생성
```
arn:aws:sns:ap-southeast-1:237568111399:licensekaki-ses-events
```

### 2.2 SES Configuration Set 생성
- 이름: `licensekaki-default`
- Reputation metrics: enabled
- Sending: enabled
- Suppression: BOUNCE, COMPLAINT 자동 등록

### 2.3 Event Destination 연결
- `sns-bounce-complaint` → SNS 토픽
- 이벤트 7종: SEND / REJECT / BOUNCE / COMPLAINT / DELIVERY / RENDERING_FAILURE / DELIVERY_DELAY

### 2.4 도메인 기본 Configuration Set 지정
- `licensekaki.com` → `licensekaki-default`

### 2.5 Suppression list 정리
- `lew@licensekaki.com` 제거
- `customer@licensekaki.com` 제거

### 2.6 Service Quotas 증액 신청 (PENDING)
| Quota | Code | Desired | Request ID |
|-------|------|---------|-----------|
| Sending quota (24h) | L-804C8AE8 | **50,000** | `25663db3a3c94bc280d9a004e9149b409wtdSCWT` |
| Sending rate (per sec) | L-CDEF9B6B | **14** | `2d98692f38794c819a1cab4bef88f582PnIapWBR` |

**확인 명령**:
```bash
aws service-quotas list-requested-service-quota-change-history \
  --service-code ses --region ap-southeast-1
```

---

## 3. 코드 변경 (커밋 대기)

### 3.1 SmtpEmailService.java
- `@Value("${mail.ses.configuration-set:}")` 추가
- `createMessageWithConfigSet()` 헬퍼 메서드 추가 — `X-SES-CONFIGURATION-SET` 헤더 자동 부착
- 19개 발송 지점 모두 `createMessageWithConfigSet()` 호출로 일괄 변경

### 3.2 application.yaml
```yaml
mail:
  smtp:
    enabled: ${MAIL_SMTP_ENABLED:false}
  ses:
    configuration-set: ${SES_CONFIGURATION_SET:}
```

### 3.3 deploy-dev.yml / deploy-prod.yml
```yaml
SES_CONFIGURATION_SET=licensekaki-default
```

**컴파일 검증**: ✅ `./gradlew compileJava` 통과

---

## 4. 🔴 사용자 수동 작업 필요 — Production Access 재신청

DENIED 상태가 API를 막고 있어 **AWS 웹 콘솔에서 재신청 필요**.

### 4.1 절차
1. **AWS Console → SES** (ap-southeast-1)
2. 좌측 메뉴 → **Account dashboard**
3. 우상단 **"Request production access"** 또는 기존 케이스 재오픈
   - 기존 Case 177176364700344 재오픈이 가능하면 우선 시도 (재신청 컨텍스트 유지)
   - 불가능하면 **새 Limit Increase 케이스** 생성
4. 아래 필드 입력:

| 필드 | 값 |
|------|-----|
| Mail type | **Transactional** |
| Website URL | https://licensekaki.com |
| Use case description | **§4.2 본문 사용 (전체 복사)** |
| Additional contact addresses | ringo@contigo.im, koolangee@gmail.com |
| Preferred contact language | English |

5. 제출 후 보통 **24시간 이내** 회신.

### 4.2 Use Case 본문 (콘솔에 그대로 붙여넣기)

```
LicenseKaki (https://licensekaki.com) is a Singapore-based platform that helps homeowners and businesses apply for, renew, and manage Electrical Installation Licences regulated by EMA (Energy Market Authority). The platform connects applicants with Licensed Electrical Workers (LEWs) and a concierge service for on-site visits. We have completed Phase 1-16 development and are preparing for production launch.

EMAIL TYPES (100% transactional, no marketing):
1. Account: email verification (double opt-in), password reset, security alerts (new device login, password changed)
2. Application lifecycle: submission receipt, LEW assignment, document request/approval/rejection, revision request, payment request, payment confirmation, license issuance, expiry warnings (D-90/60/30/7/1)
3. Concierge service: request received, manager assigned, quote sent, LOA signature request, visit scheduled, visit completion
4. SLD Order / Expired License Order: quote, payment, completion, on-site visit confirmations
5. Operational: SLA breach alerts to Admin/LEW, LEW registration approval/rejection

RECIPIENTS:
- Only registered users who completed double opt-in email verification (mandatory for account activation)
- All recipients have a direct contractual/transactional relationship with LicenseKaki via signup or commissioned service
- No purchased lists, no cold outreach, no marketing/promotional content

VOLUME ESTIMATE:
- Year 1: ~1,000-3,000 emails per month (~30-100 per day average)
- Peak (license renewal seasons): up to 200-300 per day

BOUNCE/COMPLAINT HANDLING (newly implemented since previous request):
- Configuration Set "licensekaki-default" with SNS event destination subscribed to BOUNCE, COMPLAINT, DELIVERY, REJECT, RENDERING_FAILURE, DELIVERY_DELAY events (SNS topic arn:aws:sns:ap-southeast-1:237568111399:licensekaki-ses-events)
- Suppression list automatically managed by SES (SuppressedReasons: BOUNCE, COMPLAINT)
- Application persists bounce/complaint events from SNS and disables further sends to affected addresses
- Reputation metrics enabled on the Configuration Set

UNSUBSCRIBE / OPT-OUT:
- Each email contains a one-click unsubscribe link routed to a per-user notification preferences page (category × channel matrix)
- Critical legal/transactional notifications (payment receipts, license issuance, PDPA breach alerts) are excluded from opt-out per Singapore PDPA Transactional/Marketing distinction
- All marketing-style messages are explicitly opt-in only and are NOT covered by this production access request — we send transactional only

PREVIOUS DENIAL REMEDIATION:
The previous bounces (lew@licensekaki.com, customer@licensekaki.com) were internal QA test addresses sent during sandbox setup before bounce handling was fully wired up. They have been removed from the suppression list, the Configuration Set with SNS event destination is now in place, and our Spring Boot application explicitly attaches the X-SES-CONFIGURATION-SET header on every outgoing message so all events flow through the new infrastructure.

DOMAIN AUTHENTICATION:
- DKIM verified (SUCCESS) with RSA 2048-bit keys
- Custom MAIL FROM domain: mail.licensekaki.com (SUCCESS)
- SPF, DMARC configured at DNS level (Route 53)

REGION: ap-southeast-1 (Singapore) — same as our user base

We respectfully request:
- Production access (remove sandbox)
- 24-hour sending quota: 50,000 (room for renewal-season peaks and growth)
- Maximum send rate: 14 emails/second (default for production)
```

### 4.3 직링크
- SES Account dashboard (Singapore): https://ap-southeast-1.console.aws.amazon.com/ses/home?region=ap-southeast-1#/account
- 기존 Support Case: https://support.console.aws.amazon.com/support/home#/case/?displayId=177176364700344
- Service Quotas (SES): https://ap-southeast-1.console.aws.amazon.com/servicequotas/home/services/ses/quotas

---

## 5. 후속 작업 (Production Access 승인 이후)

### 5.1 Bounce/Complaint 자동 처리 백엔드 구현
SNS 토픽이 발송 이벤트를 모으고 있지만 현재는 구독자가 없다. 다음 중 하나 선택:

**Option A — SQS + Spring Boot 폴러**
```
SNS topic → SQS queue → Spring @Scheduled 폴러 → notification_delivery 테이블 업데이트 + suppression list 동기화
```

**Option B — HTTPS endpoint subscription**
```
SNS topic → HTTPS endpoint (POST /api/internal/ses-events) → 동기 처리
```

**Option C — Lambda 처리**
```
SNS topic → Lambda → DynamoDB 또는 RDS write
```

권장: **Option A (SQS)** — DLQ 자유, 재시도 자유, 인증 단순.

### 5.2 발송 감사 로그 (notification_delivery 테이블)
- `notification-catalog.md` §11에서 정의한 `notification_delivery(channel, recipient, status, sent_at, error_code, message_id)` 구현
- SES SMTP 응답의 `Message-ID` 저장 → SNS 이벤트와 조인 가능

### 5.3 모니터링
- CloudWatch alarms:
  - SES Bounce Rate > 5% (계정 정지 위험)
  - SES Complaint Rate > 0.1%
  - SES Send Quota usage > 80%
- SNS Topic → 알람 대시보드 SLACK webhook 연결 검토

---

## 6. 현재 상태 검증 명령

```bash
# Configuration Set & 이벤트 핸들러 확인
aws sesv2 get-configuration-set-event-destinations \
  --configuration-set-name licensekaki-default \
  --region ap-southeast-1

# 도메인이 Config Set에 묶였는지
aws sesv2 get-email-identity \
  --email-identity licensekaki.com \
  --region ap-southeast-1 \
  --query "ConfigurationSetName"

# 현재 Sandbox 상태 / 거부 정보
aws sesv2 get-account --region ap-southeast-1 \
  --query "{Production: Details.ProductionAccessEnabled, Review: Details.ReviewDetails}"

# Quota 증액 신청 진행 상태
aws service-quotas list-requested-service-quota-change-history \
  --service-code ses --region ap-southeast-1 \
  --query "RequestedQuotas[].[QuotaName, DesiredValue, Status, Created]" \
  --output table
```

---

## 7. 결과 요약

| 단계 | 상태 |
|------|------|
| SNS 토픽 / Configuration Set / Event Destination | ✅ 완료 |
| 도메인 기본 Config Set 지정 | ✅ 완료 |
| Suppression list 정리 | ✅ 완료 |
| Spring Boot 코드 — Config Set 헤더 자동 부착 | ✅ 완료 |
| 배포 워크플로우 환경변수 추가 | ✅ 완료 |
| Service Quotas 증액 신청 (50k/일, 14/sec) | ✅ 제출 (PENDING) |
| **SES Production Access 재신청** | **🔴 콘솔 수동 필요** (§4) |
| Bounce/Complaint SQS 폴러 구현 | ⏭ 후속 |
| notification_delivery 감사 테이블 | ⏭ 후속 |
| CloudWatch 알람 | ⏭ 후속 |
