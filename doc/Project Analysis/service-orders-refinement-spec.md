# Service Orders 후속 UX/제품 정제 스펙

**문서 종류**: Product Specification (Refinement)
**대상 기능**: Lighting Layout Order, Power Socket Order, Request for LEW Service Order
**작성일**: 2026-04-21
**상태**: 제품·엔지니어링 사인오프 대기

---

## 1. Background

세 기능(Lighting Layout / Power Socket / Request for LEW Service)은 기존 `SldOrder` 구조를 그대로 클론하여 빠르게 출시되었다. 그 결과 상태 머신, 폼 필드(Address · Postal Code · Building Type · Capacity kVA · Requirements Note · Sketch File), 산출물 모델이 셋 모두 동일하다. 그러나 UX 리뷰에서 (1) LEW Service는 파일 산출물이 아닌 **현장 작업**이므로 `SLD_UPLOADED` 단계가 의미 없고, (2) **kVA 필드**가 조명·콘센트 주문에는 적합하지 않다는 도메인 미스매치가 확인되었다. 본 스펙은 이를 해결하기 위한 제품 결정 사항, 기능별 폼, LEW Service 상태 머신, 산출물 모델을 정의한다.

## 2. Product Decisions Needed

1. **LEW Service 상태 Enum 분리 여부**
   - **제안 기본값**: `LewServiceOrderStatus`를 별도 Enum 세트로 교체 (Section 4 참조).
   - **Trade-off**: 분리하면 타입 안전성·UI 라벨 명확성 확보. 단, `lew_service_orders` 기존 레코드에 대한 Enum 마이그레이션 필요(현재 개발 DB 한정으로 수용 가능). 공유 시 조건부 라벨링 로직이 세 화면에 분기되어 장기 유지비용 증가.

2. **산출물(Deliverable) 모델 통합 vs 기능별 분리**
   - **제안 기본값**: **기능별 분리**. Lighting/Power Socket은 단일 파일(PDF/DWG) 업로드, LEW Service는 `ServiceReport`(방문 일시 + 담당 LEW + 작업 보고서 + 사진 N장) 전용 모델.
   - **Trade-off**: 통합 모델(예: 공용 `ServiceDeliverable`)은 코드 재사용에 유리하나, LEW Service의 구조화된 필드(방문 일시, LEW 식별자)를 nullable 컬럼 잔치로 만들어 스키마가 지저분해짐. 분리가 도메인에 더 충실함.

3. **kVA 필드 처리**
   - **제안 기본값**: Lighting/Power Socket에서 **완전 제거** (DB 컬럼 drop 대신 deprecated 주석 + UI에서 숨김 → 차기 마이그레이션에서 drop). LEW Service에서는 **선택(optional)** 으로 유지.
   - **Trade-off**: 완전 drop은 롤백이 어렵지만, 스키마 정합성이 분명해짐. Hidden 방식은 컬럼이 남아 혼선 유발 가능. LEW Service는 전기 작업 성격상 용량 정보가 의미 있음.

4. **파일 명칭 및 `uploaded_file_seq` 의미 재정의**
   - **제안 기본값**: 엔티티·컬럼명은 유지하되 Lighting은 "Lighting Layout Drawing", Power Socket은 "Power Socket Layout Drawing"으로 UI 라벨 변경. LEW Service는 이 컬럼을 사용하지 않음(대신 `ServiceReport`).
   - **Trade-off**: 엔티티 이름까지 재명명하면 리팩터링 비용 과다. 라벨만 교체하면 DB·API 안정성 유지.

5. **SLD_MANAGER 역할 기능별 분리 여부**
   - **제안 기본값**: 본 PR 범위에서는 단일 `SLD_MANAGER`가 세 기능을 모두 처리. 역할 세분화는 **범위 외**.

## 3. Feature-specific Form Specs

### 3.1 Lighting Layout

