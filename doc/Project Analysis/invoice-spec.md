# E-Invoice (영수증) 발행 기능 스펙

_작성일: 2026-04-22 · 작성자: product-manager · 상태: Draft (Phase 1 착수용)_

---

## 1. 목적·범위

결제가 확인된 신청자에게 **LicenseKaki(HanVision Holdings Private Ltd.) 명의의 E-Invoice PDF**를 자동 발행해 합법적 결제 증빙을 제공한다. 발행 시점은 `AdminPaymentService.confirmPayment()` 완료 직후이고, 수령자는 신청자 본인(MyPage) + 관리자다. 문서 성격은 싱가포르 GST 면세 소규모 사업자 기준의 **E-Invoice(영수증 겸 청구서)** — 정식 세금계산서(Tax Invoice)는 아니며 `No electronic signature is necessary…` 문구로 전자 원본임을 명시한다. Phase 1은 License Application 결제에 한정하고, SLD Order / Concierge Request 결제로의 확장은 데이터 모델에서만 대비하고 UI/트리거는 P3로 미룬다.

---

## 2. 데이터 모델

### 2.1 `invoices` 테이블 (신규)

> Payment 당 1건. Immutability 원칙(§설계원칙 3) — 스냅샷 컬럼은 모두 `@Column(updatable = false)`.

