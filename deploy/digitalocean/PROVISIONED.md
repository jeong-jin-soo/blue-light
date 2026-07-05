# DO 생성 자원 기록 (2026-06-16)

> 비밀값(키/비번)은 여기 기록하지 않음 — GitHub Secrets/비밀저장소에 보관. 아래는 식별자/호스트(비민감)만.

> ⚠️ **2026-07-05 비용축소 통합**: WhatsApp 퍼스트 전환으로 백엔드 상시가동 필요성이 줄어 **단일 드롭릿(prod-a)** 올인원으로 통합.
> - **삭제됨**: Load Balancer(`bluelight-prod-lb`), dev 드롭릿(`bluelight-dev`), prod-b 드롭릿(`bluelight-prod-b`), dev DNS A 레코드, apex/www AAAA 레코드.
> - **변경됨**: MySQL = 드롭릿 내 `mysql:8.4` 컨테이너(볼륨 `bluelight_mysql_data`), TLS = 드롭릿 **Caddy**(Let's Encrypt) 종단, DNS apex/www A → `159.223.58.11`, **sld-agent 제거**.
> - **유지**: prod-a 드롭릿, DO Spaces(`bluelight-uploads-prod`), DOCR(`licensekaki`).
> - **관찰 후 삭제 예정**: Managed MySQL `bluelight-db`(로컬 컨테이너 안정 확인 후). 로컬 덤프 `~/bluelight-aws-rescue/consolidation/`.
> - 정본 compose/Caddyfile: `deploy/digitalocean/docker-compose.do.yml`, `deploy/digitalocean/Caddyfile`. 배포: `.github/workflows/deploy-prod.yml`(단일호스트).
> - 아래 원본 표(3대·LB·Managed)는 통합 전 이력.

## 리전
- 전부 **SGP1 (Singapore)**, VPC `default-sgp1` = `2143e40e-c72b-463e-845f-581fc07ac152` (10.104.0.0/20)

## 컨테이너 레지스트리 (DOCR)
- `registry.digitalocean.com/licensekaki` (Basic, $5/월)

## Managed MySQL — `bluelight-db` ($15/월)
- ID: `265acafe-7e5b-4e86-a997-b644c6cd744b`, MySQL **8.4**, 단일노드 db-s-1vcpu-1gb
- Public host: `bluelight-db-do-user-38710938-0.i.db.ondigitalocean.com:25060`
- Private host(VPC 내 권장): `private-bluelight-db-do-user-38710938-0.i.db.ondigitalocean.com:25060`
- DB: `bluelight` / 앱 유저: `bluelight_admin` (비번은 Secrets)
- CA 인증서: `doctl databases ca-certificate 265acafe-... ` (로컬 `/tmp/do-mysql-ca.crt`)
- JDBC: `jdbc:mysql://<host>:25060/bluelight?sslMode=REQUIRED&serverTimezone=Asia/Singapore&characterEncoding=UTF-8&allowPublicKeyRetrieval=true`

## Spaces — 파일 저장 ($5/월)
- 버킷: `bluelight-uploads-prod` (SGP1, Private, CDN off)
- 엔드포인트: `https://sgp1.digitaloceanspaces.com`
- 키: `bluelight-spaces` (Limited Access, 해당 버킷 Read/Write/Delete) — 값은 Secrets

## Droplets ($18/월 ×3) — image: docker-20-04 (Ubuntu 22.04), 2GB/2vCPU
| 이름 | ID | Public IP | Private IP | 역할 | 태그 |
|---|---|---|---|---|---|
| bluelight-dev | 577957075 | 159.223.76.209 | 10.104.0.3 | 개발(단일) | bluelight, bluelight-dev |
| bluelight-prod-a | 577957239 | 159.223.58.11 | 10.104.0.4 | 운영 | bluelight, bluelight-prod |
| bluelight-prod-b | 577957240 | 159.223.46.56 | 10.104.0.5 | 운영 | bluelight, bluelight-prod |
- 기본 사용자: `root` (마켓플레이스 docker 이미지)

## Load Balancer — `bluelight-prod-lb` ($12/월)
- ID: `21f64db9-dca5-421c-a734-b400285a4064`, 타겟 태그 `bluelight-prod`(prod-a/b 자동)
- IP: **`129.212.216.161`** ← apex DNS A 레코드에 사용
- 현재 규칙: HTTP :80 → :80, health `/health`. **HTTPS(443)는 DNS를 DO로 옮긴 뒤 Let's Encrypt 인증서 추가**

## 월 비용 합계
DOCR $5 + MySQL $15 + Spaces $5 + Droplet $54 + LB $12 = **≈$91/월**

## DNS A 레코드 매핑 (DO DNS 이전 시)
| 이름 | → |
|---|---|
| `licensekaki.com` (apex) | 129.212.216.161 (LB) |
| `dev.licensekaki.com` | 159.223.76.209 (bluelight-dev) |

---
## 다음 단계 (배포 페이즈)
1. LB IP 확인 → PROVISIONED/records 갱신
2. Droplet 준비: DOCR 로그인(`DO_REGISTRY_TOKEN`), `/root/bluelight` 디렉토리, dev엔 Caddy
3. GitHub Secrets 세팅(README 표) + 워크플로를 `.github/workflows/`로 이관(또는 신규)
4. **dev 검증 배포**(develop 푸시 → bluelight-dev) ← prod/DNS 건드리기 전 파이프라인 검증
5. DB 마이그(`migrate-db.sh`) + 파일 마이그(`migrate-files.sh`)
6. DNS DO 이전(NameCheap NS) + 이메일 레코드 복제(`dns/records.md`)
7. LB HTTPS 인증서 + 컷오버 + 검증
8. AWS 정리 + 노출 키 로테이션
