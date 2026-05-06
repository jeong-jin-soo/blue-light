# Concierge 흐름 강화 + 별도 수금(Offline Payment) + 영수증 자동 발행 — 정식 스펙

작성일: 2026-05-01
작성자: product-manager 에이전트
관련 도메인: Concierge / Payment / Invoice / User(Role)
선행 PR: 4-PR LEW 검토 동선 시리즈(`16493ba`/`fcce9d1`/`3e81d0e`/`978500e`), Concierge Phase 1
관련 메모리: `memory/lew-review-flow-roadmap.md`, `doc/Project Analysis/kaki-concierge-service-prd.md` (있을 시)

---

## §1. 요구사항 요약

신청자(또는 제3자)가 컨시어지 폼으로 간단한 요청을 제출하면, **CONCIERGE_MANAGER가 LEW(본인 또는 타 LEW)에게 할당**하고, **매니저/LEW가 신청자를 대리하여 신청서를 작성·제출**한다. 결제는 **기존 LEW의 `request-payment` 게이트와 무관하게** ADMIN/CONCIERGE_MANAGER가 외부 채널(은행 송금 / PayNow 오프라인 / 현금 등)로 수금한 결과를 시스템에 직접 결제 확정으로 입력할 수 있어야 하며(=일반 신청에도 동일 적용), **결제 확정 즉시 영수증이 자동 발행되어 신청자에게 PDF 첨부 이메일로 전달**된다. 컨시어지 신청자에게는 추가 입력을 요구하지 않고(JIT 위배 금지) 이미 받은 스냅샷을 사용한다.

---

## §2. 범위 / 비범위

### 2.1 범위 (In Scope)
1. **컨시어지 LEW 할당 단계 신설**: `ConciergeRequest.assignedLewSeq` + `LEW_ASSIGNED` 상태 + assign-lew 엔드포인트
2. **셀프 할당 지원**: 매니저가 동시에 LEW 권한도 가진 경우 본인에게 할당. 단일 enum 보존(옵션 C)으로 User 엔티티 손대지 않음 — 자세한 비교는 §4.
3. **신청서 대행 작성 권한 확장**: 기존 `createApplicationOnBehalf`를 **assigned LEW까지** 호출 가능하게 확장
4. **별도 수금(Offline Payment) 도메인**: `Payment.paymentMethod` 컬럼 enum 확장 + ADMIN/CONCIERGE_MANAGER 전용 manual-payment 엔드포인트 (Application용 + Concierge용)
5. **결제 게이트 우회**: LEW의 `request-payment` 호출 없이도 ADMIN이 직접 결제 확정 가능
6. **영수증 자동 발행 트리거 확장**: `InvoiceGenerationService.generateFromPayment()`를 manual-payment 후크에서도 호출 + Concierge referenceType 분기
7. **영수증 PDF 자동 이메일 발송**: AFTER_COMMIT 패턴, 발송 실패 시 결제 확정 유지 + 재발송 알림

### 2.2 비범위 (Out of Scope)
- 외부 PG(Stripe, NETS) 자동 정산 통합
- 회계 시스템(Xero, QuickBooks) 자동 동기화
- 환불·취소 워크플로우(별도 스펙으로 분리)
- 모바일 앱 UI
- 다중 역할의 정규화된 1:N user_roles 테이블 도입(D1=B/C에서 다룸)
- 컨시어지 폼 자체의 마케팅·랜딩 페이지 개편

---

## §3. 사용자 시나리오 (S1~S7)

### S1. 컨시어지 폼 제출 → 매니저 수신 → 신청자 통화 → 견적
*(기존 Phase 1 흐름 — 변경 없음, 베이스라인으로 명시)*
1. 신청자가 `/api/public/concierge/request`에 이름·이메일·전화·메모·동의 5종 제출
2. 시스템이 `ConciergeRequest` 생성(status=SUBMITTED, applicantUser 자동 가입)
3. CONCIERGE_MANAGER 대시보드에서 ASSIGNED → CONTACTING → QUOTE_SENT 진행

### S2. 매니저 본인을 LEW로 셀프 할당
1. 매니저 A는 CONCIERGE_MANAGER 역할 + (옵션 C에서는 검증 정책상) 본인이 LEW Grade 보유 사실을 시스템이 신뢰 (D1=C 기준 — 옵션 A/B에서는 user.secondaryRoles 또는 user_roles 테이블에서 LEW 역할 보유 확인)
2. 매니저 A가 ConciergeRequest 상세에서 "LEW 할당" → 본인 selectbox 선택
3. `POST /api/concierge-manager/requests/{id}/assign-lew` body=`{ lewUserSeq: A.userSeq, selfAssign: true }`
4. 시스템이 ConciergeRequest.assignedLewSeq = A.userSeq, status=`LEW_ASSIGNED`, lewAssignedAt=now
5. AuditLog `CONCIERGE_LEW_ASSIGNED` 기록(`selfAssign=true`)
6. 알림: 셀프 할당이므로 LEW 측 알림은 음소거(중복 방지), 단 ADMIN 모니터링용으로 audit 표시

### S3. 매니저가 다른 LEW에게 할당
1. 매니저 A가 LEW B(role=LEW, status=APPROVED)를 selectbox로 선택
2. assign-lew 호출 → ConciergeRequest 상태 LEW_ASSIGNED, assignedLewSeq=B
3. 알림: LEW B에게 인앱 + 이메일 (`CONCIERGE_LEW_ASSIGNED_LEW`)
4. LEW B가 로그인하면 본인 대시보드에 "컨시어지 배정" 카드가 보임