```sql
CREATE TABLE IF NOT EXISTS invoices (
    invoice_seq                         BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_number                      VARCHAR(30)   NOT NULL,
    -- 참조 (payments와 동일한 poly 구조)
    payment_seq                         BIGINT        NOT NULL,
    reference_type                      VARCHAR(30)   NOT NULL,  -- APPLICATION / SLD_ORDER / CONCIERGE_REQUEST
    reference_seq                       BIGINT        NOT NULL,
    application_seq                     BIGINT        NULL,      -- reference_type=APPLICATION 일 때 채움 (조회 편의)
    recipient_user_seq                  BIGINT        NOT NULL,
    issued_by_user_seq                  BIGINT        NULL,      -- 자동 발행 시 NULL, 재발행 시 admin seq
    issued_at                           DATETIME(6)   NOT NULL,
    -- 금액
    total_amount                        DECIMAL(12,2) NOT NULL,
    qty_snapshot                        INT           NOT NULL DEFAULT 1,
    rate_amount_snapshot                DECIMAL(12,2) NOT NULL,
    currency_snapshot                   VARCHAR(5)    NOT NULL DEFAULT 'SGD',
    -- 발행자(당사) 스냅샷
    company_name_snapshot               VARCHAR(150)  NOT NULL,
    company_alias_snapshot              VARCHAR(80),
    company_uen_snapshot                VARCHAR(30)   NOT NULL,
    company_address_line1_snapshot      VARCHAR(200),
    company_address_line2_snapshot      VARCHAR(200),
    company_address_line3_snapshot      VARCHAR(200),
    company_email_snapshot              VARCHAR(120),
    company_website_snapshot            VARCHAR(120),
    -- 빌링 대상(To:) 스냅샷 — Application Layer B 우선
    billing_recipient_name_snapshot     VARCHAR(150)  NOT NULL,
    billing_recipient_company_snapshot  VARCHAR(200),
    billing_address_line1_snapshot      VARCHAR(300),
    billing_address_line2_snapshot      VARCHAR(300),
    billing_address_line3_snapshot      VARCHAR(300),
    billing_address_line4_snapshot      VARCHAR(300),
    -- 설치 장소(Description에 들어갈 블록) 스냅샷
    installation_name_snapshot          VARCHAR(200),  -- e.g. "WW HOTPOT F&B PTE LTD"
    installation_address_line1_snapshot VARCHAR(300),
    installation_address_line2_snapshot VARCHAR(300),
    installation_address_line3_snapshot VARCHAR(300),
    installation_address_line4_snapshot VARCHAR(300),
    -- Description 본문 (긴 문장 — period/licence 표현 포함)
    description_snapshot                TEXT          NOT NULL,
    -- PayNow (하단 QR 블록) 스냅샷
    paynow_uen_snapshot                 VARCHAR(30),
    paynow_qr_file_seq_snapshot         BIGINT,
    -- Footer note 스냅샷
    footer_note_snapshot                VARCHAR(500),
    -- 생성된 PDF 파일
    pdf_file_seq                        BIGINT        NOT NULL,
    -- 감사 / 삭제
    created_at                          DATETIME(6)   NOT NULL,
    updated_at                          DATETIME(6),
    created_by                          BIGINT,
    updated_by                          BIGINT,
    deleted_at                          DATETIME(6),
    PRIMARY KEY (invoice_seq),
    UNIQUE KEY uk_invoices_number (invoice_number),
    UNIQUE KEY uk_invoices_payment (payment_seq),
    KEY idx_invoices_ref (reference_type, reference_seq),
    KEY idx_invoices_application (application_seq),
    KEY idx_invoices_recipient (recipient_user_seq),
    CONSTRAINT fk_invoices_payment  FOREIGN KEY (payment_seq) REFERENCES payments(payment_seq),
    CONSTRAINT fk_invoices_pdf      FOREIGN KEY (pdf_file_seq) REFERENCES files(file_seq),
    CONSTRAINT fk_invoices_paynowqr FOREIGN KEY (paynow_qr_file_seq_snapshot) REFERENCES files(file_seq),
    CONSTRAINT fk_invoices_recipient FOREIGN KEY (recipient_user_seq) REFERENCES users(user_seq),
    CONSTRAINT fk_invoices_issuer   FOREIGN KEY (issued_by_user_seq) REFERENCES users(user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.2 `Invoice` 엔티티 주의사항

- 모든 `*_snapshot` 컬럼: `@Column(updatable = false)` 적용. LOA 스냅샷 패턴(`Application.loaCompanyNameSnapshot`)과 동일.
- `pdf_file_seq`는 재발행 시에만 `updatable = true`(Section 8). 그 외 `@Column(updatable = false)` 적용 컬럼과 구분.
- Soft delete: `@SQLDelete + @SQLRestriction` — 법적 문서라 물리 삭제 금지.
- `BaseEntity` 상속하여 `createdAt/updatedAt/createdBy/updatedBy` 유지.

### 2.3 기존 엔티티 변경

- **없음**. Application/Payment/User/FileEntity는 그대로 사용. 추가 FK만 `files`, `users`, `payments`를 향한다.

---

## 3. `system_settings` 신규 키

모두 `DatabaseMigrationRunner.seedSystemSettings()`에 추가해 환경과 무관하게 1회 시드.

| key | 타입 | 기본값 | 용도 | 변경 영향 |
|---|---|---|---|---|
| `invoice_company_name` | string | `HanVision holdings Private Ltd.` | PDF 좌상단 / 우상단 뱃지 회사명 | 이후 발행 영수증에만 반영. 기존 PDF 불변 |
| `invoice_company_alias` | string | `Licensekaki` | 좌상단 괄호 표기 `(Licensekaki)` | 동일 |
| `invoice_company_uen` | string | `202627777H` | 우상단 `UEN: …` 뱃지 | 동일 |
| `invoice_company_address_line1` | string | `12 WOODLANDS SQUARE` | 좌상단 주소 1행 | 동일 |
| `invoice_company_address_line2` | string | `#13-79 WOODS SQUARE TOWER ONE,` | 좌상단 주소 2행 | 동일 |
| `invoice_company_address_line3` | string | `SINGAPORE 737715` | 좌상단 주소 3행 | 동일 |
| `invoice_company_email` | string | `Admin@licensekaki.com` | 좌상단 이메일 | 동일 |
| `invoice_company_website` | string | `Licensekaki.com` | 좌상단 웹사이트 | 동일 |
| `invoice_paynow_uen` | string | `202627777H` | 하단 `UEN Paynow:` | 동일 |
| `invoice_paynow_qr_file_seq` | long (as string) | 비어 있음 | 하단 SG QR 이미지 (FileEntity seq) | 비어 있으면 PDF에 placeholder 텍스트 `[QR pending]` |
| `invoice_number_prefix` | string | `IN` | 영수증 번호 접두 | 접두만 바꿔도 과거 번호 변경 없음 |
| `invoice_currency` | string | `SGD` | 기본 통화 코드 | Phase 1에선 단일값만 지원 |
| `invoice_footer_note` | string | `No electronic signature is necessary, as this document serves as an official E-Invoice.` | 하단 안내문 | 동일 |

