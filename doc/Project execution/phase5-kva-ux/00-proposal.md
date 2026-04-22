# Phase 5 — kVA 입력 UX 개선 전략 제안서

**작성일**: 2026-04-17
**작성자**: Product Manager (LicenseKaki)
**범위**: NewApplicationPage Step 2의 kVA 선택 UX를 재설계하여, 신청자가 **정확한 kVA를 모르더라도 신청을 완료**할 수 있게 하고, LEW가 이후 공식 문서로 확정하는 플로우를 정식화한다.
**원칙**: Phase 1(신청 단순화) → Phase 2(서류 인프라) → Phase 3(LEW 구조화 워크플로)의 연속선상에서, **Just-in-Time Disclosure**와 **Role-based Deferral**을 kVA 영역에 확장한다.

---

## 1. 문제 정의

### 1.1 왜 사용자가 kVA를 모르는가

실제 LicenseKaki 신청자 페르소나를 3개로 분류하면 문제 범위가 드러난다.

- **P1. HDB 거주자 / 신규 이사자** — 주소·우편번호는 알지만 kVA는 처음 듣는 개념. 기본 HDB 계약이 45 kVA라는 사실조차 모름.
- **P2. 샵/F&B 소상공인** — 임대 shophouse에서 요식업/살롱을 시작하는 사장. SP 고지서는 임대인 명의. "냉장고 3대, 에어컨 2대 있는데 몇 kVA냐?"는 질문에 답이 없음.
- **P3. 공장/산업 담당자** — 내부 시설팀이 대신 신청. SLD 도면은 있지만 비기술직 총무가 업로드하러 들어옴. **tier 선택 실수 시 재계약·환불 이슈** 가능.

### 1.2 현재 UX가 놓치고 있는 시나리오
- 드롭다운에 "모름" 선택지 없음 → 임의 tier 선택 후 제출 → LEW가 공식 문서 확인 후 재협의 발생.
- kVA 용어 설명·예시 부재 → 대략 감으로 선택 → **낮은 tier를 무의식적으로 선택**하는 bias.
- Step 1에서 수집한 building_type / address가 Step 2 추천에 전혀 활용되지 않음.

### 1.3 비즈니스 영향
- **전환율 저하**: Step 2 이탈 가설 (현재 미계측, Phase 5에서 계측 필요). "모르겠어서 나중에 다시" → 다시 안 옴.
- **가격 불일치 리워크**: 신청자 선택 tier ≠ 실제 kVA → LEW가 정정 요청 → 결제 tier 재산정 → 사용자 실망/환불 이슈.
- **CS 문의 증가**: "내 kVA가 뭐예요?" 문의가 ops 시간을 잠식.

---

## 2. 설계 원칙

| 원칙 | 적용 |
|---|---|
| **Just-in-Time Disclosure** | kVA가 확실한 사용자는 현재 UX 그대로, 불확실한 사용자만 추가 경로 노출. |
| **Role-based Deferral** | 판단이 어려우면 LEW에게 위임. Phase 3 `DocumentRequest` 인프라 재활용. |
| **Progressive Disclosure** | `UNKNOWN → ESTIMATED → CONFIRMED` 단계적 확정. 각 단계가 사용자에게 명시적. |
| **No Silent Bias** | 추천값은 항상 근거(건물 유형, 면적)와 함께 표시. 사용자가 override 가능. |

---

## 3. 개선 방안 — 5개 옵션 비교

| 옵션 | 핵심 | 장점 | 단점 |
|---|---|---|---|
| **A. "잘 모르겠어요" 버튼 + LEW 위임** | 드롭다운에 "Not sure" 추가. 임시 tier 저장 → LEW가 확정 | 최소 변경, 빠른 신청 | 가격 불확정 → 결제 지연 |
| **B. 건물 유형 기반 추천 엔진** | Step 1 데이터 활용, tier 자동 추천 | UI 단순, 자동화 | 상업/산업 추정 정확도 낮음 |
| **C. 가이드 설문 (Decision Tree)** | 3–5개 질문으로 tier 안내 | 교육적, 친절 | 신청 단계 증가, 이탈 위험 |
| **D. Photo-first (브레이커 사진)** | Step 2 전 차단기 사진 업로드 → LEW/OCR 확인 | 정확도 최상 | 비동기, 촬영 부담 |
| **E. 하이브리드 (권장)** | A+B+도움말 통합 | 모든 페르소나 커버 | 구현 복잡도 +중 |

