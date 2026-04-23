# 공통 문서번호 생성기 (Document Number Generator) 스펙

_작성일: 2026-04-23 · 작성자: product-manager · 상태: **Confirmed** (2026-04-23, 결정권자: ringo@contigo.im)_

---

## 0. TL;DR

- **새 번호 체계**: `LK-{DOC_PREFIX}-YYYYMMDD-NNNN`
  - 예: 영수증 → `LK-RCP-20260423-0001`
- **설계 원칙 준수**: Document Type은 **마스터 테이블**(`document_type_catalog` 패턴을 따라 `document_number_types`)로 관리. 코드 상 ENUM/상수 하드코딩 금지.
- **채번 소스**: 별도 시퀀스 테이블 `document_number_sequence` (type × 날짜 UNIQUE PK)에서 **row-lock 기반 원자적 증가**. 기존 `InvoiceNumberGenerator`의 "count + exists + retry" 방식은 원자성이 약해 부하 시 충돌 확률↑ — 공통 모듈은 더 견고한 구조로 재설계.
- **기존 Invoice와의 관계**: 기존 `Invoice` 엔티티를 **그대로 유지하고 번호 생성만 공통 엔진에 위임**(Q1=B 확정). `invoices.invoice_number`는 도메인 고유 컬럼 유지. P1에서 공통 엔진 도입 + `InvoiceNumberGenerator`를 공통 엔진 위임으로 리팩터링. 기존 발행본의 번호(`IN20260422001`)는 그대로 두고, **새 발행분부터 신규 형식(`LK-RCP-YYYYMMDD-NNNN`) 적용**(Immutability 준수).
- **MVP 대상**: 영수증(= 기존 Invoice 엔티티, 접두어 `RCP`). Phase 2 이후 SLD Order/LEW Service Order/Concierge Request/공식 레터/Credit Note 등 순차 확장.

---

## 1. 요구사항 요약

LicenseKaki 플랫폼에서 발행되는 모든 공식 문서에 대해 **도메인에 흩어지지 않은 단일 채번 엔진**을 제공하여, 문서 종류별 유일하고 추적 가능한 문서번호를 일관된 형식으로 발번한다. 당장은 **영수증(Receipt)** 발급을 위한 채번이 필요하지만, 모듈은 처음부터 **N개 문서 타입을 동시에 지원**하도록 설계한다.

---

## 2. 배경 — 기존 자산 분석

### 2.1 이미 존재하는 것

| 자산 | 경로 | 상태 |
|---|---|---|
| `InvoiceNumberGenerator` | `blue-light-backend/src/main/java/com/bluelight/backend/api/invoice/InvoiceNumberGenerator.java` | 구현됨. 형식 `IN20260422001` (prefix + yyyyMMdd + **3자리**) |
| `InvoiceNumberGeneratorTest` | 같은 경로 test | 구현됨 |
| `invoice-spec.md` | `doc/Project Analysis/invoice-spec.md` | Draft |
| `invoices` 테이블 | `schema.sql:754-810` | 운영 스키마에 존재, `uk_invoices_number` unique |
| `invoice_number_prefix` system_setting | `DatabaseMigrationRunner.seedSystemSettings` | 시드됨, 기본값 `IN` |
| `document_type_catalog` 테이블 | `schema.sql:200` | 첨부 파일용이지만, "마스터 테이블로 도메인 코드를 관리" 하는 확립된 패턴 |
| `PaymentReferenceType` enum | `domain/payment/PaymentReferenceType.java` | `APPLICATION / CONCIERGE_REQUEST / SLD_ORDER` — 다형 참조 패턴 |
| `SystemSetting` key-value | `domain/setting/SystemSetting.java` | 단순 key-value, 복잡한 구조는 별도 테이블 권장 |

### 2.2 기존 방식의 한계

기존 `InvoiceNumberGenerator.next()`는 다음 3단계로 동작한다:

```java
long baseCount = repository.countByInvoiceNumberStartingWith(prefix + date);
for (attempt 0..4) {
    String candidate = prefix + date + format("%03d", baseCount + 1 + attempt);
    if (!repository.existsByInvoiceNumber(candidate)) return candidate;
}
throw INVOICE_NUMBER_COLLISION;
```

문제점:
- **경쟁 조건**: `count` 시점과 `insert` 시점 사이에 다른 트랜잭션이 같은 번호를 넣을 수 있음. UNIQUE 제약 덕에 DB에선 막히지만, 재시도 5회 내에 안 잡히면 500 에러. 현재는 결제 당 1회라 부하가 낮아 티가 안 나지만, 공통 엔진으로 올릴 때는 "여러 도메인에서 동시 발번"이 현실화됨.
- **full-scan 위험**: `countByInvoiceNumberStartingWith`는 `LIKE 'IN20260422%'` — 복합 인덱스 없으면 느려짐. 일별 발급량이 늘어나면 리스크.
- **Document Type 확장성 0**: prefix가 `invoice_number_prefix` 한 개의 system_setting에 고정. "문서 종류별 prefix"를 자연스럽게 확장할 수 없음.
- **3자리 순번**: 하루 999건 초과 시 문자열 폭 변동(`1000`이 되어 총 길이가 바뀜) → 포맷 안정성 저하.

### 2.3 본 스펙의 접근

공통 채번 엔진은 **시퀀스 테이블 row-lock** 기반으로 재설계한다. 기존 `InvoiceNumberGenerator`는 Phase 2에서 이 공통 엔진을 호출하도록 **리팩터링**하고, 당일 수에 따라 형식이 달라지는 숫자 부분은 **공통 포맷**(4자리)으로 통일한다.

---

## 3. 번호 체계

### 3.1 형식

```
LK-{DOC_PREFIX}-YYYYMMDD-NNNN
│   │           │         │
│   │           │         └─ 해당 DOC_PREFIX × 해당 YYYYMMDD 범위 내 0001부터 증가 (4자리, 고정폭)
│   │           └─ 발행일 (Asia/Singapore 기준, 뒤 §7)
│   └─ 문서 종류 2차 접두어 (3~4자, 대문자 알파벳 전용)
└─ 고정 1차 접두어 (LicenseKaki)
```

### 3.2 문자 집합 / 구분자