**캐싱**: `SystemSettingService`가 현재 매 조회마다 DB를 읽으므로 별도 캐시 필요 없음. 영수증 발행 빈도는 결제당 1회로 낮다.

**UI 편집 위치**: `SystemSettingsPage`에 "Invoice / 회사 정보" 신설 탭. QR 업로드는 기존 파일 업로드 API 재사용 후 `invoice_paynow_qr_file_seq`에 file_seq 저장(이미 `payment_paynow_qr`에서 동일 패턴 사용 중 — `AdminPriceSettingsController.java:136` 참조).

---

## 4. API 엔드포인트

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/applications/{id}/invoice` | 인증됨 + 신청자 본인 | 본인 소유 Application의 Invoice PDF 스트리밍. PAID 이후만 허용 |
| GET | `/api/admin/applications/{id}/invoice` | `ADMIN`, `SYSTEM_ADMIN` | Admin 열람 |
| GET | `/api/admin/invoices/{id}` | `ADMIN`, `SYSTEM_ADMIN` | 메타데이터(JSON) — 번호·스냅샷 확인용 |
| POST | `/api/admin/applications/{id}/invoice/regenerate` | `ADMIN`, `SYSTEM_ADMIN` | **PDF만 재생성** (스냅샷 불변). 사유 필드 required |

### 에러 코드

- `INVOICE_NOT_FOUND` (404) — 해당 Application에 Invoice가 아직 없음.
- `PAYMENT_NOT_CONFIRMED` (400) — Application.status != `PAID`/`IN_PROGRESS`/`COMPLETED`.
- `INVOICE_FORBIDDEN` (403) — 본인 소유가 아님 (자체 감사 로깅).
- `INVOICE_ALREADY_EXISTS` (409) — 자동 발행 시 payment_seq unique 충돌 (재시도 아닌 실패).
- `INVOICE_QR_PENDING` (warn only) — QR 설정이 비어 있어도 PDF는 발행하되 warn 로그.

### `@PreAuthorize` 예시

- Applicant GET: `@PreAuthorize("isAuthenticated()")` + 서비스 계층 본인 검증.
- Admin GET/POST: `@PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")`.
- LEW는 **접근 불가** — 영수증은 당사와 신청자 간 계약서 성격.

---

## 5. 자동 발행 트리거

**위치**: `AdminPaymentService.confirmPayment()`의 `notificationService.createNotification(...)` 호출 직전(현재 라인 80~95 근처).

**흐름**:

1. `InvoiceGenerationService.generateFromPayment(savedPayment, application)` 호출.
2. 내부에서:
   - 스냅샷 수집 (회사·빌링·설치·금액).
   - `invoice_number` 생성 (§6).
   - `iText`로 PDF 생성 → `FileStorageService.storeBytes(...)` → `FileEntity` 저장.
   - `Invoice` 엔티티 저장.
   - AuditLog `INVOICE_GENERATED` 기록.
3. **실패 정책**: 결제 본 트랜잭션은 롤백하지 않음. `@TransactionalEventListener(PHASE=AFTER_COMMIT)` 또는 `try/catch` + `log.error("Invoice generation failed, continuing payment", e)` + `notificationService.createNotification(admin, INVOICE_GENERATION_FAILED)`. 관리자는 `/api/admin/applications/{id}/invoice/regenerate`로 복구.
4. **중복 방지**: `uk_invoices_payment`(unique on payment_seq) + 서비스 계층에서 `invoiceRepository.existsByPaymentPaymentSeq(...)` 선검사.

