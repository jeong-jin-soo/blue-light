# Phase 6 — 통합 LEW 리뷰 (Unified LEW Review)

**작성일**: 2026-04-24
**범위**: LEW의 리뷰 작업을 하나의 페이지로 통합. 기존 분리되어 있던 **CoF 입력 / 서류 요청 / kVA 확정 / SLD 확정 / LOA 열람**을 `/lew/applications/:id/review` 단일 탭 페이지에서 수행하며, CoF finalize 전제조건을 가드로 강제한다. 또한 kVA가 override로 변경되면 이미 finalized된 CoF를 재서명 대기 상태로 되돌린다.
**원칙**: LEW가 "확인만 하면 자동 결제 요청되는 것처럼 보이는" 설계 공백을 제거하고, finalize 트리거 경로를 명시적 액션 + 가드로 분리한다. 또한 `Application.selectedKva`를 운영 진본(SSOT)으로 확정하고 CoF는 이를 snapshot으로 기록한다.
**선행 배포**: Phase 1 (LEW Review Form CoF P1+P2), Phase 3 (LEW 서류 요청 워크플로), Phase 5 (kVA UX) 전부 머지 완료.

---

## 1. 사용자 스토리 & 측정 지표

### User Stories
- **US-1 (LEW)**: 하나의 리뷰 페이지에서 서류 요청·kVA 확정·SLD 확정·CoF 서명을 모두 처리하고 싶다. 여러 페이지를 오가는 현재 동선은 "어디서 무엇을 해야 하는지"를 기억해야 하고, CoF만 눌러도 바로 결제 요청이 나가는 것처럼 보여 실수 가능성이 크다.
- **US-2 (LEW)**: CoF를 finalize할 때 서류 보완이 끝났는지, kVA가 확정됐는지, SLD가 확정됐는지를 UI가 명시적으로 보여주고 미완료 항목으로 바로 이동할 수 있어야 한다.
- **US-3 (ADMIN)**: 결제 승인 이전 단계에서 kVA를 override하는 경우(오측·재고지 등), 신청자에게 재결제를 요구하기 전에 LEW가 반드시 CoF를 재서명해야 한다. CoF의 법적 기록이 실제 운영 kVA와 달라지면 안 된다.
- **US-4 (LEW)**: kVA override 때문에 재서명이 필요해지면 인앱 알림으로 즉시 인지하고 싶다.
- **US-5 (신청자)**: LEW가 CoF를 서명하면 결제 단계로 넘어간다는 것을 알림으로 받고 싶다.
- **US-6 (감사)**: kVA override → CoF 재발급 시나리오가 별도 감사 이벤트로 추적돼야 한다.

### 측정 지표 (배포 후 4주)
| 지표 | 베이스라인 | 목표 |
|---|---|---|
| CoF finalize 실패 중 가드로 차단된 비율 | — | ≥ 80% (UI 가드가 서버 응답보다 먼저 막음) |
| LEW가 리뷰를 위해 페이지 전환한 횟수/신청 (median) | 측정 필요 | ≤ 1 (통합 페이지에서 완결) |
| kVA override 발생 건 중 CoF 재발급 분기가 정상 동작한 비율 | — | 100% (PENDING_PAYMENT 한정) |
| CoF finalize 후 "결제 요청이 잘못됐다" 재요청 건수 | 측정 필요 | ≥ 50% 감소 |

---

## 2. 수용 기준 (Acceptance Criteria)

### A. CoF finalize 가드 (3종)
1. **AC-G1** GIVEN `Application.kvaStatus != CONFIRMED`, WHEN `POST /api/lew/applications/{id}/cof/finalize`, THEN 400 `KVA_NOT_CONFIRMED` + finalize 미실행.
2. **AC-G2** GIVEN `DocumentRequest` 중 status ∈ {REQUESTED, UPLOADED}가 1건 이상, WHEN finalize 호출, THEN 400 `DOCUMENT_REQUESTS_PENDING` + finalize 미실행. 메시지에 pending 개수 포함.
3. **AC-G3** GIVEN `Application.sldOption == REQUEST_LEW`, `SldRequest`가 없거나 `status != CONFIRMED`, WHEN finalize 호출, THEN 400 `SLD_NOT_CONFIRMED` + finalize 미실행. `sldOption != REQUEST_LEW`이면 이 가드 생략.
4. **AC-G4** GIVEN 위 3종 가드 모두 통과, THEN 기존 Phase 1 검증(MSSL/consumer/voltage/approvedLoadKva/interval 등)을 수행하고 finalize 진행.

