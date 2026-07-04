# WhatsApp 퍼스트 랜딩 개편 (2026-07)

## 배경
오픈 후 신청자·LEW 모두 셀프서비스 신청 흐름을 어려워해 사용률이 저조했다.
미팅 결정(산출물: `LicenseKaki-Landing-Page-Content.pptx` / `licensekaki-page-content.pdf`)에 따라
공개 페이지에서 회원가입·신청 진입을 제거하고, 모든 문의를 WhatsApp Business 채널로 일원화한다.
뒷단 프로세스(신청 생성·검토·LEW 배정·결제 확인)는 담당자가 내부 시스템에서 대행한다.

## 구조
- **Page 1 `/`** — 히어로("EMA Electrical Licenses, Made Simple") + WhatsApp CTA + 서비스 카드 6개 + 신뢰 배너
- **Page 2 `/services`** — 서비스별 앵커 섹션(`#new-license`, `#renewal-license`, `#sld`,
  `#lighting-layout-plan`, `#power-layout-plan`, `#expired-license`).
  각 섹션: 쉬운 설명 + 불릿 + WHO NEEDS THIS + 서비스명 프리필 WhatsApp 버튼.
  담당자가 상담 중 특정 섹션 링크를 신청자에게 전달하는 용도로도 사용.
- **플로팅 WhatsApp 버튼** — 두 공개 페이지 공통 (`FloatingWhatsAppButton`)
- 서비스 라인업 변경: `Apply for Licence→New License`, `Renewal License 신규`,
  `Power Socket→Power Layout Plan`, `LEW Service 카드 제거`
- 카피 출처: 미팅 문서 PDF 판본 (Lighting Layout 의 "meet EMA and code requirements" 표현은
  규제기관 요건 오인 소지로 PPTX 판본 대신 PDF 판본 채택)
- 기존 신청 라우트·페이지는 삭제하지 않음 — 공개 진입점만 제거 (셀프서비스 복귀 대비)
- LEW 가입 노출: 신규 공개 페이지에서 링크 자체를 제거 (`lew_registration_open` 설정과 무관)

## WhatsApp 번호 관리 (설정 우선 원칙)
- 정본: `system_settings.whatsapp_business_number` (시드: `+65 8796 7667`)
- 공개 조회: `GET /api/public/contact-info` (`PublicContactController`)
- 관리자 변경: SystemSettingsPage 카드 → `PUT /api/admin/system/whatsapp-number`
- 프론트: `useWhatsAppNumber()` 훅 + `buildWhatsAppLink()` (`+`·공백 정규화 → `wa.me/6587967667?text=...`)

### 설정 우선 원칙 예외 기록
`useWhatsAppNumber.ts` 의 `FALLBACK_WHATSAPP_NUMBER = '+65 8796 7667'` 하드코딩.
**사유**: WhatsApp 버튼이 공개 페이지의 유일한 전환·문의 채널이므로, 공개 API 장애 또는
설정 미입력 시에도 버튼이 죽으면 안 된다. API 정상 응답 시에는 항상 설정값이 우선한다.
번호가 영구 변경되면 이 폴백 상수도 함께 갱신할 것.

## 남은 운영 확인 사항
- 담당자 대행 신청 생성 흐름(계정 없는 신청자) 지원 여부 점검
- WhatsApp 상담 → PayNow QR 전달 → 입금 확인 운영 절차를 담당자 매뉴얼에 반영