### S4. 매니저/LEW가 신청서 대행 작성 → 신청 발송
1. 할당된 LEW B가 신청자에게 전화 또는 방문하여 정보 수집
2. LEW B가 ConciergeRequest 상세에서 "대행 신청서 작성" 클릭
3. `POST /api/concierge-manager/requests/{id}/applications` body=`CreateApplicationRequest` (기존 컨트롤러 재사용. 권한 가드를 `MANAGER` → `MANAGER OR ASSIGNED_LEW`로 확장)
4. ApplicationService.createOnBehalfOf 호출 → Application 생성(owner=원 신청자, viaConciergeRequestSeq=cr.seq, createdBy=LEW B.userSeq)
5. ConciergeRequest 자동 전이 → APPLICATION_CREATED, applicationSeq 연결
6. AuditLog `APPLICATION_CREATED_ON_BEHALF` (actor=LEW B)

### S5. 매니저/ADMIN이 외부 채널 수금 → 결제 확정 입력 → 영수증 자동 발행 → 이메일
1. 신청자가 PayNow QR로 매니저 회사계좌에 송금(또는 은행이체) 완료
2. 매니저가 ConciergeRequest 상세 또는 Application 상세에서 "수동 결제 확정" 모달 오픈
3. 입력: 금액, 결제일, 결제채널 enum(BANK_TRANSFER / PAYNOW_OFFLINE / CASH / OTHER), 참조번호(메모), 영수증 발행 여부(default=true)
4. `POST /api/concierge-manager/requests/{id}/manual-payment` (또는 `/api/admin/applications/{id}/manual-payment`)
5. 시스템 처리:
   - Payment 생성(status=SUCCESS, paymentMethod=enum, recordedByUserSeq=actor, recordedAt=now, referenceType=CONCIERGE_REQUEST 또는 APPLICATION)
   - ConciergeRequest.linkPayment(paymentSeq) 호출 → 상태 전이(QUOTE_SENT/LEW_ASSIGNED → APPLICATION_CREATED → AWAITING_LICENCE_PAYMENT 우회 → IN_PROGRESS, 또는 PR-2에서 결정)
   - Application.markAsPaid() 호출(referenceType=APPLICATION일 때) → Application status=PAID
   - **AFTER_COMMIT** 훅에서 InvoiceGenerationService.generateFromPayment() 호출
   - **AFTER_COMMIT** 훅에서 Invoice PDF 첨부 이메일 발송 → 신청자
6. AuditLog: `MANUAL_PAYMENT_RECORDED`, `INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT`

### S6. 일반 신청(컨시어지 아님)에서도 ADMIN이 별도 수금 → 결제 확정
1. ADMIN이 Application 상세에서 "수동 결제 확정" 클릭 (LEW의 request-payment 호출 없이도 가능, D3=C 정책)
2. `POST /api/admin/applications/{id}/manual-payment`
3. 동일 처리 — Payment 생성 + Invoice 자동 발행 + 이메일

### S7. LEW가 결제 요청 안 한 상태에서 ADMIN이 결제 확정 → 영수증
- D3=C: ADMIN은 Application의 어떤 상태에서도 manual-payment 호출 가능(상태 전이 가드는 도메인 메서드가 처리)
- 단 이미 PENDING_PAYMENT/PAID/IN_PROGRESS면 중복 차단(409) — AC-A3

---

## §4. 도메인 변경 — 다중 역할 모델

### 4.1 옵션 비교

| 옵션 | 변경 내용 | 장점 | 단점 |
|------|-----------|------|------|
| **A** | `User.secondaryRoles: Set<UserRole>` 컬렉션 컬럼 (JSON 또는 별도 join 컬럼 1개 + 코드 split) | User 엔티티만 수정. 기존 단일 `role` 보존. | JSON 파싱 의존, 인덱스 어렵, 권한 평가 시 매번 두 필드 OR 조회 |
| **B** | `user_roles` 1:N 정규화 테이블 (user_seq, role) | 정공법, 향후 권한 매트릭스 확장 용이 | 광범위 마이그레이션 — Spring Security UserDetails, JWT claims, 모든 `@PreAuthorize`, 시드 데이터 영향. 본 스펙 기간 내 위험 |
| **C** | User 엔티티 무변경. ConciergeRequest에 `assignedLewSeq` 컬럼만 추가하고, "할당 가능한 LEW"는 `user.role = LEW` 인 사용자로 한정. **셀프 할당은 매니저가 LEW seq를 선택하는 행위로 간주(매니저가 LEW Grade를 가지고 있다면 본인 LEW 계정으로 별도 user 생성/연결되어 있어야 함)** | 작업량 최소, 영향 범위 최소 | "한 사람이 두 역할을 동시에 가지는" 케이스를 단일 user 레코드로 표현 못 함. 셀프 할당 의미가 "내가 가진 별개 LEW 계정에 할당"이 됨 |

### 4.2 추천: 옵션 C
- 본 스펙에서는 옵션 C 채택을 권장하되, **D1**로 명시하여 검토자 확정 필요.
- 옵션 A로 가는 경우 §5 assign-lew 검증 로직이 `target.role==LEW || target.secondaryRoles.contains(LEW)`로 수정되며, JWT 발급 시점에 secondaryRoles까지 claim에 포함시켜야 함(별도 PR로 분리).
- 옵션 B는 본 스펙 범위 외(별도 로드맵).

---

## §5. 컨시어지 LEW 할당

### 5.1 데이터 모델 변경
```sql
-- DatabaseMigrationRunner ALTER (idempotent)
ALTER TABLE concierge_requests
  ADD COLUMN IF NOT EXISTS assigned_lew_seq BIGINT NULL,
  ADD COLUMN IF NOT EXISTS lew_assigned_at DATETIME(6) NULL,
  ADD CONSTRAINT IF NOT EXISTS fk_concierge_assigned_lew
    FOREIGN KEY (assigned_lew_seq) REFERENCES users(user_seq);

CREATE INDEX IF NOT EXISTS idx_concierge_assigned_lew
  ON concierge_requests (assigned_lew_seq, status);
```

