# Phase 6 — Data Model

**목표**: `Application.selectedKva`와 `CertificateOfFitness.approvedLoadKva`의 역할을 명확히 분리. `Application.selectedKva`를 운영 진본(Single Source of Truth, SSOT)으로 확정하고, CoF는 finalize 시점의 법적 스냅샷만 보관.

---

## 1. 필드 역할 요약

| 필드 | 성격 | 저장 대상 | 수명 |
|---|---|---|---|
| `Application.selectedKva` | **운영 데이터** (SSOT) — 가격 산정·결제·발주·EMA 제출의 진본값 | `applications.selected_kva` | 생성 후 변경 가능 (UNKNOWN → CONFIRMED → ADMIN override 허용, 결제 후 lock) |
| `Application.kvaStatus` | 확정 상태 플래그 | `applications.kva_status` (UNKNOWN\|CONFIRMED) | 동일 |
| `Application.kvaSource` | 값 출처 | `applications.kva_source` (USER_INPUT\|LEW_VERIFIED) | 동일 |
| `CoF.approvedLoadKva` | **법적 기록** — EMA ELISE 제출 문서상 수치 | `certificate_of_fitness.approved_load_kva` | finalize 시점 snapshot, 이후 immutable |

**핵심 원칙**
- LEW가 CoF 폼에서 `approvedLoadKva`를 **임의 입력 불가**.
- Draft 단계에서 `CoF.approvedLoadKva`는 `Application.selectedKva`를 거울처럼 반영(매 Save 시 동기화).
- Finalize 순간 `CoF.approvedLoadKva := Application.selectedKva`로 스냅샷 확정.

---

## 2. 생명주기 타임라인

```
시각        Application.selectedKva       CoF.approvedLoadKva       CoF.finalized
─────────────────────────────────────────────────────────────────────────
T0 (신청)   X (applicant input, UNKNOWN)  —                         —
T1 (CoF Draft 생성)   X                   X (derived, 거울값)        false
T2 (LEW kVA 확정)     Y (CONFIRMED)       X (Draft 덮어쓰기 Y)       false  ← 다음 Draft 저장 시
T3 (LEW finalize)     Y                   Y (snapshot, 확정)         true
                                           ──────── IMMUTABLE ────────
T4 (Edge: ADMIN override, PENDING_PAYMENT)
  - pre-PAID   Y' (new)                  Y' (re-snapshot via         false  ← reopen
                                              reopenForReissue)
  - post-PAID  409 KVA_LOCKED_AFTER_PAYMENT — 상태 변경 없음
T5 (LEW 재서명)   Y'                       Y' (스냅샷 재확정)          true
```

---

## 3. 불변성 & 스냅샷 정책

### 3-1. Draft 단계 (finalized=false)
- `CoF.approvedLoadKva`는 `Application.selectedKva`와 항상 같아야 한다.
- 동기화 지점:
  - **Draft Save** (`LewReviewService.saveDraftCof`) — request 값 무시하고 `application.getSelectedKva()` 사용
  - **초기 Draft 생성** — 동일

### 3-2. Finalize 순간
- `CoF.snapshotApprovedLoadKva(application.getSelectedKva())` 호출
- 이후 `cof.finalize(...)` 호출 → `certifiedAt = now()` 세팅
- 이 시점 이후 `CoF.approvedLoadKva`는 **immutable**

### 3-3. 재발급 (Edge Case "b")
- 조건: `Application.status == PENDING_PAYMENT` && `CoF.finalized == true` && `force == true`
- `CoF.reopenForReissue(newKva)` 실행:
  1. `certifiedAt = null`
  2. `lewConsentDate = null`
  3. `approvedLoadKva = newKva` (새 스냅샷 준비)
  4. `certifiedByLew`는 **보존**(감사 추적용, 재finalize 시 덮어씀)
- `Application.reopenForCofReissue()` 실행: status `PENDING_PAYMENT → PENDING_REVIEW`
- LEW가 다시 finalize하면 `snapshotApprovedLoadKva`가 다시 실행되어 최종 값으로 고정

### 3-4. kvaStatus는 override 후에도 CONFIRMED 유지
- ADMIN override는 `kvaStatus=CONFIRMED` + `kvaSource=LEW_VERIFIED`를 유지(`Application.confirmKva(force=true)`)
- 이번 Phase에서는 "override 후 kvaStatus=UNKNOWN 회귀" 정책을 **채택하지 않음**.
  - 이유: ADMIN override는 비즈니스적으로 확정 행위이며, LEW 재서명만 남은 상태. kVA를 다시 UNKNOWN으로 돌리면 LEW가 kVA 탭에서 한 번 더 Confirm해야 하는 불필요한 단계가 추가됨.
  - 단, CoF는 재서명을 강제 — 법적 기록이 운영 값과 달라지면 안 되므로.

