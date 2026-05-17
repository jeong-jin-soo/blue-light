# 이메일 발송 — AWS SES → Resend 전환 기록

> **작성일**: 2026-04-30
> **결과**: dev 환경 발송 정상 가동 ✅
> **이전 작업 기록**: [`aws-ses-production-access.md`](./aws-ses-production-access.md)

---

## 1. 배경 — SES Production Access 두 차례 거부

| # | 날짜 | 결과 | 거부 사유 (추정) |
|---|------|------|-----------------|
| 1차 | 2026-04-27 이전 | DENIED (Case `177176364700344`) | 초기 sandbox 테스트 중 발생한 bounce 2건 (lew@licensekaki.com, customer@licensekaki.com) |
| 2차 | 2026-04-27 (재오픈 시도) | 자동 폐쇄 (14일 무응답) | — |
| 3차 | 2026-04-29 (관련 사례 생성) | DENIED — "security reasons" | AI 자동 스크리너 추정. 도메인 등록일 2개월(2026-02-22) + bounce 이력 + SNS 구독자 미연결 패턴 |

→ **시간이 해결할 수 없는 문제(도메인 reputation 누적)** 가 핵심 거부 요인. 운영 가동을 더 미룰 수 없어 Resend로 전환.

---

## 2. Resend 채택 사유

| 기준 | Resend | Postmark | SendGrid | Mailgun |
|------|--------|----------|----------|---------|
| 무료 tier | 3,000/월 · 100/일 ⭐ | 100/월 trial | 100/일 | 100/일 (3개월) |
| 신규 도메인 친화 | ✅ 매우 | ✅ | ⚠️ DMARC 강제 | ⚠️ |
| 승인 속도 | 즉시 | 같은 날 | 24h | 1~3일 |
| 모던 도구 (React Email 등) | ✅ | — | — | — |
| **백엔드 인프라** | **AWS SES (Tokyo) wrap** | 자체 | 자체 | 자체 |

→ Resend가 무료 tier·신규 도메인 친화·즉시 승인의 3박자. 흥미롭게도 Resend는 백엔드로 **AWS SES**를 사용 — DNS의 `feedback-smtp.ap-northeast-1.amazonses.com` MX가 그 증거.

---

## 3. 인프라 구성

### 3.1 Resend 계정·도메인 인증

| 항목 | 값 |
|------|-----|
| 도메인 | `licensekaki.com` |
| 인증 상태 | **Verified** |
| API 키 (dev) | `licensekaki-dev` (Sending access) |
| API 키 (prod) | `licensekaki-prod` (Sending access) |

### 3.2 Route 53 DNS 레코드 (4건, 2026-04-29 적용)

| Name | Type | Value |
|------|------|-------|
| `resend._domainkey.licensekaki.com` | TXT | `p=MIGfMA0GCSqGSIb3DQE...mcE1AjQIDAQAB` (DKIM 공개키) |
| `send.licensekaki.com` | MX | `10 feedback-smtp.ap-northeast-1.amazonses.com` |
| `send.licensekaki.com` | TXT | `v=spf1 include:amazonses.com ~all` |
| `_dmarc.licensekaki.com` | TXT | `v=DMARC1; p=none;` |

> 기존 `mail.licensekaki.com` SES MX/TXT는 **다른 서브도메인**이라 충돌 없이 공존. 추후 SES 미사용이 확정되면 정리.

### 3.3 Spring Boot 설정

**환경변수** (`.env`, GitHub Secrets):
```
MAIL_SMTP_ENABLED=true
MAIL_HOST=smtp.resend.com
MAIL_PORT=465
MAIL_USERNAME=resend
MAIL_PASSWORD=<API key>
MAIL_FROM=noreply@licensekaki.com
MAIL_FROM_NAME=LicenseKaki
SES_CONFIGURATION_SET=         (빈 값 — Resend는 자체 트래킹)
```

**`application.yaml`** — 변경 없음 (이미 환경변수 추상화 완료).

**`MailConfig.java`** — 포트별 TLS 모드 자동 분기 (§4 참고).

---

## 4. 핵심 트러블슈팅 — 포트 465 SMTPS와 STARTTLS 충돌

### 4.1 증상

배포 후 회원가입 인증메일 재발송 시:
- `AuthService` 로그: `Verification email resent: userSeq=21` ✅
- `SmtpEmailService` 후속 로그(success/failure): **0건** ❌
- Resend Emails 탭: 발송 시도 **0건** ❌
- 사용자 화면: 에러 없음 (`@Async`라서 silent)

### 4.2 원인

`MailConfig.java`가 **포트와 무관하게 STARTTLS 모드만** 강제:

```java
props.put("mail.smtp.starttls.enable", "true");
props.put("mail.smtp.starttls.required", "true");
```

- 포트 465 = **SMTPS (implicit SSL)** — 연결 즉시 SSL 핸드셰이크
- 포트 587 = **Submission (STARTTLS)** — 평문 연결 후 STARTTLS 명령

