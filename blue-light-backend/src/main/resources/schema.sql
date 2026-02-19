-- ============================================
-- Project Blue Light - Database Schema
-- MySQL 8.0 / UTF8MB4
-- ============================================

-- 1. 사용자
CREATE TABLE IF NOT EXISTS users (
    user_seq       BIGINT       NOT NULL AUTO_INCREMENT,
    email          VARCHAR(100) NOT NULL,
    password       VARCHAR(255) NOT NULL,
    name           VARCHAR(50)  NOT NULL,
    phone          VARCHAR(20),
    role           VARCHAR(20)  NOT NULL DEFAULT 'APPLICANT',
    approved_status VARCHAR(20),
    lew_licence_no  VARCHAR(50),
    lew_grade       VARCHAR(20),
    company_name    VARCHAR(100),
    uen             VARCHAR(20),
    designation     VARCHAR(50),
    correspondence_address     VARCHAR(255),
    correspondence_postal_code VARCHAR(10),
    email_verified          BOOLEAN DEFAULT FALSE,
    email_verification_token VARCHAR(255),
    pdpa_consent_at DATETIME(6),
    signature_url   VARCHAR(255),
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    created_by     BIGINT,
    updated_by     BIGINT,
    deleted_at     DATETIME(6),
    PRIMARY KEY (user_seq),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 라이선스 신청
CREATE TABLE IF NOT EXISTS applications (
    application_seq    BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq           BIGINT        NOT NULL,
    address            VARCHAR(255)  NOT NULL,
    postal_code        VARCHAR(10)   NOT NULL,
    building_type      VARCHAR(50),
    selected_kva       INT           NOT NULL,
    quote_amount       DECIMAL(10,2) NOT NULL,
    status             VARCHAR(30)   NOT NULL DEFAULT 'PENDING_REVIEW',
    license_number     VARCHAR(50),
    license_expiry_date DATE,
    review_comment     TEXT,
    assigned_lew_seq   BIGINT,
    sp_account_no            VARCHAR(30),
    application_type         VARCHAR(10)   NOT NULL DEFAULT 'NEW',
    sld_fee                  DECIMAL(10,2),
    original_application_seq BIGINT,
    existing_licence_no      VARCHAR(50),
    renewal_reference_no     VARCHAR(50),
    existing_expiry_date     DATE,
    renewal_period_months    INT,
    ema_fee                  DECIMAL(10,2),
    sld_option               VARCHAR(20)   DEFAULT 'SELF_UPLOAD',
    expiry_notified_at       DATETIME(6),
    created_at         DATETIME(6),
    updated_at         DATETIME(6),
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         DATETIME(6),
    PRIMARY KEY (application_seq),
    KEY idx_applications_user_seq (user_seq),
    KEY idx_applications_status (status),
    KEY idx_applications_assigned_lew (assigned_lew_seq),
    KEY idx_applications_type (application_type),
    CONSTRAINT fk_applications_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_applications_assigned_lew FOREIGN KEY (assigned_lew_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_applications_original FOREIGN KEY (original_application_seq) REFERENCES applications (application_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. 결제 로그
CREATE TABLE IF NOT EXISTS payments (
    payment_seq    BIGINT        NOT NULL AUTO_INCREMENT,
    application_seq BIGINT       NOT NULL,
    transaction_id VARCHAR(100),
    amount         DECIMAL(10,2) NOT NULL,
    payment_method VARCHAR(20)   DEFAULT 'CARD',
    status         VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    paid_at        DATETIME(6),
    updated_at     DATETIME(6),
    created_by     BIGINT,
    updated_by     BIGINT,
    deleted_at     DATETIME(6),
    PRIMARY KEY (payment_seq),
    KEY idx_payments_application_seq (application_seq),
    CONSTRAINT fk_payments_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. 현장 점검
CREATE TABLE IF NOT EXISTS inspections (
    inspection_seq     BIGINT       NOT NULL AUTO_INCREMENT,
    application_seq    BIGINT       NOT NULL,
    inspector_user_seq BIGINT       NOT NULL,
    checklist_data     JSON,
    inspector_comment  TEXT,
    signature_url      VARCHAR(255),
    inspected_at       DATETIME(6),
    updated_at         DATETIME(6),
    created_by         BIGINT,
    updated_by         BIGINT,
    deleted_at         DATETIME(6),
    PRIMARY KEY (inspection_seq),
    KEY idx_inspections_application_seq (application_seq),
    CONSTRAINT fk_inspections_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq),
    CONSTRAINT fk_inspections_inspector FOREIGN KEY (inspector_user_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. 첨부 파일
CREATE TABLE IF NOT EXISTS files (
    file_seq        BIGINT       NOT NULL AUTO_INCREMENT,
    application_seq BIGINT       NOT NULL,
    file_type       VARCHAR(30)  NOT NULL,
    file_url        VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255),
    file_size       BIGINT,
    uploaded_at     DATETIME(6),
    updated_at      DATETIME(6),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_at      DATETIME(6),
    PRIMARY KEY (file_seq),
    KEY idx_files_application_seq (application_seq),
    CONSTRAINT fk_files_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. 시스템 설정 (key-value)
CREATE TABLE IF NOT EXISTS system_settings (
    setting_key   VARCHAR(100)  NOT NULL,
    setting_value TEXT          NOT NULL,
    description   VARCHAR(255),
    updated_at    DATETIME(6),
    updated_by    BIGINT,
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. 비밀번호 재설정 토큰
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    token_seq      BIGINT       NOT NULL AUTO_INCREMENT,
    user_seq       BIGINT       NOT NULL,
    token          VARCHAR(255) NOT NULL,
    expires_at     DATETIME(6)  NOT NULL,
    used_at        DATETIME(6),
    created_at     DATETIME(6),
    PRIMARY KEY (token_seq),
    UNIQUE KEY uk_password_reset_token (token),
    KEY idx_password_reset_user (user_seq),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. SLD 요청
CREATE TABLE IF NOT EXISTS sld_requests (
    sld_request_seq  BIGINT      NOT NULL AUTO_INCREMENT,
    application_seq  BIGINT      NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    applicant_note   TEXT,
    lew_note         TEXT,
    uploaded_file_seq BIGINT,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    created_by       BIGINT,
    updated_by       BIGINT,
    deleted_at       DATETIME(6),
    PRIMARY KEY (sld_request_seq),
    KEY idx_sld_requests_application (application_seq),
    CONSTRAINT fk_sld_requests_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq),
    CONSTRAINT fk_sld_requests_file FOREIGN KEY (uploaded_file_seq) REFERENCES files (file_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. 용량별 단가표
CREATE TABLE IF NOT EXISTS master_prices (
    master_price_seq BIGINT        NOT NULL AUTO_INCREMENT,
    description      VARCHAR(50),
    kva_min          INT           NOT NULL,
    kva_max          INT           NOT NULL,
    price            DECIMAL(10,2) NOT NULL,
    sld_price        DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    is_active        TINYINT(1)    DEFAULT 1,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    created_by       BIGINT,
    updated_by       BIGINT,
    deleted_at       DATETIME(6),
    PRIMARY KEY (master_price_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. 챗봇 대화 기록
CREATE TABLE IF NOT EXISTS chat_messages (
    chat_message_seq  BIGINT       NOT NULL AUTO_INCREMENT,
    session_id        VARCHAR(36)  NOT NULL,
    user_seq          BIGINT,
    role              VARCHAR(10)  NOT NULL,
    content           TEXT         NOT NULL,
    created_at        DATETIME(6),
    PRIMARY KEY (chat_message_seq),
    KEY idx_chat_messages_session (session_id),
    KEY idx_chat_messages_user (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. 데이터 유출 통보 (PDPA)
CREATE TABLE IF NOT EXISTS data_breach_notifications (
    breach_seq         BIGINT       NOT NULL AUTO_INCREMENT,
    title              VARCHAR(200) NOT NULL,
    description        TEXT         NOT NULL,
    severity           VARCHAR(20)  NOT NULL DEFAULT 'HIGH',
    status             VARCHAR(30)  NOT NULL DEFAULT 'DETECTED',
    affected_count     INT          DEFAULT 0,
    data_types_affected VARCHAR(500),
    containment_actions TEXT,
    pdpc_notified_at   DATETIME(6),
    pdpc_reference_no  VARCHAR(100),
    users_notified_at  DATETIME(6),
    resolved_at        DATETIME(6),
    reported_by        BIGINT,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6),
    PRIMARY KEY (breach_seq),
    KEY idx_breach_status (status),
    KEY idx_breach_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. 감사 로그 (append-only)
CREATE TABLE IF NOT EXISTS audit_logs (
    audit_log_seq    BIGINT       NOT NULL AUTO_INCREMENT,
    user_seq         BIGINT,
    user_email       VARCHAR(100),
    user_role        VARCHAR(20),
    action           VARCHAR(50)  NOT NULL,
    action_category  VARCHAR(30)  NOT NULL,
    entity_type      VARCHAR(50),
    entity_id        VARCHAR(50),
    description      VARCHAR(500),
    before_value     JSON,
    after_value      JSON,
    ip_address       VARCHAR(45),
    user_agent       VARCHAR(500),
    request_method   VARCHAR(10),
    request_uri      VARCHAR(255),
    http_status      INT,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (audit_log_seq),
    KEY idx_audit_logs_user (user_seq),
    KEY idx_audit_logs_action (action),
    KEY idx_audit_logs_category (action_category),
    KEY idx_audit_logs_entity (entity_type, entity_id),
    KEY idx_audit_logs_created_at (created_at),
    KEY idx_audit_logs_composite (action_category, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
