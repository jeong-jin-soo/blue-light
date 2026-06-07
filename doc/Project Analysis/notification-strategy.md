# LicenseKaki 알림 전략 (Notification Strategy)

**작성일**: 2026-04-24
**작성자**: Strategist (Product Planner)
**상태**: Draft v1.0 — 전략 방향 (AC·스펙은 PM의 `notification-requirements.md` 참조)
**대상 독자**: Product Manager, Backend·Frontend 개발자, Admin 운영팀, 법무·CS
**범위**: LicenseKaki 전 제품군(신청서, SLD Order, Concierge, Expired License, LEW Service, Power Socket, Lighting)의 알림 전반

---

## 1. 전략 요약

- **"정부 ELISE 보다 2배 빠르고, 1/3 더 조용하게"** — EMA ELISE는 승인·만료 시점에만 공식 이메일을 보내는 수동적 채널이다. LicenseKaki는 여정 전 과정에서 **"다음 액션이 명확한 알림"**을 제공해 "기다리는 불안"을 제거하는 것이 차별화 포인트다. 다만 알림 수는 줄여야 한다 — 상태 변경은 모두 인앱에, 이메일은 "내가 행동해야 하는 시점"에만.
- **채널 역할을 3계층으로 고정한다**. ① **인앱(Always-on 로그)**: 모든 이벤트 기록 · 옵트아웃 불가. ② **이메일(공식 기록)**: 법적·재무적 증빙 + 상세 정보. 옵트아웃 불가(거래 이메일). ③ **WhatsApp/SMS(즉각 행동)**: 결제 마감·방문 예약·LOA 서명 등 "몇 시간 내 액션" 이벤트 한정. 옵트인 후 사용.
- **싱가포르 컨텍스트에서 WhatsApp은 "있어야 하는" 채널**. 싱가포르 성인 약 85%가 WhatsApp 일일 사용자이며, 정부(HealthHub, MOM, IRAS)도 이미 WhatsApp 알림을 도입했다. 경쟁사가 이메일만 쓰는 상황에서 WhatsApp 템플릿 알림은 저비용으로 얻을 수 있는 **가장 큰 체감 차별화**다. PSG 보조금(최대 50%)으로 운영비 낮춤 가능.
- **역할별 경험은 "정보 밀도"로 갈라진다**. Applicant는 안심 위주 + 각 상태변경마다 알림. LEW/Manager는 업무 효율 위주 + **일괄 다이제스트**(오전 1회/오후 1회). Admin은 "이상 징후(SLA 위반·결제 실패·Breach)"만.
- **알림 피로도는 "카테고리 × 채널" 매트릭스로 제어한다**. 사용자가 카테고리(결제·상태변경·리마인더·마케팅) × 채널(이메일·SMS·WhatsApp) 16칸 매트릭스에서 개별 옵트아웃 가능. 싱가포르 PDPA는 Transactional은 묵시동의, Marketing은 명시 opt-in을 요구하므로 **법적 분리**가 전제.
- **"액션 한 번 이내 규칙"**: 한 이메일 = 한 CTA. 한 SMS = 한 링크. 여러 액션이 동시 필요하면 인앱으로 보내고 이메일은 "대시보드 보러 오세요"로 요약. 이는 PM 스펙에 모든 템플릿의 "primary CTA" 필드 필수로 이어진다.

---

## 2. 채널 전략 매트릭스

### 2.1 채널별 역할 정의

| 채널 | 목적 | 강점 | 한계 | 비용(SGD) | 법적 성격 |
|------|------|------|------|----------|----------|
| **인앱 알림(로그인 사용자)** | 전체 상태 이력 로그 · 실시간 업데이트 | 무료 · 풍부한 UI · 읽음/미읽음 추적 · 액션 버튼 임베드 | 로그인 사용자만 · 모바일 앱 없으면 미접근 시 지연 | 0 | Transactional(묵시동의) |
| **이메일(거래용)** | 공식 기록 · 증빙 · 첨부 · 상세 설명 | 법적 증빙 · 검색 가능 · 첨부 파일 · 낮은 비용 | 평균 open rate 20~30% · 긴급 알림 부적합 · 스팸함 위험 | ~$0.001 | Transactional |
| **이메일(마케팅)** | 재참여 · 교육 콘텐츠 · 뉴스레터 | 브랜딩 · 분석 가능 | PDPA 명시 opt-in 필수 · DNC 적용 없음 | ~$0.001 | Marketing(opt-in) |
| **SMS** | 단문 긴급 알림 · OTP · 마감 임박 | 95%+ open rate · 즉시성 · 로그인 불필요 | 160자 제한 · 이미지 불가 · 비용 · DNC 체크 필수 | ~$0.04 | Transactional 또는 Marketing |
| **WhatsApp Business API** | 양방향 대화 · 리치 포맷(이미지·버튼·PDF) · 현지 선호 | 싱가포르 85% 커버리지 · 풍부한 템플릿 · 버튼 액션 · 낮은 개당 비용 | Meta 템플릿 사전 승인 필요 · 24h 대화창 밖에선 유료 템플릿만 · 옵트인 필수 | ~$0.03-0.14 | Meta 정책 + PDPA |
| **Push(모바일 앱)** | 실시간 경고 · 로그인 필요 없음 | 즉시성 · 무료(인프라 제외) | **LicenseKaki 모바일 앱 현재 없음** → Phase 3 이후 | 0 | Transactional |