→ 465 포트에 STARTTLS를 시도하면 서버가 RFC 위반으로 즉시 연결 종료. javax.mail 예외가 `@Async` 메서드 안에서 silent로 사라져 디버깅이 매우 어려웠음.

### 4.3 수정 (commit `98dc560`)

`MailConfig.java`에 포트별 자동 분기:

```java
boolean implicitSsl = (port == 465);
if (implicitSsl) {
    props.put("mail.smtp.ssl.enable", "true");
    props.put("mail.smtp.starttls.enable", "false");
} else {
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.starttls.required", "true");
}
log.info("SMTP enabled — host={}, port={}, mode={}", host, port,
         implicitSsl ? "SMTPS(implicit-SSL)" : "STARTTLS");
```

### 4.4 검증

dev 서버에서 EC2 Instance Connect로 임시 SSH 접근하여 직접 확인:

```
03:10:00.626Z  INFO MailConfig       : SMTP enabled — host=smtp.resend.com, port=465, mode=SMTPS(implicit-SSL)
03:23:49.676Z  INFO AuthService      : Verification email resent: userSeq=21
03:23:53.642Z  INFO SmtpEmailService : Email verification email sent to: system@informax855.com   ← 4초 후 성공
```

→ Resend Emails 탭 도달 + 실수신함 도착 확인.

---

## 5. 디버깅 노하우 — dev 서버 백엔드 로그 직접 접근

dev.licensekaki.com은 ALB를 거치고 EC2 자체는 `bluelight-server` (i-0317282cf4a7d39ef, ap-southeast-7, 43.210.92.190).

```bash
# 1. 임시 SSH 키 페어 생성
ssh-keygen -t rsa -b 2048 -f /tmp/ec2_temp_key -N ""

# 2. EC2 Instance Connect로 60초 윈도우 동안 키 주입
aws ec2-instance-connect send-ssh-public-key --region ap-southeast-7 \
  --instance-id i-0317282cf4a7d39ef \
  --instance-os-user ec2-user \
  --ssh-public-key file:///tmp/ec2_temp_key.pub

# 3. 60초 안에 SSH 접속
ssh -i /tmp/ec2_temp_key ec2-user@43.210.92.190

# 4. 컨테이너 로그·환경변수 확인
sudo docker exec bluelight-backend env | grep -iE 'MAIL_|SES_'
sudo docker logs --tail 200 bluelight-backend 2>&1 | grep -iE 'mail|smtp|email'
```

> SSM Session Manager는 EC2 IAM Role에 SSM 권한이 없어 `InvalidInstanceId` 에러로 사용 불가.
> CloudWatch Logs도 백엔드 로그 미연동 상태 — 향후 개선 필요.

---

## 6. 후속 백로그 (우선순위 순)

### P0 — 운영 안전성
- [ ] **Prod 환경 GitHub Secrets 6개 입력** + main 브랜치 머지 → Prod 배포
- [ ] **API 키 rotate** — 채팅에 평문 노출된 dev/prod 키 모두 revoke 후 재발급

### P1 — 디버깅 인프라 (silent failure 방지)
- [ ] `AsyncUncaughtExceptionHandler` 등록 — `@Async` 예외를 명시적으로 ERROR 로그로 기록
- [ ] SMTP Health Indicator — `/actuator/health/mail`에서 SMTP 연결 가능 여부 즉시 확인
- [ ] CloudWatch Logs 연동 — Docker logs 자동 수집 (Logs Driver 설정)

### P2 — Resend 활용도 향상
- [ ] **Webhook 등록** — Resend → 우리 서버로 bounce/complaint/delivery 이벤트 전달
- [ ] `notification_delivery` 감사 테이블 구현 (event 영속화)
- [ ] React Email 템플릿 도입 (현재 인라인 HTML 1,200줄 분리)

### P3 — SES 연관 정리 (선택)
- [ ] AWS SES Configuration Set `licensekaki-default` 정리 또는 보존 결정
- [ ] SES MX/TXT 레코드 (`mail.licensekaki.com.`) 정리 또는 보존 결정
- [ ] SES production access 항소 진행 여부 (Resend로 충분하면 보류)

---

## 7. 참고 — Resend가 AWS SES를 백엔드로 사용

DNS 레코드 `send.licensekaki.com → 10 feedback-smtp.ap-northeast-1.amazonses.com` 가 노출하는 사실:

- Resend 자체가 **AWS SES (Tokyo region) 위에서 실행** 중
- 그래서 deliverability·infrastructure는 SES와 동등
- 차이점은 Resend가 sandbox/production 승인 절차를 자체적으로 더 유연하게 운영
- **본질적으로 SES를 우회한 게 아니라 더 친절한 wrapper를 통해 같은 인프라를 쓰는 셈**

---

## 8. 관련 커밋

| Hash | 메시지 |
|------|--------|
| `99815bb` | feat: 이메일 발송 백엔드를 AWS SES → Resend SMTP로 전환 |
| `98dc560` | fix(mail): 포트 465 SMTPS(implicit SSL) 지원 추가 — Resend 발송 실패 해결 |
