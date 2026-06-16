#!/usr/bin/env bash
# S3(bluelight-uploads-prod) → DO Spaces 파일 이관 초안.
# 실측: 75 객체 / 21.5 MB. 객체 키(경로) 100% 보존 — DB가 경로 참조.
# 사전 1회 전체 동기화 → 컷오버 직전 델타 동기화 1회.
set -euo pipefail

S3_BUCKET="bluelight-uploads-prod"
SPACES_BUCKET="<spaces-bucket-name>"
SPACES_ENDPOINT="https://sgp1.digitaloceanspaces.com"

# === 방법 1: rclone (권장) ===
# rclone config 로 두 remote 생성:
#   aws    (type=s3, provider=AWS, region=ap-southeast-7, AWS 키)
#   spaces (type=s3, provider=DigitalOcean, endpoint=sgp1.digitaloceanspaces.com, Spaces 키)
rclone_sync() {
  rclone sync "aws:${S3_BUCKET}" "spaces:${SPACES_BUCKET}" \
    --checksum --progress
}

# === 방법 2: aws cli 2단계 (로컬 경유) ===
awscli_sync() {
  local tmp=./_filebak
  aws s3 sync "s3://${S3_BUCKET}" "$tmp"
  aws s3 sync "$tmp" "s3://${SPACES_BUCKET}" --endpoint-url "$SPACES_ENDPOINT"
}

echo "==> 파일 동기화 (rclone)"
rclone_sync
# awscli_sync   # rclone 미설치 시 대안

echo "==> 검증: 객체 수 비교"
echo "  S3:     $(aws s3 ls s3://${S3_BUCKET} --recursive | wc -l)"
echo "  Spaces: $(aws s3 ls s3://${SPACES_BUCKET} --recursive --endpoint-url ${SPACES_ENDPOINT} | wc -l)"
echo "완료. 앱 전환 후 LoA PDF/SP account 다운로드·미리보기로 복호화 확인."
