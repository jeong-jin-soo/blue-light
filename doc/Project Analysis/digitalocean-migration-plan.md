# AWS → DigitalOcean 마이그레이션 플랜

> 작성: 2026-06-16 · 상태: 초안(실행 전) · 근거: AWS 계정 `237568111399` 실측 조회
>
> **실행 초안 세트**: [`deploy/digitalocean/`](../../deploy/digitalocean/README.md) (compose·CI 워크플로·DNS·마이그 스크립트)

## 0. 배경 / 동기
- AWS 계정이 **국가 KH(캄보디아)** 로 오설정 → 한국 카드 결제 거절·세금/청구 엔티티 불일치 가능성(결제문제의 유력 원인).
- IAM `lisenceKaki-deployer` 키가 평문 노출(로테이션 필요) — 결제폭탄 리스크.
- DigitalOcean은 한국에서 결제 설정이 단순하고 요금이 예측 가능, **싱가포르(SGP1) 데이터센터**가 현재 AWS 태국(ap-southeast-7)보다 타깃 고객(싱가포르)에 가까움.
- 전 서비스가 **도커화**돼 있고 파일저장이 **S3 인터페이스 + 엔드포인트 오버라이드 지원**이라 이전 난이도 낮음.

## 1. 현재 AWS 인벤토리 (실측)
| 자원 | 상세 |
|---|---|
| EC2 | `bluelight-server`(개발, 43.209.42.87/EIP, t3.small), `bluelight-prod-a`(43.209.205.207), `bluelight-prod-b`(43.210.100.80) — 전부 running |
| ALB ×2 | `bluelight-prod-alb-...`(운영, apex), `bluelight-alb-...`(개발, dev.licensekaki.com) |
| RDS | `bluelight-db` MySQL, db.t4g.micro, available |
| ECR ×3 | bluelight-frontend / -backend / -agent (ap-southeast-7) |
| S3 | `bluelight-uploads-prod` — 사용량 거의 0 ($0.0004/월) |
| Route53 | `licensekaki.com.` 호스티드존(18 레코드) |
| Registrar | **NameCheap** (NS만 awsdns 지정) |
| 비용 | 4월 $61 → 5월 $156 → 6월 ~$140 추정, 다음달 예측 $145 |