### 2.2 이벤트 유형 × 채널 권장 매트릭스

| 이벤트 유형 | 예시 | 인앱 | 이메일 | SMS | WhatsApp | 비고 |
|------------|------|:---:|:---:|:---:|:---:|------|
| **계정 보안** | 로그인 새 기기, 비번 재설정 | Y | Y | — | — | 이메일 필수(증빙) |
| **OTP / 인증** | 2FA, 이메일 인증 | — | Y | Y(대안) | — | 이메일 기본, 미수신 시 SMS |
| **신청 접수/상태 변경** | PENDING_REVIEW, IN_PROGRESS | Y | Y | — | — | 인앱+이메일 쌍 |
| **보완 요청(REVISION_REQUESTED)** | LEW 코멘트 | Y | Y | — | Y | 행동 요구 — WhatsApp 버튼으로 대시보드 바로가기 |
| **결제 요청(PENDING_PAYMENT)** | PayNow 청구 | Y | Y | — | Y | 금액·reference code 포함 |
| **결제 마감 임박 리마인더** | 72h/24h 전 | Y | Y(24h) | Y(24h 선택) | Y(24h) | 마감 24h 전만 긴급 채널 사용 |
| **결제 확인** | PAID | Y | Y | — | — | 영수증 첨부 |
| **면허 발급 완료** | COMPLETED | Y | Y(PDF 첨부) | — | Y(PDF 링크) | 이메일 공식, WhatsApp은 알림만 |
| **면허 만료 사전 알림** | D-90/60/30/7 | Y | Y | — | Y(D-7) | 마지막 1주만 WhatsApp |
| **방문 예약(Concierge/Expired/LEW Service)** | VISIT_SCHEDULED | Y | Y | Y(D-1) | Y | 현장 서비스 — 주소·시간 중요 |
| **방문 당일 도착 임박** | 30분 전 | — | — | Y | Y | 즉시성만 필요 |
| **SLD 도면 업로드(SLD_UPLOADED)** | SLD_MANAGER 완료 | Y | Y | — | Y(옵션) | 미리보기 링크 |
| **LOA 서명 요청(Concierge)** | LOA_SIGN_REQUIRED | Y | Y | — | Y | 법적 중요 + 긴급 |
| **SLA 위반 경고(Admin)** | 24h 미응답 | Y | Y | — | — | Admin 전용 |
| **데이터 침해 알림(Breach)** | PDPA §26D | — | Y | Y(선택) | — | 법적 필수, 이메일 기본 |
| **마케팅/뉴스레터** | 제품 업데이트 | — | Y(opt-in) | — | Y(opt-in) | PDPA 명시 동의 필요 |
| **NPS·피드백 요청** | 완료 후 D+3 | Y | Y | — | — | 1회만 |

**판단 원칙**:
1. 이메일 없이 SMS/WhatsApp만 보내지 않는다 — 이메일은 항상 "공식 기록"으로 백업.
2. SMS/WhatsApp은 **24시간 내 액션 필요** 이벤트에만 사용.
3. 동일 이벤트에 3개 채널 이상 동시 발송 금지(중복 피로).
4. Admin 알림은 인앱·이메일까지만 — 외부 채널(SMS/WhatsApp)로 내부 운영 이슈 전송 금지(PDPA 데이터 외부화 리스크).

---

## 3. 역할별 경험 원칙