### B. CoF approvedLoadKva SSOT (3종)
5. **AC-S1** GIVEN `saveDraftCof` 호출, WHEN request body에 `approvedLoadKva`가 임의 값으로 포함, THEN 백엔드는 request 값을 **무시**하고 `Application.selectedKva`로 Derive하여 저장한다. 응답에도 Application 값이 반영된다.
6. **AC-S2** GIVEN `finalizeCof` 직전, THEN `CoF.approvedLoadKva := Application.selectedKva` 스냅샷으로 덮어쓴 뒤 저장하고 `finalize()`를 호출한다. 스냅샷은 1회 발생(immutable after finalize).
7. **AC-S3** GIVEN LEW CoF 화면 Step 2 렌더링, THEN `approvedLoadKva` 입력란은 제거되고 현재 `Application.selectedKva` + 상태 배지만 **읽기 전용**으로 표시된다. kVA 변경은 **kVA 탭**의 Confirm/Override로만 가능.

### C. CoF finalize 알림 (1종)
8. **AC-N1** GIVEN finalize 성공, THEN 신청자(`Application.user`)에게 인앱 `Notification(type=CERTIFICATE_OF_FITNESS_FINALIZED, referenceType=Application, referenceId=applicationSeq)`가 발송된다. 알림 실패는 finalize 트랜잭션을 롤백시키지 않는다(swallow + warn log).

### D. kVA override 후 CoF 재발급 (5종, Edge Case "b")
9. **AC-R1** GIVEN `Application.status == PENDING_PAYMENT` && `CoF.finalized == true` && `force == true`, WHEN `PATCH /api/admin/applications/{id}/kva` 성공, THEN
   - `CoF.certifiedAt = null`, `CoF.lewConsentDate = null`
   - `CoF.approvedLoadKva := 새 Application.selectedKva` (재스냅샷)
   - `Application.status: PENDING_PAYMENT → PENDING_REVIEW`
   - `CoF.certifiedByLew`는 감사 추적을 위해 보존(재finalize 시 덮어써짐)
10. **AC-R2** GIVEN 결제 완료 이후(`PAID`/`IN_PROGRESS`/`COMPLETED`/`EXPIRED`), WHEN kVA override 시도, THEN 기존 가드 그대로 409 `KVA_LOCKED_AFTER_PAYMENT`. 재발급 분기 **미실행**.
11. **AC-R3** GIVEN CoF Draft 상태(`finalized == false`) 또는 CoF 레코드 없음, WHEN kVA override, THEN 재발급 분기 **미실행**(기존 단순 override 경로 유지).
12. **AC-R4** GIVEN 재발급 분기 실행, THEN 감사 로그에 `KVA_OVERRIDDEN_BY_ADMIN`와 별도로 `COF_REISSUED_BY_KVA_OVERRIDE`가 기록된다(metadata: prior/new application status, prior/new kVA).
13. **AC-R5** GIVEN 재발급 분기 실행, THEN 인앱 알림 2건 발송:
   - LEW (`assignedLew`): `COF_REISSUED_BY_KVA_OVERRIDE`, title="CoF re-signature required"
   - 신청자: `COF_REISSUED_BY_KVA_OVERRIDE`, title="kVA updated — awaiting LEW re-signature"