### 5.2 ConciergeRequestStatus enum 확장
- 신규 값: `LEW_ASSIGNED`
- 전이표 추가:
  - `CONTACTING → LEW_ASSIGNED`
  - `QUOTE_SENT → LEW_ASSIGNED` (견적 후 LEW 할당)
  - `LEW_ASSIGNED → LEW_ASSIGNED` (재할당, idempotent)
  - `LEW_ASSIGNED → APPLICATION_CREATED` (LEW가 신청서 작성 완료)
  - `LEW_ASSIGNED → CANCELLED`

### 5.3 신규 엔드포인트
```
POST /api/concierge-manager/requests/{id}/assign-lew
권한: CONCIERGE_MANAGER, ADMIN, SYSTEM_ADMIN
Request:
  {
    "lewUserSeq": 123,
    "selfAssign": false   // optional, audit용
  }
Response: ConciergeRequestDetail (assignedLewSeq, assignedLewName, lewAssignedAt 포함)
```

### 5.4 검증 규칙
1. lewUserSeq의 user.role == LEW (옵션 C) **또는** user.role == CONCIERGE_MANAGER 인 동시에 옵션 A의 secondaryRoles에 LEW 포함 (D1=A)
2. 대상 LEW의 user.status == APPROVED
3. 호출자가 ConciergeOwnershipValidator.assertManagerCanAccess 통과
4. selfAssign=true일 때: lewUserSeq == actor.userSeq 또는 actor 본인의 별도 LEW 계정 seq (옵션 C)
5. 재할당 허용 — 이전 LEW에게는 알림 `CONCIERGE_LEW_UNASSIGNED_LEW`

### 5.5 도메인 메서드 추가
```java
// ConciergeRequest.java
public void assignLew(User lew, boolean selfAssign) {
    if (lew == null) throw new IllegalArgumentException("LEW required");
    transitionTo(ConciergeRequestStatus.LEW_ASSIGNED);
    this.assignedLewSeq = lew.getUserSeq();
    this.lewAssignedAt = LocalDateTime.now();
}
```

### 5.6 ConciergeRequestDetail DTO 확장
- 추가 필드: `assignedLewSeq`, `assignedLewName`, `assignedLewEmail`, `lewAssignedAt`, `selfAssigned`(audit row 기반 derived)

---

## §6. 신청서 대행 작성 권한 확장

### 6.1 현재 상태
- `ConciergeManagerService.createApplicationOnBehalf`는 CONCIERGE_MANAGER/ADMIN/SYSTEM_ADMIN만 호출 가능 (Controller `@PreAuthorize`)
- LEW는 호출 불가

### 6.2 변경
- ConciergeManagerController `@PreAuthorize`에 LEW 추가 → `hasAnyRole('CONCIERGE_MANAGER', 'ADMIN', 'SYSTEM_ADMIN', 'LEW')`
- 단, LEW의 경우 추가 가드: **assignedLewSeq == actor.userSeq**일 때만 허용
- `ConciergeOwnershipValidator`에 `assertLewCanAccess(cr, actor)` 신설
  - actor.role==LEW이면 cr.assignedLewSeq == actor.userSeq 검증
  - 매니저/ADMIN은 기존 로직 유지
- 호출 가능 상태(D7=B 정책): CONTACTING / QUOTE_SENT / **LEW_ASSIGNED**

### 6.3 audit
- 기존 `APPLICATION_CREATED_ON_BEHALF`에 actor.role 정보 포함 (이미 들어감) — actor.role=LEW 케이스 audit 검증 테스트만 추가

---

## §7. 별도 수금 (Offline Payment) 도메인

### 7.1 데이터 모델 — D2 결정 분기
- **옵션 A**: `ManualPaymentRecord` 별도 엔티티 (회계상 결제와 분리)
  - 단점: 영수증 자동 발행이 Payment에서만 일어나는 기존 로직과 단절. 듀얼-라이트 가능성. 회계상 결제 통합 조회 어려움.
- **옵션 B (추천)**: `Payment` 엔티티에 `paymentMethod` 컬럼 enum 확장 + `recordedByUserSeq`/`recordedAt` 컬럼 추가
  - 장점: 기존 InvoiceGenerationService.generateFromPayment 그대로 재사용. 결제 통합 조회. AdminPaymentService 패턴 일관성.
  - 단점: 기존 row 백필 필요 (`paymentMethod=PAYNOW_ONLINE` default).

### 7.2 Payment 엔티티 변경 (옵션 B 채택 가정)
```sql
ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS payment_method_enum VARCHAR(30) NULL,
  ADD COLUMN IF NOT EXISTS recorded_by_user_seq BIGINT NULL,
  ADD COLUMN IF NOT EXISTS recorded_at DATETIME(6) NULL,
  ADD COLUMN IF NOT EXISTS reference_note VARCHAR(500) NULL;

UPDATE payments SET payment_method_enum = 'PAYNOW_ONLINE'
  WHERE payment_method_enum IS NULL AND deleted_at IS NULL;

-- 기존 payment_method (VARCHAR(20)) 컬럼은 legacy "CARD"/"PAYNOW" 문자열 보존 → DEPRECATED, 새 컬럼이 정본
```

신규 enum:
```java
public enum PaymentMethod {
    PAYNOW_ONLINE,    // 신청자가 사이트 내에서 PayNow 결제 후 confirmPayment
    BANK_TRANSFER,    // 은행 송금 (수동)
    PAYNOW_OFFLINE,   // 매니저가 받은 PayNow QR 송금 (수동)
    CASH,             // 현금 수금
    OTHER             // 기타 (audit reason 필수)
}
```

설정 우선 원칙 적용: enum 자체는 코드에 고정(법적 카테고리 분류). UI 표시 라벨·정렬 순서는 `system_settings.payment_method_labels_json`에서 로드.