| 역할 | 사용자 멘탈 모델 | 알림 톤 | 기본 빈도 정책 | 추천 채널 조합 | 기본 다이제스트 |
|------|----------------|---------|----------------|--------------|--------------|
| **APPLICANT** | "내 신청이 어디까지 갔나?" — 불안 기반 | 안심·친절·투명 | **상태 변경마다 즉시 1회** | 인앱(항상) + 이메일(항상) + WhatsApp(선택 옵트인) | 없음(개별 발송) |
| **LEW** | "오늘 내 할 일 얼마나 쌓였나?" — 업무 기반 | 간결·구조화·리스트 | **실시간 인앱 + 이메일은 다이제스트** | 인앱(항상) + 이메일(오전 9시/오후 3시 다이제스트) | Yes |
| **ADMIN** | "뭐가 잘못됐나?" — 예외 탐지 | 사실 기반·링크 중심·정량 | **이상 징후만** | 인앱(항상) + 이메일(SLA 위반 시) | Yes(일 1회 요약) |
| **SYSTEM_ADMIN** | "시스템 건강?" — 운영·보안 | 기술적·로그 링크 포함 | **치명적 이벤트만** | 인앱 + 이메일 + (옵션) Slack webhook | Yes |
| **SLD_MANAGER** | "지금 작업할 주문?" — 큐 처리 | 간결·SLD 썸네일 포함 | **실시간 인앱 + 이메일 다이제스트** | 인앱 + 이메일(오전 다이제스트) | Yes |
| **CONCIERGE_MANAGER** | "오늘 방문 일정? 고객 연락처?" — 현장 | 액션 지향·연락처 포함 | **방문 관련 즉시 + 나머지 다이제스트** | 인앱 + 이메일 + WhatsApp(방문 D-1, 도착 30분 전) | Yes(비방문 건) |

### 3.1 역할 차별화 원칙

- **APPLICANT: "안심"을 디자인한다** — 상태 변경이 없어도 3일간 상태 동일 시 "진행 중입니다" 안심 이메일 1회(예: LEW 심사 중). 단, 중복 방지 플래그 필수.
- **LEW/Manager: "컨텍스트 스위칭"을 최소화한다** — 건별 알림이 아닌 "오늘의 큐" 형태. 이메일 제목은 `[Digest] 오늘의 신청 7건`.
- **Admin: "신호 대 잡음 비율"이 생명** — 정상 플로우는 인앱 로그에만 남기고, **SLA·오류·Breach**만 이메일로 푸시.
- **Concierge: "현장 모드" 별도 디자인** — 모바일 최적화 WhatsApp 메시지에 고객 전화번호 tel: 링크 · 주소 maps: 링크 임베드.

---

## 4. 핵심 사용자 여정 타임라인

> 표기법: `[채널]` = 발송 채널(IA 인앱, EM 이메일, SM SMS, WA WhatsApp). 중요도 `★`=Critical, `●`=Important, `○`=Informational.

### 4.1 신규 신청자의 첫 신청 여정 (Applicant 중심)

| # | 단계 | 타임라인 | 수신자 | 채널 | 중요도 | 메시지 톤 | 비고 |
|---|------|---------|--------|------|:---:|----------|------|
| A1 | 회원가입(이메일 인증) | 즉시 | Applicant | EM | ★ | 인증 링크 24h 유효 | SingPass 연계 시 생략 |
| A2 | 가입 환영 + 온보딩 | 인증 완료 후 | Applicant | EM + IA | ○ | 친절·다음 스텝 안내 | "첫 신청 시작" CTA 단일 |
| A3 | 신청서 작성 중 이탈 | 24h/72h 후 | Applicant | EM | ○ | "저장된 초안이 있어요" | 2회까지만 |
| A4 | 신청 제출(PENDING_REVIEW) | 즉시 | Applicant | IA + EM | ● | "접수되었습니다 · LEW 심사 24~72h" | 신청서 PDF 첨부 |
| A4b | LEW 할당 알림 | A4 직후 | LEW | IA + EM | ● | "신규 심사 1건 할당" | **다이제스트 옵션 있으면 다이제스트** |
| A5 | LEW 보완 요청(REVISION_REQUESTED) | 즉시 | Applicant | IA + EM + WA(옵트인) | ★ | "추가 서류 필요" · CTA=대시보드 | 보완 항목 목록 포함 |
| A5r | 보완 미제출 리마인더 | D+2, D+5 | Applicant | EM + WA(D+5만) | ● | 독려 톤 · D+7에 자동 취소 예고 | 2회 리마인더 최대 |
| A6 | KVA 확정(LEW) | 즉시 | Applicant | IA + EM | ● | 수수료 확정 | 다음: 결제 요청 예고 |
| A7 | 결제 요청(PENDING_PAYMENT) | 즉시 | Applicant | IA + EM | ★ | PayNow 계좌·reference code | 피싱 방지 문구 |
| A7r | 결제 마감 리마인더 | D-3, D-1 | Applicant | EM(D-3) + WA·SM(D-1) | ★ | 마감 시각 명시 | D-1만 긴급 채널 |
| A8 | 결제 확인(PAID) | 즉시 | Applicant + LEW | IA + EM | ● | 영수증 첨부(Applicant) · "작업 시작 알림"(LEW) | |
| A9 | 작업 진행 중(IN_PROGRESS) 상태 동결 3일 | D+3 | Applicant | EM | ○ | "LEW 현장 작업 중" | 안심 메시지, 1회만 |
| A10 | 면허 발급 완료(COMPLETED) | 즉시 | Applicant | IA + EM + WA(옵트인) | ★ | 면허 번호·만료일·PDF | WhatsApp은 "PDF 다운로드" 버튼 |
| A10b | LEW에게 완료 통보 | 즉시 | LEW | IA(로그 목적) | ○ | 다이제스트에 포함 | 개별 이메일 생략 |
| A11 | 만료 사전 알림 | D-90, D-60, D-30, D-7 | Applicant | EM(3회) + WA(D-7) | ● | 갱신 유도 · Expired License 주문 CTA | D-90은 정보, D-30부터 행동 촉구 |
| A12 | 만료 후 서비스 추천 | D+1 | Applicant | EM | ○ | Expired License 주문 | 1회만 |
| A13 | NPS 피드백 요청 | 완료 D+3 | Applicant | IA + EM | ○ | 5점 척도 1클릭 | 응답률 20% 목표 |

