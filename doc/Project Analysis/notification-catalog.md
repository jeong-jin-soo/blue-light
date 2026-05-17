# LicenseKaki 통합 알림 카탈로그 (Master Notification Catalog)

> **작성일**: 2026-04-24
> **위치**: Single Source of Truth — 모든 알림(역할 × 이벤트 × 채널)을 한 파일로 통합
> **근거 문서**:
> - 요구사항·AC: [`notification-requirements.md`](./notification-requirements.md)
> - 전략·채널·여정: [`notification-strategy.md`](./notification-strategy.md)
> **목적**: 인프라 선행 스프린트(SMS/WhatsApp 게이트웨이, phoneNumber JIT 수집, Preference Center, Digest 엔진)의 **구현 범위 확정**과 실제 개발 순서 기준 제공

---

## 0. 범례

| 약어 | 의미 |
|------|------|
| **E** | 이메일 |
| **I** | 인앱 알림 (로그인 사용자 / Notification 테이블) |
| **S** | SMS |
| **W** | WhatsApp Business API |
| **★** | Critical — 법적·재무·보안 필수, 옵트아웃 불가, Quiet Hours 예외 |
| **●** | Important — 여정 진행상 알아야 함, 카테고리별 옵트아웃 가능 |
| **○** | Informational — 참고·안심·다이제스트 |
| **M** | Marketing — 명시 opt-in만, Spam Control Act §ADV 표기 필수 |
| **✓** | 현재 구현됨 |
| **∆** | 부분 구현 (채널 일부 누락) |
| **✗** | 미구현 |

**공통 원칙 (모든 항목에 적용)**
1. 한 메시지 = 한 primary CTA (Stripe 패턴)
2. 제목·SMS 본문에 민감정보(실명·주소·면허번호·금액) 금지 — 플랫폼 링크로 유도
3. Transactional vs Marketing 엄격 분리 — 거래 알림에 프로모션 콘텐츠 금지
4. 인앱(I)은 **모든** 상태 전이에 기본 생성, 옵트아웃 불가 (감사 추적용)
5. Quiet Hours (SGT 22:00~08:00): Critical만 즉시 발송, 나머지는 08:00에 큐잉
6. 사용자당 일일 상한: E 5통 / S 2통 / W 4통 — 초과 시 자동 다이제스트 전환
7. 멱등성: `user_seq + reference_type + reference_id + type + channel` 30분 내 중복 방지
8. 실패 격리: 알림 발송 실패는 비즈니스 트랜잭션 롤백 금지 (`afterCommit` 훅 + WARN 로그)

---

## 1. 채널 선택 Decision Matrix

| 조건 | 필수 채널 | 선택 채널 |
|------|-----------|-----------|
| 모든 상태 전이 (로그) | **I** | — |
| 외부 증빙 필요 (결제, 면허, LOA) | **E** | — |
| 시간 제약 <24h + 법적/재무 결과 | **E + I** | S 또는 W |
| 방문 약속 (Concierge, Expired License, LEW 현장) | **E + I** | W(D-1) + S(도착 30분 전) |
| 서명 요청 (LOA, 전자문서) | **E + I** | W(리마인더) |
| Admin 내부 운영 | **I** | E(SLA 위반 시) |
| 보안 (비번 변경, 새 기기) | **E + I** | — |
| 마케팅 | **E(opt-in)** | W(opt-in) |

---

## 2. APPLICANT (신청자) 알림 48종

### 2.1 계정·보안

| # | 이벤트 | 타이밍 | 수신자 | 채널 | 중요도 | 목적 | 내용 요약 | CTA | 현재 |
|---|--------|--------|--------|------|:---:|------|----------|------|:---:|
| A-01 | 회원가입 → 이메일 인증 요청 | 가입 직후 | 본인 | E | ★ | 액션 유도 | 24h 유효 인증 링크 + 서비스 소개 1줄 | `/verify-email?token=` | ✓ |
| A-02 | 가입 환영·온보딩 | 인증 완료 후 | 본인 | E+I | ○ | 재참여 유도 | 첫 신청 시작 안내 + 서비스 허브 링크 | `/applications/new` | ✗ P2 |
| A-03 | 비밀번호 재설정 링크 | forgot-password 요청 | 본인 | E | ★ | 액션 유도 | 1h 유효 재설정 링크 + 본인 확인 안내 | `/reset-password?token=` | ✓ |
| A-04 | 비밀번호 변경 성공 | resetPassword 완료 | 본인 | E | ★ | 보안 통보 | "방금 비밀번호가 변경되었습니다. 본인이 아니면…" + 지원 연락처 | (없음) | **✗ P0** |
| A-05 | 새 디바이스/IP 로그인 감지 | 로그인 + 신규 UA 지문 | 본인 | E | ● | 보안 알림 | 시각·IP·UA + 본인이 아니면 즉시 조치 링크 | `/security/sessions` | ✗ P2 |
| A-06 | 비활성 계정 활성화 링크 | 비활성 상태 로그인 시도 | 본인 | E | ★ | 액션 유도 | 활성화 링크 + PDPA 재동의 안내 | `/activate?token=` | ✓ |
| A-07 | 재이메일 인증 요청 | 사용자 요청 | 본인 | E | ● | 액션 유도 | 새 인증 링크 | `/verify-email?token=` | ✓ |

