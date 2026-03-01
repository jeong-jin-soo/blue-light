#!/usr/bin/env python3
"""
SLD 템플릿 데이터를 MySQL sld_templates 테이블에 임포트하는 스크립트.

Usage:
    cd blue-light-ai
    python -m scripts.import_sld_templates

환경변수(.env)에서 MySQL 접속 정보를 읽음.
기존 데이터가 있으면 UPSERT (filename 기준 중복 시 UPDATE).
"""

import json
import logging
import os
import sys
from pathlib import Path

# 프로젝트 루트를 sys.path에 추가
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pymysql
from pymysql.cursors import DictCursor

from app.config import settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# 데이터 파일 경로
DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "sld-info"
JSON_FILE = DATA_DIR / "sld_database.json"
TEMPLATES_DIR = DATA_DIR / "slds"


def load_json_data() -> list[dict]:
    """sld_database.json 파일 로드."""
    if not JSON_FILE.exists():
        logger.error(f"JSON 파일이 존재하지 않습니다: {JSON_FILE}")
        sys.exit(1)

    with open(JSON_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    logger.info(f"JSON 파일 로드 완료: {len(data)}건")
    return data


def create_table_if_not_exists(conn: pymysql.Connection):
    """sld_templates 테이블이 없으면 생성."""
    ddl = """
    CREATE TABLE IF NOT EXISTS sld_templates (
        sld_template_seq  BIGINT        NOT NULL AUTO_INCREMENT,
        phase             VARCHAR(20)   NOT NULL COMMENT 'single_phase | three_phase',
        kva               DECIMAL(10,2)          COMMENT 'kVA 용량',
        main_breaker_type VARCHAR(20)            COMMENT 'MCB | MCCB | ELCB',
        circuit_count     INT           NOT NULL DEFAULT 0 COMMENT '서브 회로 수',
        filename          VARCHAR(255)  NOT NULL COMMENT 'PDF 파일명',
        file_path         VARCHAR(500)  NOT NULL COMMENT '템플릿 PDF 상대 경로',
        detail_json       JSON          NOT NULL COMMENT '전체 도면 상세 정보',
        created_at        DATETIME(6)            DEFAULT CURRENT_TIMESTAMP(6),
        updated_at        DATETIME(6)            DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
        PRIMARY KEY (sld_template_seq),
        UNIQUE KEY uk_sld_templates_filename (filename),
        KEY idx_sld_templates_phase (phase),
        KEY idx_sld_templates_kva (kva),
        KEY idx_sld_templates_breaker (main_breaker_type),
        KEY idx_sld_templates_phase_kva (phase, kva)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    """
    with conn.cursor() as cursor:
        cursor.execute(ddl)
    conn.commit()
    logger.info("sld_templates 테이블 확인/생성 완료")


def import_data(conn: pymysql.Connection, data: list[dict]):
    """JSON 데이터를 sld_templates 테이블에 UPSERT."""
    upsert_sql = """
    INSERT INTO sld_templates (phase, kva, main_breaker_type, circuit_count, filename, file_path, detail_json)
    VALUES (%s, %s, %s, %s, %s, %s, %s)
    ON DUPLICATE KEY UPDATE
        phase = VALUES(phase),
        kva = VALUES(kva),
        main_breaker_type = VALUES(main_breaker_type),
        circuit_count = VALUES(circuit_count),
        file_path = VALUES(file_path),
        detail_json = VALUES(detail_json)
    """

    inserted = 0
    skipped = 0

    with conn.cursor() as cursor:
        for entry in data:
            try:
                # 검색 가능 컬럼 추출
                phase = entry.get("supply_type", "").strip()
                kva = entry.get("kva")  # None 허용 (Cable Extension 등)
                main_breaker_type = entry.get("main_breaker", {}).get("type")
                circuit_count = len(entry.get("sub_circuits", []))
                filename = entry.get("filename", "").strip()
                file_path = entry.get("file_path", f"slds/{filename}").strip()

                if not filename:
                    logger.warning(f"filename 누락 — 건너뜀: {entry}")
                    skipped += 1
                    continue

                # 템플릿 PDF 파일 존재 확인
                pdf_path = TEMPLATES_DIR / filename
                if not pdf_path.exists():
                    logger.warning(f"PDF 파일 없음: {pdf_path} — 데이터는 임포트")

                # detail_json: 검색 컬럼 제외한 전체 원본 JSON
                detail_json = json.dumps(entry, ensure_ascii=False)

                cursor.execute(upsert_sql, (
                    phase,
                    kva,
                    main_breaker_type,
                    circuit_count,
                    filename,
                    file_path,
                    detail_json,
                ))
                inserted += 1

            except Exception as e:
                logger.error(f"임포트 실패 — {entry.get('filename', '?')}: {e}")
                skipped += 1

    conn.commit()
    logger.info(f"임포트 완료: {inserted}건 성공, {skipped}건 스킵")


def verify_data(conn: pymysql.Connection):
    """임포트 결과 확인."""
    with conn.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) AS cnt FROM sld_templates")
        total = cursor.fetchone()["cnt"]

        cursor.execute("""
            SELECT phase, COUNT(*) AS cnt,
                   MIN(kva) AS min_kva, MAX(kva) AS max_kva
            FROM sld_templates
            GROUP BY phase
        """)
        stats = cursor.fetchall()

        cursor.execute("""
            SELECT main_breaker_type, COUNT(*) AS cnt
            FROM sld_templates
            GROUP BY main_breaker_type
        """)
        breaker_stats = cursor.fetchall()

    logger.info(f"\n=== 임포트 검증 ===")
    logger.info(f"총 템플릿 수: {total}건")
    logger.info(f"\n[Phase별 분포]")
    for row in stats:
        logger.info(f"  {row['phase']}: {row['cnt']}건 (kVA: {row['min_kva']} ~ {row['max_kva']})")
    logger.info(f"\n[Main Breaker Type별 분포]")
    for row in breaker_stats:
        logger.info(f"  {row['main_breaker_type']}: {row['cnt']}건")


def main():
    logger.info("=== SLD 템플릿 데이터 임포트 시작 ===")
    logger.info(f"MySQL: {settings.mysql_host}:{settings.mysql_port}/{settings.mysql_database}")
    logger.info(f"JSON 소스: {JSON_FILE}")

    # MySQL 연결
    try:
        conn = pymysql.connect(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_user,
            password=settings.mysql_password,
            database=settings.mysql_database,
            charset="utf8mb4",
            cursorclass=DictCursor,
            connect_timeout=10,
        )
    except Exception as e:
        logger.error(f"MySQL 연결 실패: {e}")
        sys.exit(1)

    try:
        # 1. 테이블 생성 확인
        create_table_if_not_exists(conn)

        # 2. JSON 데이터 로드
        data = load_json_data()

        # 3. 데이터 임포트
        import_data(conn, data)

        # 4. 결과 확인
        verify_data(conn)

    finally:
        conn.close()

    logger.info("=== 임포트 완료 ===")


if __name__ == "__main__":
    main()