---

## 4. 권장안: **옵션 E (하이브리드)**

### 4.1 구성
1. **Smart Default**: Step 1의 `buildingType`·주소를 기반으로 tier를 **자동 선택**하되, 라벨에 "Suggested based on HDB flat" 근거 표시.
2. **"Not sure" 명시적 선택지**: 드롭다운 최상단에 `"I don't know — let LEW confirm"` 옵션.
3. **Tip 박스**: 드롭다운 옆에 "kVA를 어디서 확인하나요?" 접이식 도움말 (SP 고지서 샘플 이미지, 메인 차단기 사진 예시).
4. **가격 표시 정책**: Not sure 선택 시 **"From S$350 (final price after LEW confirmation)"** 포맷. 결제는 LEW 확정 후에만 활성화.
5. **LEW 확정 플로우**: Phase 3의 `DocumentRequest` 재사용. LEW가 SP Account PDF 또는 Main Breaker Photo를 요청 → 검토 후 `PATCH /applications/{id}/kva/confirm`으로 확정.

### 4.2 근거
- **Phase 1~3 철학과 일치**: 신청자 부담을 낮추고, 판단은 LEW에게 구조적으로 위임.
- **옵션 B 단독의 한계 회피**: 추천은 Smart Default로 쓰되, 부정확한 경우 Not sure 경로로 흡수.
- **옵션 D 단독의 부담 회피**: 사진 업로드는 LEW가 필요 시에만 요청 (Phase 3 인프라).
- **계측 가능**: `kvaStatus` 필드로 전환 funnel 추적 가능.

---

## 5. 데이터 모델 / API 영향

### 5.1 Application 엔티티 추가 필드
```
kvaStatus       ENUM('UNKNOWN', 'ESTIMATED', 'CONFIRMED')  NOT NULL default 'CONFIRMED'
kvaSource       ENUM('USER_INPUT', 'SYSTEM_SUGGESTION', 'LEW_VERIFIED')  NULL
kvaConfirmedBy  BIGINT (FK users.id)  NULL
kvaConfirmedAt  TIMESTAMP  NULL
```
- 기존 `selectedKva` 컬럼 유지 (UNKNOWN 상태에서도 최저 tier 45로 placeholder 저장).
- `@NotNull @Positive` 제약은 유지, `kvaStatus=UNKNOWN`은 tier=45로 채움.

### 5.2 가격 책정 로직
- `kvaStatus != CONFIRMED`이면 결제 단계(`PENDING_PAYMENT`) 진입 차단. `PENDING_REVIEW` 상태에서 LEW의 confirm을 대기.
- 확정 시 `selectedKva` 갱신 및 가격 재계산 이벤트 (`KVA_CONFIRMED` 감사 로그).

### 5.3 신규 API
- `PATCH /api/admin/applications/{id}/kva` — LEW/ADMIN 전용. body: `{ selectedKva, source, note }`. 응답: 갱신된 price.
- `GET /api/applications/kva-suggestion?buildingType=HDB&sizeSqm=90` — Smart Default 추천.

---

## 6. UX 흐름 (권장안)

```
Step 2 진입
 └─ Smart Default tier 자동 선택 (근거 뱃지 표시)
     ├─ 사용자 수락 → 기존 플로우 (kvaStatus=CONFIRMED, source=USER_INPUT)
     ├─ 사용자 수정 → 드롭다운에서 tier 선택 (CONFIRMED, USER_INPUT)
     └─ "I don't know" 선택
           ├─ tier=45 placeholder, kvaStatus=UNKNOWN
           ├─ 가격 영역: "From S$350 (final price after LEW confirms)"
           ├─ 안내 카드: "LEW가 신청 접수 후 공식 문서로 확정합니다"
           └─ 제출 → PENDING_REVIEW
                 └─ LEW: DocumentRequest(SP_ACCOUNT_PDF | MAIN_BREAKER_PHOTO) 생성
                       └─ 업로드 확인 → PATCH /kva → CONFIRMED
                             └─ 신청자에게 알림 "kVA가 X로 확정되었습니다. 결제 진행"
                                   └─ PENDING_PAYMENT 전이
```

---

## 7. 수용 기준 초안