### E. 통합 페이지 UI (6종)
14. **AC-UI1** GIVEN `/lew/applications/:id/review` 렌더링, THEN 상단 5개 탭(또는 4개 — SLD 조건부)이 노출된다: **Documents / kVA / SLD (sldOption=REQUEST_LEW만) / LOA / Certificate of Fitness**.
15. **AC-UI2** GIVEN 각 탭 헤더, THEN 현재 상태 배지를 렌더링:
   - Documents: pending 개수(`REQUESTED+UPLOADED`), 0이면 무표시
   - kVA: CONFIRMED → success "Confirmed", UNKNOWN → warning "Unknown"
   - SLD: CONFIRMED → success, 그 외 → warning (상태 명)
   - CoF: finalized → success "Finalized", 가드 통과 → info "Ready", 그 외 → gray "Blocked"
16. **AC-UI3** GIVEN CoF 탭 Step 3 Finalize, THEN GuardChecklist 컴포넌트가 3항목(kVA, Documents, SLD) 체크 상태를 렌더링하고, 각 미통과 항목 옆에 "Go to tab →" 링크로 해당 탭으로 이동한다.
17. **AC-UI4** GIVEN Finalize 버튼, THEN 3가드 미충족 시 disabled + aria-disabled. 클릭 차단 + 토스트로 미충족 사유 안내.
18. **AC-UI5** GIVEN LOA 탭, THEN 생성/업로드/서명 버튼은 **렌더링하지 않음**(LEW는 해당 API 호출 권한 없음). 서명 상태·LOA 파일 번호·타입만 read-only 카드로 표시.
19. **AC-UI6** GIVEN `adminApp.reviewComment != null`, THEN 페이지 상단에 warning 배너로 표시(관리자가 이전에 남긴 revision 코멘트 열람용). 수정 불가.

### F. 에러 매핑 (3종)
20. **AC-E1** GIVEN 서버가 `KVA_NOT_CONFIRMED` 반환, WHEN 프론트 처리, THEN 토스트 "kVA must be confirmed before finalizing CoF" + 자동으로 kVA 탭 전환 + 데이터 재로드.
21. **AC-E2** GIVEN 서버가 `DOCUMENT_REQUESTS_PENDING` 반환, THEN 토스트 "Pending document requests block finalization" + Documents 탭 전환 + 재로드.
22. **AC-E3** GIVEN 서버가 `SLD_NOT_CONFIRMED` 반환, THEN 토스트 "SLD must be uploaded and confirmed before finalizing CoF" + SLD 탭 전환 + 재로드.

---

## 3. 데이터 모델 변경

### 3-1. Enum/Code 추가 (배포 영향 無, 하위호환)
- `NotificationType`: `CERTIFICATE_OF_FITNESS_FINALIZED`, `COF_REISSUED_BY_KVA_OVERRIDE`
- `AuditAction`: `COF_REISSUED_BY_KVA_OVERRIDE`
- `CofErrorCode`: `KVA_NOT_CONFIRMED`, `DOCUMENT_REQUESTS_PENDING`, `SLD_NOT_CONFIRMED`

### 3-2. 도메인 메서드 신규 (스키마 변경 없음)
- `CertificateOfFitness.snapshotApprovedLoadKva(Integer)` — finalize 직전 스냅샷 기록
- `CertificateOfFitness.reopenForReissue(Integer)` — finalized 해제 + 새 kVA 재스냅샷
- `Application.reopenForCofReissue()` — PENDING_PAYMENT → PENDING_REVIEW 회귀

### 3-3. `CoF.approvedLoadKva` 컬럼 존치 정책
- **유지**. 스키마 변경 없음. Snapshot 값(법적 기록)으로 존재 이유 유지.
- 저장 시점: **finalize 직전 1회만** 기록(`snapshotApprovedLoadKva`). Draft 단계에서는 `Application.selectedKva`를 동기화하는 거울값.
- `reopenForReissue`는 이 값을 새 `selectedKva`로 덮어쓴다(다음 finalize 시 재동일값 스냅샷).

자세한 데이터 모델 규칙은 `03-data-model.md` 참조.

---

## 4. API 변경

