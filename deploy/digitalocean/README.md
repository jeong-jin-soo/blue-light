# DigitalOcean 마이그레이션 — 실행 초안 세트

> 상태: **초안(실행 전, 미적용)** · 상위 플랜: [`../../doc/Project Analysis/digitalocean-migration-plan.md`](../../doc/Project%20Analysis/digitalocean-migration-plan.md)
>
> 기존 AWS 운영을 건드리지 않도록 이 디렉토리에 격리해 두었습니다. 검증 후 실제 위치로 승격합니다.

## 확정 사항 (논의 반영)
- 개발 = **단일 Droplet**, 운영 = **Droplet ×2 + DO Load Balancer**
- 배포 = 현재와 동일 **Droplet + docker-compose** (App Platform 미사용)
- 파일 = **S3 → Spaces** (실측 75객체/21.5MB, 키·암호화키 보존)
- 이메일 = **Resend 유지**(앱 무변경), DNS 레코드 보존/삭제 정리
- 도메인 = **DO DNS 이전**(NameCheap NS 변경) 또는 Route53 A만 교체

## 파일 구성
| 파일 | 용도 | 적용 위치(승격 시) |
|---|---|---|
| `docker-compose.do.yml` | DO용 compose (Spaces endpoint+키 주입 추가) | 레포 루트 `docker-compose.yml` 대체 |
| `.env.do.example` | 서버 `.env` 템플릿(플레이스홀더) | GitHub Secrets로 주입 |
| `Caddyfile.dev` | 개발 Droplet TLS 리버스 프록시 | dev Droplet `/etc/caddy/Caddyfile` |
| `workflows/deploy-dev.do.yml` | dev CI (DOCR + 단일 Droplet) | `.github/workflows/deploy-dev.yml` 대체 |
| `workflows/deploy-prod.do.yml` | prod CI (DOCR + Droplet×2 롤링) | `.github/workflows/deploy-prod.yml` 대체 |
| `scripts/provision.sh` | doctl로 인프라 생성(Droplet/LB/MySQL/Spaces/DOCR) | 1회 실행 |
| `scripts/migrate-db.sh` | RDS → DO Managed MySQL 덤프/임포트 | 컷오버 |
| `scripts/migrate-files.sh` | S3 → Spaces 동기화 | 사전 + 컷오버 델타 |
| `dns/records.md` | DO DNS 레코드 구성(doctl, 이메일 보존) | DNS 전환 |

## 변경 GitHub Secrets (AWS → DO)
| 제거(AWS) | 추가(DO) |
|---|---|
| `AWS_ACCOUNT_ID`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`(ECR/IAM 용) | `DIGITALOCEAN_ACCESS_TOKEN`(CI push), `DO_REGISTRY_TOKEN`(Droplet pull) |
| (ECR 레지스트리) | `DOCKER_REGISTRY=registry.digitalocean.com/licensekaki` |
| `DEV_SERVER_HOST`, `PROD_SERVER_HOST_1/2` (구 IP) | DO Droplet IP로 갱신 |
| `AWS_S3_REGION=ap-southeast-7` | `AWS_S3_REGION=sgp1`, `AWS_S3_ENDPOINT=https://sgp1.digitaloceanspaces.com` |
| (IAM Role로 S3 인증) | `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` = **Spaces 키**(S3 SDK가 env 자격증명 사용) |
| `CORS_ALLOWED_ORIGINS`, `PASSWORD_RESET_BASE_URL` | 도메인 동일(`https://licensekaki.com`,`https://dev.licensekaki.com`) — 값 유지 |

유지(변경 없음): `DB_URL`(호스트만 DO로), `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `FILE_ENCRYPTION_KEY`(**동일값 필수**), `SLD_AGENT_SERVICE_KEY`, `MAIL_*`(Resend), `GEMINI_API_KEY`.

## 주의 (불변식)
1. **`FILE_ENCRYPTION_KEY` 동일 유지** — 다르면 기존 암호화 파일 복호화 불가.
2. **Spaces 객체 키 = S3 객체 키 그대로** — DB가 경로 참조.
3. **이메일 DNS(resend._domainkey, send.* MX/SPF, apex MX) 보존** — 누락 시 발송/수신 깨짐.
4. 빌드 플랫폼 amd64 유지(현 t3.small=amd64, DO Basic Droplet=amd64 → 변경 불필요).