1. **AC-K1** Step 2 진입 시 Step 1의 `buildingType`·주소 기반으로 tier가 **pre-selected**되고 근거 뱃지가 노출된다.
2. **AC-K2** kVA 드롭다운 최상단에 `"I don't know — let LEW confirm"` 옵션이 존재한다.
3. **AC-K3** "I don't know" 선택 시 가격 표시가 `"From S$350"` 포맷으로 바뀌고, 제출 버튼은 활성 유지된다.
4. **AC-K4** 제출된 신청은 `kvaStatus=UNKNOWN`으로 저장되고, 결제 단계(`PENDING_PAYMENT`) 진입이 차단된다.
5. **AC-K5** LEW 신청 상세 페이지에 `kvaStatus` 배지(UNKNOWN/ESTIMATED/CONFIRMED)가 표시되고, UNKNOWN인 경우 "kVA 확정" 액션 버튼이 노출된다.
6. **AC-K6** `PATCH /api/admin/applications/{id}/kva`는 LEW/ADMIN만 호출 가능하며, 성공 시 `selectedKva`·`price`·`kvaStatus=CONFIRMED`가 갱신되고 `KVA_CONFIRMED` 감사 로그가 기록된다.
7. **AC-K7** kVA 확정 시 신청자에게 인앱+이메일 알림이 발송되고, 상태가 `PENDING_PAYMENT`로 전이된다.
8. **AC-K8** Tip 박스("kVA를 어디서 확인하나요?")는 SP 고지서 샘플과 브레이커 사진 예시를 포함하며, 모바일에서 접이식으로 동작한다.

---

## 8. 리스크

| 리스크 | 영향 | 완화책 |
|---|---|---|
| UNKNOWN 신청이 LEW 확정 지연으로 정체 | 결제 지연, 사용자 불만 | LEW 대시보드에 "kVA 확정 대기" 전용 필터·SLA 24h 표시 |
| Smart Default 추천값이 부정확 → 사용자가 무심코 수락 | 잘못된 tier 결제 | 추천 tier는 "suggested" 라벨, 결제 전 한 번 더 확인 모달 |
| LEW 작업 부담 증가 | 처리량 병목 | Phase 3 DocumentRequest 재사용, OCR 자동화는 Phase 6 과제로 분리 |
| 가격이 올라간 경우 사용자 이탈 | 전환율 하락 | 확정 시 "가격이 조정되었습니다" 사전 고지 + 취소 옵션 제공 |

---

## 9. 구현 범위 — PR 분리

- **PR#1 (Backend/DB)**: `Application`에 `kvaStatus`·`kvaSource`·`kvaConfirmedBy`·`kvaConfirmedAt` 추가, 마이그레이션, 결제 단계 진입 가드, `PATCH /kva` API, 감사 로그.
- **PR#2 (Frontend/UX)**: Step 2 Smart Default + "I don't know" + Tip 박스 + 가격 표시 정책, `GET /kva-suggestion` 연동.
- **PR#3 (LEW 확정 플로우)**: LEW 신청 상세의 kVA 확정 모달, DocumentRequest 연계, 알림 트리거, 상태 전이 테스트.

---

## 10. 의사결정 필요 항목

1. **권장안 채택 여부** — 옵션 E(하이브리드)로 진행 OK? 아니면 A/B만 먼저?
2. **UNKNOWN 시 placeholder tier** — 최저(45) 저장 vs 중간값(200) 저장 vs NULL 허용. (제안: 최저 45 — `From` 표기가 UX상 자연스럽고 하방 안전.)
3. **Smart Default의 공격성** — pre-select 하되 경고 모달 없음 vs pre-select 없이 suggestion만 표시. (제안: pre-select + 근거 뱃지.)
4. **LEW 확정 SLA** — 24h 목표 공표 vs 내부 지표만. (제안: 사용자 노출은 "typically within 1 business day".)
5. **OCR/자동 인식** — Phase 5에 포함 vs Phase 6 분리. (제안: Phase 6 분리.)
6. **Smart Default 추천 엔진의 규칙 기반 vs 통계 기반** — 초기 MVP는 규칙 기반(HDB=45, shophouse=100, factory=500) 제안.

사용자 승인 후 `01-spec.md`에서 수용 기준 및 상태 전이를 확정하고, `02-ux-design.md`로 화면 흐름을 구체화한다.