### 2.2 Application 본 플로우

| # | 이벤트 | 타이밍 | 수신자 | 채널 | 중요도 | 목적 | 내용 요약 | CTA | 현재 |
|---|--------|--------|--------|------|:---:|------|----------|------|:---:|
| A-08 | **신청서 접수 확인** | `createApplication` 성공 즉시 | 본인 | **E+I** | ● | 정보 전달 | 접수 번호, 설비 주소, LEW 심사 예상기간(24~72h) + 신청서 PDF 첨부 | `/applications/{id}` | **✗ P0** |
| A-09 | 신청서 초안 저장됨 (이탈 리마인더) | D+1 / D+3 | 본인 | E | ○ | 재참여 | "작성 중 신청서가 있어요" — 2회까지만 | `/applications/{id}/edit` | ✗ P2 |
| A-10 | LEW 배정됨 | `assignLew` 성공 | 본인 | **E+I** | ● | 정보 전달 | "심사관 {name}님이 배정되었습니다" + 예상 처리 기간 | `/applications/{id}` | ∆ P1 |
| A-11 | kVA 확정됨 | `confirmKva` | 본인 | **E+I** | ● | 정보 전달 | 확정 kVA + 수수료 견적 확정 고지 | `/applications/{id}` | ∆ (I만) P1 |
| A-12 | 서류 요청 생성 (LEW) | LEW 요청 생성 | 본인 | E+I | ● | 액션 유도 | 필요 서류 목록 + 제출 마감 | `/applications/{id}#documents` | ✓ |
| A-13 | 서류 승인 (LEW) | LEW 승인 | 본인 | E+I | ● | 정보 전달 | 승인된 서류명 + 남은 필요 서류 | `/applications/{id}#documents` | ✓ |
| A-14 | **서류 반려 (LEW)** | LEW 반려 | 본인 | **E+I+S** | ★ | 액션 유도 긴급 | 반려 사유 + 재업로드 요청 + 24h SLA | `/applications/{id}#documents` | ∆ (S 없음) P1 |
| A-15 | **보완 요청 (REVISION_REQUESTED)** | `requestRevision` | 본인 | **E+I** (+ W opt-in) | ★ | 액션 유도 | 보완 사항 전문 + 수정 제출 CTA | `/applications/{id}/edit` | ∆ (I 없음) P1 |
| A-16 | 보완 미제출 리마인더 | D+2 / D+5 | 본인 | E (+ W D+5만) | ● | 독려 | "D+7에 자동 취소됩니다" + 재촉 — 최대 2회 | `/applications/{id}/edit` | ✗ P1 |
| A-17 | **결제 요청 (PENDING_PAYMENT)** | `approveForPayment` | 본인 | **E+I** | ★ | 액션 유도 | PayNow UEN·reference code·금액·마감일 + 피싱 방지 문구 | `/applications/{id}/payment` | ∆ (I 없음) P0 |
| A-18 | 결제 마감 D-3 리마인더 | 스케줄러 | 본인 | E | ● | 독려 | 마감 시각 명시 + PayNow 재안내 | `/applications/{id}/payment` | ✗ P1 |
| A-19 | **결제 마감 D-1 리마인더** | 스케줄러 | 본인 | **E+S** (+ W opt-in) | ★ | 긴급 독려 | 24h 내 마감 경고 — SMS는 160자, 단축링크 | `lk.sg/p/{code}` | ✗ P0 |
| A-20 | **결제 확인 완료 (PAID)** | `confirmPayment` | 본인 | **E+I** | ● | 정보 전달 | 영수증 PDF 첨부 + 다음 단계(작업 개시) 안내 | `/applications/{id}` | ∆ (I 없음) P0 |
| A-21 | 작업 진행 중 안심 메시지 | IN_PROGRESS 상태 D+3 동결 시 | 본인 | E | ○ | 안심 | "LEW가 현장 작업 진행 중입니다" — 1회만, 플래그 필수 | `/applications/{id}` | ✗ P2 |
| A-22 | **면허 발급 완료 (COMPLETED)** | `completeApplication` | 본인 | **E+I+S** (+ W opt-in) | ★ | 정보 전달 중요 | 면허번호·만료일·PDF 첨부, SMS는 발급 사실만 | `/applications/{id}/licence` | ∆ (S·I 없음) P0 |
| A-23 | Admin 상태 강제 변경 | `updateStatus` | 본인 | I (+ 선택 E) | ● | 정보 전달 | 변경 전/후 상태 + 사유 (있으면) | `/applications/{id}` | ✗ P1 |