**묶음 전략**: A4·A4b처럼 같은 신청서에서 여러 이벤트가 5분 내 발생 시, LEW에게는 다이제스트 대기(10분 디바운스) 후 합쳐서 1건 발송.

### 4.2 LEW의 심사 업무 여정 (LEW 중심)

| # | 단계 | 타임라인 | 수신자 | 채널 | 중요도 | 비고 |
|---|------|---------|--------|------|:---:|------|
| L1 | 심사 할당(PENDING_REVIEW) | 즉시 IA / 10분 디바운스 후 EM 다이제스트 | LEW | IA + EM(다이제스트) | ● | **오전 9시/오후 3시 2회 다이제스트** |
| L2 | 서류 업로드(Applicant가 보완 완료) | 즉시 IA / 15분 디바운스 후 EM | LEW | IA + EM(다이제스트) | ● | |
| L3 | SLA 경고(24h 미응답) | D-1 | LEW | IA + EM | ★ | 개별 발송(묶지 않음) |
| L3b | SLA 위반(48h 초과) | 즉시 | LEW + Admin | IA + EM | ★ | Admin 함께 통지 |
| L4 | 결제 확인(PAID) — 작업 시작 트리거 | 즉시 | LEW | IA + EM | ● | 주소·연락처 포함 |
| L5 | 현장 작업 예약(LEW가 날짜 입력 시) | 즉시 | Applicant | IA + EM + WA(D-1) | ● | 양방향 동기화 |
| L6 | 신청자 DOC 요청 회신 | 10분 디바운스 후 IA | LEW | IA(다이제스트) | ○ | 긴급도 낮음 |
| L7 | 일간 마감 요약 | 매일 오후 6시 | LEW | EM | ○ | "오늘 처리 5건, 미처리 3건" |

**핵심 인사이트**: LEW는 하루 평균 5~15건 처리하므로 **건별 이메일은 폭탄**. 다이제스트가 기본, 긴급(SLA)만 실시간.

### 4.3 컨시어지 매니저의 방문형 서비스 여정 (Concierge Manager 중심)

