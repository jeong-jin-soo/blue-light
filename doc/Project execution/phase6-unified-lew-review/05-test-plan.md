# Phase 6 — Test Plan

**범위**: 백엔드 가드/스냅샷/재발급 + 프론트엔드 통합 페이지 회귀. 단위 테스트는 자동화, UI 테스트는 로컬 수동 검증 체크리스트로 제공.

---

## 1. 단위·통합 테스트 매트릭스

### 1-1. `LewReviewServiceTest`

**기존 유지**: 배정 권한, Draft Save, finalize 재호출, MSSL, Contestable/retailer, Generator, 상태 전제, CoF 미존재 — 16 테스트 그대로.

**Phase 6 신규 7 테스트**:

| # | 테스트 | AC | 검증 |
|---|---|---|---|
| T01 | `phase6_finalize_kva_not_confirmed_400` | AC-G1 | `kvaStatus=UNKNOWN` → 400 `KVA_NOT_CONFIRMED`, 알림 미발송 |
| T02 | `phase6_finalize_pending_documents_400` | AC-G2 | DocumentRequest REQUESTED/UPLOADED 3건 → 400 `DOCUMENT_REQUESTS_PENDING` |
| T03 | `phase6_finalize_sld_not_confirmed_400` | AC-G3 | `sldOption=REQUEST_LEW` && `SldRequest.status=UPLOADED` → 400 `SLD_NOT_CONFIRMED` |
| T04 | `phase6_finalize_sld_missing_400` | AC-G3 | `sldOption=REQUEST_LEW` && `SldRequest` 없음 → 400 `SLD_NOT_CONFIRMED` |
| T05 | `phase6_finalize_sld_confirmed_ok` | AC-G3, AC-G4 | `sldOption=REQUEST_LEW` && `SldRequest.status=CONFIRMED` → finalize 성공 |
| T06 | `phase6_finalize_snapshots_approved_load_kva` | AC-S2 | CoF.approvedLoadKva=100, Application.selectedKva=45 → finalize 후 CoF=45 (스냅샷 확정) |
| T07 | `phase6_finalize_sends_notification_to_applicant` | AC-N1 | finalize 성공 시 `NotificationType.CERTIFICATE_OF_FITNESS_FINALIZED` 발송 검증 |
| T08 | `phase6_save_draft_derives_approved_load_kva_from_application` | AC-S1 | request.approvedLoadKva=999 → 응답에 Application.selectedKva(45) 반영 |

**초기 setup**: `documentRequestRepository.countByApplicationAndStatusIn(...) → 0` 기본 mock. 개별 테스트가 필요시 override.

### 1-2. `ApplicationKvaServiceTest`

**기존 유지**: B-3 LOCKED, AC-P1 ALREADY_CONFIRMED, AC-A3 INVALID_TIER, AC-A2 FORBIDDEN, B-4 LEW/ADMIN audit — 6 테스트 그대로.

**Phase 6 신규 4 테스트**:

| # | 테스트 | AC | 검증 |
|---|---|---|---|
| T09 | `Phase6_CoF_finalized_상태에서_kVA_override_시_CoF_reopen_및_상태_회귀` | AC-R1, R4, R5 | PENDING_PAYMENT + CoF finalized + force=true → `reopenForReissue`, `reopenForCofReissue`, 감사 2종, 알림 3건(applicant KVA_CONFIRMED + LEW/applicant COF_REISSUED) |
| T10 | `Phase6_CoF_미존재_상태에서_kVA_override_시_재발급_분기_미실행` | AC-R3 | CoF 없음 → reopen 미호출, 감사 1종(KVA_OVERRIDDEN_BY_ADMIN만) |
| T11 | `Phase6_CoF_Draft_상태에서_kVA_override_시_재발급_미실행` | AC-R3 | CoF Draft(finalized=false) → reopen 미호출 |
| T12 | `Phase6_force_false_신규_확정_시_재발급_분기_미실행` | AC-R3 (보강) | previousStatus=UNKNOWN + force=false → cofRepository 조회 자체 안 함 |

### 1-3. 실행 결과 (PR 1 커밋 기준)
- 전체 450 테스트 passed, 0 failures
- `./gradlew test` 로 검증
- CI 통합: 기존 GitHub Actions 파이프라인에서 자동 실행

---

## 2. 도메인 레이어 방어적 검증 테스트 (신규 권장)