### 2.3 면허 만료 생애주기

| # | 이벤트 | 타이밍 | 수신자 | 채널 | 중요도 | 목적 | 내용 요약 | CTA | 현재 |
|---|--------|--------|--------|------|:---:|------|----------|------|:---:|
| A-24 | 만료 D-90 사전 알림 | 스케줄러 | 본인 | E | ○ | 정보 전달 | 만료일 + 갱신 절차 안내 | `/applications/{id}/renew` | ✗ P1 |
| A-25 | 만료 D-60 리마인더 | 스케줄러 | 본인 | E | ● | 독려 | 이제부터 갱신 신청 가능 | `/applications/{id}/renew` | ✗ P1 |
| A-26 | 만료 D-30 경고 | 스케줄러 | 본인 | E+I | ● | 경고 | 마감 임박 + Expired License 방문 서비스 추천 | `/applications/{id}/renew` | ∆ (I 없음) |
| A-27 | 만료 D-7 경고 | 스케줄러 | 본인 | **E+W**(opt-in) | ★ | 긴급 경고 | 1주 이내 만료 + 갱신 마감 | `/applications/{id}/renew` | ✗ P0 |
| A-28 | 만료 D-1 최종 경고 | 스케줄러 | 본인 | E+S | ★ | 긴급 경고 | 내일 만료 | `lk.sg/r/{code}` | ✗ P0 |
| A-29 | **면허 자동 EXPIRED 전환** | 스케줄러 EXPIRED 전환 | 본인 | **E+I** | ★ | 경고 | 만료됨 + 방문 갱신 서비스 CTA | `/orders/expired-licence/new` | **✗ P0** |
| A-30 | 만료 후 D+1 서비스 추천 | 스케줄러 | 본인 | E | ○ (M 성격) | 재참여 | Expired License Order 안내 — 1회만 | `/orders/expired-licence/new` | ✗ P2 |

### 2.4 Kaki Concierge (v1.5)

| # | 이벤트 | 타이밍 | 수신자 | 채널 | 중요도 | 목적 | 내용 요약 | CTA | 현재 |
|---|--------|--------|--------|------|:---:|------|----------|------|:---:|
| A-31 | 컨시어지 접수 확인 | `notifySubmitted` | 본인 | E+I (+ 계정 링크) | ● | 정보 전달 | 접수 코드 + 24h 내 연락 약속 + 계정 설정 링크(C3 케이스) | `/concierge/requests/{code}` | ✓ |
| A-32 | **컨시어지 담당자 배정** | Manager 지정 | 본인 | **E+I** | ● | 정보 전달 | 담당자 이름 + 연락 예정 시각 | `/concierge/requests/{code}` | **✗ P1** (enum 미사용) |
| A-33 | 컨시어지 견적 발송 | `notifyQuoteSent` (통화 후) | 본인 | E+I | ★ | 액션 유도 | verification phrase + 견적 금액 + PayNow | (PayNow) | ✓ |
| A-34 | **컨시어지 LOA 서명 요청** | `generateLoa` | 본인 | **E+I+S** (+ W opt-in) | ★ | 액션 유도 | 72h 유효 서명 링크 — SMS는 단축 링크만 | `/loa/{token}` | **✗ P0** (enum 미사용) |
| A-35 | LOA 서명 리마인더 | 48h 미서명 | 본인 | E+S | ★ | 긴급 독려 | 72h 만료 경고 | `/loa/{token}` | ✗ P0 |
| A-36 | LOA 대리업로드 확인 | Manager upload | 본인 | E | ★ | 법적 공지 | 대리 업로드 사실 + 7일 이의 창구 | `mailto:support@…` | ✓ |
| A-37 | **컨시어지 라이선스료 결제 요청** | `viaConcierge` + PENDING_PAYMENT | 본인 | **E+I** | ★ | 액션 유도 | Manager 이름 포함 + A-17과 분리된 본문 | `/applications/{id}/payment` | **✗ P0** (enum 미사용) |
| A-38 | **컨시어지 방문 일정 확정** | `scheduleVisit` | 본인 | **E+I+S** | ★ | 정보 전달 중요 | 일시·주소·Manager 정보 + iCal(.ics) 첨부 | `/concierge/requests/{code}` | **✗ P0** |
| A-39 | 방문 D-1 리마인더 | 스케줄러 09:00 SGT | 본인 | **S** (+ W) | ● | 독려 | 내일 방문 + Manager 연락처 tel: 링크 | `lk.sg/v/{code}` | ✗ P0 |
| A-40 | 방문 도착 30분 전 | Manager 출발 신호 | 본인 | **S+W** | ● | 실시간 | "Manager가 이동 중입니다" + 연락처 | (tel:) | ✗ P1 |
| A-41 | 컨시어지 방문 완료 (사진 업로드) | `uploadVisitPhotos` 완료 | 본인 | **E+I** | ● | 액션 유도 확인 | 현장 사진 썸네일 + 작업 내역 + 완료 확인 요청 | `/concierge/requests/{code}/confirm` | ✗ P1 |
| A-42 | 컨시어지 최종 완료 통지 | COMPLETED | 본인 | **E+I** (+ W) | ● | 정보 전달 | 감사 메시지 + NPS 요청 예고 | `/concierge/requests/{code}` | **✗ P1** (enum 미사용) |
| A-43 | 컨시어지 취소 통보 | Manager/Admin 취소 | 본인 | **E+I** | ● | 정보 전달 | 취소 사유 + 환불 절차 | `/concierge/requests/{code}` | **✗ P1** (enum 미사용) |

