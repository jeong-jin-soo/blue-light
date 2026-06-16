#!/usr/bin/env bash
# DigitalOcean 인프라 프로비저닝 초안 (doctl) — 검토 후 실행.
# 사전: doctl auth init (DIGITALOCEAN_ACCESS_TOKEN), SSH 키 DO 등록.
set -euo pipefail

REGION=sgp1                      # 싱가포르
SIZE_DEV=s-2vcpu-2gb            # 개발 단일
SIZE_PROD=s-2vcpu-2gb          # 운영 ×2
IMAGE=docker-20-04             # Docker 사전설치 Ubuntu
SSH_KEY_ID="57135477"          # bluelight-do (등록 완료 2026-06-16)
REGISTRY_NAME="licensekaki"    # DOCR 이름 (registry.digitalocean.com/licensekaki) — 'bluelight'는 타 계정 선점

echo "==> [1/5] Container Registry (DOCR)"
doctl registry create "$REGISTRY_NAME" --subscription-tier basic || echo "이미 존재"

echo "==> [2/5] Spaces 버킷 (파일 저장) — 콘솔/​s3 API로 생성 권장"
echo "    버킷명 예: bluelight-uploads-prod (리전 $REGION). Spaces 키도 발급."

echo "==> [3/5] Managed MySQL"
doctl databases create bluelight-db \
  --engine mysql --version 8 --region "$REGION" \
  --size db-s-1vcpu-1gb --num-nodes 1 || echo "이미 존재"
# 생성 후: DB/유저 생성, CA 인증서 다운로드, Trusted Sources에 Droplet 추가
#   doctl databases db create   <db-id> bluelight
#   doctl databases user create <db-id> bluelight_admin
#   doctl databases connection  <db-id>            # JDBC URL 확인

echo "==> [4/5] Droplets (dev 1, prod 2)"
doctl compute droplet create bluelight-dev \
  --region "$REGION" --size "$SIZE_DEV" --image "$IMAGE" --ssh-keys "$SSH_KEY_ID" --wait
doctl compute droplet create bluelight-prod-a bluelight-prod-b \
  --region "$REGION" --size "$SIZE_PROD" --image "$IMAGE" --ssh-keys "$SSH_KEY_ID" --wait

echo "==> [5/5] Load Balancer (운영 ×2, 443→80, 관리형 인증서)"
echo "    콘솔에서 LB 생성 → forwarding HTTPS:443 → HTTP:80,"
echo "    Let's Encrypt 인증서(licensekaki.com), health check GET /health,"
echo "    droplet 태그/이름으로 prod-a,prod-b 부착 권장."

echo "==> Droplet 공인 IP:"
doctl compute droplet list --format Name,PublicIPv4 --no-header | grep bluelight || true

cat <<'NEXT'

다음 수동 단계:
  - 각 Droplet: docker compose 준비, DOCR 로그인용 DO 토큰 배치, /home/<user>/bluelight 생성
  - 개발 Droplet: Caddy 설치 + Caddyfile.dev 배치
  - GitHub Secrets 갱신 (README 표 참고)
  - DNS: dns/records.md 따라 구성
NEXT