---

## 6. 영수증 번호 생성 규칙

- **형식**: `{prefix}{yyyyMMdd}{nnn}` — 예: `IN20260422001`.
- **근거**: 샘플은 `IN20260422`만 표기되어 있으나, 하루 여러 결제가 확인될 수 있으므로 시퀀스 필수. 샘플은 제1건으로 간주.
- **구현**:
  1. `InvoiceNumberGenerator.next(today)` — `invoiceRepository.countByInvoiceNumberStartingWith("IN" + yyyyMMdd)` + 1.
  2. `UNIQUE KEY uk_invoices_number`에서 충돌 시 최대 5회 재시도(동시 발행 대비).
  3. 트랜잭션 격리 수준은 READ COMMITTED 기본 유지. 일반적 부하에서 충돌 확률 낮음.
- **이관 호환**: 향후 다른 참조 타입(SLD/Concierge)이 들어와도 동일 번호 체계 공유 가능(prefix 분리 필요 시 `invoice_number_prefix` 설정값으로 주입).

---

## 7. PDF 레이아웃 (iText — OpenPDF 1.3.x)

A4 595 × 842pt 기준. `LoaGenerationService`와 동일하게 `BaseFont.HELVETICA` 사용.

| 블록 | x | y (top→down) | 폰트 | 크기 | 데이터 소스 |
|---|---|---|---|---|---|
| 좌상단 회사 라인 1: `HanVision holdings Private Ltd. (Licensekaki)` | 50 | 800 | Helvetica-Bold | 10 | `company_name_snapshot` + alias |
| 좌상단 회사 라인 2: website | 50 | 784 | Helvetica | 9 | `company_website_snapshot` |
| 좌상단 회사 라인 3: email | 50 | 770 | Helvetica | 9 | `company_email_snapshot` |
| 좌상단 회사 라인 4~6: 주소 3행 | 50 | 754 / 740 / 726 | Helvetica | 9 | `company_address_line{1..3}_snapshot` |
| 우상단 로고 | 430 | 800 | 이미지 | 100×40pt | `classpath:templates/licensekaki-logo.png` (기존 에셋) |
| 우상단 회사명+UEN | 430 | 745 / 730 | Helvetica / Bold | 9 / 9 | `company_name_snapshot`, `UEN: ` + uen |
| 타이틀 `INVOICE` | 50 | 680 | Helvetica-Bold | 24 | 상수 |
| 번호 라인 | 50 | 650 | Helvetica-Bold | 12 | `invoice_number` |
| 날짜 라인 `Date: 22 Apr 26` | 50 | 632 | Helvetica | 10 | `issued_at`, `dd MMM yy` |
| `To:` + 빌링 4행 | 50 | 600 / 586 / 572 / 558 / 544 | Helvetica | 10 | `billing_*` |
| 테이블 헤더 (Description/Qty/Rate/Amount) | 50 / 360 / 430 / 500 | 500 (셀 상단) | Helvetica-Bold | 10 | 상수 |
| Description 본문 (5~8줄) | 50 | 480 ~ 370 | Helvetica | 9 | `description_snapshot` + installation 블록 |
| Qty / Rate / Amount | 360 / 430 / 500 | 480 | Helvetica | 10 | `qty_snapshot`, `rate_amount_snapshot`, `total_amount` |
| Total 라인 (우측 정렬) | 430 | 340 | Helvetica-Bold | 11 | `Total: $` + `total_amount` |
| Footer note | 50 | 300 | Helvetica-Oblique | 9 | `footer_note_snapshot` |
| PaymentMethod 헤더 `Payment Method via SG QR` | 400 | 260 | Helvetica-Bold | 10 | 상수 |
| QR 이미지 | 400 | 150 | 이미지 | 110×110pt | FileEntity(`paynow_qr_file_seq_snapshot`) or placeholder |
| `UEN Paynow: 202627777H` | 400 | 130 | Helvetica | 9 | `paynow_uen_snapshot` |

