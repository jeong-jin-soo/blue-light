# SLD self-upload → LEW 작성 전환 + SLD 요금 청구 스펙

> 상태: 설계 확정(구현 대기). 작성 2026-06-20.
> 관련 정본: `kva-postpayment-adjustment-spec.md`(사후 정산 원장), `payment-gateway-marketplace-spec.md`(결제 게이트), `invoice-spec.md`(인보이스 재발행).

## 1. 배경 / 문제

신청자는 신청 생성 시 SLD 제출 방식(`sldOption`)을 고른다: `SELF_UPLOAD`(직접 업로드) / `SUBMIT_WITHIN_3_MONTHS`(EMA 3개월 유예) / `REQUEST_LEW`(LEW 작성 요청). SLD 작성비(`master_prices.sld_price`)는 **REQUEST_LEW일 때만** 견적(`quoteAmount`)에 가산된다.

현실: 신청자가 "직접 SLD를 내겠다"고 했어도 **(a) 끝내 제출하지 못하거나 (b) 제출한 SLD가 유효하지 않을** 수 있다. 이 경우 LEW가 SLD를 작성해 넣어야 하고, **SLD 작성 요금이 추가 청구되어야 한다.**

핵심 난점은 **타이밍**이다. 결제 게이트가 kVA 확정 단독으로 조기화(2026-06-18)되었기 때문에, SLD 무효 사실은 **결제 이후에 드러나는 경우가 많다** → 사후 추가 청구가 필요.

### 현재 모델 (확인된 사실)
- `Application.sldOption`은 생성 시 1회 설정, **변경 경로 없음**. `SldRequest`는 REQUEST_LEW일 때만 생성.
- `quoteAmount = tierPrice + calloutFee(NEW) + sldFee(REQUEST_LEW) + emaFee`. `Application.sldFee`는 생성 스냅샷(nullable).
- 결제 게이트 = `kvaStatus == CONFIRMED` 단독.
- 결제 후 정산 원장 `kva_adjustment_record` 존재: `previous/new_quote_amount`, `amount_difference`(부호 있음), `admin_payment_adjustment`(PENDING/PAID_DIFFERENCE/REFUNDED/WAIVED), `settled_amount`, `receipt_reference_number`, soft-delete 미적용 ledger. 단 현재 **kVA 변경 전용**.
- `KvaPostPaymentService.recalculateQuote()`는 sldOption이 REQUEST_LEW면 **sldFee를 이미 가산** → 사후 SLD 요금 추가는 기존 정산 기계장치 재사용 가능.

## 2. 결정 사항

| # | 결정 | 비고 |
|---|------|------|
| D-1 | 사후 추가 SLD 요금은 **기존 정산 원장 재사용** | `kva_adjustment_record`를 "견적 조정 원장"으로 일반화(`adjustment_type` 추가) |
| D-2 | 전환 트리거 = **LEW 개시 + ADMIN 정산** | LEW가 무효/미제공 발견 → 전환 개시. 사후 수금·정산은 ADMIN/재무 |
| D-3 | 신청자 동의 = **통보만(동의 불필요)** | E1은 결제요청 금액으로 고지, E2는 통보 알림 |
| D-4 | 전환 진입점 2개: **E1 결제요청 시 선택(권장/조기)** + **E2 사후 전환(폴백)** | 둘 다 동일 도메인 로직 공유 |
| D-5(확정대기) | **발급 게이트**: `sldOption==REQUEST_LEW`면 발급 전 `SldRequest=CONFIRMED` 요구 | 요금 받고 SLD 없이 발급 방지. 권장=포함 |
| D-6(확정대기) | **정산 게이트**: 미결제 보충청구(PENDING adjustment) 있으면 완료 차단 | kVA 정산과 동일 원칙. 권장=포함 |

## 3. 도메인 모델 변경

### 3.1 Application
신규 도메인 메서드:
```java
/**
 * self-upload/3개월유예 → LEW 작성(REQUEST_LEW) 전환 + SLD 요금 가산.
 * status 는 변경하지 않는다(전환은 검토/결제후 단계 어디서든 가능).
 */
public void switchSldToLewCreated(BigDecimal sldFee, BigDecimal newQuoteAmount, User actor) {
    if (this.sldOption == SldOption.REQUEST_LEW) {
        throw new IllegalStateException("Already LEW-created SLD");
    }
    this.sldOption = SldOption.REQUEST_LEW;
    this.sldFee = sldFee;            // 전환 시점 master_prices 스냅샷
    this.quoteAmount = newQuoteAmount;
}
```
- `status`/`kvaStatus`/`kvaSource` 불변. 변경 이력은 §6 원장에 기록.

