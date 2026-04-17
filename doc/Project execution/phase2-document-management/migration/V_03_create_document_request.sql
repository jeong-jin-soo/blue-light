-- ============================================
-- Phase 2 PR#1 — V_03: Document Request 테이블 생성
-- ============================================
-- 멱등성: CREATE TABLE IF NOT EXISTS
-- 의존성: V_01, V_02 선행 (FK fk_dr_type → document_type_catalog.code)
--          + applications, files, users 테이블 (Phase 1까지 이미 존재)
-- ============================================

CREATE TABLE IF NOT EXISTS document_request (
    document_request_id  BIGINT       NOT NULL AUTO_INCREMENT,
    application_seq      BIGINT       NOT NULL,
    document_type_code   VARCHAR(40)  NOT NULL,
    custom_label         VARCHAR(200),
    lew_note             VARCHAR(1000),
    status               VARCHAR(20)  NOT NULL,
    fulfilled_file_seq   BIGINT,
    requested_by         BIGINT,
    requested_at         DATETIME(6),
    fulfilled_at         DATETIME(6),
    reviewed_at          DATETIME(6),
    reviewed_by          BIGINT,
    rejection_reason     VARCHAR(1000),
    created_at           DATETIME(6),
    updated_at           DATETIME(6),
    created_by           BIGINT,
    updated_by           BIGINT,
    deleted_at           DATETIME(6),
    PRIMARY KEY (document_request_id),
    KEY idx_dr_app_status (application_seq, status),
    KEY idx_dr_type (document_type_code),
    CONSTRAINT fk_dr_application  FOREIGN KEY (application_seq)    REFERENCES applications (application_seq),
    CONSTRAINT fk_dr_type         FOREIGN KEY (document_type_code) REFERENCES document_type_catalog (code),
    CONSTRAINT fk_dr_file         FOREIGN KEY (fulfilled_file_seq) REFERENCES files (file_seq),
    CONSTRAINT fk_dr_requested_by FOREIGN KEY (requested_by)       REFERENCES users (user_seq),
    CONSTRAINT fk_dr_reviewed_by  FOREIGN KEY (reviewed_by)        REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