- 1차/2차 접두어: **대문자 알파벳만** (`[A-Z]+`), 구분자 `-`.
- 숫자부: **숫자만** (`[0-9]{4}`).
- 구분자로 `-`를 선택한 이유: 육안 가독성 높고, 브라우저 더블클릭 시 세그먼트 단위로 선택되어 복사 편리 (UX).
- 전체 정규식: `^LK-[A-Z]{2,5}-[0-9]{8}-[0-9]{4}$`
- 총 길이: 최소 19자(`LK-XX-YYYYMMDD-0001`) ~ 최대 22자(`LK-XXXXX-YYYYMMDD-0001`).

### 3.3 초기 문서 타입 카탈로그 (MVP — P1에서 시드)

| code | prefix | label_ko | label_en | description |
|---|---|---|---|---|
| `RECEIPT` | `RCP` | 영수증 | Receipt | 결제 영수증 — 기존 `Invoice` 엔티티의 번호를 이 타입으로 발번 (§9 참조) |

※ 프로젝트의 E-Invoice는 "영수증 겸 청구서" 성격(invoice-spec.md §1). Q1=B 결정에 따라 **별도 `Receipt` 엔티티는 신설하지 않으며**, 기존 `Invoice` 엔티티의 번호 생성기만 공통 엔진에 위임하도록 리팩터링한다. 따라서 P1에서는 `RECEIPT` 타입 **하나만** 시드한다.

### 3.4 확장 예정 문서 타입 (Phase 2+, 최종 접두어는 Phase 2 킥오프 시점에 확정)

| code | prefix (제안) | label_ko | 비고 |
|---|---|---|---|
| `SLD_ORDER` | `SLO` | SLD 주문서 | 별도 Phase에서 도입 |
| `LEW_SERVICE_ORDER` | `LSO` | LEW 방문 서비스 주문서 | |
| `CONCIERGE_REQUEST` | `CCR` | Concierge 요청서 | |
| `OFFICIAL_LETTER` | `LTR` | 공식 레터 | |
| `CREDIT_NOTE` | `CDN` | Credit Note / 환불 증빙 | invoice-spec P3 |

※ 위 prefix는 **제안**이며, 실제 값은 Phase 2 시드 시점에 최종 확정(Q3 결정). 하드코딩 금지 원칙 준수.
※ `INVOICE` 별도 타입은 두지 않음 — 영수증과 인보이스가 동일 문서이며, 모두 `RECEIPT(RCP)` 타입으로 발번됨.

---

## 4. Document Type 관리 방식 — **마스터 테이블**

### 4.1 의사결정: ENUM vs 마스터 테이블 vs system_settings

| 대안 | 장점 | 단점 | 채택 |
|---|---|---|---|
| **(A) Java ENUM** | 단순·컴파일 타임 안전 | 문서 타입 추가 시 **코드 배포 필요**. "설정 우선" 원칙 위배 | ❌ |
| **(B) `system_settings` key-value** | 즉시 추가 가능 | key가 분산되어 한눈에 안 보임. 다중 필드(prefix + label_ko + label_en + 활성 여부 등) 표현이 비효율 | ❌ |
| **(C) 마스터 테이블 `document_number_types`** | `document_type_catalog` 패턴과 동일. Admin UI로 즉시 CRUD. 다중 속성 자연스럽게 수용 | 테이블 하나 추가 | ✅ |

**결론**: 대안 (C). 프로젝트 내 확립된 "카탈로그 테이블 + Admin UI" 패턴을 그대로 답습 (CLAUDE.md §설계 원칙 "설정 우선").

### 4.2 테이블 설계 — `document_number_types`