| 엔드포인트 | 변경 | 비고 |
|---|---|---|
| `PUT /api/lew/applications/{id}/cof` | Draft Save. request.approvedLoadKva **무시** | AC-S1 |
| `POST /api/lew/applications/{id}/cof/finalize` | 가드 3종 + 스냅샷 + finalize 알림 추가 | AC-G1~G4, AC-S2, AC-N1 |
| `PATCH /api/admin/applications/{id}/kva` | 재발급 분기 + 알림 + 감사 추가 | AC-R1~R5 |

신규 엔드포인트 없음.

---

## 5. 상태 머신 (요약)

정상 경로:
```
PENDING_REVIEW
  ├─ (LEW: 서류 요청 ↔ 신청자 보완) ×반복
  ├─ (LEW: kVA 확정)   → kvaStatus=CONFIRMED
  ├─ (LEW: SLD 확정, if REQUEST_LEW)
  └─ (LEW: CoF finalize — 3가드 통과 시)
       → PENDING_PAYMENT + CoF.finalized=true
```

재발급(엣지) 경로:
```
PENDING_PAYMENT + CoF.finalized
  └─ (ADMIN: PATCH /kva force=true)
       ├─ status ∈ LOCKED → 409, 재발급 미실행 (AC-R2)
       └─ else
            → CoF.reopenForReissue(newKva)
            → Application.reopenForCofReissue()
            → Application.status: PENDING_PAYMENT → PENDING_REVIEW
            → CoF.finalized = false
            → 감사 + 알림
            → (LEW가 다시 finalize 필요)
```

다이어그램은 `04-state-machine.md` 참조.

---

## 6. 범위 외 (Out of Scope)

1. **결제 확인** (ADMIN 전용) — 현 구조 유지
2. **신청 완료/라이선스 발급** (ADMIN 전용) — 현 구조 유지
3. **LEW 배정/해제** — ADMIN 페이지에서만 수행
4. **kVA hint 필드 일원화** — consumerType/supplyVoltage/retailer hint는 `*_hint` 네이밍으로 이미 의미 분리됐으므로 이번 Phase에서 손대지 않음 (kVA만 예외적으로 일원화)
5. **이메일 알림** — 이번 Phase에서는 인앱 알림만 추가. 이메일은 후속 PR에서 검토

---

## 7. 관련 파일 (구현 기준)

**백엔드**
- `blue-light-backend/src/main/java/com/bluelight/backend/service/cof/LewReviewService.java` (finalize 가드 + 스냅샷 + 알림)
- `blue-light-backend/src/main/java/com/bluelight/backend/api/admin/ApplicationKvaService.java` (재발급 분기 + 알림 + 감사)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/cof/CertificateOfFitness.java` (snapshotApprovedLoadKva / reopenForReissue)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/application/Application.java` (reopenForCofReissue)
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationType.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/audit/AuditAction.java`
- `blue-light-backend/src/main/java/com/bluelight/backend/common/exception/CofErrorCode.java`

**프론트엔드**
- `blue-light-frontend/src/pages/lew/LewReviewFormPage.tsx` (5-tab 재구성)
- `blue-light-frontend/src/pages/lew/sections/CofStepInputs.tsx` (kVA read-only)
- `blue-light-frontend/src/pages/lew/sections/CofStepReviewFinalize.tsx` (GuardChecklist)
- `blue-light-frontend/src/components/ui/Tabs.tsx` (신규)

**테스트**
- `blue-light-backend/src/test/java/com/bluelight/backend/service/cof/LewReviewServiceTest.java` (가드/스냅샷/알림)
- `blue-light-backend/src/test/java/com/bluelight/backend/api/admin/ApplicationKvaServiceTest.java` (재발급 분기 4케이스)

---

**후속 문서**
- `02-ux-design.md` — 탭 구조, GuardChecklist UI, 상태 배지 규칙
- `03-data-model.md` — selectedKva ↔ approvedLoadKva 관계 정리
- `04-state-machine.md` — 재발급 엣지 케이스 다이어그램
- `05-test-plan.md` — 가드/스냅샷/재발급 테스트 매트릭스
