# Payment Integration Guide

Blue Light 플랫폼 결제 연동 가이드.
신청자가 `PENDING_PAYMENT` 상태에서 결제하고, 결제 확인 후 `PAID`로 자동 전환하는 흐름을 구현하기 위한 3가지 옵션을 정리한다.

> **현재 상태**: 수동 확인 방식 (Admin이 결제 확인 후 직접 상태 변경)
> **목표**: 결제 자동 확인 또는 QR 코드 기반 간편 결제 도입

---

## 목차

1. [Option A: Stripe PayNow](#option-a-stripe-paynow)
2. [Option B: HitPay](#option-b-hitpay)
3. [Option C: 자체 QR 생성 + 수동 확인](#option-c-자체-qr-생성--수동-확인)
4. [비교표](#비교표)
5. [구현 우선순위 권장안](#구현-우선순위-권장안)

---

## Option A: Stripe PayNow

### 개요
Stripe의 PayNow 결제 수단을 활용. Stripe가 QR 코드 생성, 결제 확인, webhook 알림까지 모두 처리.

### 수수료
| 항목 | 요금 |
|------|------|
| PayNow 거래 | **1.3%** per transaction |
| 카드 결제 (참고) | 3.4% + S$0.50 |
| 월 고정비 | 없음 |
| 설정비 | 없음 |

> 예: SGD $500 결제 시 수수료 = $6.50

### 사전 준비
1. [Stripe Singapore 계정](https://dashboard.stripe.com/register) 생성 및 KYC 인증
2. PayNow는 SG 계정에 자동 활성화됨 (별도 신청 불필요)
3. Webhook endpoint URL 준비 (HTTPS 필수)
4. Test mode API Key 확보

### Backend 구현 절차

#### Step 1: 의존성 추가

```groovy
// build.gradle
implementation 'com.stripe:stripe-java:28.+'
```

#### Step 2: 설정값 추가

```yaml
# application.yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:sk_test_...}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:whsec_...}
  paynow-expiry-minutes: ${STRIPE_PAYNOW_EXPIRY:60}
```

#### Step 3: PaymentIntent 생성 API

```
POST /api/applications/{id}/create-payment
```

처리 흐름:
1. Application이 `PENDING_PAYMENT` 상태인지 확인
2. Stripe PaymentIntent 생성:
   - `amount`: application.quoteAmount (센트 단위 변환: x100)
   - `currency`: "sgd"
   - `payment_method_types`: ["paynow"]
   - `metadata`: applicationSeq, userEmail 등
3. PaymentIntent의 `client_secret`을 프론트엔드에 반환

#### Step 4: Webhook 수신 엔드포인트

```
POST /api/webhook/stripe  (public, 인증 불필요)
```

처리 흐름:
1. `Stripe-Signature` 헤더로 요청 검증
2. `payment_intent.succeeded` 이벤트 처리:
   - metadata에서 applicationSeq 추출
   - Payment 레코드 생성 (transactionId = PaymentIntent ID)
   - Application 상태를 `PAID`로 변경
3. `payment_intent.payment_failed` 이벤트: 실패 로그 기록
4. 반드시 2xx 응답 반환 (30초 이내)

#### Step 5: SecurityConfig 설정

```java
// /api/webhook/stripe 를 permitAll + CSRF 제외
```

### Frontend 구현 절차

#### Step 1: Stripe.js 설치

```bash
npm install @stripe/stripe-js @stripe/react-stripe-js
```

#### Step 2: 결제 흐름 UI

`ApplicationDetailPage`의 `PENDING_PAYMENT` 섹션에서:

1. "Pay Now with PayNow" 버튼 클릭
2. Backend API 호출 → `client_secret` 수신
3. `stripe.confirmPayNowPayment(clientSecret)` 호출
4. Stripe가 QR 코드 팝업/모달 표시
5. 고객이 은행 앱으로 QR 스캔 → 결제 완료
6. QR 코드 유효시간: 약 60분
7. 결제 성공 시 페이지 새로고침 → 상태 `PAID` 확인

### 참고 문서
- Stripe PayNow 공식: https://docs.stripe.com/payments/paynow
- Accept a PayNow payment: https://docs.stripe.com/payments/paynow/accept-a-payment
- Stripe Java SDK: https://github.com/stripe/stripe-java
- Webhook 설정: https://docs.stripe.com/webhooks/quickstart?lang=java
- Spring Boot 통합 가이드: https://www.baeldung.com/java-stripe-api

---

## Option B: HitPay

### 개요
싱가포르 로컬 결제 게이트웨이. PayNow를 포함한 다양한 결제 수단 지원. REST API 기반으로 Java에서 HTTP 클라이언트로 연동.

### 수수료
| 항목 | 요금 |
|------|------|
| PayNow QR | **0.4%** flat |
| 카드 (>S$100) | 0.65% + S$0.30 |
| 카드 (<S$100) | 0.9% (최소 S$0.20) |
| 월 고정비 | 없음 |
| 설정비 | 없음 |

> 예: SGD $500 결제 시 수수료 = $2.00

### 사전 준비
1. [HitPay 계정](https://dashboard.hitpayapp.com/register) 생성 및 KYC 인증
2. Dashboard > Developers > API Keys에서 API Key 발급
3. Dashboard > Developers > Webhook Endpoints에서 webhook URL 등록
4. Sandbox 환경: `api.sandbox.hit-pay.com`

### Backend 구현 절차

#### Step 1: HTTP 클라이언트 설정

HitPay는 공식 Java SDK가 없으므로 RestTemplate 또는 WebClient 사용.

```yaml
# application.yaml
hitpay:
  api-key: ${HITPAY_API_KEY:}
  api-url: ${HITPAY_API_URL:https://api.sandbox.hit-pay.com/v1}
  webhook-salt: ${HITPAY_WEBHOOK_SALT:}
```

#### Step 2: Payment Request 생성 API

```
POST /api/applications/{id}/create-payment
```

HitPay API 호출:
```
POST {hitpay.api-url}/payment-requests
Headers:
  X-BUSINESS-API-KEY: {api-key}
  Content-Type: application/json
Body:
  {
    "amount": "500.00",
    "currency": "SGD",
    "payment_methods": ["paynow_online"],
    "email": "applicant@example.com",
    "name": "Applicant Name",
    "reference_number": "BL-{applicationSeq}",
    "redirect_url": "https://bluelight.sg/applications/{id}",
    "webhook": "https://api.bluelight.sg/api/webhook/hitpay",
    "purpose": "Blue Light Licence Application #{applicationSeq}"
  }
```

응답에서 `url` (checkout URL)과 `id` (payment request ID)를 프론트엔드에 반환.

#### Step 3: Webhook 수신 엔드포인트

```
POST /api/webhook/hitpay  (public, 인증 불필요)
```

처리 흐름:
1. `Hitpay-Signature` 헤더 검증 (HMAC-SHA256, salt = webhook secret)
2. `payment_request.completed` 이벤트 처리:
   - `reference_number`에서 applicationSeq 추출
   - Payment 레코드 생성
   - Application 상태를 `PAID`로 변경
3. 반드시 2xx 응답 반환

#### Step 4: Signature 검증

```java
// HMAC-SHA256(requestBody, webhookSalt) == Hitpay-Signature 헤더
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(salt.getBytes(), "HmacSHA256"));
String computed = Hex.encodeHexString(mac.doFinal(body.getBytes()));
// computed == signatureHeader 비교
```

### Frontend 구현 절차

HitPay은 별도 프론트엔드 SDK 없이도 동작 (redirect 방식):

1. "Pay Now" 버튼 클릭
2. Backend API 호출 → HitPay checkout `url` 수신
3. `window.location.href = url` 로 HitPay 결제 페이지로 이동
4. 결제 완료 후 `redirect_url`로 복귀
5. Webhook이 백엔드에서 상태 업데이트 처리

또는 Drop-In UI 방식:
1. HitPay JavaScript SDK 로드
2. 모달 형태로 결제 UI 표시
3. 결제 완료 후 콜백 처리

### Rate Limits
| 엔드포인트 | 제한 |
|-----------|------|
| 일반 API | 400 req/min |
| Payment Request | 70 req/min |

### 참고 문서
- HitPay API 문서: https://docs.hitpayapp.com/introduction
- Online Payments 가이드: https://docs.hitpayapp.com/apis/guide/online-payments
- Webhook Events: https://docs.hitpayapp.com/apis/guide/events
- 가격: https://hitpayapp.com/pricing

---

## Option C: 자체 QR 생성 + 수동 확인

### 개요
SGQR/EMVCo 표준에 따라 PayNow QR 코드를 직접 생성하여 표시. 결제 확인은 Admin이 수동으로 처리 (현재 방식 유지).

### 수수료
| 항목 | 요금 |
|------|------|
| 거래 수수료 | **없음** (직접 은행 계좌로 입금) |
| QR 라이브러리 | 무료 (오픈소스) |

### 사전 준비
1. PayNow Corporate 등록 완료 (UEN 기반, 은행 인터넷뱅킹에서 등록)
2. 수취용 은행 계좌 번호 및 UEN 확인

### SGQR PayNow QR 코드 구조

EMVCo Merchant-Presented QR 표준 (TLV 형식):

```
ID 00: Payload Format Indicator = "01"
ID 01: Point of Initiation Method
        "11" = Static (금액 미지정, 반복 사용)
        "12" = Dynamic (금액 지정, 1회용)
ID 26: Merchant Account Info (PayNow)
  ├─ Sub 00: Reverse Domain = "SG.PAYNOW"
  ├─ Sub 01: Proxy Type = "0"(모바일) / "2"(UEN)
  ├─ Sub 02: Proxy Value = UEN 번호
  ├─ Sub 03: Editable = "0"(금액 고정) / "1"(수정 가능)
  └─ Sub 04: Expiry Date (YYYYMMDD, optional)
ID 52: Merchant Category Code = "0000"
ID 53: Transaction Currency = "702" (SGD)
ID 54: Transaction Amount (e.g., "500.00")
ID 58: Country Code = "SG"
ID 59: Merchant Name
ID 60: Merchant City = "Singapore"
ID 62: Additional Data
  └─ Sub 01: Bill Number / Reference
ID 63: CRC-16 Checksum (CRC-16/CCITT-FALSE)
```

### Backend 구현 절차

#### Step 1: 의존성 추가

```groovy
// build.gradle
implementation 'com.github.mvallim:emv-qrcode:0.1.2'   // EMVCo QR 페이로드 생성
implementation 'com.google.zxing:core:3.5.3'              // QR 이미지 생성
implementation 'com.google.zxing:javase:3.5.3'
```

#### Step 2: PayNow QR 생성 서비스

QR 페이로드 문자열 조립:

```
00 02 01                          → Payload Format Indicator
01 02 12                          → Dynamic QR
26 xx                             → Merchant Account (PayNow)
   00 09 SG.PAYNOW               → Reverse Domain
   01 01 2                        → Proxy Type: UEN
   02 10 {UEN번호}                → UEN Value
   03 01 0                        → Amount NOT editable
52 04 0000                        → MCC
53 03 702                         → Currency: SGD
54 xx {금액}                      → Amount
58 02 SG                          → Country
59 xx {수취인명}                   → Merchant Name
60 09 Singapore                   → City
62 xx                             → Additional Data
   01 xx BL-{applicationSeq}      → Bill Number
63 04 {CRC16}                     → Checksum
```

CRC-16 계산: ISO/IEC 13239 (polynomial 0x1021, init 0xFFFF)

#### Step 3: QR 이미지 API

```
GET /api/applications/{id}/payment-qr
```

처리 흐름:
1. Application이 `PENDING_PAYMENT` 상태인지 확인
2. system_settings에서 PayNow UEN, 수취인명 조회
3. EMVCo 페이로드 문자열 생성 + CRC16 계산
4. ZXing으로 QR 이미지(PNG) 생성
5. `Content-Type: image/png`로 응답

#### Step 4: 결제 확인 (수동, 기존 방식 유지)

현재와 동일하게 Admin이 수동 확인:
```
POST /api/admin/applications/{id}/confirm-payment
```

### Frontend 구현 절차

`ApplicationDetailPage`의 `PENDING_PAYMENT` 섹션:

1. 기존 텍스트 결제 안내는 그대로 유지
2. QR 코드 이미지 표시:
   ```html
   <img src="/api/applications/{id}/payment-qr" alt="PayNow QR" />
   ```
3. QR 아래에 안내 문구: "Scan with your banking app to pay via PayNow"
4. 금액, UEN, Reference 정보를 텍스트로도 표시 (수동 이체 대안)

### 오픈소스 참고 구현
| 언어 | 저장소 |
|------|--------|
| Java (EMVCo) | https://github.com/mvallim/emv-qrcode |
| Go (SGQR) | https://github.com/sausheong/SGQR |
| JavaScript | https://github.com/jtaych/PayNow-QR-Javascript |
| Python | https://github.com/poonchuanan/Python-PayNow-QR-Code-Generator |
| PHP | https://github.com/chriswest101/paynow |

### 한계
- **결제 자동 확인 불가**: 은행 API 연동 없이는 결제 여부를 프로그래밍적으로 확인할 수 없음
- Admin 수동 확인 프로세스 유지 필요
- 향후 은행 API(DBS RAPID, OCBC OneConnect 등) 연동 시 자동화 가능하나 높은 구현 난이도

---

## 비교표

| 항목 | Stripe PayNow | HitPay | 자체 QR + 수동확인 |
|------|:---:|:---:|:---:|
| **PayNow QR 자동생성** | O | O | O |
| **결제 확인 자동화** | O (webhook) | O (webhook) | X (수동) |
| **수수료** | 1.3% | 0.4% | 무료 |
| **Java SDK** | O (공식) | X (REST API) | O (emv-qrcode) |
| **구현 난이도** | 낮음 | 낮음~중간 | 중간 |
| **예상 구현 기간** | 1~2일 | 2~3일 | 1~2일 (QR만) |
| **카드 결제 추가** | 쉬움 | 쉬움 | 불가 |
| **Refund 지원** | O | O | X |
| **대시보드** | O (Stripe) | O (HitPay) | X |

### 수수료 비교 (거래 금액별)

| 거래 금액 | Stripe (1.3%) | HitPay (0.4%) | 자체 QR |
|-----------|:---:|:---:|:---:|
| SGD $350 | $4.55 | $1.40 | $0 |
| SGD $500 | $6.50 | $2.00 | $0 |
| SGD $1,200 | $15.60 | $4.80 | $0 |
| SGD $2,500 | $32.50 | $10.00 | $0 |

---

## 구현 우선순위 권장안

### Phase 1 (즉시 적용 가능): 자체 QR 생성 + 수동 확인
- 현재 수동 확인 프로세스를 유지하면서 QR 코드만 추가
- 수수료 없음, 구현 1~2일
- 신청자 UX 개선 (QR 스캔으로 간편 결제)
- **권장 시점**: 거래량이 적은 초기 운영 단계

### Phase 2 (거래량 증가 시): HitPay 또는 Stripe 연동
- 결제 자동 확인으로 Admin 수동 작업 제거
- `PENDING_PAYMENT` → `PAID` 자동 전환
- **HitPay 권장**: 수수료 절약 (0.4% vs 1.3%)
- **Stripe 권장**: 글로벌 확장 또는 카드 결제 병행 필요 시
- **권장 시점**: 월 10건 이상 결제 발생 시

### Phase 3 (엔터프라이즈): 은행 직접 API 연동
- DBS RAPID / OCBC OneConnect 등
- 수수료 최소화 + 실시간 확인
- 높은 구현/유지보수 비용
- **권장 시점**: 월 거래액 SGD $100K 이상