## 2. 목표 DO 아키텍처 (사용자 확정)
- **개발**: 단일 Droplet (도커 compose로 backend+frontend+sld-agent 동시 구동) + Caddy(자동 TLS)
- **운영**: Droplet ×2 + **DO Load Balancer**(관리형 Let's Encrypt 인증서)
- **DB**: DO Managed MySQL (운영) / 개발은 Managed MySQL 공유 or 동일 인스턴스의 별도 DB
- **파일**: DO Spaces (SGP1, S3 호환)
- **레지스트리**: DO Container Registry(DOCR)
- **DNS**: DO DNS (NameCheap NS 변경)
- **배포 방식**: 현재와 동일 — Droplet + `docker-compose` (App Platform 미사용)

### 비용 추정 (월, 대략)
| 항목 | 사양 | 비용 |
|---|---|---|
| 개발 Droplet | 2GB/2vCPU | $18 |
| 운영 Droplet ×2 | 2GB/2vCPU | $36 |
| Load Balancer | small | $12 |
| Managed MySQL | 1GB/1vCPU/10GB | $15 |
| Spaces | 250GB | $5 |
| Container Registry | Basic 5GB | $5 |
| DNS | — | 무료 |
| **합계** | | **≈ $91** (현재 $140의 약 65%) |
> 개발 DB를 운영 Managed MySQL의 별도 스키마로 합치면 추가 절감 가능.

## 3. 컴포넌트별 이전 방법

### 3.1 컴퓨트 / 도커
- 현 `docker-compose.yml`(backend:8090, sld-agent:8100, frontend:80) **거의 그대로** 사용.
- Droplet에 Docker + compose 설치 → `DOCKER_REGISTRY`를 DOCR로, `IMAGE_TAG` 동일 운용.
- 볼륨(`uploads_data`, `sld_data`, `sld_temp`)은 Droplet 디스크에 마운트(파일은 Spaces 사용 시 uploads_data 비중 감소).

### 3.2 데이터베이스 (RDS → Managed MySQL)
1. DO Managed MySQL 생성(SGP1, MySQL 8).
2. `mysqldump`로 RDS 덤프 → DO로 import (DB 작아 수 분).
3. `DB_URL`을 DO 호스트로 교체. **SSL 필수** — DO 제공 CA 인증서 추가, 기존 `useSSL=true` 유지(필요 시 `verifyServerCertificate`/`trustCertificateKeyStoreUrl`).
4. DO MySQL은 기본적으로 신뢰 소스(Trusted Sources)로 Droplet만 허용하도록 방화벽 설정.

### 3.3 파일 저장 (S3 → Spaces) — **코드 변경 0**
- `S3Config.java`가 이미 `file.s3.endpoint` 엔드포인트 오버라이드 + path-style 지원.
- 환경변수만 교체:
  ```
  FILE_STORAGE_TYPE=s3
  AWS_S3_ENDPOINT=https://sgp1.digitaloceanspaces.com
  AWS_S3_REGION=sgp1            # 또는 us-east-1 (서명용 placeholder)
  AWS_S3_BUCKET=<spaces-bucket-name>
  AWS_ACCESS_KEY_ID=<spaces-access-key>
  AWS_SECRET_ACCESS_KEY=<spaces-secret-key>
  ```

#### 실제 파일 이관 (실측: S3에 75 객체 / 21.5 MB)
- 파일은 EC2 로컬이 아니라 **S3 `bluelight-uploads-prod`(ap-southeast-7)** 에 있음. 활성 prefix: `invoices/`, `loa-form-templates/`, `samples/`, `settings/`, `sld-orders/`, `users/`. (6/14까지 업로드 중 = 라이브)
- 용량 21.5 MB로 작아 **이전은 수 분**. 단 두 가지 불변식 필수:
  1. **객체 키(경로) 100% 동일 유지** — DB가 객체 키를 그대로 참조하므로 prefix/파일명 보존(접두사 변경 금지).
  2. **`FILE_ENCRYPTION_KEY` 동일 값** — 파일은 클라이언트측 AES-256-GCM 암호화된 블롭. 복사는 바이트 단위 그대로, 키만 같으면 DO에서 복호화됨(재암호화 불필요).
- 이관 방법(택1):
  ```bash
  # rclone: aws(소스) → spaces(대상) 두 remote 설정 후
  rclone sync aws:bluelight-uploads-prod spaces:<spaces-bucket> --progress

  # 또는 aws cli 2단계 (로컬 경유)
  aws s3 sync s3://bluelight-uploads-prod ./_filebak
  aws s3 sync ./_filebak s3://<spaces-bucket> \
    --endpoint-url https://sgp1.digitaloceanspaces.com
  ```
- **컷오버 직전 델타 sync** 1회(초기 sync 이후 새로 올라온 파일 반영). S3는 한동안 보존 후 정리.
- 검증: DO 전환 후 앱에서 LoA PDF/SP account 등 **다운로드·미리보기로 복호화 정상** 확인.

### 3.4 컨테이너 레지스트리 (ECR → DOCR)
- DOCR 생성 → `doctl registry login`.
- CI에서 이미지 빌드 후 DOCR로 push.

### 3.5 CI/CD (`.github/workflows/` 수정)
- `ci.yml`: 변경 최소(테스트/빌드 동일).
- `deploy-dev.yml` / `deploy-prod.yml`:
  - ECR 로그인/push → **DOCR 로그인/push**로 교체.
  - 배포 타깃: EC2 SSH → **Droplet SSH**(`docker compose pull && up -d`)로 교체.
  - GitHub Secrets 갱신: `DOCKER_REGISTRY`, `DEV_SERVER_HOST`/`PROD_*_HOST`(DO IP), SSH 키, DB_URL, S3(Spaces) 키, `CORS_ALLOWED_ORIGINS`, `PASSWORD_RESET_BASE_URL`.

## 4. 도메인 연결 (사전 정리)

**현황**: Registrar = NameCheap, NS = Route53(awsdns). apex와 `dev.`가 ALB Alias.

**권장안**: DNS를 **DO DNS로 이전**(AWS 완전 탈출, 무료). 절차:
1. DO에 `licensekaki.com` 도메인 추가 후, **아래 §5 이메일 레코드 포함 전 레코드 재생성**.
2. NameCheap → Domain → Nameservers를 **Custom DNS**로:
   ```
   ns1.digitalocean.com
   ns2.digitalocean.com
   ns3.digitalocean.com
   ```
3. 전파 후 apex/`dev.` A 레코드를 DO IP로 운용.

**대안(보수적)**: Route53 호스티드존 유지($0.50/월)하고 **A 레코드만 DO IP로** 변경 → 이메일/DNS 레코드 재작성 리스크 없음. 단 AWS 계정을 완전히 닫지는 못함.

### A 레코드 매핑 (AWS Alias → DO)
| 이름 | 기존(AWS) | 신규(DO) |
|---|---|---|
| `licensekaki.com` (apex) | ALIAS → prod ALB | A → **운영 LB IP** |
| `dev.licensekaki.com` | ALIAS → dev ALB | A → **개발 Droplet IP** |

> apex를 A로 직접 두려면 DO LB의 고정 IP 사용. (DO LB는 IP 제공)

### TLS
- 운영: DO Load Balancer + 관리형 Let's Encrypt 인증서(도메인 검증).
- 개발: Droplet의 Caddy/nginx+certbot 자동 발급.
- **ACM 검증 CNAME(`_167dabbf….acm-validations.aws`)은 폐기** — 더 이상 불필요.

## 5. 이메일 연동 (사전 정리) — 핵심

**발송 = Resend (변경 없음).** SES 프로덕션 거부로 2026-04-29 전환됨. 앱은 `smtp.resend.com:465`만 바라봄. 서버 위치 무관하게 동작.
**수신 = Google Workspace** (apex MX → SMTP.GOOGLE.COM).

> ⚠️ DNS를 이전하면 아래 **보존 레코드를 새 DNS에 그대로 복제**해야 발송/수신·도달률이 유지됩니다. 누락 시 메일이 스팸 처리되거나 실패합니다.

### 보존(반드시 복제)
| 이름 | 타입 | 값 | 용도 |
|---|---|---|---|
| `licensekaki.com` | MX | `1 SMTP.GOOGLE.COM` | 수신(Workspace) |
| `_dmarc.licensekaki.com` | TXT | `v=DMARC1; p=none;` | DMARC |
| `resend._domainkey` | TXT | `p=MIGfMA0...QAB` | **Resend DKIM(발송)** |
| `send.licensekaki.com` | MX | `10 feedback-smtp.ap-northeast-1.amazonses.com` | **Resend 반송경로** |
| `send.licensekaki.com` | TXT | `v=spf1 include:amazonses.com ~all` | **Resend SPF** |
| `google._domainkey` | TXT | `v=DKIM1;k=rsa;p=MIIB...QAB` | Workspace DKIM |
| `licensekaki.com` | TXT | `google-site-verification=12LLU...Dv0` | Workspace 인증 |

### 삭제(AWS 잔재, 죽은 레코드)
| 이름 | 타입 | 사유 |
|---|---|---|
| `mail.licensekaki.com` | MX/TXT (`feedback-smtp.ap-southeast-1...`, SPF) | 옛 직접 SES(미사용) |
| `*.dkim.amazonses.com` CNAME ×3 (`bdcssvb6...`, `lbuzpc...`, `o6anjt5...`) | CNAME | 옛 SES DKIM(미사용) |
| `_167dabbf….` | CNAME | ACM 인증서 검증(ALB 폐기 시 불필요) |
| apex A, `dev.` A | A(Alias) | ALB 폐기 → DO IP로 교체 |

### 검증 절차
1. DNS 이전 후 Resend 대시보드에서 `licensekaki.com` 도메인 **Verified** 확인(DKIM/SPF/MX 통과).
2. 실제 발송 테스트(비밀번호 재설정·알림 메일) → 수신함 도달 + `mail-tester.com` 스팸 점수 확인.
3. Workspace 수신 정상 확인.

## 6. 컷오버 절차 (다운타임 최소화)
1. **사전(무중단)**: DO 인프라 전부 구축(Droplet·LB·MySQL·Spaces·DOCR), 이미지 push, 앱 기동 확인(임시 IP/서브도메인으로 스모크 테스트).
2. **사전**: DNS TTL을 60~300초로 낮춰 둠(전파 단축).
3. **DB 컷오버**: 운영 트래픽 잠깐 차단 → 최종 `mysqldump` → DO import → 앱이 DO DB 바라보게 전환.
4. **DNS 전환**: apex/`dev.` A를 DO IP로. (DNS 이전 방식이면 NameCheap NS 변경은 사전에, 레코드는 DO에서 미리 준비)
5. **검증**: 헬스체크, 로그인, 파일 업로드(Spaces), 메일 발송, 결제 흐름 스모크 테스트.
6. **롤백**: 문제 시 A 레코드를 기존 ALB로 즉시 원복(AWS는 한동안 살려둠).
7. **정리(후속)**: 수일 안정 후 AWS 리소스 정지→삭제, 마지막에 계정 정리.

## 7. 보안 (이전과 별개로 즉시)
- 노출된 IAM `lisenceKaki-deployer` 키 **폐기→재발급**, GitHub Secrets 갱신.
- `AWS` 평문 파일 → 비밀저장소로 이동, 레포에서 제거(`.gitignore`엔 이미 `/AWS` 추가됨).
- DO에선 IAM 키 대신 Spaces 전용 키 사용(권한 최소화).
- DB 비밀번호·JWT_SECRET 교체 권장(이전 시점에 함께).

## 8. 실행 체크리스트
- [ ] DO 계정/결제(한국) 설정, `doctl` 설치
- [ ] Droplet(개발 1, 운영 2) + LB 생성, 방화벽
- [ ] Managed MySQL 생성 + CA 인증서 + Trusted Sources
- [ ] Spaces 버킷(SGP1) + 전용 키
- [ ] DOCR 생성, 이미지 push
- [ ] `docker-compose`/env 정비(Spaces 엔드포인트, DB_URL, FILE_ENCRYPTION_KEY 동일값)
- [ ] CI 워크플로 DOCR+Droplet로 수정, Secrets 갱신
- [ ] DB 덤프→import 리허설
- [ ] DO DNS에 §5 이메일 레코드 + A 레코드 구성
- [ ] NameCheap NS 변경(또는 Route53 A만 교체)
- [ ] Resend 도메인 Verified 재확인 + 발송 테스트
- [ ] 컷오버 → 검증 → (안정 후) AWS 정리
- [ ] IAM 키/시크릿 로테이션

## 9. 확인 필요 (오픈 이슈)
- DB 실제 용량(덤프 시간 산정) — `bluelight-db` 사이즈 확인.
- 운영 Droplet 사양: 현재 t3.small(2GB) ×2 부하 기준 2GB/2vCPU 적정 여부(부하 측정 후 조정).
- Resend 계정 접근 권한(도메인 재검증용).
- NameCheap 로그인 접근(NS 변경용).
- Spaces 리전 SGP1 확정(데이터 레지던시 요건 시).
