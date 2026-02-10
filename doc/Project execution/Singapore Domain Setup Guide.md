# Blue Light 싱가포르 웹 도메인 셋팅 가이드

## 1. 도메인 종류 비교

| 도메인 | 예시 | 연 비용 | 특징 |
|--------|------|---------|------|
| **.com.sg** | `bluelight.com.sg` | **SGD 28~50/년** | 싱가포르 상업용. 사업자등록(ACRA) 필요 → 신뢰도 최고 |
| **.sg** | `bluelight.sg` | SGD 28~50/년 | 싱가포르 일반용. 사업자 아니어도 가능, 짧고 깔끔 |
| **.com** | `bluelight-sg.com` | **USD 7~15/년** | 제한 없음. 글로벌 인지도 높지만 싱가포르 SEO 불리 |

### 추천 전략

```
1순위:  bluelight.com.sg  — 싱가포르 비즈니스 신뢰도 최고
2순위:  bluelight.sg      — 짧고 모던, 동시 구매 권장
3순위:  bluelight.com     — 글로벌 확장용 (이미 타인 소유 가능성 높음)
```

> **SEO 관점**: `.sg`와 `.com.sg` 모두 Google에서 싱가포르 지역 검색에 동일한 가산점을 받습니다. `.com`은 지역 가산점 없음.

---

## 2. .sg / .com.sg 등록 요건 (핵심)

### 외국인/외국 회사의 경우

SGNIC(Singapore Network Information Centre) 규정상 반드시 **싱가포르 현지 연락처**가 필요합니다:

| 요건 | 상세 |
|------|------|
| **Administrative Contact** | 싱가포르 주소 보유자 필수 |
| **SingPass 인증** | 등록 후 21일 이내 SingPass로 본인인증 필수 |
| **미인증 시** | 21일 후 도메인 정지(Suspend) |

### 등록 방법 3가지

| 방법 | 조건 | 비용 | 난이도 |
|------|------|------|--------|
| **A. 싱가포르 파트너가 대행** | 프로젝트 요청자(싱가포르)가 SingPass로 직접 등록 | 도메인 비용만 | 쉬움 |
| **B. Trustee(대리인) 서비스** | 레지스트라가 현지 대리인 제공 | 도메인 + Trustee 비용 (연 $10~30 추가) | 쉬움 |
| **C. 법인 설립 후 등록** | 싱가포르 법인(ACRA) 등록 → .com.sg 가능 | 법인 비용 별도 | 복잡 |

### Blue Light 프로젝트 추천: **방법 A**

> 싱가포르 프로젝트 요청자가 이미 있으므로, **요청자가 .com.sg 도메인을 직접 등록**하고 DNS를 개발팀이 관리하는 것이 가장 간편하고 저렴합니다.

---

## 3. 레지스트라(구매처) 비교

### SGNIC 공인 레지스트라 중 추천

| 레지스트라 | .sg 가격/년 | .com.sg 가격/년 | Trustee | 특징 |
|-----------|-----------|---------------|---------|------|
| **Exabytes SG** (exabytes.sg) | SGD 39 | SGD 39 | 제공 | 싱가포르 로컬 |
| **Vodien** (vodien.com) | SGD 45 | SGD 45 | 제공 | Dreamscape 산하, SG 기반 |
| **CLDY** (cldy.com) | **SGD 28** | **SGD 28** | 제공 | **최저가**, SG 기반 |
| **Gandi** (gandi.net) | ~USD 36 | ~USD 36 | 제공 | 글로벌 레지스트라, UI 우수 |
| **GoDaddy** (godaddy.com) | ~USD 40 | - | 제공 | 글로벌 최대, 갱신비 비쌈 주의 |

### 비추천 / 불가

| 레지스트라 | 사유 |
|-----------|------|
| **AWS Route 53** | .sg / .com.sg **신규 등록 중단됨** (기존 고객만 관리) |
| **Cloudflare** | .sg TLD **미지원** |
| **Namecheap** | .sg 등록 가능하나 Trustee 필요 시 별도 |

---

## 4. DNS 셋팅 구성

도메인 구매 후 DNS를 어디서 관리할지가 중요합니다.

### 추천: Cloudflare DNS (무료)

도메인은 SGNIC 공인 레지스트라에서 구매하되, **DNS 관리는 Cloudflare로 이전** (무료):

```
[도메인 구매]           [DNS 관리]              [서버]
CLDY / Exabytes  ──▶  Cloudflare (무료)  ──▶  AWS 인프라
(SGNIC 공인)           Nameserver 변경          (Phase B 구성)
```