### 7.3 신규 엔드포인트
#### Application용 (ADMIN 전용)
```
POST /api/admin/applications/{id}/manual-payment
권한: ADMIN, SYSTEM_ADMIN
Request:
  {
    "amount": 350.00,            // BigDecimal, > 0
    "paidAt": "2026-05-01T14:30:00",  // 결제일시 (선택, default=now)
    "paymentMethod": "BANK_TRANSFER",
    "referenceNote": "DBS 송금 ref 12345",
    "issueReceipt": true         // 기본 true
  }
Response:
  {
    "paymentSeq": 555,
    "applicationSeq": 100,
    "applicationStatus": "PAID",
    "invoiceSeq": 999,            // issueReceipt=true면 즉시
    "invoiceNumber": "INV-2026-0123",
    "receiptEmailScheduled": true
  }
```

#### Concierge용 (CONCIERGE_MANAGER + ADMIN)
```
POST /api/concierge-manager/requests/{id}/manual-payment
권한: CONCIERGE_MANAGER, ADMIN, SYSTEM_ADMIN
Request: 위와 동일
Response: ConciergeRequestDetail (paymentSeq + invoiceSeq 포함)
```

### 7.4 결제 게이트와의 관계
- 기존: LEW가 `request-payment` 호출 → Application status=PENDING_PAYMENT → applicant 결제 → AdminPaymentService.confirmPayment
- 신규: ADMIN/MANAGER가 `manual-payment` 호출 시 위 단계 모두 우회 가능 (D3=C 정책)
- LEW가 request-payment 호출하지 않은 케이스: ADMIN은 어떤 상태에서도 호출 가능, MANAGER는 Concierge 흐름에서 LEW_ASSIGNED 이상부터 가능 (Concierge는 LEW의 request-payment를 사용하지 않으므로)

---

## §8. 영수증 자동 발행 트리거 확장

### 8.1 현재 상태
- `InvoiceGenerationService.generateFromPayment(Payment, Application)` 시그니처가 Application을 강제 요구
- 호출처: `AdminPaymentService.confirmPayment` (PAYNOW_ONLINE 결제 확정 시)
- Concierge referenceType은 enum에 존재하지만 호출처 없음

### 8.2 변경
1. **시그니처 오버로드**:
   ```java
   public Invoice generateFromPayment(Payment payment, Application application);  // 기존
   public Invoice generateFromConciergePayment(Payment payment, ConciergeRequest cr);  // 신규
   ```
   또는 폴리모픽 단일 메서드:
   ```java
   public Invoice generateFromPayment(Payment payment, InvoiceContext ctx);
   // ctx = ApplicationContext(application) | ConciergeContext(cr)
   ```
   추천: **두 번째 방식(폴리모픽)** — 미래 SLD_ORDER 결제 확장 용이

2. **Concierge 스냅샷 매핑**:
   - billingRecipientName ← cr.submitterName
   - billingAddress ← null 허용 (컨시어지 폼에는 주소 필드 없음. settings에서 default fallback 또는 빈 라인)
   - description ← `"Concierge service fee — request " + cr.publicCode`
   - installationName/Address ← null
   - applicationSeq ← cr.applicationSeq (있으면) 또는 null
   - referenceType ← "CONCIERGE_REQUEST"

3. **호출 위치**:
   | 호출처 | 시점 | 컨텍스트 |
   |--------|------|----------|
   | `AdminPaymentService.confirmPayment` | 기존 (PAYNOW_ONLINE) | ApplicationContext |
   | `ManualPaymentService.recordOfflinePayment` (신규, Application용) | manual-payment | ApplicationContext |
   | `ConciergeManualPaymentService.recordOfflinePayment` (신규) | manual-payment | ConciergeContext |

### 8.3 영수증 PDF 이메일 발송
- 발송 메서드: `EmailService.sendInvoicePdfEmail(toEmail, recipientName, invoiceNumber, pdfBytes)`
- 호출 시점: **AFTER_COMMIT** 트랜잭션 동기화
- 발송 실패 시: 결제 + 영수증 발행은 유지(D5=B), 알림 `INVOICE_EMAIL_DELIVERY_FAILED_ADMIN` 발송 + 수동 재발송 엔드포인트 (`POST /api/admin/invoices/{id}/resend-email`, PR-5)

### 8.4 LEW request-payment 미호출 케이스
- ADMIN의 manual-payment 호출 → Payment 생성 + Application status=PAID (skip PENDING_PAYMENT)
- Application.markAsPaid()가 PENDING_PAYMENT 외 상태에서도 호출 가능하도록 도메인 메서드 가드 완화 검토 (또는 ADMIN 전용 `forceMarkAsPaid()`)
- D3=C에 따라 ADMIN은 PENDING_REVIEW부터 호출 가능

---

## §9. 데이터 모델 종합

### 9.1 신규 / 변경 컬럼
| 테이블 | 컬럼 | 타입 | 비고 |
|--------|------|------|------|
| payments | payment_method_enum | VARCHAR(30) | enum: PAYNOW_ONLINE/BANK_TRANSFER/PAYNOW_OFFLINE/CASH/OTHER |
| payments | recorded_by_user_seq | BIGINT | manual-payment 호출자 user_seq |
| payments | recorded_at | DATETIME(6) | 시스템 입력 시점 |
| payments | reference_note | VARCHAR(500) | 송금 참조번호 등 |
| concierge_requests | assigned_lew_seq | BIGINT FK→users | LEW 할당 |
| concierge_requests | lew_assigned_at | DATETIME(6) | 할당 시점 |