### 2.5 SLD Order

| # | 이벤트 | 타이밍 | 수신자 | 채널 | 중요도 | 목적 | 내용 요약 | CTA | 현재 |
|---|--------|--------|--------|------|:---:|------|----------|------|:---:|
| A-44 | **SLD 견적 제안됨** | `proposeQuote` | 본인 | **E+I** | ★ | 액션 유도 | 금액·유효기간·PayNow 정보 | `/orders/sld/{id}` | **✗ P1** |
| A-45 | SLD 견적 응답 리마인더 | D-3 / D-1 | 본인 | E (+ W D-1) | ● | 독려 | 유효기간 만료 경고 | `/orders/sld/{id}` | ✗ P1 |
| A-46 | **SLD 도면 업로드 완료** | `uploadSld` | 본인 | **E+I** (+ W opt-in) | ● | 액션 유도 확인 | 도면 미리보기 링크 + 검토 요청 | `/orders/sld/{id}` | **✗ P1** |
| A-47 | SLD 주문 완료 | `markComplete` | 본인 | E+I | ● | 정보 전달 | DXF/PDF 첨부 + 사용 안내 | `/orders/sld/{id}` | ✗ P2 |

### 2.6 Expired License Order (방문형)

| # | 이벤트 | 타이밍 | 수신자 | 채널 | 중요도 | 목적 | 내용 요약 | CTA | 현재 |
|---|--------|--------|--------|------|:---:|------|----------|------|:---:|
| A-48 | **Expired License 견적 제안** | `proposeQuote` | 본인 | **E+I** | ★ | 액션 유도 | 견적·방문 가용 시간대 | `/orders/expired-licence/{id}` | **✗ P1** |
| A-49 | **Expired License 방문 일정 확정** | `scheduleVisit` | 본인 | **E+I+S** | ★ | 정보 전달 중요 | iCal 첨부 + Manager 정보 | `/orders/expired-licence/{id}` | **✗ P0** |
| A-50 | Expired License 방문 D-1 리마인더 | 스케줄러 | 본인 | **S** (+ W) | ● | 독려 | 내일 방문 확인 | (단축링크) | ✗ P0 |
| A-51 | Expired License 방문 체크인 | `checkIn` (Manager) | 본인 | I | ○ | 정보 전달 | "Manager가 도착했습니다" | `/orders/expired-licence/{id}` | ✗ P2 |
| A-52 | **Expired License 방문 완료** | `uploadVisitPhotos` | 본인 | **E+I** | ● | 액션 유도 확인 | 사진·진단 결과 + 완료 확인 요청 | `/orders/expired-licence/{id}/confirm` | **✗ P1** |
| A-53 | Expired License 최종 완료 | 완료 확인 | 본인 | E+I | ● | 정보 전달 | 감사 + 영수증 | `/orders/expired-licence/{id}` | ✗ P2 |

### 2.7 피드백

| # | 이벤트 | 타이밍 | 수신자 | 채널 | 중요도 | 목적 | 내용 요약 | CTA | 현재 |
|---|--------|--------|--------|------|:---:|------|----------|------|:---:|
| A-54 | NPS 피드백 요청 | 완료 D+3 | 본인 | E+I | ○ | 재참여 | 5점 척도 1-click — 1회만 | `/feedback/{token}` | ✗ P2 |

---

## 3. LEW (Licensed Electrical Worker) 알림 12종

> **경험 원칙**: 업무 효율 위주. Important/Informational은 **09:00 / 15:00 SGT 2회 다이제스트**, Critical만 실시간.