| # | 단계 | 타임라인 | 수신자 | 채널 | 중요도 | 비고 |
|---|------|---------|--------|------|:---:|------|
| C1 | 신규 Concierge Request 접수(N2) | 즉시 | Admin + Concierge Manager | IA + EM | ● | PDPA 주의: 제목에 고객명 미포함 |
| C1b | 고객에게 접수 확인(N1) | 즉시 | Applicant | IA + EM + 계정 설정 링크 | ★ | 옵션 B만(v1.5) |
| C2 | 배정(CONCIERGE_REQUEST_ASSIGNED, N3) | 즉시 | 배정된 Manager | IA + EM | ● | 고객 연락처 포함 |
| C3 | 견적 제안(Quote 이메일) | 통화 후 | Applicant | EM | ★ | verification phrase 포함 |
| C4 | LOA 서명 요청(N5) | 즉시 | Applicant | IA + EM + WA(옵트인) | ★ | 토큰 72h 유효 |
| C4b | LOA 대리 업로드 확인(N5-UploadConfirm) | 즉시 | Applicant | IA + EM | ★ | **법적 중요 — 이메일 필수** · 7일 이의 창구 |
| C5 | 방문 예약 확정 | 즉시 | Applicant + Manager | IA + EM + WA | ● | 주소·일시 명시 |
| C5r | 방문 D-1 리마인더 | D-1 09:00 | Applicant + Manager | EM + WA + SM(옵션) | ● | Manager에게는 "내일 방문 3건" 다이제스트 |
| C5a | 방문 도착 임박 | 30분 전 | Applicant | WA + SM | ● | Manager → Applicant 양방향 |
| C5b | 방문 당일 확인(Manager 출발 시) | 출발 시 | Applicant | WA | ○ | "지금 이동 중" — 선택 |
| C6 | 방문 완료(사진 업로드) | 즉시 | Applicant | IA + EM | ● | 사진 썸네일 포함 |
| C7 | 라이선스 결제 요청(N6b) | 즉시 | Applicant | IA + EM | ★ | |
| C8 | 컨시어지 완료(N7) | 즉시 | Applicant | IA + EM + WA | ● | 감사 메시지 |
| C9 | Manager 일일 방문 요약 | 매일 08:00 | Manager | EM + WA(옵션) | ○ | "오늘 방문 3건 · 주소 A/B/C" |
| C10 | SLA 위반 경고(24h 미접수, N9) | 24h 경과 | Admin | IA + EM | ★ | 개별 발송 |
| C11 | 취소 통보(N8) | 즉시 | Applicant | IA + EM | ● | 환불 안내 |

**핵심 인사이트**: Concierge는 **현장 모드**가 다른 모든 역할과 차별됨 — WhatsApp 활용도가 가장 높음. 고객도 Manager도 현장에서 빠른 대화 가능.

### 4.4 SLD 매니저의 도면 작업 여정 (SLD Manager 중심)

| # | 단계 | 타임라인 | 수신자 | 채널 | 중요도 | 비고 |
|---|------|---------|--------|------|:---:|------|
| S1 | SLD 주문 접수(PENDING_QUOTE) | 즉시 IA / 15분 디바운스 후 EM | SLD Manager | IA + EM(다이제스트) | ● | |
| S2 | 견적 제안(QUOTE_PROPOSED) | 즉시 | Applicant | IA + EM | ★ | PayNow 정보 포함 |
| S2r | 견적 응답 리마인더 | D-3, D-1 | Applicant | EM + WA(D-1) | ● | 유효기간 지정 |
| S3 | 결제 완료(PAID) — 작업 시작 트리거 | 즉시 | SLD Manager | IA + EM | ● | 요구사항 JSON 링크 |
| S4 | SLD 업로드(SLD_UPLOADED) | 즉시 | Applicant | IA + EM + WA | ● | 미리보기 이미지 WhatsApp |
| S5 | 수정 요청(REVISION_REQUESTED) | 즉시 | SLD Manager | IA + EM | ● | 수정 이유 포함 |
| S6 | 완료(COMPLETED) | 즉시 | Applicant | IA + EM | ● | DXF/PDF 첨부 |
| S7 | SLD Manager 일일 큐 요약 | 매일 08:00 | SLD Manager | EM | ○ | "대기 4건 / 진행 2건" |

---

## 5. 알림 피로도·수신거부 정책

### 5.1 중요도 레벨 정의

| 레벨 | 정의 | 예시 | 옵트아웃 | 전송 시간 |
|------|------|------|---------|----------|
| **Critical (★)** | 법적·재무적 의무 또는 계정 보안 | 결제 마감 24h 전, LOA 서명, Breach 알림, 면허 발급 | **불가** | 08:00~22:00 SGT, 그 외 시간은 큐잉 |
| **Important (●)** | 여정 진행상 사용자가 알아야 할 상태 변경 | 신청 접수, 보완 요청, 방문 예약 | **채널별 가능**(인앱 제외) | 08:00~21:00 SGT |
| **Informational (○)** | 참고 정보, 안심 메시지, 다이제스트 | 진행 중 안심, NPS, 일일 요약 | **가능** | 09:00~18:00 SGT |
| **Marketing** | 마케팅·교육·신규 기능 | 뉴스레터, 프로모션 | **명시 opt-in 필수(PDPA)** | 10:00~18:00 평일만 |

### 5.2 수신거부 매트릭스 (Applicant 기준 UI)

사용자 설정 페이지에서 **카테고리 × 채널** 매트릭스 제공:

| 카테고리 | 인앱 | 이메일 | SMS | WhatsApp |
|---------|:---:|:---:|:---:|:---:|
| 보안·계정 | [고정:ON] | [고정:ON] | 옵션 | — |
| 신청 상태 변경 | [고정:ON] | [고정:ON] | — | 옵션 |
| 결제 | [고정:ON] | [고정:ON] | 옵션 | 옵션 |
| 리마인더 | ON/OFF | ON/OFF | ON/OFF | ON/OFF |
| 방문/현장 | [고정:ON] | [고정:ON] | 옵션 | 옵션 |
| 안심 메시지 | ON/OFF | ON/OFF | — | — |
| 만료 알림 | [고정:ON] | [고정:ON] | — | 옵션 |
| 마케팅·뉴스 | OFF(기본) | OFF(기본) | OFF(기본) | OFF(기본) |
| NPS·피드백 | ON/OFF | ON/OFF | — | — |

**원칙**:
- `[고정:ON]` = 법적·거래적 필수, 옵트아웃 불가(Transactional).
- `ON/OFF` = 사용자 선택. 기본값은 ON(실제로 도움되는 정보).
- Marketing은 기본 OFF, 명시 opt-in만 전환. PDPA §13 준수.
- 채널 비활성화 시에도 인앱 기록은 항상 유지(감사 추적).

### 5.3 빈도 가이드라인

- **사용자당 일일 최대 이메일 수**: 5통 (초과 시 자동 다이제스트 전환)
- **사용자당 일일 최대 SMS 수**: 2통 (초과 시 인앱만)
- **사용자당 일일 최대 WhatsApp 수**: 4통
- **동일 이벤트 중복 방지**: 같은 `referenceId + type` 조합은 30분 내 중복 발송 금지(멱등성)
- **리마인더 최대**: 동일 이벤트에 대해 3회까지(예: 결제 마감 D-7/D-3/D-1)

### 5.4 야간·주말 제한 (싱가포르 시간 SGT)

| 시간대 | Critical | Important | Informational | Marketing |
|-------|:---:|:---:|:---:|:---:|
| 평일 08:00~18:00 | ✅ | ✅ | ✅ | ✅ |
| 평일 18:00~22:00 | ✅ | ✅ | ✅(인앱·이메일만) | ❌ |
| 평일 22:00~08:00 | ✅(인앱만 즉시, 외부채널 08:00 큐잉) | 큐잉 | 큐잉 | ❌ |
| 주말/공휴일 09:00~21:00 | ✅ | ✅(긴급만) | ✅(인앱·이메일만) | ❌ |
| 주말/공휴일 기타 | 큐잉 | 큐잉 | 큐잉 | ❌ |

**근거**: 싱가포르는 미국 TCPA 같은 법적 quiet hours는 없으나, PDPA + DNC Registry + 업계 관행(Listrak/Sinch 권고: 21:00~09:00 회피). 마케팅은 Spam Control Act 준수 — 제목에 "ADV" 붙이기 필수.

### 5.5 PDPA 준수 핵심

- **Transactional(거래) vs Marketing(마케팅) 명확 분리** — 거래 이메일에 마케팅 콘텐츠 금지(bundled consent 금지).
- **DNC Registry 체크** — 마케팅 SMS·Voice·Fax 발송 전 필수. 이메일은 DNC 미적용.
- **"STOP" 자동 처리** — SMS/WhatsApp에서 "STOP" 수신 시 24h 내 해당 번호 해당 카테고리 opt-out.
- **consent log 보관** — 언제, 어떻게, 어떤 카테고리에 동의했는지 `user_consent_logs` 테이블에 버전(`TermsVersion.CURRENT`)과 함께.
- **Breach 알림** — PDPA §26D에 따라 침해 발생 시 3일 내 PDPC와 피해자에게 통지. `DataBreachService`와 연동.

---

## 6. 벤치마킹 사례 및 인사이트

### 6.1 싱가포르 현지

| 서비스 | 관찰 포인트 | LicenseKaki 벤치마크 포인트 |
|-------|-----------|------------------------|
| **EMA ELISE** (경쟁 기준) | 이메일 단일 채널 · 접수·승인·만료 시점만 알림 · 상태 조회는 포털 수동 | **차별화 기회**: 여정 전체 실시간 알림 + 다이제스트 · WhatsApp으로 즉시성 확보 |
| **SingPass 앱** | 알림 인박스 내장 · 정부 기관별 카테고리 · 푸시 + 인앱 혼합 | 인앱 로그의 **"카테고리별 탭"** 디자인 참고 |
| **HealthHub (MOH)** | WhatsApp 백신 예약 · 양방향 대화 | Concierge 방문 예약·확인에 양방향 WhatsApp 활용 |
| **IRAS myTax Portal** | 세무 신고 시즌 이메일 다이제스트 · 마감 D-30/D-7 리마인더 | 면허 만료 사전 알림의 **"3-touch 리마인더 리듬"** 참고 |
| **ICA 디지털 알림** | 하드카피 → 디지털 전환(2024~) · 푸시 + 이메일 | 미래 모바일 앱 도입 시 정부 수준 신뢰성 확보 방향 |