### 9.2 신규 enum 값
- `ConciergeRequestStatus.LEW_ASSIGNED`
- `PaymentMethod` (신규 enum 클래스)
- `AuditAction`: `MANUAL_PAYMENT_RECORDED`, `CONCIERGE_LEW_ASSIGNED`, `CONCIERGE_LEW_UNASSIGNED`, `INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT`, `INVOICE_EMAIL_DELIVERY_FAILED`, `INVOICE_EMAIL_RESENT`
- `NotificationType`: `MANUAL_PAYMENT_CONFIRMED_APPLICANT`, `INVOICE_ISSUED_APPLICANT`, `CONCIERGE_LEW_ASSIGNED_LEW`, `CONCIERGE_LEW_UNASSIGNED_LEW`, `INVOICE_EMAIL_DELIVERY_FAILED_ADMIN`

### 9.3 system_settings (설정 우선 원칙 준수)
- `payment_method_labels_json`: enum별 표시 라벨 (영문/한글 다국어)
- `invoice_email_subject_template`: `"Receipt for {invoiceNumber} — LicenseKaki"`
- `invoice_email_body_template_html_seq`: 템플릿 파일 file_seq
- `invoice_company_name`, `invoice_paynow_uen` 등은 기존 활용

---

## §10. Given-When-Then 수용 기준

### 10.1 Manual Payment (Application)
- **AC-A1**: GIVEN Application status=PENDING_PAYMENT, WHEN ADMIN이 manual-payment(amount=350, BANK_TRANSFER) 호출, THEN 200 + Payment row(method=BANK_TRANSFER, recordedByUserSeq=admin) + Invoice 자동 발행 + Application status=PAID 전이 + 신청자에게 영수증 PDF 이메일 발송 큐에 등록
- **AC-A2**: GIVEN Application status=PENDING_REVIEW, WHEN ADMIN이 manual-payment 호출, THEN D3=C에 따라 200 (모든 상태에서 ADMIN 호출 가능). MANAGER가 같은 상황에서 호출 시 409 (MANAGER는 Concierge 흐름에서만 가능, Application 직접 호출 불가)
- **AC-A3**: GIVEN Application status=PAID(이미 결제 완료), WHEN ADMIN이 manual-payment 호출, THEN 409 `ALREADY_PAID` (중복 결제 방지)
- **AC-A4**: GIVEN ConciergeRequest status=QUOTE_SENT, WHEN MANAGER가 concierge manual-payment 호출, THEN 200 + Payment(referenceType=CONCIERGE_REQUEST) + Invoice 자동 발행(referenceType=CONCIERGE_REQUEST) + 신청자 이메일 발송 + ConciergeRequest 결제 단계 전이
- **AC-A5**: GIVEN Application status=PENDING_PAYMENT, WHEN ADMIN이 manual-payment(issueReceipt=false) 호출, THEN 200 + Payment 생성 + **Invoice 미발행** + 이메일 미발송 (회계 보정·내부 정산용)
- **AC-A6**: GIVEN amount=0, WHEN manual-payment 호출, THEN 400 `INVALID_AMOUNT`
- **AC-A7**: GIVEN amount(=400)가 ConciergeRequest.quotedAmount(=350)와 다름, WHEN MANAGER가 manual-payment 호출, THEN D4=B에 따라 200 + audit에 `quotedAmount=350, paidAmount=400, diff=+50` 기록

### 10.2 LEW 할당
- **AC-L1**: GIVEN ConciergeRequest status=QUOTE_SENT, WHEN MANAGER가 assign-lew(lewUserSeq=B) 호출, THEN 200 + assignedLewSeq=B + lewAssignedAt=now + status=LEW_ASSIGNED + LEW B에게 인앱+이메일 알림
- **AC-L2**: GIVEN MANAGER A가 옵션 C에서 본인의 별도 LEW 계정(seq=A')을 보유, WHEN A가 assign-lew(lewUserSeq=A', selfAssign=true) 호출, THEN 200 + audit `selfAssign=true` + LEW 측 알림은 음소거
- **AC-L3**: GIVEN lewUserSeq의 user.role != LEW (옵션 C) 또는 secondaryRoles에 LEW 없음(옵션 A), WHEN assign-lew 호출, THEN 400 `INVALID_LEW`
- **AC-L4**: GIVEN ConciergeRequest.assignedLewSeq=B(이미 할당), WHEN MANAGER가 assign-lew(lewUserSeq=C) 재호출, THEN 200 + assignedLewSeq=C로 갱신 + LEW B에게 `CONCIERGE_LEW_UNASSIGNED_LEW` 알림 + LEW C에게 신규 할당 알림
- **AC-L5**: GIVEN target LEW.status=PENDING(미승인), WHEN assign-lew 호출, THEN 400 `LEW_NOT_APPROVED`

### 10.3 신청서 대행 (LEW 권한 확장)
- **AC-D1**: GIVEN ConciergeRequest.assignedLewSeq=B(LEW B 할당됨), status=LEW_ASSIGNED, WHEN LEW B가 createApplicationOnBehalf 호출, THEN 200 + Application 생성(owner=원 신청자, createdBy=B) + ConciergeRequest 자동 전이 APPLICATION_CREATED
- **AC-D2**: GIVEN ConciergeRequest.assignedLewSeq=B, WHEN 다른 LEW C가 createApplicationOnBehalf 호출, THEN 403 `FORBIDDEN`
- **AC-D3**: GIVEN ConciergeRequest.assignedLewSeq=B, WHEN MANAGER가 createApplicationOnBehalf 호출, THEN 200 (기존 동작 보존, MANAGER는 항상 가능)
- **AC-D4**: GIVEN ConciergeRequest.assignedLewSeq=null (LEW 미할당), WHEN LEW가 호출, THEN 403 `LEW_NOT_ASSIGNED`