| # | 이벤트 | 타이밍 | 채널 | 중요도 | 다이제스트 | 목적 | 내용 요약 | 현재 |
|---|--------|--------|------|:---:|:----------:|------|----------|:---:|
| L-01 | **LEW 가입 승인** | `approveLew` | **E+I** | ★ | No | 정보 전달 | 대시보드 CTA + 가이드 링크 | **✗ P0** |
| L-02 | **LEW 가입 반려** | `rejectLew` (reason 필수) | **E** | ★ | No | 액션 유도 | 반려 사유 + 재신청 방법 | **✗ P0** |
| L-03 | 신청 할당됨 | `assignLew` 즉시 I / 10분 debounce 후 E | I + E(Digest) | ● | Yes | 업무 배정 | 신청 번호·주소·예상 소요 | ∆ (I 없음) P1 |
| L-04 | 배정 해제됨 | `unassignLew` | **E+I** | ● | No | 정보 전달 | 해제 사유 | **✗ P1** |
| L-05 | 서류 업로드 완료 (검토 필요) | 15분 debounce | I + E(Digest) | ● | Yes | 업무 트리거 | 업로드된 서류 목록 | ✓ |
| L-06 | 결제 확인됨 (작업 개시) | `confirmPayment` | **E+I** | ● | No | 업무 트리거 | 현장 주소·연락처 + 작업 가능 상태 | ✓ |
| L-07 | 보완 재제출 받음 | `resubmit` | I + E(Digest) | ● | Yes | 업무 재개 | 재제출 내용 | **✗ P1** |
| L-08 | **SLA 경고 (24h 미응답)** | 스케줄러 | **E+I** | ★ | No | 독려 | 미처리 건 + 마감 경고 | **✗ P1** |
| L-09 | **SLA 위반 (48h 초과)** | 스케줄러 | **E+I** (+ Admin 공동) | ★ | No | 에스컬레이션 | Admin에게 동시 통보 | ✗ P0 |
| L-10 | LEW Service Order 관련 이벤트 | 각 상태 전이 | E+I | ● | Yes (비긴급) | 현장 업무 | 예약·변경·완료 | **✗ P1** |
| L-11 | 현장 작업 예약 완료 (LEW → Applicant) | LEW가 날짜 입력 시 | (신청자 발송 트리거) | ● | No | 업무 연계 | — | ✗ P1 |
| L-12 | **일간 마감 요약** | 매일 18:00 SGT | E | ○ | (본체) | 일일 회고 | 오늘 처리 N건 / 미처리 M건 | ✗ P2 |

---

## 4. ADMIN 알림 10종

> **경험 원칙**: 신호 대 잡음 비율이 생명. 정상 플로우는 인앱 로그, **SLA·오류·Breach만 이메일 푸시**.

| # | 이벤트 | 타이밍 | 채널 | 중요도 | 목적 | 내용 요약 | 현재 |
|---|--------|--------|------|:---:|------|----------|:---:|
| M-01 | **신규 Application 접수** | `createApplication` | I (+ E 옵션) | ● | 업무 큐 | 접수 번호·kVA·예상 LEW 할당 | **✗ P0** |
| M-02 | **LEW 신규 가입 신청** | 회원가입 with LEW role | **E+I** | ● | 승인 업무 | 지원자 정보 + 증빙 요약 | **✗ P0** |
| M-03 | 컨시어지 신규 접수 | `notifySubmitted` | E+I | ● | 업무 큐 | PDPA 주의 — 제목에 고객명 배제 | ✓ |
| M-04 | **컨시어지 24h SLA 위반 경고** | 스케줄러(시간당) | **E+I** | ★ | 에스컬레이션 | Request Code + Manager + 경과 시간 | **✗ P0** (enum 미사용) |
| M-05 | LEW SLA 위반 공동 수신 | L-09와 동시 | **E+I** | ★ | 에스컬레이션 | Application + LEW 정보 | ✗ P0 |
| M-06 | 결제 실패 / PayNow 매칭 실패 | 매칭 스케줄러 | **E+I** | ★ | 운영 이상 | 신청자·금액·reference code | ✗ P1 |
| M-07 | Invoice 자동발행 실패 | `invoiceGenerationService` | I (+ E) | ● | 운영 이상 | 실패 사유 + 재시도 결과 | ∆ |
| M-08 | **데이터 침해 알림 (Breach)** | `DataBreachService` 트리거 | **E+I** | ★ | 법적 대응 | PDPA §26D 3일 내 PDPC 통지 대상 | ✗ P1 |
| M-09 | LEW 라이센스 자동 만료 감지 | 스케줄러 | E+I | ● | 관리 | 만료된 LEW 목록 | ✗ P1 |
| M-10 | 일일 운영 요약 다이제스트 | 매일 09:00 SGT | E | ○ | 모니터링 | 접수·완료·SLA 지표 | ✗ P2 |

---

## 5. SYSTEM_ADMIN 알림 5종

