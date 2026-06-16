#!/usr/bin/env bash
# RDS MySQL → DO Managed MySQL 이관 초안.
# 컷오버 시 운영 트래픽 잠깐 차단 후 최종 1회 실행 권장.
set -euo pipefail

# --- 소스 (AWS RDS) ---
SRC_HOST="bluelight-db.c58igm2q6xqm.ap-southeast-7.rds.amazonaws.com"
SRC_PORT=3306
SRC_USER="bluelight_admin"
SRC_DB="bluelight"

# --- 대상 (DO Managed MySQL) — 2026-06-16 생성됨 ---
DST_HOST="bluelight-db-do-user-38710938-0.i.db.ondigitalocean.com"
DST_PORT=25060
DST_USER="bluelight_admin"
DST_DB="bluelight"
DST_CA="/tmp/do-mysql-ca.crt"   # doctl databases ca-certificate 로 받음

DUMP="bluelight_$(date +%Y%m%d_%H%M%S).sql"

echo "==> RDS 덤프"
mysqldump -h "$SRC_HOST" -P "$SRC_PORT" -u "$SRC_USER" -p \
  --single-transaction --routines --triggers --events \
  --set-gtid-purged=OFF \
  "$SRC_DB" > "$DUMP"
echo "    -> $DUMP ($(du -h "$DUMP" | cut -f1))"

echo "==> DO Managed MySQL import (SSL)"
mysql -h "$DST_HOST" -P "$DST_PORT" -u "$DST_USER" -p \
  --ssl-ca="$DST_CA" --ssl-mode=REQUIRED \
  "$DST_DB" < "$DUMP"

echo "==> 검증: 테이블/행수 비교"
echo "   SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='$DST_DB';"
echo "완료. 앱 DB_URL 을 DO 호스트로 전환하세요."