**추가/변경 필드**
| 필드 | 타입 | 필수 | 예시 | 검증 |
|---|---|---|---|---|
| fixtureCount | Integer | required | 24 | 1–10000 |
| fixtureType | Enum(`LED`, `FLUORESCENT`, `MIXED`, `OTHER`) | required | `LED` | - |
| roomOrZoneCount | Integer | required | 6 | 1–500 |
| floorAreaSqm | Decimal(8,2) | optional | 120.50 | 0–100000 |
| ceilingHeightM | Decimal(4,2) | optional | 2.80 | 0–50 |

**제거/숨김**: `selectedKva` (UI 제거, DB 컬럼은 Phase 2 drop).
**페이지 제목**: "Lighting Layout 주문"
**힌트**: "조명 기구 개수, 종류, 구역 정보를 입력하면 SLD_MANAGER가 레이아웃을 설계합니다."
**플레이스홀더 예시**: fixtureCount — "예: 24", fixtureType — "LED 선택 시 색온도/루멘은 요구사항 메모에 기재"

### 3.2 Power Socket

**추가/변경 필드**
| 필드 | 타입 | 필수 | 예시 | 검증 |
|---|---|---|---|---|
| socketCount13ASingle | Integer | optional | 8 | 0–1000 |
| socketCount13ADouble | Integer | optional | 6 | 0–1000 |
| socketCount15A | Integer | optional | 2 | 0–1000 |
| socketCount20AIsolator | Integer | optional | 1 | 0–1000 |
| socketCountOther | Integer | optional | 0 | 0–1000 |
| floorAreaSqm | Decimal(8,2) | optional | 95.00 | 0–100000 |

**폼 레벨 검증**: 소켓 개수 합계 ≥ 1.
**제거/숨김**: `selectedKva`.
**페이지 제목**: "Power Socket 배치 주문"
**힌트**: "소켓 종류별 수량과 설치 면적을 입력하세요."

### 3.3 Request for LEW Service

**추가/변경 필드**
| 필드 | 타입 | 필수 | 예시 | 검증 |
|---|---|---|---|---|
| serviceType | Enum(`INSPECTION`, `REPAIR`, `INSTALLATION`, `LICENSABLE_WORK`, `EMERGENCY`) | required | `REPAIR` | - |
| urgency | Enum(`WITHIN_24H`, `THIS_WEEK`, `FLEXIBLE`) | required | `THIS_WEEK` | - |
| preferredLewGrade | Enum(`GRADE_7`, `GRADE_8`, `GRADE_9`, `LER`, `ANY`) | optional, default `ANY` | `GRADE_8` | - |
| siteVisitRequired | Boolean | required, default `true` | true | - |
| selectedKva | Integer | optional | 63 | 0–10000 |

**제거/숨김**: 없음 (kVA는 유지).
**페이지 제목**: "Request for LEW Service"
**힌트**: "현장 점검, 수리, 설치, 긴급 출동 등 LEW 인력 파견 요청입니다. 도면 산출물이 아니며 방문 후 작업 보고서가 제공됩니다."
**플레이스홀더**: serviceType — "작업 성격을 선택하세요", urgency — "언제까지 필요한가요?"

## 4. LEW Service Status Model

**상태 전이 그래프**

```
PENDING_QUOTE
  → QUOTE_PROPOSED
       → QUOTE_REJECTED (terminal)
       → PENDING_PAYMENT
            → PAID
                 → LEW_ASSIGNED
                      → VISIT_SCHEDULED
                           → WORK_COMPLETED
                                → REVISION_REQUESTED → LEW_ASSIGNED (재작업)
                                → COMPLETED (terminal)
```

**상태별 UI 라벨·매니저 액션**

| 상태 | Applicant 라벨 | Manager 라벨 | Manager Action |
|---|---|---|---|
| PENDING_QUOTE | 견적 대기 중 | 견적 제안 필요 | 금액·메모 입력 → `QUOTE_PROPOSED` |
| QUOTE_PROPOSED | 견적 검토 | 신청자 응답 대기 | (없음) |
| QUOTE_REJECTED | 거절됨 | 종료됨 | (없음) |
| PENDING_PAYMENT | 결제 대기 | 결제 대기 | (시스템) 결제 완료 확인 |
| PAID | 배정 대기 | LEW 배정 필요 | LEW 지정 → `LEW_ASSIGNED` |
| LEW_ASSIGNED | LEW 배정됨 | 방문 일정 등록 필요 | 방문 일시 입력 → `VISIT_SCHEDULED` |
| VISIT_SCHEDULED | 방문 예정 | 작업 수행 중 | 작업 보고서·사진 업로드 → `WORK_COMPLETED` |
| WORK_COMPLETED | 결과 확인 | 신청자 확인 대기 | (없음) |
| REVISION_REQUESTED | 재작업 요청됨 | 재작업 필요 | 보고서 재업로드 또는 재방문 |
| COMPLETED | 완료 | 완료 | (없음) |