### 10.4 영수증 이메일 발송
- **AC-R1**: GIVEN manual-payment 정상 처리, WHEN AFTER_COMMIT 훅 실행, THEN EmailService.sendInvoicePdfEmail 호출 + 첨부 PDF=Invoice.pdfFileSeq에서 로드
- **AC-R2**: GIVEN 이메일 발송 실패(SMTP 오류), WHEN AFTER_COMMIT 훅 실행, THEN D5=B에 따라 결제+영수증은 유지 + audit `INVOICE_EMAIL_DELIVERY_FAILED` 기록 + ADMIN에게 알림
- **AC-R3**: GIVEN 발송 실패 후 ADMIN이 resend-email 호출, THEN 200 + 재발송 시도 + audit `INVOICE_EMAIL_RESENT`

### 10.5 권한 가드
- **AC-P1**: APPLICANT가 manual-payment 호출, THEN 403
- **AC-P2**: 다른 매니저가 본인 배정이 아닌 ConciergeRequest의 manual-payment 호출, THEN 403 (ADMIN만 전체 접근 가능)

---

## §11. 보안 · PDPA

1. **Manual Payment 권한**: ADMIN/SYSTEM_ADMIN은 Application·Concierge 전부, CONCIERGE_MANAGER는 자기 배정 ConciergeRequest만, LEW는 호출 불가 (결제 확정은 LEW 권한 외)
2. **Audit 영구 기록**: manual-payment의 모든 입력값(amount, method, paidAt, referenceNote, actorUserSeq, ip, userAgent)을 AuditLog에 기록. **Audit row는 soft delete 금지** (이미 audit_logs 테이블 정책)
3. **Amount 사후 보정 불가**: manual-payment Payment row는 immutable. 잘못 입력 시 별도 정정 row(refund + 새 Payment)로 처리 (별도 스펙)
4. **영수증 PDF 자동 이메일**: PDPA §13 transactional 분류 — 별도 동의 불필요. 단, 컨시어지 신청 시 받은 PDPA 동의(`pdpaConsentAt`)를 영구 보존 (이미 됨)
5. **셀프 할당 audit**: AuditAction.CONCIERGE_LEW_ASSIGNED row의 description에 `selfAssign=true` 명시. Admin 모니터링용으로 ADMIN 페이지에서 셀프 할당 비율 KPI 노출 가능 (별도 PR)
6. **LEW의 ConciergeRequest 접근**: 본인 배정 건만 — `ConciergeOwnershipValidator.assertLewCanAccess` 신설
7. **이메일 첨부 PDF 만료**: 첨부된 PDF는 그 자체로 영구 보관(invoice_pdf 파일은 `Invoice.pdfFileSeq` 영구). 이메일 본문에 영수증 다운로드 링크가 있다면 JWT 토큰 만료 정책(24h) 적용

---

## §12. 위험 분석

| ID | 위험 | 영향 | 완화책 |
|----|------|------|--------|
| **R1** | Manual payment amount 오타 → 잘못된 영수증 발행 | 회계 무결성 침해, 신청자 클레임 | UI 확인 모달 + 입력 후 즉시 audit + 정정 워크플로우 별도 (refund + 신규 Payment) |
| **R2** | 영수증 이메일 발송 실패 → 결제 확정은 됨 | 신청자 인지 못함 | D5=B + ADMIN 알림 + 재발송 엔드포인트 + 재발송 SLA(24h 내) |
| **R3** | LEW 셀프 할당 audit 무결성 | 자기 검토 = 4-eyes 원칙 위배 | 옵션 C에서 셀프 할당은 본인의 별도 LEW 계정 seq로 하므로 user는 다름. audit에 selfAssign 플래그 + ADMIN 모니터링 KPI |
| **R4** | 옵션 C 채택 시 "한 사람 두 역할" 표현 한계 | UX 혼란 (왜 LEW로 별도 가입해야 하나?) | UI에 "LEW로 신청 처리할 매니저는 별도 LEW 계정 필요"안내 + 향후 옵션 A로 확장 가능성 메모 |
| **R5** | manual-payment의 Application 상태 가드 우회 | 비정상 상태 전이 (예: REVISION_REQUESTED에서 PAID로) | Application.forceMarkAsPaid() 가드: D3=C에 따라 ADMIN은 PENDING_REVIEW 이후만 허용. 이전 상태(DRAFT, NEW)는 거부 |
| **R6** | 컨시어지 referenceType의 Invoice 빌링 주소 비어있음 | 영수증 PDF 빌링 라인 빈칸 | settings 기본값 fallback (예: "Concierge Service") + 추후 Concierge에 주소 수집 필드 추가 검토 |
| **R7** | Payment 컬럼 `payment_method`(VARCHAR) vs `payment_method_enum`(VARCHAR) 듀얼 보유 | 어느 것이 정본인지 혼동 | 신규 enum 컬럼이 정본. legacy 컬럼은 read-only로 유지하되 신규 코드는 enum 컬럼만 사용. 별도 스펙으로 마이그레이션 클린업 |

---

## §13. 검토자 결정 결과 (확정 2026-05-04)

**채택**: D1=**B** (사용자 정정) / D2=B / D3=C / D4=B / D5=B / D6=A / D7=B.

**D1=B 채택 의미**:
- `user_roles` 1:N 정규화로 한 User가 여러 UserRole을 동시 보유 (예: CONCIERGE_MANAGER + LEW)
- 기존 `User.role` 단일 컬럼은 호환을 위해 "primary role"로 유지하되, 권한 검증은 `User.roles: Set<UserRole>` 기반
- LEW 셀프 할당 = 매니저 본인이 roles에 LEW 포함하고 있을 때 assignedLewSeq에 본인 seq 입력 가능
- PR-1 범위가 확장됨: 다중 역할 모델 도입 + Spring Security Authentication·@PreAuthorize 통합 포함