### 6.2 글로벌 SaaS

| 서비스 | 관찰 포인트 | LicenseKaki 벤치마크 포인트 |
|-------|-----------|------------------------|
| **Stripe** | 결제 이벤트별 이메일 · 각 이메일 단일 CTA · Dashboard 딥링크 | **한 이메일 = 한 CTA** 원칙 · 인보이스 · 결제 영수증 템플릿 |
| **Asana** | 다이제스트 기본 · 실시간 알림은 @멘션·기한 임박만 · 인앱 우선 | **LEW·Manager 다이제스트** 디자인 직접 차용 |
| **Slack** | 카테고리 × 채널 세밀 제어 · 방해금지 시간 설정 | **수신거부 매트릭스** UI + 사용자 설정 기본형 |
| **Airbnb** | 여행 D-7/D-1/당일 3-touch SMS + 이메일 · 호스트 간 인앱 메시지 | **Concierge·LEW Service 방문 리마인더** 3-touch |
| **DocuSign** | 서명 요청 후 D+1/D+3 자동 리마인더 · 만료 경고 | **LOA 서명 리마인더**(Concierge) |
| **Intercom/Courier** | Preference Center + 채널별 fallback · 다이제스트 배치 | **Preference Center** 기술 구조 차용 |

### 6.3 핵심 인사이트 (적용 권장)

1. **"인박스 제로"를 위한 다이제스트** (Asana 패턴) — LEW/Manager는 실시간 건별 이메일 폭탄 금지. 오전·오후 2회 묶음.
2. **"한 메시지 한 CTA"** (Stripe 패턴) — 모든 이메일·WhatsApp 템플릿은 primary action 1개. 사용자가 선택장애 없도록.
3. **"3-touch 리듬"** (IRAS·Airbnb) — D-7/D-3/D-1 같은 리마인더 템플릿화.
4. **"카테고리 매트릭스 설정"** (Slack·Intercom) — 카테고리 × 채널 옵트아웃을 Preference Center에 제공.
5. **"양방향 WhatsApp"** (HealthHub) — 수동 알림을 넘어 Concierge Manager ↔ 고객 대화 스레드로 활용(Phase 2).

---

## 7. PM 스펙 문서와의 연계 지점

PM이 작업 중인 `notification-requirements.md`에 다음 사항을 **반드시 반영**해야 한다. 이 전략은 "무엇을/왜"이며, PM 스펙은 "어떻게 만들 것인가"다.

### 7.1 스펙에 반드시 들어가야 할 데이터 모델

- **`notification_type` 확장**: 본 문서 §4의 모든 이벤트에 대응하는 enum 값. 현재 `NotificationType`은 10개뿐이며, **누락된 핵심 이벤트** 예시:
  - `APPLICATION_SUBMITTED`, `APPLICATION_REVISION_REQUESTED`, `APPLICATION_PAID`, `APPLICATION_COMPLETED`
  - `SLD_ORDER_QUOTED`, `SLD_ORDER_UPLOADED`, `SLD_ORDER_COMPLETED`
  - `EXPIRED_LICENSE_VISIT_SCHEDULED`, `EXPIRED_LICENSE_VISIT_REMINDER`
  - `LICENSE_EXPIRY_WARNING_D90/D60/D30/D7`
  - `NPS_SURVEY_REQUEST`
- **`notification_category`**: 카테고리 분류 컬럼(SECURITY / STATUS / PAYMENT / REMINDER / VISIT / REASSURANCE / EXPIRY / MARKETING / FEEDBACK). 수신거부 매트릭스의 집계 키.
- **`notification_severity`**: CRITICAL / IMPORTANT / INFORMATIONAL / MARKETING. 전송 시간·채널·중복방지 규칙의 기준.
- **`user_notification_preferences`**: (`user_seq`, `category`, `channel`, `enabled`, `updated_at`) — 5.2 매트릭스 저장.
- **`notification_delivery`**: 각 채널(EMAIL/SMS/WHATSAPP/INAPP)별 발송 레코드. 중복방지 키(`user_seq + reference_type + reference_id + type + channel + window`).
- **`notification_digest_batch`**: LEW·Manager 다이제스트 묶음 단위(08:55~09:00 집계, 09:00 발송).