**좌표는 대략치**. 최종 구현에서 실제 샘플과 시각 유사도 80% 이상이면 수용(§11 AC).

---

## 8. 재발행 정책 (Immutability)

1. **스냅샷 불변**: 12개 `*_snapshot` 컬럼은 모두 `@Column(updatable = false)`. Hibernate가 UPDATE SQL에서 제외.
2. **재발행 시 변경 가능 필드**: `pdf_file_seq`, `updated_at`, `updated_by`만 UPDATE 허용.
3. **동작**:
   - 기존 `FileEntity`(pdf_file_seq)는 **soft delete 하지 않고 유지** — 감사 목적. (운영 옵션: `INVOICE_PDF_ARCHIVED` 액션으로 별도 기록)
   - 새 PDF를 생성해 `FileEntity` 저장 → `invoice.pdf_file_seq` UPDATE.
   - `INVOICE_REGENERATED` 감사 로그에 사유(필수 입력) 기록.
4. **금액 변경 불가**: 금액이 틀렸다면 재발행 아님 — Credit Note 발행 대상(§P3).
5. **취소/환불**: 이번 범위 외. 향후 `credit_notes` 테이블과 별도 엔티티로 처리(P3 flag).

---

## 9. PDPA·보안

1. **영수증에 포함되는 개인정보**: 이름·회사명·주소·이메일·설치 주소 — 모두 `Application` 스냅샷(Layer B, `jit-reask-audit.md §9`). User 최신 프로필은 **참조하지 않음** (Immutability 보장).
2. **파일 저장**: `FileEncryptionUtil`은 **적용하지 않음**. 근거:
   - 영수증은 신청자에게 제공하는 공식 문서로, 본인이 언제든 다운로드·인쇄해야 함.
   - 기존 LOA PDF와 동일 패턴 유지 (`LoaGenerationService`는 평문 저장).
   - S3 전환 시 server-side encryption(SSE-S3) + signed URL로 대체.
3. **감사 로그 (`AuditAction` 추가)**:
   - `INVOICE_GENERATED` — 자동 발행.
   - `INVOICE_DOWNLOADED` — 본인 또는 admin 다운로드.
   - `INVOICE_REGENERATED` — Admin PDF 재생성 (사유 포함).
   - `INVOICE_GENERATION_FAILED` — 자동 발행 실패 (복구 추적용).
4. **접근 제어**:
   - 본인 소유 검증: `application.getUser().getUserSeq().equals(currentUser.getUserSeq())`.
   - Admin/SYSTEM_ADMIN 전체 조회.
   - LEW 접근 불가 (403).
   - 본인 아닌 요청은 `INVOICE_DOWNLOAD_FORBIDDEN` 감사 이벤트 기록.

---

## 10. Phase 계획

### P1 — 핵심 (Phase 1 착수 대상, 2주 예상)
- `invoices` 테이블 + `Invoice` 엔티티 + Repository.
- `DatabaseMigrationRunner.seedSystemSettings()`에 13개 키 추가.
- `InvoiceGenerationService` (스냅샷 수집 + iText PDF + FileEntity 저장).
- `InvoiceNumberGenerator`.
- `AdminPaymentService.confirmPayment()`에서 자동 발행 트리거.
- 본인용 다운로드 API + MyPage 결제 내역 화면에 "Download Invoice" 버튼.
- Admin 조회 API.
- `AuditAction` 4개 추가.

### P2 — 관리 (1주)
- `SystemSettingsPage` "Invoice / 회사 정보" 탭 (13개 키 편집 + QR 업로드).
- Admin Invoice 목록 / 재생성 화면.
- 재발행 사유 필수 검증.