```sql
CREATE TABLE IF NOT EXISTS document_number_types (
    code            VARCHAR(40)   NOT NULL,          -- 논리 식별자 (예: RECEIPT, INVOICE)
    prefix          VARCHAR(10)   NOT NULL,          -- 번호의 2차 접두어 (예: RCP)
    label_ko        VARCHAR(120)  NOT NULL,
    label_en        VARCHAR(120)  NOT NULL,
    description     VARCHAR(500),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    display_order   INT           NOT NULL DEFAULT 0,
    created_at      DATETIME(6),
    updated_at      DATETIME(6),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_at      DATETIME(6),
    PRIMARY KEY (code),
    UNIQUE KEY uk_document_number_types_prefix (prefix),
    CONSTRAINT ck_document_number_types_prefix_format
        CHECK (prefix REGEXP '^[A-Z]{2,5}$'),
    CONSTRAINT ck_document_number_types_code_format
        CHECK (code REGEXP '^[A-Z_]{3,40}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

> `CHECK` 제약은 MySQL 8.0 이상에서 지원됨 (프로젝트 Tech Stack 기준 충족).

### 4.3 Admin UI (Phase 2)

`SystemSettingsPage`에 **"Document Number Types"** 탭 신설.
- 목록/추가/수정/활성 토글.
- prefix 중복 검증, 정규식 검증은 서버에서 필수(UI는 즉시 피드백).
- Soft delete만 허용 — 번호에 이미 사용된 code/prefix는 히스토리로 유지.
- **prefix 변경 금지(또는 경고)**: 과거 발행 문서의 번호와 불일치 야기 — UI에서 "prefix는 변경 불가" 정책 기본 적용. 변경 허용 시 감사 로그 `DOC_NUM_TYPE_PREFIX_CHANGED` 필수.

---

## 5. 시퀀스 채번 방식 — **row-lock 기반 원자적 증가**

### 5.1 의사결정: 동시성 제어

| 대안 | 요지 | 장점 | 단점 | 채택 |
|---|---|---|---|---|
| **(A) count + retry** (현 Invoice) | `count(...) + 1` 후 UNIQUE 충돌 시 5회 재시도 | 테이블 1개, 간단 | 경쟁 조건에서 재시도 폭주 위험, full-scan 우려 | ❌ |
| **(B) MySQL `AUTO_INCREMENT`** | 각 문서 테이블에 auto_increment 컬럼 | 가장 빠름 | **type × 날짜별 리셋 불가** (auto_increment는 전역 단조 증가) | ❌ |
| **(C) `document_number_sequence` 테이블 + `SELECT ... FOR UPDATE`** | (type, date) 복합 PK 행을 row-lock 후 `next_value` 증가 | 원자적, 정확, 예측 가능, 날짜별 자연 리셋 | 트랜잭션 동안 행 잠금 → 매우 짧은 임계구역 유지 필요 | ✅ |
| **(D) `UPDATE ... SET next_value = next_value + 1` + `RETURNING`** | MySQL 8에서는 `RETURNING` 미지원 | - | MySQL 미지원 | ❌ |
| **(E) Redis `INCR`** | 외부 카운터 | 매우 빠름 | Redis 의존성 신설, DB-Redis 정합성 관리 부담 | ❌ (지금 규모엔 과투자) |

**결론**: (C). MySQL에서 공식 권장되는 패턴. Spring의 `@Transactional(isolation = READ_COMMITTED)` + `SELECT ... FOR UPDATE`로 race-free.

### 5.2 테이블 설계 — `document_number_sequence`

```sql
CREATE TABLE IF NOT EXISTS document_number_sequence (
    doc_type_code    VARCHAR(40)   NOT NULL,   -- FK to document_number_types.code
    issue_date       DATE          NOT NULL,
    next_value       INT           NOT NULL DEFAULT 1,  -- 다음 발번될 시퀀스 (1부터 시작)
    last_issued_at   DATETIME(6),                       -- 감사·디버깅용
    last_issued_by   BIGINT,                            -- 발번 당시 사용자(있다면)
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    PRIMARY KEY (doc_type_code, issue_date),
    CONSTRAINT fk_docnumseq_type FOREIGN KEY (doc_type_code)
        REFERENCES document_number_types (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 5.3 채번 알고리즘 (의사 코드)

```java
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    private static final ZoneId SG_ZONE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_SEQ = 9999;
    private static final String PRIMARY_PREFIX = "LK";  // 설정 우선 원칙 예외: 회사 고정 브랜드 (§10 참조)

    private final DocumentNumberTypeRepository typeRepository;
    private final DocumentNumberSequenceRepository seqRepository;
    private final Clock clock;

    @Transactional
    public String generate(String docTypeCode) {
        DocumentNumberType type = typeRepository.findActiveByCode(docTypeCode)
            .orElseThrow(() -> new BusinessException("DOC_TYPE_NOT_FOUND", ...));

        LocalDate today = LocalDate.now(clock.withZone(SG_ZONE));

        // 1. row-lock (없으면 삽입 후 lock)
        DocumentNumberSequence seq = seqRepository
            .findByTypeAndDateForUpdate(docTypeCode, today)
            .orElseGet(() -> seqRepository.save(
                DocumentNumberSequence.firstOf(docTypeCode, today)));

        int nextValue = seq.getNextValue();
        if (nextValue > MAX_SEQ) {
            throw new BusinessException("DOC_NUMBER_OVERFLOW",
                "Daily sequence exceeded 9999 for " + docTypeCode + " on " + today,
                HttpStatus.CONFLICT);
        }

        seq.advance();  // next_value++; last_issued_at = now

        return String.format("%s-%s-%s-%04d",
            PRIMARY_PREFIX, type.getPrefix(), today.format(DATE_FMT), nextValue);
    }
}
```

### 5.4 트랜잭션 정책

- **격리 수준**: `READ_COMMITTED` (프로젝트 기본 유지).
- **호출자의 트랜잭션 경계 이슈** — 핵심 설계 결정:
  - **기본**: `generate()`는 호출자의 트랜잭션에 참여(`REQUIRED`). 호출자 트랜잭션이 롤백되면 `next_value` 증가도 롤백 → **번호가 다음 발번 시 재사용**됨.
  - **대안**: `REQUIRES_NEW`로 분리하면 롤백돼도 번호는 "사용됨"으로 남아 **구멍(hole)** 발생.
  - **결정**: **`REQUIRED` 채택**. 이유:
    1. 영수증 발급은 Payment 확인 직후에만 호출되며, Invoice 본 저장 트랜잭션이 실패하면 번호도 무효화되는 게 직관적.
    2. 구멍(hole) 있는 번호는 감사·법적 문서에서 의심 사유가 됨 — 연속성 유지가 선호됨.
    3. UNIQUE 제약(§6)이 있으므로 "롤백된 번호"가 남을 가능성은 없음.

### 5.5 UNIQUE 보강 — 도메인 테이블에서의 유일성

채번은 시퀀스 테이블에서 원자적으로 증가하지만, **발급된 번호가 실제 도메인 테이블에 고유하게 저장되는지**는 각 도메인 테이블의 UNIQUE 제약으로 이중 안전망을 제공한다 (예: `invoices.invoice_number` unique). 이는 시퀀스 테이블이 (매우 낮은 확률로) 오염되더라도 중복 레코드가 DB에 들어가지 않음을 보장한다.

---

## 6. 공통 API

### 6.1 내부 Java API

```java
public interface DocumentNumberService {
    /** 단일 문서 타입에 대해 다음 번호를 발번. */
    String generate(String docTypeCode);

    /** 번호 형식 검증 (유효성만). */
    boolean isValid(String documentNumber);

    /** 번호 파싱 — 감사·디버깅용. */
    Optional<ParsedDocumentNumber> parse(String documentNumber);
}

public record ParsedDocumentNumber(
    String primaryPrefix,     // "LK"
    String docPrefix,         // "RCP"
    LocalDate issueDate,
    int sequence              // 1 ~ 9999
) {}
```

### 6.2 Admin REST API (Phase 2)

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/admin/document-number-types` | `ADMIN`, `SYSTEM_ADMIN` | 문서 타입 목록 |
| POST | `/api/admin/document-number-types` | `ADMIN`, `SYSTEM_ADMIN` | 신규 타입 추가 |
| PATCH | `/api/admin/document-number-types/{code}` | `ADMIN`, `SYSTEM_ADMIN` | 라벨·활성 상태 수정 (prefix 변경은 별도 플로우) |
| DELETE | `/api/admin/document-number-types/{code}` | `SYSTEM_ADMIN` | Soft delete (사용 이력 있으면 경고) |

---

## 7. 날짜 기준 (Timezone)

- **기준 시각**: **Asia/Singapore (UTC+8)**.
- 근거: 프로젝트 내 이미 `Asia/Singapore` 사용이 표준화됨 (`ConciergeNotifier`, `SmtpEmailService`, `LoginActivationService` 등). 영수증 발행·결제 확인도 모두 SG 시간 기준이 자연스러움.
- **DB 저장**: `issue_date`(DATE)와 `issued_at`(DATETIME) 모두 SG 타임존 기준의 날짜로 저장 — 서버 로컬 타임존에 의존하지 않기 위해 `ZonedDateTime → LocalDate`로 변환 후 저장.
- **캘린더 전환 순간**: SG 시간 00:00:00.000에 시퀀스가 새 `issue_date`로 리셋됨. 이는 `next_value=1`인 새 row가 자동 생성되는 것으로 구현(§5.3).
- **열린 질문**: 발행일이 결제 시각을 기준으로 하는 게 맞는지, 또는 admin이 "Issue Date"를 수동 지정하는 케이스가 있는지 확인 필요. 기본은 "발번 시점의 SG 날짜".

---

## 8. 예외 케이스 처리

| # | 케이스 | 처리 |
|---|---|---|
| 1 | **동시 발번 경쟁** | `SELECT FOR UPDATE`로 row-lock. 두 트랜잭션 중 후행은 대기 후 다음 값 획득. |
| 2 | **4자리 초과 (>9999/일)** | `DOC_NUMBER_OVERFLOW` (HTTP 409) 예외. 운영 Alert + 관리자 수동 개입. Phase 2에서 "자정 직전 임계치 초과 경고 알림" 기능 추가 가능. **당분간 9999는 실현 가능성 낮음**(현재 일일 결제 건수 << 100건). |
| 3 | **날짜 변경 시 리셋** | PK가 `(doc_type_code, issue_date)`이므로 새 날짜엔 새 row 생성 + `next_value=1` 자동. |
| 4 | **문서 타입 비활성화(active=false)** | `findActiveByCode`가 empty → `DOC_TYPE_INACTIVE` 400 예외. 과거 발행된 번호는 유효 유지. |
| 5 | **호출자 트랜잭션 롤백** | `REQUIRED` 전파이므로 `next_value` 증가도 롤백 → 번호 재사용 (§5.4). |
| 6 | **레거시 번호와의 충돌** | 기존 `IN20260422001`은 `LK-` 접두가 없어 형식 자체가 다름 → 충돌 불가. 마이그레이션 불필요. |
| 7 | **Prefix 변경 후 과거 번호 조회** | 번호 내의 prefix가 변경 전 값이어도 파싱 로직은 **번호 자체로 완결**이므로 문제 없음. 단 마스터 테이블의 현재 prefix와 다를 수 있다는 점은 Admin UI에서 경고. |
| 8 | **문서 타입 삭제 후 과거 번호 소유 레코드 존재** | Soft delete만 허용, 물리 삭제 금지. `document_number_sequence`의 FK 때문에도 물리 삭제 불가. |
| 9 | **시계 오차** | DB 서버 시각을 `CURRENT_DATE`로 조회하는 건 **피함**. 애플리케이션 `Clock` 주입 후 `Asia/Singapore` 기준 변환으로 통일(테스트 용이성). |
| 10 | **서킷 브레이커 — DB 장애** | 번호 발번 실패는 상위 호출자(예: InvoiceGenerationService)가 처리. 영수증 자동 발행 실패 정책은 invoice-spec §5를 따름 (본 트랜잭션 롤백 금지, 알림 + 수동 재생성). |

### 8.1 취소/환불 시 번호 처리

- **번호 재사용·회수 금지**: 발급된 번호는 `document_number_sequence`에서 이미 소비된 것으로 간주(트랜잭션 커밋된 경우). 취소/환불 시에도 기존 번호는 그대로 두고, 별도의 **Credit Note**(code=`CREDIT_NOTE`, prefix=`CDN`)를 새 번호로 발행한다 (invoice-spec.md P3와 정합).

---

## 9. 영수증 도메인과의 연결점 (Q1=B 확정)

### 9.1 기본 방향

**기존 `Invoice` 엔티티는 유지**하고, **번호 생성기만 공통 엔진에 위임**하도록 리팩터링한다. 별도 `Receipt` 엔티티는 신설하지 않는다(Q1=B). 프로젝트의 E-Invoice가 "영수증 겸 청구서" 성격(invoice-spec §1)이고, 법적·UX적 맥락에서 두 문서를 분리할 니즈가 없기 때문.

### 9.2 호출 시점

`AdminPaymentService.confirmPayment()` 직후(기존 invoice-spec §5와 동일 트리거):

1. `InvoiceGenerationService.generateFromPayment(savedPayment, application)` 호출 (기존과 동일).
2. 내부에서 번호 획득 방식만 변경:
   - 기존: `invoiceNumberGenerator.next(LocalDate.now())` → `IN20260423001`
   - **변경 후**: `invoiceNumberGenerator.next(LocalDate.now())` → 내부에서 `documentNumberService.generate("RECEIPT")` 위임 → `LK-RCP-20260423-0001`
3. `invoices.invoice_number` 컬럼에 새 형식 번호 저장 (컬럼 `VARCHAR(30)` 유지 — 최장 22자로 여유).
4. PDF 생성 → `FileEntity` 저장 → `Invoice` 엔티티 저장 (기존 플로우 그대로).

### 9.3 `InvoiceNumberGenerator` 리팩터링 (P1.4)

기존 클래스를 삭제하지 않고 **공통 엔진 위임 Facade**로 재작성. 호출자(`InvoiceGenerationService`)의 코드는 그대로 두어 영향 범위를 최소화한다.

```java
// AFTER (P1.4)
@Component
@RequiredArgsConstructor
public class InvoiceNumberGenerator {
    private static final String DOC_TYPE_CODE = "RECEIPT";
    private final DocumentNumberService documentNumberService;

    /** 영수증 번호 생성. 내부 구현은 공통 문서번호 엔진에 위임. */
    public String next(LocalDate date) {
        // date 파라미터는 더 이상 사용하지 않음 (DocumentNumberService가 SG 시각 기준으로 자체 결정).
        // 하위 호환을 위해 시그니처 유지. @Deprecated(추후 제거 예정).
        return documentNumberService.generate(DOC_TYPE_CODE);
    }
}
```

- 기존 `InvoiceRepository.countByInvoiceNumberStartingWith` / `existsByInvoiceNumber`는 더 이상 Generator에서 쓰이지 않음. Repository 메서드 자체는 일단 유지(다른 용도 가능성) → P2 정리 시 제거.
- `SystemSettingRepository` 의존성 제거.

### 9.4 기존 테스트 처리

- `InvoiceNumberGeneratorTest` (기존 5개 테스트): **전면 재작성 필요** — mock 대상이 `DocumentNumberService`로 바뀌며, 형식도 `IN20260422003` → `LK-RCP-20260422-0003`. 새 테스트는 "위임이 올바르게 이뤄지는가"만 검증하고, 포맷·충돌 로직은 `DocumentNumberServiceTest`에서 검증.
- `InvoiceGenerationServiceTest`: 번호 형식을 기대하는 assertion이 있으면 새 형식으로 수정.

### 9.5 호환성 — 기존 발행분

- 기존 `IN20260422001` 형식 번호를 가진 invoice 레코드는 **절대 변경하지 않음** (invoice-spec §8 Immutability).
- Invoice 목록/검색 UI는 **두 형식 모두 허용**:
  - 정규식: `^(IN[0-9]{11}|LK-[A-Z]{2,5}-[0-9]{8}-[0-9]{4})$`
- 영수증 번호로 검색 시 두 형식 공존하는 기간 동안 전부 매칭되어야 함. (P1 범위: Frontend 변경 없음. P2에서 Admin 검색 UI에 두 형식 모두 허용하도록 반영 필요 — Phase 2 태스크로 이관.)

---

## 10. 설계 원칙 — 하드코딩 예외

### 10.1 예외 요청 — `LK`(1차 접두어)

```java
private static final String PRIMARY_PREFIX = "LK";  // 설정 우선 원칙 예외: 회사 고정 브랜드
```

- **이유**: `LK`는 LicenseKaki 브랜드 고정 값. 변경될 이유가 없으며, 변경 시 모든 발행 문서의 번호 체계가 깨지므로 **의도적으로 변경 불가** 해야 한다.
- **예외 기록**: 본 문서 및 코드 내 주석으로 명시 (CLAUDE.md §1 "특수 예외 (기록 필수)" 준수).
- **만약 변경이 필요하면**: 마이그레이션 전용 스크립트 + 과거 번호에 대한 alias 매핑 테이블 별도 작업 필요 — Phase 2 이후에 고려.

### 10.2 원칙 준수 항목

- `DOC_PREFIX`: 마스터 테이블(`document_number_types.prefix`)에서만 조회. 코드 하드코딩 금지.
- `MAX_SEQ = 9999`: 형식 상수 — prefix 자체가 아니므로 예외 대상 아님. 9999 초과는 형식 규칙 위반이므로 코드 상수로 OK.
- `Asia/Singapore`: 이미 프로젝트 표준 (`ConciergeNotifier` 등). 시스템 설정으로 빼지 않음.

---

## 11. 데이터 모델 요약

### 11.1 신규 테이블 2개

- `document_number_types` (마스터, §4.2)
- `document_number_sequence` (채번 시퀀스, §5.2)

### 11.2 기존 테이블 변경

- **없음** (P1 기준). 영수증이 시나리오 (B)로 진행되면 `invoices.invoice_number`의 새 값이 길어지므로 컬럼 길이 확인:
  - 현재: `VARCHAR(30)` — 신규 형식 최장 22자(`LK-XXXXX-YYYYMMDD-0001`)이므로 **문제 없음**.

### 11.3 system_settings 변경

- **제거 대상** (Deprecate): `invoice_number_prefix` — 공통 엔진 도입 후 불용. `DatabaseMigrationRunner`에서 시드 유지하되 실제 조회는 안 함. 주석에 `@Deprecated since 2026-04` 표기.
- **신규**: 없음 (모든 설정은 마스터 테이블로).

---

## 12. 검증 기준 (Acceptance Criteria)

- [ ] **AC-1 (형식)**: 생성된 번호는 정규식 `^LK-[A-Z]{2,5}-[0-9]{8}-[0-9]{4}$`와 매칭된다.
- [ ] **AC-2 (유일성)**: 같은 문서 타입 × 같은 날짜 내에서 생성된 번호는 유일하다. 1,000회 반복 생성 후 `distinct count == 1000`.
- [ ] **AC-3 (동시성)**: 50개 스레드가 동시에 `generate("RECEIPT")` 호출 시 중복 번호 0개, 실패 0개, 모두 순차적 `0001~0050`.
- [ ] **AC-4 (날짜 리셋)**: 날짜가 바뀌면 `NNNN`이 `0001`부터 다시 시작한다 (날짜를 Clock으로 주입해 테스트).
- [ ] **AC-5 (SG 타임존)**: UTC 23:30에 호출해도 SG 시간은 다음날 07:30 → `issue_date`가 SG 기준 날짜로 기록된다.
- [ ] **AC-6 (타입별 독립)**: `RECEIPT`와 `INVOICE`가 같은 날 각각 호출될 때 시퀀스는 독립이다(`RCP-...-0001`과 `INV-...-0001`이 공존).
- [ ] **AC-7 (오버플로 검출)**: `next_value`가 9999를 초과하려 할 때 `DOC_NUMBER_OVERFLOW` (409) 발생.
- [ ] **AC-8 (비활성 타입)**: `active=false`인 타입 호출 시 `DOC_TYPE_INACTIVE` (400) 발생. 과거 발행 번호 조회는 성공.
- [ ] **AC-9 (롤백 일관성)**: 호출자 트랜잭션 롤백 시 `document_number_sequence.next_value`도 롤백된다.
- [ ] **AC-10 (설정 우선 준수)**: `DOC_PREFIX`(RCP/INV 등)가 Java 코드에 하드코딩된 문자열로 존재하지 않는다. grep 검증. (예외: `"LK"` 1차 접두어만 허용.)
- [ ] **AC-11 (Admin UI)**: 관리자가 새 Document Type을 UI로 추가하면, 코드 배포 없이 즉시 `generate(newCode)` 가능.
- [ ] **AC-12 (레거시 공존)**: 기존 `IN20260422001` 형식 invoice 레코드는 그대로 유지되며, 새 발급은 `LK-RCP-...` 형식이다.
- [ ] **AC-13 (감사 로그)**: 문서 타입 추가/수정/prefix 변경 시 AuditLog에 `DOC_NUM_TYPE_CREATED/UPDATED/PREFIX_CHANGED` 기록.

---

## 13. Phase 계획

### Phase 1 — 공통 엔진 + 영수증(Invoice) 적용

> 확정된 구현 범위. 아래 P1.x 태스크를 **순서대로** 진행한다. 각 태스크는 단일 커밋 단위로 가능한 수준이며, 최종 커밋 전에 **로컬 검증**(CLAUDE.md §"배포 전 로컬 검증 필수")을 반드시 수행한다.

#### P1.1 — 스키마 및 엔티티 추가

**파일**
- `blue-light-backend/src/main/resources/schema.sql` — 아래 2개 테이블을 `invoices` 테이블 **이전**(§5 시퀀스 테이블이 FK로 `document_number_types` 참조)에 추가.
  - `document_number_types` (§4.2)
  - `document_number_sequence` (§5.2)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/docnumber/DocumentNumberType.java` — 신규 엔티티 (Soft delete 패턴, BaseEntity 상속)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/docnumber/DocumentNumberTypeRepository.java` — `findByCodeAndActiveTrue`, `existsByPrefix` 등
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/docnumber/DocumentNumberSequence.java` — 신규 엔티티. 복합 PK `(docTypeCode, issueDate)`는 `@IdClass` 또는 `@EmbeddedId` 사용 (프로젝트 내 기존 패턴 확인 후 통일).
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/docnumber/DocumentNumberSequenceRepository.java` — `findByDocTypeCodeAndIssueDateForUpdate` (`@Lock(LockModeType.PESSIMISTIC_WRITE)` 적용)

**검증**: 로컬 MySQL에 부트업하여 테이블 생성 확인 (`DESCRIBE document_number_types;` 등).

#### P1.2 — 공통 엔진 서비스 구현

**파일**
- `blue-light-backend/src/main/java/com/bluelight/backend/api/docnumber/DocumentNumberService.java`
  - 의존성: `DocumentNumberTypeRepository`, `DocumentNumberSequenceRepository`, `Clock`
  - `@Transactional` 기본 전파(`REQUIRED`) — §5.4
  - `generate(String docTypeCode)` 메서드: §5.3 의사코드 기반 구현
  - `isValid(String)`, `parse(String)` 유틸 (정규식 `^LK-[A-Z]{2,5}-[0-9]{8}-[0-9]{4}$`)
  - 상수 `PRIMARY_PREFIX = "LK"` 및 주석 `// 설정 우선 원칙 예외: 회사 고정 브랜드 (spec §10.1)`
- `blue-light-backend/src/main/java/com/bluelight/backend/api/docnumber/ParsedDocumentNumber.java` — record 타입

**예외 코드**
- `DOC_TYPE_NOT_FOUND` (404), `DOC_TYPE_INACTIVE` (400), `DOC_NUMBER_OVERFLOW` (409) — `BusinessException` 사용

**Clock 빈**: 프로젝트 내 `Clock` 빈이 있는지 grep 확인. 없으면 `@Configuration` 클래스에 `@Bean public Clock clock() { return Clock.system(ZoneId.of("Asia/Singapore")); }` 추가 — 단 기존 관례와 충돌하지 않도록 `ConciergeNotifier` 등이 `ZoneId.of("Asia/Singapore")`를 직접 쓰는 스타일을 확인하고 통일.

#### P1.3 — 마스터 데이터 시드

**파일**
- `blue-light-backend/src/main/java/com/bluelight/backend/config/DatabaseMigrationRunner.java`
  - `seedDocumentNumberTypes()` 신규 메서드 추가 (`seedSystemSettings()`와 동일 패턴).
  - `RECEIPT` 1건만 시드: `(code='RECEIPT', prefix='RCP', label_ko='영수증', label_en='Receipt', description='결제 영수증 (E-Invoice)', active=true, display_order=10)`
  - 멱등성: `existsById("RECEIPT")` 체크 후 없을 때만 insert.
  - `run()` 메서드 상단에 호출 추가 (시드 순서는 `seedSystemSettings()` 직후 권장).

**검증**: 로컬 DB drop 후 재기동 시 `SELECT * FROM document_number_types;` 로 `RECEIPT` 1건 확인.

#### P1.4 — `InvoiceNumberGenerator` 위임 전환

**파일**
- `blue-light-backend/src/main/java/com/bluelight/backend/api/invoice/InvoiceNumberGenerator.java`
  - 의존성을 `DocumentNumberService` 하나로 교체.
  - `InvoiceRepository`, `SystemSettingRepository` 의존성 제거.
  - `next(LocalDate)` 시그니처는 **유지**(호출자 영향 최소화). 내부 구현은 `documentNumberService.generate("RECEIPT")`.
  - 클래스·메서드에 `@Deprecated(since = "2026-04", forRemoval = true)` 표기 + Javadoc에 "Phase 2에서 완전 제거, 호출자는 직접 `DocumentNumberService` 사용" 명시.
- `InvoiceGenerationService.java` — 기존 `invoiceNumberGenerator.next(LocalDate.now())` 호출부는 수정 불필요(시그니처 그대로).

**검증**: `grep -rn "invoiceNumberGenerator" src/main` 로 호출 지점이 변경되지 않았음을 확인.

#### P1.5 — Deprecated system_setting 표기

**파일**
- `blue-light-backend/src/main/java/com/bluelight/backend/config/DatabaseMigrationRunner.java`
  - `seedSystemSettings()` 내 `invoice_number_prefix` 시드 라인에 `// @Deprecated 2026-04: DocumentNumberService로 대체. Phase 2에서 제거 예정.` 주석 추가.
  - description 필드에도 `[DEPRECATED 2026-04]` 접두 추가.
  - 실제 row는 유지 (Q7 결정).

#### P1.6 — 테스트

**신규 테스트**
- `blue-light-backend/src/test/java/com/bluelight/backend/api/docnumber/DocumentNumberServiceTest.java` — 단위 테스트 (Mockito):
  - 정상 발번 → 포맷 정확성 (AC-1)
  - 비활성 타입 → `DOC_TYPE_INACTIVE` (AC-8)
  - 존재하지 않는 타입 → `DOC_TYPE_NOT_FOUND`
  - 9999 초과 → `DOC_NUMBER_OVERFLOW` (AC-7)
  - `Clock` 주입으로 특정 날짜 강제 후 포맷 확인 (AC-5)
  - `isValid` / `parse` 유틸 검증
- `blue-light-backend/src/test/java/com/bluelight/backend/api/docnumber/DocumentNumberServiceIntegrationTest.java` — 통합 테스트 (`@SpringBootTest`, `@Transactional`은 쓰지 않음):
  - 실제 DB(H2 또는 MySQL TestContainer — 프로젝트 관행 확인 후 선택)에 대해 `generate("RECEIPT")` 호출 → `document_number_sequence.next_value` 증가 확인.
  - **동시성 테스트 (AC-3)**: `ExecutorService` + `CompletableFuture.allOf`로 50개 스레드 병렬 호출 → 중복 0, 누락 0, 결과 집합이 `LK-RCP-{date}-0001`~`LK-RCP-{date}-0050` 정확히 일치 확인.
  - 날짜 변경 리셋 (AC-4): `Clock`을 다른 날짜로 교체 후 `next_value`가 1로 리셋됨을 확인.
  - 타입별 독립 (AC-6): 두 번째 타입을 임시로 추가하여 시퀀스가 독립임을 확인.
- `blue-light-backend/src/test/java/com/bluelight/backend/api/invoice/InvoiceNumberGeneratorTest.java` — 기존 5개 테스트 **재작성**:
  - Mock 대상: `DocumentNumberService`.
  - `next(any)` 호출 시 `documentNumberService.generate("RECEIPT")`가 1회 호출되고 반환값이 그대로 전달되는지 verify.
  - 포맷·충돌 로직은 `DocumentNumberServiceTest`가 책임지므로 여기선 **위임 검증만**.

**회귀 테스트**
- `InvoiceGenerationServiceTest.java` — 기존 테스트 내 invoice number 형식 기대값(`IN\d{11}` 패턴)이 있는지 확인, 있으면 `LK-RCP-\d{8}-\d{4}` 패턴으로 수정.

**로컬 검증 절차 (CLAUDE.md 준수)**
1. `cd blue-light-backend && docker compose up -d`
2. 로컬 DB drop → 재부트 → 시드 확인 (`SELECT * FROM document_number_types;`)
3. `./gradlew test` 전체 통과
4. 수동 시나리오: 로컬 프론트엔드에서 결제 확인 플로우 실행 → invoice PDF 생성 → invoice_number가 `LK-RCP-20260423-0001` 형식인지 DB 확인
5. 정상 동작 확인 후에만 커밋

### Phase 2 — Admin UI + 타 도메인 확장 (목표: 2주)

| # | 작업 | 산출물 |
|---|---|---|
| P2.1 | Admin REST API (`/api/admin/document-number-types/*`) | `api/admin/AdminDocumentNumberTypeController.java` |
| P2.2 | `SystemSettingsPage`에 "Document Number Types" 탭 (목록/추가/수정/비활성) | frontend |
| P2.3 | `InvoiceNumberGenerator` 완전 제거, 호출부 직접 `DocumentNumberService` 사용 | invoice 도메인 |
| P2.4 | SLD Order / LEW Service Order / Concierge Request / Credit Note 시드 및 호출부 연결 (Q3 최종 확정) | 각 도메인 |
| P2.5 | `invoice_number_prefix` system_setting **row 제거** + 관련 코드 참조 제거 확인 | `DatabaseMigrationRunner.java` |
| P2.6 | Admin Invoice 검색 UI — 두 형식(`IN...` / `LK-RCP-...`) 모두 허용 | frontend |

### Phase 3 — 확장성·운영 (목표: 옵션)

| # | 작업 |
|---|---|
| P3.1 | 일별 시퀀스 임계치 경고 (예: 8000건 초과 시 Admin 알림) |
| P3.2 | 연간 통계 대시보드 (type별 발행량 추이) |
| P3.3 | Credit Note / Refund 전용 플로우 — invoice-spec §P3과 통합 |
| P3.4 | 번호 검색/자동완성 API (고객지원용) |

---

## 14. 엣지 케이스

1. **테스트 환경에서 `Clock` 고정**: `FixedClock` 빈을 `@TestConfiguration`으로 주입 → 날짜 의존 테스트 재현 가능.
2. **DB 재기동 시 시퀀스 무결성**: `document_number_sequence`의 `next_value`는 DB에 저장되므로 재기동 영향 없음.
3. **다중 백엔드 인스턴스**: 운영에서 server 2대 이상 돌려도 row-lock이 인스턴스 간 상호 배제를 보장.
4. **마스터 테이블 FK 변경**: `document_number_types.code` PK는 수정 금지(실수 방지). 라벨/prefix만 수정. prefix 변경은 별도 warn 플로우.
5. **DST 변경**: SG는 DST 없음 (UTC+8 고정).
6. **날짜 경계에서 발번**: 트랜잭션이 오래 열려 있어 `LocalDate.now()` 계산 시점과 커밋 시점 사이에 날짜가 바뀔 수 있음 → `generate()` 함수는 단일 트랜잭션 내 빠르게(수 ms) 종료되도록 유지. 날짜 경계 영향은 실측 가능.
7. **숫자 포맷 패딩 실패**: `%04d`로 고정 폭 보장. `next_value`가 10000 이상이면 오버플로 예외가 선행.
8. **재시도 없음**: row-lock 사용 시 재시도 루프 불필요. invoice-spec의 "최대 5회 재시도"는 공통 엔진에서는 **제거**.

---

## 15. 범위 외 (Out of Scope)

- 발급된 번호의 **재발급/교체** (재발급이 필요한 경우는 항상 새 번호 발행; 원래 번호는 감사 기록으로만 유지)
- **Credit Note 실제 구현**: 번호 체계는 여기서 정의하지만, Credit Note 엔티티·워크플로우·PDF 포맷은 invoice-spec P3
- **Legacy invoice 번호 마이그레이션** (`IN20260422001` → `LK-INV-20260422-0001`): 불가 (Immutability)
- **다국어 형식**: 번호 형식 자체는 영어 문자만 사용 (국제 표준)
- **QR 코드로서의 번호**: 번호를 QR로 인코딩해 영수증 하단에 박는 기능은 별도 스펙
- **JWT/토큰 형식과 유사한 난독화**: 번호는 의도적으로 **가독성 우선**이며 순차성 노출은 허용 (§16 보안 섹션 참조)

---

## 16. 보안 / PDPA 고려

### 16.1 열거 공격 가능성

- 번호가 `LK-RCP-20260423-0001`처럼 **순차적이고 예측 가능**하므로, `0002, 0003, ...`를 열거해 타인의 영수증 URL에 접근하려는 시도가 이론상 가능.
- **완화**: 영수증 조회 API(`/api/applications/{id}/invoice`)는 **documentNumber가 아닌 application_id 또는 invoice_seq 기반**이며, 접근 제어는 "본인 소유" 검증 + admin 전용(invoice-spec §4). 번호가 유출돼도 URL 추측으로 접근 불가.
- **검증 항목**: 번호 기반 직접 조회 API는 **Phase 2 이후에도 만들지 않는다**. 번호는 단지 사용자가 육안으로 보고·보조적으로 검색하는 식별자.

### 16.2 PDPA

- 번호 자체엔 PII 없음(타입 + 날짜 + 순번).
- 번호 검색 결과 화면은 기존 PDPA 접근 제어(본인 소유 / admin) 재사용 → 신규 위험 없음.
- 감사 로그에 번호만 남는 건 **PII 수집 아님**.

### 16.3 감사 로깅

다음 AuditAction 추가 제안:
- `DOC_NUMBER_GENERATED` — 채번 시점 (type_code, issued_number, issue_date 기록)
- `DOC_NUM_TYPE_CREATED`
- `DOC_NUM_TYPE_UPDATED`
- `DOC_NUM_TYPE_PREFIX_CHANGED` (중요 — 운영 추적용)
- `DOC_NUM_TYPE_DEACTIVATED`

---

## 17. UX 고려

### 17.1 사용자가 번호를 보는 위치

| 화면 | 용도 |
|---|---|
| MyPage 결제 내역 | "영수증 번호: `LK-RCP-20260423-0001`" 표시, 클릭 시 PDF 다운로드 |
| PDF 영수증 상단 | 큰 글씨로 표기 |
| 이메일 본문 | 결제 확인 메일에 번호 포함 |
| 고객지원 문의 | 사용자가 번호를 검색·복사해서 제시 |
| Admin 영수증 목록 | 검색/필터 |

### 17.2 가독성 / 복사

- 구분자 `-` 덕분에 더블클릭 시 세그먼트 단위 선택 가능.
- 총 19~22자 — 이메일/슬랙에 붙여도 줄바꿈 없이 표현 가능.
- 숫자만 있는 기존 `IN20260422001`보다 **사람이 "이건 무슨 문서구나"**를 즉시 인지 가능 (RCP=영수증).

### 17.3 대소문자 일관성

- 표기는 **대문자 고정** (`LK-RCP-...`).
- 검색 시에는 `UPPER()`로 대소문자 무시하여 매칭 (Phase 2 관리 UI).

---

## 18. 필요한 에이전트 지원 — 판단 결과

기획 단계에서 다음 에이전트 호출을 검토했다.

| Agent | 호출 여부 | 이유 |
|---|---|---|
| strategist | **미호출** | 영수증 번호 체계는 싱가포르 IRAS/GST 가이드에 명시적 규제 없음 (GST 등록 사업자만 tax invoice 번호 규칙 적용 — 본 프로젝트는 GST 면세). 경쟁사 벤치마킹은 `LK-RCP-YYYYMMDD-NNNN` 형식이 업계 관행(프리픽스+날짜+순번) 범주에 들어 있음. 추가 조사 편익 < 비용. |
| developer | **미호출** (본 기획 내 자문으로 갈음) | Spring Boot 동시성 제어 방식은 공통 지식 범주. `SELECT ... FOR UPDATE` 패턴이 MySQL 8 + JPA 표준. 구현 단계(Phase 1.2)에서 개발자가 판단·조율 가능한 여지 있음. |
| security-expert | **미호출** | §16에서 열거 공격 가능성 자체를 판단 완료 — "번호 기반 직접 조회 API를 만들지 않으면 위험 없음"이 결론. PDPA 간접 영향도 없음. 실제 공통 엔진 완성 시 Phase 2 단계에서 보안 리뷰 요청 가능. |
| ux-expert | **미호출** | §17에서 가독성·복사·검색 관점 고려 완료. 구분자 `-`·대문자 고정·자릿수 고정 등 기본 원칙으로 충분. Phase 2 Admin UI 상세 디자인 단계에서 호출 예정. |

**결론**: 기획 단계에서는 별도 에이전트 호출 없이 본 문서로 충분. Phase 2 Admin UI 디자인 시점에 ux-expert, 구현 후 보안 리뷰 시점에 security-expert 호출 권장.

---

## 19. 확정된 결정 (2026-04-23)

> 결정 일자: **2026-04-23** · 결정권자: **ringo@contigo.im** · 기록: product-manager

| # | 질문 | 확정 결정 | 근거 |
|---|---|---|---|
| **Q1** | 영수증 vs 인보이스 관계 | **B — 기존 `Invoice` 엔티티 유지, 번호 생성만 공통 엔진에 위임** | 현재 E-Invoice가 "영수증 겸 청구서" 성격이며, 분리할 법적/UX 니즈가 없음. 중복 엔티티 회피. (§9 반영) |
| **Q2** | 영수증 접두어 | **`RCP`** | Receipt 축약. 싱가포르 회계 관행에서 일반적. (§3.3 반영) |
| **Q3** | 타 문서 타입 접두어 | Phase 2 킥오프 시점에 최종 확정 | 현재 제안값(SLO/LSO/CCR/LTR/CDN)은 참고용. P1에서는 `RECEIPT` 하나만 시드. |
| **Q4** | 날짜 기준 타임존 | **`Asia/Singapore`** | 프로젝트 표준(ConciergeNotifier/SmtpEmailService 등 선례). |
| **Q5** | 트랜잭션 전파 정책 | **`REQUIRED`** (호출자 롤백 시 번호도 롤백) | 구멍(hole) 없는 연속 번호가 법적 문서 감사에 유리. UNIQUE 제약으로 중복 안전망. |
| **Q6** | 일일 9999 초과 | 4자리 고정 유지 | 현재 일일 결제량 << 100건. Phase 3에서 임계치 경고 기능만 추가, 자릿수 확장은 재검토 시 결정. |
| **Q7** | `invoice_number_prefix` system_setting | **유지 + Deprecated 주석만** | 제거 시 참조 검증 비용 > 유지 비용. Phase 2에서 row 제거. (P1.5 반영) |

---

## 20. 참고

- `CLAUDE.md` §1 설계 원칙 — "설정 우선", 하드코딩 금지
- `doc/Project Analysis/invoice-spec.md` — Receipt/Invoice 도메인 모델, Immutability 정책
- `blue-light-backend/.../invoice/InvoiceNumberGenerator.java` — 기존 (리팩터링 대상)
- `blue-light-backend/src/main/resources/schema.sql:200` (`document_type_catalog`), `:754` (`invoices`)
- `blue-light-backend/.../setting/SystemSetting.java` — 단순 key-value 확장 패턴
- `blue-light-backend/.../payment/PaymentReferenceType.java` — 다형 참조 enum 패턴 (문서 타입 extension 시 참고)
- MySQL 8 `SELECT ... FOR UPDATE` + InnoDB row-level lock