### Cloudflare DNS 무료 혜택
- **CDN 캐싱** — 프론트엔드 정적 파일 무료 CDN (CloudFront 대체 가능)
- **DDoS 보호** — 무료 L3/L4/L7 DDoS 방어
- **SSL/TLS** — 무료 HTTPS 인증서 자동 발급
- **WAF 기본 규칙** — 무료 플랜에도 기본 보안 규칙 포함
- **분석** — 트래픽 분석 대시보드

### DNS 레코드 설정 예시

```
bluelight.com.sg DNS Records (Cloudflare)

| Name     | Type  | Value                 | Proxy        |
|----------|-------|-----------------------|--------------|
| @        | A     | ALB IP (또는 CNAME)    | Proxied      |
| www      | CNAME | bluelight.com.sg      | Proxied      |
| api      | CNAME | alb-xxxx.ap-se-1...   | Proxied      |
| @        | MX    | inbound-smtp.ses...   | DNS only     |
| @        | TXT   | v=spf1 include:ses... | DNS only     |
```

| 서브도메인 | 용도 |
|-----------|------|
| `bluelight.com.sg` | 프론트엔드 (React) |
| `api.bluelight.com.sg` | 백엔드 API (Spring Boot) |
| `www.bluelight.com.sg` | → 메인 도메인 리다이렉트 |

---

## 5. 전체 셋팅 절차 (Step by Step)

### Step 1: 도메인 구매 (싱가포르 요청자)
```
1. 프로젝트 요청자에게 도메인 구매 요청
2. CLDY.com (SGD 28/년) 또는 Exabytes.sg (SGD 39/년) 접속
3. "bluelight.com.sg" 검색 → 구매
4. Administrative Contact에 요청자 정보 입력
5. SingPass 인증 완료 (21일 이내 필수!)
```

### Step 2: Cloudflare DNS 이전 (개발팀)
```
1. Cloudflare 무료 계정 생성
2. "Add a Site" → bluelight.com.sg 입력
3. Cloudflare가 제시하는 Nameserver 2개 복사
4. 레지스트라(CLDY 등) 관리 패널에서 Nameserver를 Cloudflare로 변경
5. 24~48시간 대기 (DNS 전파)
```

### Step 3: DNS 레코드 설정 (개발팀)
```
1. Cloudflare에서 A/CNAME 레코드 설정
2. 프론트엔드: @ → S3/CloudFront 또는 서버 IP
3. API: api 서브도메인 → ALB 엔드포인트
4. SSL: Cloudflare "Full (strict)" 모드 설정
5. 이메일: MX + SPF + DKIM 레코드 (SES 연동 시)
```

### Step 4: 서버 CORS 설정 변경
```properties
# Backend application.yaml
CORS_ALLOWED_ORIGINS=https://bluelight.com.sg,https://www.bluelight.com.sg

# Frontend .env
VITE_API_BASE_URL=https://api.bluelight.com.sg
```

---

## 6. 비용 요약

| 항목 | 연 비용 | 월 환산 |
|------|---------|---------|
| .com.sg 도메인 (CLDY) | SGD 28 (~USD 21) | ~$1.75 |
| .sg 도메인 (동시 구매 권장) | SGD 28 (~USD 21) | ~$1.75 |
| Cloudflare DNS + CDN + DDoS | **무료** | $0 |
| SSL 인증서 (Cloudflare) | **무료** | $0 |
| **합계** | **~USD 42/년** | **~$3.50/월** |

> Cloudflare 무료 CDN을 활용하면 `Production Cost Analysis.md`에서 산정한 **CloudFront $8/월 + WAF $11/월 = $19/월 절감** 가능

---

## 7. 요청자에게 전달할 액션 아이템

요청자(싱가포르)에게 아래 내용을 전달하면 됩니다:

> **도메인 구매 요청 사항**
> 1. CLDY.com 또는 Exabytes.sg 에서 `bluelight.com.sg` 도메인 구매
> 2. Administrative Contact에 본인 정보 입력
> 3. **SingPass 인증을 21일 이내 반드시 완료**
> 4. 구매 완료 후 레지스트라 로그인 정보를 개발팀에 공유 (DNS 설정용)
> 5. (선택) `bluelight.sg`도 함께 구매하면 브랜드 보호에 유리

---

## 참고 자료
- SGNIC 공인 레지스트라 목록: https://www.sgnic.sg/domain-registration/list-of-registrars
- SGNIC 도메인 등록 FAQ: https://www.sgnic.sg/faq/domain-registration
- CLDY 도메인 가격: https://www.cldy.com/sg/domain-registration/