| # | 이벤트 | 타이밍 | 채널 | 중요도 | 목적 | 내용 요약 | 현재 |
|---|--------|--------|------|:---:|------|----------|:---:|
| S-01 | 시스템 장애 (SMTP 실패율 >5%) | Metrics 임계치 | **E** (+ 옵션 Slack) | ★ | 운영 경고 | 최근 N분 실패율 + 로그 링크 | ✗ P1 |
| S-02 | 파일 암호화 키 로딩 실패 | 앱 부팅 시 | **E** | ★ | 운영 경고 | 환경 이름 + 실패 원인 | ✗ P1 |
| S-03 | AI Service 장시간 연결 실패 | Health check 스케줄러 | E | ● | 운영 경고 | 컨테이너 상태 + git commit | ✗ P2 |
| S-04 | DB 백업 실패 | 백업 스케줄러 | **E** | ★ | 운영 경고 | 백업 시각 + 실패 원인 | ✗ P1 |
| S-05 | ADMIN M-* 공동 수신 (옵션) | 각 M-* 이벤트 | E+I | ● | 권한 백업 | 동일 본문 | ✗ P2 |

---

## 6. SLD_MANAGER 알림 7종

> **경험 원칙**: "지금 작업할 주문" 큐 처리. 다이제스트 기본.

| # | 이벤트 | 타이밍 | 채널 | 중요도 | 다이제스트 | 목적 | 현재 |
|---|--------|--------|------|:---:|:----------:|------|:---:|
| D-01 | **새 SLD Order 접수** | `createOrder` 15분 debounce | I + E(Digest) | ● | Yes | 업무 큐 | **✗ P0** |
| D-02 | 매니저로 배정됨 | `assignManager` | **E+I** | ● | No | 업무 배정 | **✗ P1** |
| D-03 | **결제 완료 (작업 개시)** | `acceptQuote` → PAID | **E+I** | ● | No | 업무 트리거 | **✗ P0** |
| D-04 | 신청자 견적 거절 | `rejectQuote` | E+I | ● | Yes | 기회 상실 | **✗ P1** |
| D-05 | **신청자 수정 요청** | `requestRevision` (SLD) | **E+I** | ● | No | 업무 재개 | **✗ P0** |
| D-06 | 신청자 완료 확인 | `confirmCompletion` | I | ○ | Yes | 완료 기록 | **✗ P2** |
| D-07 | 일일 큐 요약 | 매일 08:00 SGT | E | ○ | (본체) | 큐 가시화 | ✗ P2 |

---

## 7. CONCIERGE_MANAGER 알림 9종

> **경험 원칙**: 현장 모드 — WhatsApp 활용도 최고. 방문 관련은 즉시, 그 외는 다이제스트.

| # | 이벤트 | 타이밍 | 채널 | 중요도 | 다이제스트 | 목적 | 현재 |
|---|--------|--------|------|:---:|:----------:|------|:---:|
| C-01 | 신규 컨시어지 접수 | `notifySubmitted` | E+I | ● | No | 업무 큐 | ✓ |
| C-02 | **매니저로 배정됨** | Manager 지정 | **E+I** | ● | No | 업무 배정 | **✗ P1** (enum 미사용) |
| C-03 | **24h 첫 접촉 SLA 임박/위반** | 스케줄러 | **E+I** | ★ | No | 에스컬레이션 | **✗ P0** (enum 미사용) |
| C-04 | 신청자 LOA 서명 완료 | `LoaService.sign` | **E+I** | ● | No | 다음 업무 트리거 | **✗ P1** |
| C-05 | **Expired License Order 접수** | `createOrder` | **E+I** | ● | No | 업무 큐 | **✗ P0** |
| C-06 | **Expired License 재방문 요청** | `requestRevisit` | **E+I** | ★ | No | 업무 재개 | **✗ P0** |
| C-07 | Expired License 신청자 완료 확인 | `confirmCompletion` | I | ○ | Yes | 완료 기록 | **✗ P2** |
| C-08 | **일일 방문 요약** | 매일 08:00 SGT | E (+ W 옵션) | ○ | (본체) | 현장 계획 | ✗ P1 |
| C-09 | 방문 도착 30분 전 트리거 | Manager 버튼 | (Applicant S+W 발송 트리거) | ● | No | 실시간 | ✗ P1 |

---

## 8. 스케줄 기반 / 리마인더 알림 통합

