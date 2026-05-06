-- ============================================
-- Phase 2 PR#1 — V_01: Document Type Catalog 생성
-- ============================================
-- 적용 대상: 운영 / 스테이징 MySQL 8.0
-- 멱등성: CREATE TABLE IF NOT EXISTS
-- 의존성: 없음 (가장 먼저 실행)
-- 후속: V_02 (seed) → V_03 (document_request)
-- ============================================

CREATE TABLE IF NOT EXISTS document_type_catalog (
    code               VARCHAR(40)  NOT NULL,
    label_en           VARCHAR(120) NOT NULL,
    label_ko           VARCHAR(120) NOT NULL,
    description        VARCHAR(500),
    help_text          VARCHAR(1000),
    accepted_mime      VARCHAR(200) NOT NULL,
    max_size_mb        INT          NOT NULL DEFAULT 10,
    template_url       VARCHAR(500),
    example_image_url  VARCHAR(500),
    required_fields    JSON,
    icon_emoji         VARCHAR(16),
    display_order      INT          NOT NULL DEFAULT 0,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         DATETIME(6),
    updated_at         DATETIME(6),
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         DATETIME(6),
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