| ID | 항목 | 옵션 | 추천 |
|----|------|------|------|
| **D1** | 다중 역할 모델 | A: User.secondaryRoles Set 컬럼 / B: user_roles 1:N 정규화 / C: User 무변경, 별도 LEW 계정 사용 | **C** (작업 최소, 옵션 A 확장 여지 보존) |
| **D2** | Manual payment 데이터 모델 | A: ManualPaymentRecord 별도 엔티티 / B: Payment.paymentMethod 컬럼 확장 | **B** (자동 영수증 흐름 재사용) |
| **D3** | Manual payment 호출 가능 신청 상태 | A: PENDING_PAYMENT만 / B: PENDING_REVIEW부터 모두 / C: ADMIN은 PENDING_REVIEW 이후 모든 상태, MANAGER는 Concierge 한정 | **C** (ADMIN 유연성 + MANAGER 권한 명확) |
| **D4** | amount ≠ 견적금액 시 | A: 거부 / B: 허용 + audit 차이 기록 / C: 허용 + 경고 modal | **B** (회계 정정 케이스 흔함) |
| **D5** | 영수증 이메일 발송 실패 시 | A: 결제 롤백 / B: 결제 확정 유지 + 알림 + 수동 재발송 / C: 자동 재시도 3회 후 B | **B** (결제와 통보의 트랜잭션 분리) |
| **D6** | LEW 셀프 할당 허용 여부 | A: 허용 (본인의 별도 LEW 계정 seq) / B: 차단 (다른 LEW 필수) | **A** (요구사항에 명시적으로 "본인이 LEW면 자기 자신 할당") |
| **D7** | 신청서 대행 작성 권한 | A: 매니저만 / B: 매니저 + assigned LEW / C: 매니저 + 모든 LEW | **B** (assigned LEW 한정으로 audit 명확) |

---

## §14. PR 분할 (총 4~5 PR, 약 8~9일)

### PR-1 (M, 2일) — 데이터 모델 + 마이그레이션 + 도메인 가드
**범위**:
- ConciergeRequestStatus.LEW_ASSIGNED 추가 + 전이표 갱신
- ConciergeRequest.assignedLewSeq, lewAssignedAt 컬럼 + assignLew() 도메인 메서드
- PaymentMethod enum 신설
- Payment 엔티티: payment_method_enum, recorded_by_user_seq, recorded_at, reference_note 컬럼
- DatabaseMigrationRunner ALTER 스크립트 (idempotent)
- AuditAction enum 신규 값 추가
- NotificationType enum 신규 값 추가
- 단위 테스트: 도메인 메서드 + 전이 가드
**브랜치**: `feature/concierge-data-model`

### PR-2 (M, 2일) — 별도 수금 백엔드 + 영수증 자동 발행
**범위**:
- ManualPaymentService (Application용) + 엔드포인트 `/api/admin/applications/{id}/manual-payment`
- ConciergeManualPaymentService + 엔드포인트 `/api/concierge-manager/requests/{id}/manual-payment`
- InvoiceGenerationService 폴리모픽 컨텍스트 도입 (ApplicationContext / ConciergeContext)
- ConciergeContext용 스냅샷 빌더
- AFTER_COMMIT 트랜잭션 동기화로 Invoice 발행 + 이메일 발송
- EmailService.sendInvoicePdfEmail 메서드
- 통합 테스트: AC-A1~A7, AC-R1~R3
**브랜치**: `feature/manual-payment-and-auto-invoice`

### PR-3 (M, 2일) — LEW 할당 + 셀프 할당 + 신청서 대행 권한 확장
**범위**:
- assign-lew 엔드포인트 + 검증 로직
- ConciergeOwnershipValidator.assertLewCanAccess
- ConciergeManagerController `@PreAuthorize`에 LEW 추가
- createApplicationOnBehalf의 LEW 가드 추가
- 알림 발송 (LEW에게 인앱+이메일)
- 통합 테스트: AC-L1~L5, AC-D1~D4
**브랜치**: `feature/concierge-lew-assignment`

### PR-4 (M, 2일) — 프론트엔드 UI
**범위**:
- ADMIN AdminApplicationDetailPage: "수동 결제 확정" 모달 (amount, method enum dropdown, paidAt picker, note, issueReceipt checkbox)
- ConciergeRequestDetailPage (ADMIN/MANAGER용): "LEW 할당" 모달 + "수동 결제 확정" 모달
- LEW LewConciergeRequestDetailPage (신규): 본인 배정 ConciergeRequest 상세 + "신청서 대행 작성" CTA
- 결제 이력 카드 (Payment row + Invoice row 표시 + PDF 다운로드 링크)
- 알림 라우팅: CONCIERGE_LEW_ASSIGNED_LEW → LEW 페이지
**브랜치**: `feature/manual-payment-frontend`

### PR-5 (S, 1일, 옵션) — 영수증 재발송 + 발행 이력
**범위**:
- `POST /api/admin/invoices/{id}/resend-email`
- ADMIN AdminInvoiceListPage: 발송 이력 컬럼 (sentAt, deliveryStatus, lastResendAt)
- 발송 실패 ADMIN 대시보드 위젯
**브랜치**: `feature/invoice-email-resend`

---

## §15. 관련 기존 파일