---

## 4. Draft vs Finalized 상태 매트릭스

| 상태 | CoF.approvedLoadKva | 수정 경로 | LEW UI 표시 |
|---|---|---|---|
| CoF 레코드 없음 | — | saveDraftCof 호출 시 자동 생성 | — |
| Draft (finalized=false) | = Application.selectedKva | Draft Save 때마다 자동 동기화 | read-only 카드, 값은 Application에서 |
| Finalized (finalized=true) | 스냅샷 확정값 (immutable) | 수정 불가. 재발급으로만 reopen | read-only, 값은 CoF.approvedLoadKva 자체 |
| Reopened (finalized=false, 이전 finalized 이력 있음) | = Application.selectedKva (reopen 시 새 값) | 재finalize로 확정 | read-only |

---

## 5. 하위호환성

### 5-1. 기존 레코드 처리
- 이미 finalized된 CoF: `approvedLoadKva` 이미 기록됨 → 그대로 immutable 유지
- Draft 상태 CoF with stale `approvedLoadKva` ≠ Application.selectedKva: 다음 saveDraft/finalize 시 자동 일치됨
- 마이그레이션 스크립트 **불필요** (saveDraft가 항상 동기화하므로)

### 5-2. API 하위호환성
- `PUT /api/lew/applications/{id}/cof` request body의 `approvedLoadKva` 필드는 계속 받지만 **ignore**
- 클라이언트가 잘못된 값을 보내더라도 서버는 Application.selectedKva로 덮어씀 (멱등)
- 프론트엔드는 Phase 6 PR 2부터 request에서 approvedLoadKva 생략, DTO 자체는 유지

---

## 6. 다른 중복 필드(검토됨, 미변경)

| 필드 | Application 측 | CoF 측 | 정책 |
|---|---|---|---|
| consumerType | `applicantConsumerTypeHint` | `consumerType` | **hint 네이밍으로 이미 의미 분리** — 일원화 불필요 |
| supplyVoltage | `applicantSupplyVoltageHint` | `supplyVoltageV` | 동일 |
| retailerCode | `applicantRetailerHint` | `retailerCode` | 동일 |
| hasGenerator | `applicantHasGeneratorHint` | `hasGenerator` | 동일 |
| generatorCapacityKva | `applicantGeneratorCapacityHint` | `generatorCapacityKva` | 동일 |
| msslAccountNo | `applicantMsslHintEnc/Hmac/Last4` | `msslAccountNoEnc/Hmac/Last4` | 동일 |

**근거**: Application 측은 `*_hint` 네이밍으로 "신청자 추정값" 의미 표시. CoF 측은 LEW가 공식 확정. 두 필드의 본질적 의미가 다르므로 일원화 불필요.
**kVA만 예외인 이유**: kVA는 가격 산정·결제·발주의 진본값이라 운영 데이터로 특별 취급 필요. 다른 필드들은 CoF 문서 기록 전용.

---

## 7. 감사 로그 메타데이터

`COF_REISSUED_BY_KVA_OVERRIDE` 이벤트 metadata 예시:

```json
{
  "previousKva": 45,
  "newKva": 100,
  "previousStatus": "CONFIRMED",
  "newKva": 100,
  "newQuote": 850.00,
  "priceDelta": 500.00,
  "note": "Site verified at 100 kVA",
  "force": true,
  "applicationStatus": "PENDING_PAYMENT",
  "cofReissued": true,
  "priorApplicationStatus": "PENDING_PAYMENT",
  "newApplicationStatus": "PENDING_REVIEW"
}
```

`KVA_OVERRIDDEN_BY_ADMIN`과 함께 두 이벤트 모두 기록되어 감사 시 재발급 발생을 명확히 추적.

---

## 8. 테스트 관점

- `CoF.snapshotApprovedLoadKva(null)` → `IllegalArgumentException`
- `CoF.snapshotApprovedLoadKva(X)` after `finalize()` → `IllegalStateException`
- `CoF.reopenForReissue(newKva)` on Draft CoF → `IllegalStateException`
- `Application.reopenForCofReissue()` on non-PENDING_PAYMENT → `IllegalStateException`
- `saveDraftCof` with `request.approvedLoadKva = 999` (잘못된 값) → 응답에 `Application.selectedKva` 값이 반영됨

자세한 테스트 매트릭스는 `05-test-plan.md` 참조.