**`CertificateOfFitnessTest`**에 추가 권장:
- `snapshotApprovedLoadKva(null)` → `IllegalArgumentException`
- `snapshotApprovedLoadKva(X)` after `finalize()` → `IllegalStateException`
- `reopenForReissue(null)` → `IllegalArgumentException`
- `reopenForReissue(X)` on non-finalized → `IllegalStateException`
- `reopenForReissue(X)` → `certifiedAt=null`, `lewConsentDate=null`, `approvedLoadKva=X`, `certifiedByLew` 보존

**`ApplicationTest`**에 추가 권장:
- `reopenForCofReissue()` on `PENDING_REVIEW` → `IllegalStateException`
- `reopenForCofReissue()` on `PENDING_PAYMENT` → status `PENDING_REVIEW`

> 주: 기존 코드베이스에 별도 `CertificateOfFitnessTest.java` / `ApplicationTest.java`가 없으면 이번 PR에서 신설은 생략하고 서비스 테스트에서 간접 검증으로 대체. 후속 개선 시 분리 권장.

---

## 3. 프론트엔드 회귀 (수동 체크리스트)

### 3-1. 빌드 & 타입
```bash
cd blue-light-frontend
npm run build
```
- [ ] `tsc -b` 에러 없음
- [ ] `vite build` 성공

### 3-2. 로컬 동작 (LEW 계정)

**Precondition**: 백엔드 `./gradlew bootRun`, 프론트 `npm run dev`, LEW `lew@licensekaki.sg / admin1234` 로 로그인, assigned 신청 1건 이상.

- [ ] `/lew/applications/:id/review` 진입 → 상단 탭 4~5개 노출 (sldOption에 따라)
- [ ] 탭 배지:
  - [ ] Documents: pending 0 → 배지 없음 / pending ≥ 1 → warning "N"
  - [ ] kVA: CONFIRMED → success "Confirmed" / UNKNOWN → warning "Unknown"
  - [ ] SLD (조건부): CONFIRMED → success / 그 외 → warning
  - [ ] CoF: finalized → success "Finalized" / 가드 통과 → info "Ready" / 차단 → gray "Blocked"
- [ ] Documents 탭 → LewDocumentReviewSection 렌더링, "+ Request Documents" 버튼 동작
- [ ] kVA 탭 → KvaSection 렌더링, UNKNOWN 시 "Confirm kVA" 모달
- [ ] SLD 탭 (sldOption=REQUEST_LEW 신청만) → AdminSldSection 렌더링
- [ ] LOA 탭 → view-only, 생성/업로드 버튼 없음, 서명 상태 표시
- [ ] CoF 탭 → Step 1/2/3 흐름 유지

### 3-3. CoF Step 2 kVA 읽기 전용
- [ ] Approved Load (kVA) 라벨 옆에 배지 (CONFIRMED→"LEW confirmed" / UNKNOWN→"kVA not confirmed")
- [ ] 입력 필드 **없음**, 대신 회색 카드에 `{selectedKva} kVA` + "Synced from Application…" 안내
- [ ] 브라우저 DevTools에서 `document.querySelector('input[name="approvedLoadKva"]')` → null

### 3-4. CoF Step 3 GuardChecklist
- [ ] 3항목 리스트(kVA, Documents, SLD는 조건부)
- [ ] 모두 통과 시 패널 배경 녹색 + 타이틀 "All prerequisites satisfied"
- [ ] 하나라도 미통과 시 패널 배경 노란색 + "Finalize is blocked" + 미통과 항목 옆 "Go to tab →" 링크
- [ ] Finalize 버튼 disabled 상태 (`aria-disabled=true`)
- [ ] "Go to tab →" 클릭 → 해당 탭으로 전환

### 3-5. 에러 매핑 (가드 우회 시도)
서버 측이 가드를 반환하면 토스트 + 탭 전환 동작해야 함. 재현:

- [ ] kVA UNKNOWN 상태에서 Finalize 강제 시도(버튼 disabled 우회, 예: fetch 직접 호출)
  → 토스트 "kVA must be confirmed…" + kVA 탭 전환
- [ ] DocumentRequest REQUESTED 상태에서 finalize
  → 토스트 + Documents 탭 전환
- [ ] sldOption=REQUEST_LEW이고 SLD UPLOADED 상태에서 finalize
  → 토스트 + SLD 탭 전환