| 스케줄러 | 주기 | 대상 | 연결 알림 | 구현 요건 |
|---------|------|------|----------|----------|
| LicenseExpiryScheduler | 일 1회 | Applicant | A-24 D-90, A-25 D-60, A-26 D-30, A-27 D-7, A-28 D-1, A-29 EXPIRED 전환, A-30 D+1 추천 | 각 플래그 분리 (`expiryNotifiedAt90/60/30/7/1`, `expiredNotifiedAt`) |
| PaymentDueScheduler | 일 1회 | Applicant | A-18 D-3, A-19 D-1 | Application.paymentDueAt 기준 |
| RevisionReminderScheduler | 일 1회 | Applicant | A-16 (D+2, D+5) | Application.revisionRequestedAt 기준, 최대 2회 |
| ConciergeSlaScheduler | 시간당 | Admin + Concierge Manager | C-03 / M-04 | 첫 `ConciergeNote` 존재 여부 기준 |
| LewSlaScheduler | 시간당 | LEW + Admin | L-08 (24h), L-09 (48h) | Application.assignedAt 기준 |
| VisitReminderScheduler | 일 1회 09:00 SGT | Applicant | A-39, A-50 (D-1 방문) | ConciergeRequest/ExpiredLicenseOrder.visitAt 기준 |
| LoaReminderScheduler | 시간당 | Applicant | A-35 (48h 미서명) | Loa.generatedAt 기준 |
| DigestScheduler | 일 2회 09:00 / 15:00 SGT | LEW, SLD_MANAGER, CONCIERGE_MANAGER | L-03, L-05, L-07, D-01, D-04, D-06, C-07 | notification_digest_batch 묶음 |
| DailySummaryScheduler | 일 1회 08:00 SGT | SLD_MANAGER, CONCIERGE_MANAGER | D-07, C-08 | 대기·진행·완료 집계 |
| LewDailySummaryScheduler | 일 1회 18:00 SGT | LEW | L-12 | 오늘 처리 / 미처리 |
| AdminDailyDigestScheduler | 일 1회 09:00 SGT | ADMIN | M-10 | KPI 스냅샷 |
| SystemHealthScheduler | 15분마다 | SYSTEM_ADMIN | S-01 (SMTP), S-03 (AI), S-04 (DB backup) | 임계치 기반 |

---

## 9. 다이제스트 묶음 규칙

| 역할 | 묶음 이벤트 | 디바운스 | 발송 시각 (SGT) | 상한 | 이메일 제목 |
|------|------------|---------|-----------------|------|-----------|
| LEW | L-03, L-05, L-07, L-10 (non-critical) | 10~15분 | 09:00 / 15:00 | 회별 50건 | `[Digest] 오늘의 심사 N건` |
| SLD_MANAGER | D-01, D-04, D-06 | 15분 | 09:00 / 15:00 | 회별 30건 | `[Digest] SLD 주문 큐` |
| CONCIERGE_MANAGER | C-07 + 비현장 이벤트 | 15분 | 15:00 | 회별 20건 | `[Digest] 컨시어지 업무` |
| ADMIN | M-03, M-07 (non-critical) | 30분 | 09:00 | — | `[Digest] 운영 요약` |

**Critical 예외**: ★ 이벤트는 디바운스 우회 즉시 발송.

---

## 10. 카테고리 × 채널 수신거부 매트릭스 (Applicant 기본)

| 카테고리 | 인앱 | 이메일 | SMS | WhatsApp | 예시 알림 |
|---------|:---:|:---:|:---:|:---:|----------|
| SECURITY | 🔒 ON | 🔒 ON | 옵션 | — | A-04, A-05 |
| STATUS | 🔒 ON | 🔒 ON | — | 옵션 | A-08, A-10, A-23 |
| PAYMENT | 🔒 ON | 🔒 ON | 옵션 | 옵션 | A-17, A-19, A-20, A-37 |
| REMINDER | ON/OFF | ON/OFF | ON/OFF | ON/OFF | A-16, A-18, A-35, A-45 |
| VISIT | 🔒 ON | 🔒 ON | 옵션 | 옵션 | A-38, A-39, A-40, A-49, A-50 |
| REASSURANCE | ON/OFF | ON/OFF | — | — | A-21 |
| EXPIRY | 🔒 ON | 🔒 ON | — | 옵션 | A-24~A-29 |
| MARKETING | — | OFF(기본) | OFF(기본) | OFF(기본) | A-30(성격상), 뉴스레터 |
| FEEDBACK | ON/OFF | ON/OFF | — | — | A-54 |

🔒 = Critical/Transactional, 옵트아웃 불가. PDPA §13 / Spam Control Act 준수.

---

## 11. 내용 템플릿 공통 요소

모든 알림 템플릿은 다음 구조를 따른다:

**이메일**
- 발신자: `LicenseKaki <noreply@licensekaki.sg>` (예약 주소)
- Reply-To: 카테고리별 (`support@`, `concierge@`, `billing@`)
- 제목: 민감정보 제외, reference code만 — `[LicenseKaki] {event} · #{publicCode}`
- 본문 블록: ① 인사 ② 1문장 핵심 ③ 상세 ④ **단일 primary CTA 버튼** ⑤ 2차 링크(대시보드) ⑥ 푸터 (주소·opt-out 링크·피싱 경고)
- 하단 opt-out: 법적 고정 알림 제외

**SMS (160자 이내)**
- 형식: `[LicenseKaki] {action} {detail}. {shortUrl}`
- 예: `[LicenseKaki] Payment due 25 Apr. Pay: lk.sg/p/A1B2`
- 민감정보 금지 — 단축 URL로 유도
- "Reply STOP to unsubscribe" 필수 (Marketing SMS)