**신규 구조화 필드**: `assignedLewUserSeq`, `scheduledVisitAt`, `serviceReportText`, `serviceReportPhotoFileSeqs`(JSON 배열 또는 연관 테이블).

## 5. Deliverable Model

| 기능 | Deliverable | 신청자 화면 표현 |
|---|---|---|
| Lighting Layout | 도면 파일 1건 (PDF 또는 DWG) + 매니저 메모 | 미리보기 + 다운로드 + Revision/Complete 버튼 |
| Power Socket | 도면 파일 1건 (PDF 또는 DWG) + 매니저 메모 | 동일 |
| LEW Service | `ServiceReport` = 방문 일시 + 담당 LEW (이름/면허번호) + 작업 보고서 텍스트 + 사진 N건 | 타임라인형 카드(방문 → 작업 → 사진 갤러리) + Revision/Complete 버튼 |

## 6. Out of Scope

- 실시간 디스패치·캘린더 통합 (Google Calendar, SMS 알림).
- 결제 분할·부분 환불 로직 (LEW Service가 재방문되는 경우의 정산 규칙).
- `SLD_MANAGER` 역할의 기능별 세분화(`LIGHTING_MANAGER`, `LEW_DISPATCHER` 등).
- 다국어 UI 라벨(본 스펙은 한국어/영문 혼용 기존 관행 유지).
- LEW 인력 풀·가용성 관리 시스템.
- 사진 업로드 시 자동 워터마크/EXIF 제거.

## 7. Acceptance Criteria

- [ ] Lighting Layout 주문 폼에서 `kVA` 필드가 보이지 않고, `fixtureCount`, `fixtureType`, `roomOrZoneCount`가 필수로 검증된다.
- [ ] Power Socket 주문 폼에서 `kVA`가 보이지 않고, 소켓 4종(13A 단/쌍, 15A, 20A) + Other 합계 ≥ 1일 때만 제출이 가능하다.
- [ ] LEW Service 주문 폼에서 `serviceType`, `urgency`, `siteVisitRequired`가 필수이며 `preferredLewGrade`는 선택, 기본값 `ANY`.
- [ ] LEW Service 주문 상세에 `SLD_UPLOADED` 상태가 노출되지 않으며, 대신 `LEW_ASSIGNED`, `VISIT_SCHEDULED`, `WORK_COMPLETED`가 적절한 라벨과 함께 표시된다.
- [ ] Manager가 `PAID` → `LEW_ASSIGNED` → `VISIT_SCHEDULED` → `WORK_COMPLETED`로 각각 정해진 입력(LEW 선택, 방문 일시, 보고서+사진)을 제공해야만 전이가 가능하다.
- [ ] LEW Service `WORK_COMPLETED` 상태에서 신청자에게 파일 다운로드 대신 타임라인 카드가 렌더링된다.
- [ ] Lighting/Power Socket의 완성 화면은 기존 SLD 주문과 동일한 파일 미리보기 + Revision/Complete UI를 유지한다.
- [ ] 기존 `SldOrder` 동작은 본 PR로 인해 회귀되지 않는다 (기존 E2E 통과).
- [ ] LEW Service에만 존재하는 필드(`assignedLewUserSeq`, `scheduledVisitAt`, `serviceReportText`, 사진 연관)는 Lighting/Power Socket 테이블에 생성되지 않는다.
- [ ] 상태 라벨은 `doc/manual/applicant/`, `doc/manual/admin/` 스크린샷 갱신 대상으로 식별된다(실제 갱신은 별도 작업).