### 3-6. kVA override 후 재발급 시나리오
1. 신청 ID를 PENDING_PAYMENT + CoF finalized 상태로 만듦(정상 경로로 finalize 완료)
2. ADMIN 계정 로그인, `/admin/applications/:id` → KvaSection Override
3. 새 kVA 입력 후 Override 수행

- [ ] ADMIN 화면: Override 성공 토스트
- [ ] 신청 상태: PENDING_REVIEW로 회귀(목록에서 확인)
- [ ] LEW 계정 알림 벨: "CoF re-signature required" 신규 알림
- [ ] LEW가 `/lew/applications/:id/review` 재진입 → CoF 탭 배지 "Ready", Finalize 가능
- [ ] LEW가 Finalize 재실행 → 정상 완료, PENDING_PAYMENT 복귀

---

## 4. DB 상태 검증 (수동 SQL)

재발급 시나리오 확인용:

```sql
-- Before override (PENDING_PAYMENT)
SELECT a.application_seq, a.status, a.selected_kva, a.kva_status,
       c.cof_seq, c.approved_load_kva, c.certified_at, c.lew_consent_date
FROM applications a LEFT JOIN certificate_of_fitness c ON c.application_seq = a.application_seq
WHERE a.application_seq = <ID>;

-- Expected: status=PENDING_PAYMENT, cof.certified_at NOT NULL, cof.approved_load_kva = a.selected_kva

-- ADMIN override (force=true) 실행 후:
-- Expected: status=PENDING_REVIEW, a.selected_kva=새값,
--           cof.certified_at IS NULL, cof.lew_consent_date IS NULL,
--           cof.approved_load_kva = 새 a.selected_kva

-- 감사 로그 확인
SELECT audit_action, created_at, metadata
FROM audit_log
WHERE target_type='Application' AND target_id = '<ID>'
  AND audit_action IN ('KVA_OVERRIDDEN_BY_ADMIN','COF_REISSUED_BY_KVA_OVERRIDE')
ORDER BY created_at DESC LIMIT 5;

-- 알림 확인
SELECT recipient_seq, type, title, created_at
FROM notifications
WHERE reference_type='Application' AND reference_id = <ID>
ORDER BY created_at DESC LIMIT 5;
```

---

## 5. 보안/권한 회귀

- [ ] 미배정 LEW가 finalize 호출 → 403 `APPLICATION_NOT_ASSIGNED` (기존 Phase 1 동작 유지)
- [ ] 미배정 LEW가 kVA 확정 호출 → 403 `ACCESS_DENIED` (기존 Phase 5 동작 유지)
- [ ] 미배정 LEW가 서류 요청 시도 → 403 `FORBIDDEN` (기존 Phase 3 동작 유지)
- [ ] LEW가 LOA 생성 시도 → 403 (페이지에 버튼 없음이 일차 방어, 서버도 차단)
- [ ] 다른 APPLICANT가 CoF finalize 호출 → 403 (ProtectedRoute + 서버 @PreAuthorize)

---

## 6. 성능

- [ ] 통합 페이지 로드 시 API 호출 수 ≤ 6건 (lew + admin + loa + sld + files + documentRequests)
- [ ] 초기 렌더링 < 2초 (로컬 환경)
- [ ] 탭 전환 시 추가 API 호출 없음 (모든 데이터 초기 로드에서 확보)

---

## 7. 롤백 시나리오

배포 후 중대 결함 발견 시:

1. 프론트엔드만 롤백: `feature/unified-lew-review` 브랜치에서 PR 2 커밋 revert → 기존 3-step CoF 페이지 복귀
2. 백엔드 PR 1 롤백: 가드·스냅샷·재발급 모두 revert. Phase 5의 기존 동작 복귀. DB 스키마 변경 없으므로 마이그레이션 불필요.
3. 재발급 분기만 롤백: `ApplicationKvaService.confirm` 내 Phase 6 분기만 revert (finalizeCof 가드는 유지)

---

## 8. 테스트 실행 요약

| 레이어 | 명령 | 결과 |
|---|---|---|
| 백엔드 전체 | `./gradlew test` | 450 passed, 0 failures |
| 백엔드 Phase 6만 | `./gradlew test --tests "*LewReviewServiceTest" --tests "*ApplicationKvaServiceTest"` | 23+10=33 passed |
| 프론트 타입/빌드 | `npm run build` | 성공 |
| 프론트 수동 | 위 체크리스트 | (로컬 검증 필요) |