### P3 — 확장 (후속)
- `SldOrder`, `ConciergeRequest` 결제에도 동일 트리거 확장.
- 취소/환불 시 `credit_notes` 발행 (별도 엔티티).
- GST 등록 시 Tax Invoice 확장.

---

## 11. Acceptance Criteria (17개)

- [ ] **AC-1** 결제 확인 후 트랜잭션 커밋 완료 시점 기준 1초 이내 Invoice 엔티티 생성 (P95).
- [ ] **AC-2** PDF 렌더가 샘플 레이아웃과 시각 유사도 80% 이상 (좌상단·우상단·타이틀·테이블·QR·footer의 위치·텍스트 일치).
- [ ] **AC-3** `invoice_company_name` 등 설정 변경 후 **그 이후** 발급된 영수증에만 새 값 반영됨.
- [ ] **AC-4** 설정 변경 후 **기존 영수증 PDF 내용 및 메타데이터 모두 불변** (스냅샷 컬럼 UPDATE 불가).
- [ ] **AC-5** `INDIVIDUAL` 신청자: `To:` 블록 = 본인 이름 + Correspondence 주소(5-part Layer B 우선). 회사명 행 생략 또는 공란.
- [ ] **AC-6** `CORPORATE` 신청자: `To:` 블록 = 본인 이름 + `company_name_snapshot` + 5-part 주소 4행.
- [ ] **AC-7** Description 블록의 설치 장소는 Application의 `installationAddressBlock/Unit/Street/Building/PostalCode` (Layer B) 사용. 없으면 `application.address` 단일 문자열.
- [ ] **AC-8** Payment 당 Invoice 1건만 생성 — `uk_invoices_payment` unique constraint 검증.
- [ ] **AC-9** 본인이 아닌 일반 사용자가 `/api/applications/{id}/invoice` 호출 시 403 + `INVOICE_FORBIDDEN` + 감사 로그.
- [ ] **AC-10** Application.status가 `PENDING_PAYMENT` 이전이면 400 `PAYMENT_NOT_CONFIRMED`.
- [ ] **AC-11** LEW 계정 로그인 상태에서 두 GET 엔드포인트 호출 시 403.
- [ ] **AC-12** Admin이 QR 파일을 업로드하지 않은 상태에서 발행 시 PDF는 정상 생성되며 QR 자리에 placeholder 박스 + `[QR pending]` 텍스트.
- [ ] **AC-13** 감사 로그에 `INVOICE_GENERATED` / `INVOICE_DOWNLOADED` / `INVOICE_REGENERATED` 기록.
- [ ] **AC-14** PDF가 `FileEntity`로 저장되어 기존 `FileStorageService` 다운로드 경로(`/api/files/**`) 재사용.
- [ ] **AC-15** 영수증 번호 unique — 동시 발급 시 재시도 로직으로 최대 5회 안에 고유 번호 확보.
- [ ] **AC-16** **설정 우선 원칙 위반 없음** — PDF 렌더 코드에 HanVision/202627777H/주소 문자열 하드코딩 전무. grep으로 검증.
- [ ] **AC-17** 자동 발행 실패해도 결제 트랜잭션은 커밋됨. `INVOICE_GENERATION_FAILED` 기록 + Admin 알림.

---

## 12. 의존 작업

- **Application Layer B snapshot**: `Application` 엔티티의 `installationAddress*` 및 `correspondenceAddress*` 필드 이미 존재 (`Application.java:378-411`). JIT 수집 완료 후 제출된 신청은 Layer B 보유 → 영수증 스냅샷이 정확.
- **FileStorageService / FileEntity**: 재사용. S3 전환 시 투명.
- **Admin Settings UI**: 현재 `SystemSettingsPage` 및 `AdminPriceSettingsController`에서 key-value 편집 패턴 확립 — 동일 패턴 확장.
- **OpenPDF 1.3.x (com.lowagie)**: `LoaGenerationService`에서 이미 사용 중 — 추가 의존성 불필요.
- **LicenseKaki 로고 이미지**: `templates/licensekaki-logo.png`를 `blue-light-backend/src/main/resources/templates/`에 배치 필요. (현재 frontend asset만 있음 — 백엔드로 복사)