### 7.2 스펙에 반드시 포함할 AC 3가지 (전략적 요구)

1. **AC-NOTIF-1 (카테고리 × 채널 옵트아웃 필수)**: 모든 알림은 발송 전 `user_notification_preferences`를 조회하여 해당 (category, channel) 쌍이 enabled인지 확인해야 한다. Critical 카테고리는 DB 값과 무관하게 항상 발송. 테스트 필수: "사용자가 리마인더-SMS를 끄면 SMS 리마인더가 발송되지 않으나 이메일 리마인더는 계속 발송된다".
2. **AC-NOTIF-2 (다이제스트 전환 규칙)**: LEW/SLD_MANAGER/CONCIERGE_MANAGER 역할의 `IMPORTANT`·`INFORMATIONAL` 이메일 알림은 즉시 발송이 아닌 다이제스트 큐에 저장되어 매일 09:00 / 15:00 SGT 2회 발송된다. `CRITICAL`은 예외로 즉시 발송. 테스트 필수: "10분 내 동일 LEW에게 5건 이벤트 발생 시 다이제스트 큐에 5건 축적 후 다음 스케줄러에서 1건의 메일로 발송된다".
3. **AC-NOTIF-3 (Quiet Hours + 큐잉)**: `CRITICAL` 외 알림은 싱가포르 시간 22:00~08:00 발송 시도 시 외부 채널(이메일/SMS/WhatsApp)로는 즉시 발송하지 않고 08:00에 큐잉되어 발송된다. 인앱 알림은 시간 제약 없음. 테스트 필수: "SGT 23:00에 Important 알림 트리거 → 외부 채널 발송 로그가 다음날 08:00에 생성됨".

### 7.3 스펙에서 명시적으로 다뤄야 할 비기능 요구

- **WhatsApp Business API 통합 추상화**: `EmailService`와 동일 패턴으로 `WhatsAppService` 인터페이스 + `MetaWhatsAppServiceImpl` / `LogOnlyWhatsAppServiceImpl`(개발용) 2개. Twilio·MessageBird·Meta Cloud API 선택지.
- **템플릿 관리**: `notification_template` 테이블 + 관리자 UI에서 톤·문구 편집 가능(하드코딩 금지, 설정 우선 원칙 §1).
- **멱등성·재시도**: 각 발송은 `idempotency_key` 보유 — SMS/WhatsApp는 외부 API 오류 시 지수 백오프 재시도 3회.
- **관측성**: `notification_delivery` 테이블의 `status`(SENT/FAILED/BOUNCED) + CS용 "내 알림 발송 로그" 페이지.
- **i18n**: 템플릿 영어/한국어/중국어 3개 언어. 기본 영어, 사용자 설정 우선.

### 7.4 범위 밖 (Phase 2+)

- 모바일 앱 Push — 앱 자체가 없음. Phase 3 이후.
- WhatsApp 양방향 대화(인바운드 메시지 처리) — Phase 2 Concierge 전용.
- AI 요약 다이제스트("오늘 신청 7건 중 보완 요청 2건") — Phase 2.
- Slack/MS Teams 연동(Admin 알림) — Phase 2 내부 운영용.

---

## 부록 A. 용어 정의

- **Transactional 알림**: 사용자 행동·거래 결과로 발생. PDPA상 묵시동의. 옵트아웃 불가 원칙.
- **Marketing 알림**: 판촉·교육·뉴스. 명시 opt-in 필수. Spam Control Act + DNC Registry 준수.
- **Digest**: 시간 윈도우 내 여러 이벤트를 1건으로 묶은 알림.
- **Debounce**: 이벤트 발생 후 짧은 윈도우(5~15분) 내 추가 이벤트를 기다렸다가 합쳐서 발송.
- **Quiet Hours**: 긴급성 낮은 알림의 외부 채널 발송을 유보하는 시간대.
- **Preference Center**: 사용자가 카테고리·채널별 알림 수신을 제어하는 설정 UI.

## 부록 B. 참고 자료

- EMA ELISE 애플리케이션 절차 (ema.gov.sg)
- PDPA §13 동의 의무 + §26D 데이터 침해 통지
- Singapore Spam Control Act (ADV 표기 의무)
- Do Not Call Registry
- WhatsApp Business Platform Pricing (2026, Meta)
- PSG Grant (WhatsApp Business API 최대 50% 보조, IMDA)
- Courier.com "Notification Fatigue" 2026 리서치
- Nielsen Norman Group "Transactional Notifications"