**WhatsApp (Business Template)**
- Meta 사전 승인 템플릿만 사용
- 리치 포맷: 이미지/PDF + 최대 3개 버튼
- 24h 대화창 이후는 템플릿만 유효
- 양방향 대화는 Phase 2 (Concierge Manager 전용)

**인앱**
- 제목: `{type}` 기반 i18n
- 본문: 100자 이내 스니펫
- 메타데이터: `referenceType`, `referenceId` 필수 (딥링크 생성용)
- 읽음 상태 추적 (`readAt`)

---

## 12. 구현 우선순위 요약

### P0.5 — 인프라 선행 (반드시 먼저)
- [ ] **SMS 게이트웨이** — Twilio 또는 AWS SNS, `SmsService` 인터페이스 (EmailService 패턴 미러)
- [ ] **WhatsApp Business API** — Meta Cloud API 또는 Twilio, `WhatsAppService` 인터페이스, 템플릿 사전 승인
- [ ] **User.phoneNumber 컬럼 + JIT 수집** — Expired License / LOA / 방문 플로우에서 요청
- [ ] **Preference Center 데이터 모델** — `user_notification_preferences`, `notification_category`, `notification_severity`
- [ ] **Digest 엔진** — `notification_digest_batch` 테이블 + `DigestScheduler`
- [ ] **Quiet Hours 큐잉** — 발송 전 시간대 체크 + 큐잉 로직
- [ ] **멱등성 키** — `idempotency_key` 컬럼, 중복 방지 인덱스
- [ ] **notification_delivery 로그** — 채널별 발송·실패·배달 레포트 기록
- [ ] **iCal 생성 유틸** — 방문 일정 이메일 첨부용

### P0 — 법적·결제·보안·SLA 필수 (인프라 이후 즉시)
| 그룹 | 알림 IDs |
|------|---------|
| Application 기본 | A-08 (접수), A-17 (결제요청 I 보강), A-19 (D-1 SMS), A-20 (결제확인 I 보강), A-22 (면허발급 S·I 보강) |
| 보안 | A-04 (비번 변경 통보) |
| LEW 관리 | L-01, L-02, L-09 (+ M-05) |
| Admin 큐 | M-01, M-02, M-04 |
| 면허 만료 | A-27 (D-7), A-28 (D-1), A-29 (EXPIRED 전환) |
| 컨시어지 긴급 | A-34 (LOA), A-35 (LOA 리마인더), A-37 (결제요청), A-38 (방문일정), A-39 (D-1 리마인더) |
| Expired License | A-49 (방문일정), A-50 (D-1), C-05, C-06 |
| SLD | D-01, D-03, D-05 |

### P1 — 운영 개선
- Application: A-10, A-11 (I 보강), A-14 (S 보강), A-15 (I 보강), A-16, A-18, A-23, A-24~A-26
- LEW: L-03 (I), L-04, L-07, L-08, L-10, L-11
- Concierge: A-32, A-40, A-41, A-42, A-43, C-02, C-04, C-08, C-09
- SLD: A-44, A-45, A-46, D-02, D-04
- Expired License: A-48, A-52
- Admin: M-06, M-08, M-09
- SYSTEM_ADMIN: S-01, S-02, S-04

### P2 — UX 추가·옵션
- A-02, A-05, A-09, A-21, A-30, A-47, A-51, A-53, A-54
- L-12, D-06, D-07, C-07, M-07, M-10, S-03, S-05
- 사용자 Preference Center UI

---

## 13. 합계

| 역할 | 알림 건수 |
|------|----------|
| APPLICANT | 54 |
| LEW | 12 |
| ADMIN | 10 |
| SYSTEM_ADMIN | 5 |
| SLD_MANAGER | 7 |
| CONCIERGE_MANAGER | 9 |
| **총** | **97** (중복·연계 포함) |

**현재 구현**: 22건 (완전 ✓), 6건 (부분 ∆) — 전체 중 **약 23%**
**신규 필요**: **69건** (P0 27건 / P1 30건 / P2 12건)

---

## 14. 참고 문서 연계

- PM 스펙 (AC·기술 요구): [`notification-requirements.md`](./notification-requirements.md)
- 전략 (채널·여정·벤치마크): [`notification-strategy.md`](./notification-strategy.md)
- Concierge v1.5 PRD §6: `doc/Project Analysis/kaki-concierge-service-prd.md`
- LEW Service 재설계: `doc/Project Analysis/lew-service-visit-redesign-spec.md`
- 현행 이메일 인터페이스: `blue-light-backend/src/main/java/com/bluelight/backend/api/email/EmailService.java`
- 현행 알림 타입 enum: `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationType.java`
- 권장 Notifier 패턴: `blue-light-backend/src/main/java/com/bluelight/backend/api/document/DocumentRequestNotifier.java`