### 3.2 정산 원장 일반화 (`kva_adjustment_record`)
- 컬럼 추가: `adjustment_type VARCHAR(20) NOT NULL DEFAULT 'KVA_CHANGE'` — enum `KVA_CHANGE | SLD_ADDED`.
- 기존 행은 마이그레이션으로 `KVA_CHANGE` 백필.
- `SLD_ADDED` 행: `previous_kva == new_kva`(kVA 불변), `amount_difference = +sldFee`, `reason = "SLD switched to LEW-created (applicant SLD unavailable/invalid)"`, `changed_by_role = LEW or ADMIN`.
- 테이블/엔티티명은 유지(ledger 연속성)하되 의미는 "견적 조정"으로 확장. (전면 리네이밍은 비용 대비 효익 낮아 보류.)

### 3.3 SldRequest
- 전환 시 `SldRequest`가 없으면 생성(status=REQUESTED). 있으면 재사용. 이후 기존 LEW 업로드/CONFIRM 흐름(`AdminSldController` sld-uploaded/sld-confirm) 그대로.

## 4. 동선

### E1 — 결제요청 시 LEW 선택 (권장, 결제 전, 원장 없음)
1. LEW가 LEW Review에서 "Request payment" 클릭.
2. `sldOption != REQUEST_LEW`이면 확인 다이얼로그에 토글 노출: **"I will create the SLD (+$X SLD fee)"** (기본 off). $X = 현재 kVA tier의 `sld_price`.
3. 토글 ON + 확인 → `requestPayment(addSldFee=true)`:
   - 트랜잭션 내에서 `switchSldToLewCreated(sldFee, recalc(quote))` 호출 + `SldRequest` 생성.
   - 인보이스 재발행(§7).
   - status → PENDING_PAYMENT 전이(기존). 결제요청 알림(A-17)의 **금액이 갱신된 총액** → 신청자 고지.
4. 토글 OFF → 기존 동작(self-upload 유지, SLD 요금 없음).

### E2 — 사후 전환 (폴백, 타이밍 분기)
독립 액션(SLD 섹션). LEW 개시:
- `POST /api/lew/applications/{id}/sld/convert-to-lew` (담당 LEW) + ADMIN 등가 경로.
- 서비스 분기:
  - **결제 전**(PENDING_REVIEW/REVISION_REQUESTED): E1과 동일(quote 갱신 + 인보이스 재발행, 원장 없음). 결제요청 전이면 이후 결제 시 갱신 총액 반영.
  - **결제 후**(PAID/IN_PROGRESS): `switchSldToLewCreated` + **조정 원장 기록**(type=SLD_ADDED, amount_difference=+sldFee, admin_payment_adjustment=PENDING) + 인보이스 재발행 + **ADMIN 정산 알림(신규)** + 신청자 통보 알림(신규).
  - **COMPLETED / EXPIRED**: 차단(409).
- ADMIN이 기존 kVA 정산 패널에서 차액 수금(PayNow) 후 `PAID_DIFFERENCE`로 수기 정산.

## 5. 요금 계산
- sldFee = `masterPriceRepository.findByKva(selectedKva).getSldPrice()`.
- newQuote 재계산은 `recalculateQuote` 로직 재사용(tier + callout(NEW) + sldFee + ema). sldOption을 REQUEST_LEW로 set 후 호출하면 sldFee 자동 포함.
- 가격 조회 API는 기존 `GET /api/prices/calculate?kva=..&sldOption=REQUEST_LEW`로 프론트 다이얼로그 금액 표시.

## 6. 정산 (D-1)
- 결제 후 경로만 원장 기록. 결제 전 경로는 quote 갱신으로 끝(신청자가 정확 총액 결제).
- 원장/정산 UI/수기 흐름은 kVA 사후조정과 100% 공유. `adjustment_type=SLD_ADDED` 필터/배지만 추가.

## 7. 인보이스
- `KvaPostPaymentService.invalidateAndRegenerateInvoice(...)`를 공용 헬퍼로 추출/재사용해 전환 시 인보이스 무효화+재발행. (`invoice-spec.md` 준수.)

## 8. 게이트 (D-5/D-6, 확정 대기)
- **발급 게이트**: `AdminApplicationService.completeApplication`에 `sldOption==REQUEST_LEW && SldRequest.status != CONFIRMED → 409 SLD_NOT_CONFIRMED` 추가.
- **정산 게이트**: 완료 전 `adjustment_type=SLD_ADDED && admin_payment_adjustment==PENDING` 존재 시 `409 SLD_FEE_NOT_SETTLED`. (kVA PENDING 정산 게이트와 통합.)