### Backend
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/concierge/ConciergeRequest.java` — assignLew() 메서드 추가
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/concierge/ConciergeRequestStatus.java` — LEW_ASSIGNED 추가
- `blue-light-backend/src/main/java/com/bluelight/backend/api/concierge/ConciergeManagerController.java` — assign-lew, manual-payment 엔드포인트
- `blue-light-backend/src/main/java/com/bluelight/backend/api/concierge/ConciergeManagerService.java` — assignLew(), recordManualPayment()
- `blue-light-backend/src/main/java/com/bluelight/backend/common/util/ConciergeOwnershipValidator.java` — assertLewCanAccess()
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/payment/Payment.java` — paymentMethod enum 컬럼
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/payment/PaymentMethod.java` — 신규
- `blue-light-backend/src/main/java/com/bluelight/backend/api/payment/AdminPaymentService.java` — 참고 (manual-payment 분리)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/payment/ManualPaymentService.java` — 신규
- `blue-light-backend/src/main/java/com/bluelight/backend/api/invoice/InvoiceGenerationService.java` — 폴리모픽 컨텍스트 변경
- `blue-light-backend/src/main/java/com/bluelight/backend/api/email/EmailService.java` — sendInvoicePdfEmail()
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/audit/AuditAction.java` — 신규 값
- `blue-light-backend/src/main/java/com/bluelight/backend/api/notification/NotificationType.java` — 신규 값
- `blue-light-backend/src/main/java/com/bluelight/backend/migration/DatabaseMigrationRunner.java` — ALTER 추가

### Frontend
- `blue-light-frontend/src/pages/admin/AdminApplicationDetailPage.tsx` — Manual Payment 모달
- `blue-light-frontend/src/pages/concierge/ConciergeRequestDetailPage.tsx` — LEW 할당 + Manual Payment 모달
- `blue-light-frontend/src/pages/lew/LewConciergeRequestDetailPage.tsx` — 신규
- `blue-light-frontend/src/api/conciergeApi.ts` — assignLew, manualPayment
- `blue-light-frontend/src/api/paymentApi.ts` — manualPayment (Application)
- `blue-light-frontend/src/api/invoiceApi.ts` — resendEmail (PR-5)

---

## §16. 마이그레이션 영향

### 16.1 DatabaseMigrationRunner ALTER 추가 (idempotent)
```sql
-- Phase X-1: payments 컬럼 확장
ALTER TABLE payments
  ADD COLUMN IF NOT EXISTS payment_method_enum VARCHAR(30) NULL,
  ADD COLUMN IF NOT EXISTS recorded_by_user_seq BIGINT NULL,
  ADD COLUMN IF NOT EXISTS recorded_at DATETIME(6) NULL,
  ADD COLUMN IF NOT EXISTS reference_note VARCHAR(500) NULL;

-- 백필: 기존 row는 PAYNOW_ONLINE 으로 가정
UPDATE payments SET payment_method_enum = 'PAYNOW_ONLINE'
  WHERE payment_method_enum IS NULL AND deleted_at IS NULL;

-- Phase X-2: concierge_requests 컬럼 확장
ALTER TABLE concierge_requests
  ADD COLUMN IF NOT EXISTS assigned_lew_seq BIGINT NULL,
  ADD COLUMN IF NOT EXISTS lew_assigned_at DATETIME(6) NULL;

ALTER TABLE concierge_requests
  ADD CONSTRAINT IF NOT EXISTS fk_concierge_assigned_lew
  FOREIGN KEY (assigned_lew_seq) REFERENCES users(user_seq);

CREATE INDEX IF NOT EXISTS idx_concierge_assigned_lew
  ON concierge_requests (assigned_lew_seq, status);
```

### 16.2 enum 값 추가 (Java enum 검증)
- ConciergeRequestStatus.LEW_ASSIGNED — 기존 row 영향 없음(신규 값)
- AuditAction 신규 값 — 기존 row 영향 없음
- NotificationType 신규 값 — 기존 row 영향 없음

### 16.3 시드 데이터
- LEW 시드(`lew@licensekaki.sg`)에 대해 통합 테스트가 LEW 할당 시나리오 사용 가능
- APPLICANT 시드는 컨시어지 폼 제출로 생성되므로 추가 시드 불필요

### 16.4 시스템 설정 신규 키
```sql
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
  ('payment_method_labels_json',
   '{"PAYNOW_ONLINE":"PayNow (online)","BANK_TRANSFER":"Bank transfer","PAYNOW_OFFLINE":"PayNow (offline)","CASH":"Cash","OTHER":"Other"}',
   'UI display labels for payment methods'),
  ('invoice_email_subject_template',
   'Receipt for {invoiceNumber} — LicenseKaki',
   'Subject line for invoice PDF email'),
  ('invoice_email_body_template_html_seq', '0', 'File seq of invoice email body HTML template')
ON DUPLICATE KEY UPDATE setting_value = setting_value;
```

### 16.5 프론트엔드 빌드 영향
- 새 모달 컴포넌트 4개 → 번들 사이즈 +5KB 정도
- API 클라이언트 메서드 5개 추가
- 라우팅 추가: `/lew/concierge-requests/:id` (LEW용)

---

## 부록 A. JIT/설정 우선 원칙 준수 체크리스트

- [x] Manual payment 입력 시 신청자에게 추가 정보 요청 안 함 (이미 받은 스냅샷 사용 — JIT)
- [x] paymentMethod enum 라벨은 system_settings.payment_method_labels_json에서 로드
- [x] 영수증 이메일 제목·본문 템플릿은 system_settings에서 로드
- [x] 회사 정보(invoice_company_*)는 기존 settings 활용
- [x] enum 자체는 코드 고정 (법적/회계 분류이므로 설정 우선 예외)
- [x] paymentMethod enum 코드 주석에 `// 설정 우선 원칙 예외: 회계 분류는 코드 고정` 명시

## 부록 B. 추후 확장 (별도 스펙)

- 옵션 C → A 마이그레이션 (User.secondaryRoles 도입)
- Manual payment refund/취소 워크플로우
- 회계 시스템(Xero) 연동
- Concierge 신청 폼에 주소 수집 추가 (영수증 빌링 라인 채우기 위함)
- LEW 셀프 할당 KPI 대시보드 (audit 모니터링)
- 옵션 Z (시공 후 진짜 CoF) — `memory/lew-review-flow-roadmap.md` 후속 항목과 병행 검토
