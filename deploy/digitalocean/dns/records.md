# DO DNS 레코드 구성 (licensekaki.com) — 초안

> 현 Registrar = **NameCheap**, NS = Route53. DO DNS 이전 시: ① DO에 아래 레코드 생성 → ② NameCheap 네임서버를 `ns1/ns2/ns3.digitalocean.com`으로 변경.
> 보수적 대안: Route53 유지하고 **A 레코드만** DO IP로 변경(이메일 레코드 손대지 않음).

## A. 신규/교체 (AWS ALB → DO)
| 이름 | 타입 | 값 |
|---|---|---|
| `@` (apex) | A | **운영 LB IP** |
| `dev` | A | **개발 Droplet IP** |

## B. 보존 (이메일 — 반드시 복제) ★
| 이름 | 타입 | 값 | 용도 |
|---|---|---|---|
| `@` | MX | `1 SMTP.GOOGLE.COM` | 수신(Google Workspace) |
| `_dmarc` | TXT | `v=DMARC1; p=none;` | DMARC |
| `resend._domainkey` | TXT | `p=MIGfMA0...QAB` (Route53 현재값 그대로) | Resend DKIM(발송) |
| `send` | MX | `10 feedback-smtp.ap-northeast-1.amazonses.com` | Resend 반송경로 |
| `send` | TXT | `v=spf1 include:amazonses.com ~all` | Resend SPF |
| `google._domainkey` | TXT | `v=DKIM1;k=rsa;p=MIIB...QAB` (현재값) | Workspace DKIM |
| `@` | TXT | `google-site-verification=12LLU...Dv0` | Workspace 인증 |

> DKIM TXT 원문 값은 길어 두 조각으로 쪼개져 저장돼 있음 — 복제 시 **공백/따옴표 없이 이어붙인 전체 문자열** 사용. 가장 안전한 방법은 Resend·Workspace 콘솔에서 현재 표시되는 값을 그대로 재입력.

## C. 삭제 (AWS 잔재 — 복제하지 말 것)
- `mail` MX/TXT (`feedback-smtp.ap-southeast-1...`) — 옛 직접 SES
- `*.dkim.amazonses.com` CNAME ×3 (`bdcssvb6...`, `lbuzpc...`, `o6anjt5...`) — 옛 SES DKIM
- `_167dabbf….acm-validations.aws` CNAME — ACM 인증서 검증(ALB 폐기)
- 기존 apex/dev A(Alias) — DO IP로 교체

## doctl 예시
```bash
doctl compute domain create licensekaki.com   # 이미 있으면 생략

# A 레코드
doctl compute domain records create licensekaki.com --record-type A --record-name @   --record-data <PROD_LB_IP>   --record-ttl 300
doctl compute domain records create licensekaki.com --record-type A --record-name dev --record-data <DEV_DROPLET_IP> --record-ttl 300

# 이메일 보존
doctl compute domain records create licensekaki.com --record-type MX  --record-name @    --record-data SMTP.GOOGLE.COM --record-priority 1 --record-ttl 300
doctl compute domain records create licensekaki.com --record-type TXT --record-name _dmarc --record-data "v=DMARC1; p=none;" --record-ttl 300
doctl compute domain records create licensekaki.com --record-type MX  --record-name send --record-data feedback-smtp.ap-northeast-1.amazonses.com --record-priority 10 --record-ttl 300
doctl compute domain records create licensekaki.com --record-type TXT --record-name send --record-data "v=spf1 include:amazonses.com ~all" --record-ttl 300
doctl compute domain records create licensekaki.com --record-type TXT --record-name resend._domainkey --record-data "p=MIGfMA0...QAB" --record-ttl 300
doctl compute domain records create licensekaki.com --record-type TXT --record-name google._domainkey --record-data "v=DKIM1;k=rsa;p=MIIB...QAB" --record-ttl 300
doctl compute domain records create licensekaki.com --record-type TXT --record-name @ --record-data "google-site-verification=12LLUdajp43cBS0aXJOFZcr1ABtiDrz3ScXlFzBHDv0" --record-ttl 300
```

## 검증
```bash
dig +short MX licensekaki.com           # 1 smtp.google.com
dig +short TXT resend._domainkey.licensekaki.com
dig +short A   licensekaki.com           # PROD_LB_IP
dig +short A   dev.licensekaki.com       # DEV_DROPLET_IP
```
그리고 Resend 대시보드 도메인 **Verified** + 실제 발송 테스트.