---

## 13. 마이그레이션 전략

**권장: 옵션 (a) — 신규 발행만.**

- 기존 PAID/COMPLETED 상태 Application에 대해서는 영수증을 자동 생성하지 않는다.
- MyPage의 "Download Invoice" 버튼은 Invoice가 없으면 비활성화 + 툴팁 `Contact admin for legacy invoice`.
- 필요 시 관리자가 **수동 백필** 가능: `POST /api/admin/applications/{id}/invoice/regenerate`를 **invoice가 없을 때도** 호출 가능하게 허용 → 이때는 신규 생성(`issued_at = now`, 스냅샷은 현재 User 값이 아닌 **Application Layer B + 현재 system_settings**)로 동작. 감사 로그에 `BACKFILL=true` 플래그.
- **옵션 (b) 일괄 백필**은 하지 않는다 — Application Layer B가 완전하지 않은 레거시 신청이 다수 존재하기 때문. 법적 무결성 위험.

---

## 14. 범위 외 (Out of Scope)

- SLD Order / Concierge Request 결제 영수증 (P3).
- Credit Note / 환불 증빙 (P3).
- 정식 Tax Invoice(GST) 포맷.
- 이메일 첨부 자동 발송 — 현재 `sendPaymentConfirmEmail`은 금액만 포함. **향후** 영수증 PDF 링크 추가(P2 후반).
- 다국어 PDF (영문 고정).
- 인보이스 번호 월별/연도별 리셋 — 일별 리셋 고정.

---

## 15. 엣지 케이스

1. **동일 Payment 재확인 시도**: `confirmPayment`는 이미 `INVALID_STATUS_FOR_PAYMENT`로 막힘 → Invoice 중복 생성 불가.
2. **Application 삭제(soft) 후 영수증 요청**: `@SQLRestriction`으로 Application 조회 실패 → 404. Invoice 자체는 유지(법적 보관).
3. **QR 파일이 삭제된 상태에서 재발행**: `paynow_qr_file_seq_snapshot`은 과거 유효 seq지만 `FileEntity`가 soft-deleted → 렌더 시 fetch 실패 → placeholder 사용 + warn 로그.
4. **회사 주소 line2가 빈 값**: PDF 좌상단에서 해당 줄만 skip, 다음 줄을 위로 올리지 않음(좌표 고정).
5. **Corporate 신청자 Company Name이 둘 다(Layer A/B) 비어 있음**: `LoaGenerationService.validateApplicantProfile`이 이미 `INCOMPLETE_PROFILE`로 막고 있으므로 결제 단계 이전에 차단됨 — 추가 검증 불필요.
6. **금액이 0원**: SLD 주문 등에서 발생 가능성 있으나 Phase 1 범위 외 — Phase 1은 Application 결제만 대상이라 0원 발생 없음.
7. **invoice_number 재시도 5회 모두 실패**: 극히 드물지만 `INVOICE_GENERATION_FAILED` 로그 + 관리자 알림 → 수동 재생성.

---

## 16. 참고 문서

- `CLAUDE.md §설계 원칙` — 설정 우선 / JIT Layer B.
- `doc/Project Analysis/jit-reask-audit.md §9` — Layer A/B/C 정의.
- `doc/Project Analysis/ema-field-jit-plan.md` — Installation / Correspondence 5-part 수집 흐름.
- `blue-light-backend/.../loa/LoaGenerationService.java` — iText 패턴, Layer B fallback 로직, `FileStorageService.storeBytes` 사용 예.
- `blue-light-backend/.../admin/AdminPriceSettingsController.java:136` — PayNow QR file_seq 저장 기존 패턴.
- `blue-light-backend/.../config/DatabaseMigrationRunner.java:895` — `seedSystemSettings` 확장 지점.