## 9. 엔드포인트
| 메서드 | 경로 | 권한 | 용도 |
|--------|------|------|------|
| POST | `/api/lew/applications/{id}/request-payment` | 담당 LEW | body `{ addSldFee?: boolean }` 추가 (E1) |
| POST | `/api/lew/applications/{id}/sld/convert-to-lew` | 담당 LEW | E2 전환(타이밍 분기) |
| POST | `/api/admin/applications/{id}/sld/convert-to-lew` | ADMIN/SYSTEM_ADMIN | E2 ADMIN 등가 |
| (기존) | `/api/admin/applications/{id}/sld-uploaded`·`/sld-confirm` | LEW/ADMIN | 전환 후 작성/확정 |
| (기존) | kVA 정산 패널 API | ADMIN | SLD_ADDED 정산 공유 |

## 10. 프론트엔드
- **결제요청 다이얼로그(E1)**: `LewReviewFormPage` 결제요청 ConfirmDialog에 SLD 요금 토글 + 금액. ON 시 `addSldFee` 전달.
- **SLD 섹션(E2)**: SELF_UPLOAD/3개월유예일 때 LEW/ADMIN용 "Applicant can't provide a valid SLD — I'll create it (+$X)" 액션 + 확인(요금·타이밍·결제후면 보충청구 고지). 전환 후 기존 REQUEST_LEW 업로드/CONFIRM UI 노출.
- **ADMIN 정산**: 기존 kVA 조정 정산 화면에 SLD_ADDED 행 표시(배지 "SLD fee").
- **신청자**: 인보이스/금액 갱신, 통보 알림.

## 11. 알림 (신규 템플릿)
- (E1) 별도 신규 없음 — 결제요청 A-17 금액이 고지.
- (E2 결제 후) 신청자 통보: "Your LEW will prepare the SLD; an additional SLD fee of $X applies." (인앱+이메일)
- (E2 결제 후) ADMIN 정산 요청: "SLD fee $X pending settlement for APP-xxxx." (기존 결제확인 알림 패턴 재사용)

## 12. 마이그레이션 (DatabaseMigrationRunner, 멱등)
- `kva_adjustment_record.adjustment_type` 컬럼 추가(IF NOT EXISTS) + 기존 행 `KVA_CHANGE` 백필.
- 신규 에러코드: `SLD_NOT_CONFIRMED`, `SLD_FEE_NOT_SETTLED`, `SLD_ALREADY_LEW`.

## 13. 엣지 케이스
- 이미 REQUEST_LEW: 전환/토글 미노출, 호출 시 409 `SLD_ALREADY_LEW`.
- `SUBMIT_WITHIN_3_MONTHS`도 전환 대상(self-upload와 동일 취급).
- kVA 사후조정과 동시 발생: recalc가 sldFee를 항상 포함하므로 정합. 두 조정은 각각 원장 행.
- 전환 취소/오작동: row 삭제 금지(ledger). 정정은 ADMIN 반대 부호 행(향후) — 1차 범위 외.
- COMPLETED 후 발견: 1차 범위 외(차단). 필요 시 재오픈 정책 별도.

## 14. 수용 기준 (AC)
- AC1: self-upload 신청에서 LEW가 결제요청 시 SLD 요금 토글 ON → 결제요청 금액 = 기존 + sldFee, sldOption=REQUEST_LEW, SldRequest 생성, 원장 없음.
- AC2: 결제 후 LEW가 convert-to-lew → 원장 SLD_ADDED(PENDING, +sldFee) 생성, 인보이스 재발행, 신청자/ADMIN 알림.
- AC3: ADMIN이 SLD_ADDED 차액을 PAID_DIFFERENCE로 정산 가능(기존 패널).
- AC4(D-5): REQUEST_LEW인데 SLD 미CONFIRMED면 완료 차단(409 SLD_NOT_CONFIRMED).
- AC5(D-6): SLD_ADDED PENDING 정산 미완이면 완료 차단(409 SLD_FEE_NOT_SETTLED).
- AC6: 이미 REQUEST_LEW면 전환 불가(409), 토글 미노출.

## 15. 구현 순서(PR 제안)
- PR1: 도메인(`switchSldToLewCreated`) + 원장 `adjustment_type` + 마이그레이션 + 단위테스트.
- PR2: E1(requestPayment `addSldFee`) + 프론트 결제요청 토글.
- PR3: E2(convert-to-lew 엔드포인트, 타이밍 분기, 결제후 원장/알림) + SLD 섹션 액션.
- PR4: 게이트(D-5/D-6) + ADMIN 정산 패널 SLD_ADDED 노출.
- PR5: 알림 템플릿 시드 + 인보이스 헬퍼 추출.
