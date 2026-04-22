-- ============================================
-- Project LicenseKaki - Database Schema
-- MySQL 8.0 / UTF8MB4
-- ============================================

-- 1. 사용자
CREATE TABLE IF NOT EXISTS users (
    user_seq       BIGINT       NOT NULL AUTO_INCREMENT,
    email          VARCHAR(100) NOT NULL,
    password       VARCHAR(255) NOT NULL,
    first_name     VARCHAR(50)  NOT NULL,
    last_name      VARCHAR(50)  NOT NULL,
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
    -- ★ Kaki Concierge v1.4/v1.5 (Phase 1 PR#1) — 계정 상태 + 가입 경로 + 동의 스냅샷
    status                    VARCHAR(30)   NOT NULL DEFAULT 'ACTIVE',
    activated_at              DATETIME(6),
    first_logged_in_at        DATETIME(6),
    signup_source             VARCHAR(30)   NOT NULL DEFAULT 'DIRECT_SIGNUP',
    signup_consent_at         DATETIME(6),
    terms_version             VARCHAR(30),
    marketing_opt_in          BOOLEAN       NOT NULL DEFAULT FALSE,
    marketing_opt_in_at       DATETIME(6),
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    created_by     BIGINT,
    updated_by     BIGINT,
    deleted_at     DATETIME(6),
    PRIMARY KEY (user_seq),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_status (status)
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
    applicant_type           VARCHAR(20)   NOT NULL DEFAULT 'INDIVIDUAL' COMMENT 'INDIVIDUAL | CORPORATE',
    sld_fee                  DECIMAL(10,2),
    original_application_seq BIGINT,
    existing_licence_no      VARCHAR(50),
    renewal_reference_no     VARCHAR(50),
    existing_expiry_date     DATE,
    renewal_period_months    INT,
    ema_fee                  DECIMAL(10,2),
    sld_option               VARCHAR(20)   DEFAULT 'SELF_UPLOAD',
    loa_signature_url        VARCHAR(255),
    loa_signed_at            DATETIME(6),
    -- ★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 3 — LOA 서명 출처 (3경로 모델)
    -- PRD §3.4a / §7.2.1-LOA 참조. 모두 updatable=false (최초 1회만 기록)
    loa_signature_source       VARCHAR(30),
    loa_signature_uploaded_by  BIGINT,
    loa_signature_uploaded_at  DATETIME(6),
    loa_signature_source_memo  VARCHAR(500),
    -- ★ Kaki Concierge v1.5, Phase 1 PR#5 Stage A — Concierge 대리 생성 연결
    via_concierge_request_seq  BIGINT,
    -- LOA 스냅샷 컬럼 (Phase 2 PR#4 / Security B-5) — UPDATE 금지, 엔티티 @Column(updatable=false)로 강제
    -- 신청 생성 시점에는 null, LOA 생성(recordLoaSnapshot) 시점에 기록됨
    applicant_name_snapshot  VARCHAR(100)  NULL,
    company_name_snapshot    VARCHAR(100)  NULL,
    uen_snapshot             VARCHAR(20)   NULL,
    designation_snapshot     VARCHAR(50)   NULL,
    snapshot_backfilled_at   DATETIME(6)   NULL,
    expiry_notified_at       DATETIME(6),
    -- Phase 5: kVA 확정 상태 (phase5-kva-ux/01-spec.md §3)
    kva_status               VARCHAR(20)   NOT NULL DEFAULT 'CONFIRMED' COMMENT 'UNKNOWN | CONFIRMED',
    kva_source               VARCHAR(20)   NULL                         COMMENT 'USER_INPUT | LEW_VERIFIED',
    kva_confirmed_by         BIGINT        NULL,
    kva_confirmed_at         DATETIME(6)   NULL,
    -- Phase 5 B-2: 낙관적 락 (동시성 공격 방어)
    version                  BIGINT        NOT NULL DEFAULT 0,
    -- EMA ELISE 확장 필드 (P1.1) — 모두 nullable. 주소 일부는 필드 단위 암호화(v1:...)
    installation_name                  VARCHAR(200),
    premises_type                      VARCHAR(30),
    is_rental_premises                 TINYINT(1),
    landlord_ei_licence_no             VARCHAR(255),
    renewal_company_name_changed       TINYINT(1),
    renewal_address_changed            TINYINT(1),
    installation_address_block         VARCHAR(20),
    installation_address_unit          VARCHAR(20),
    installation_address_street        VARCHAR(200),
    installation_address_building      VARCHAR(200),
    installation_address_postal_code   VARCHAR(10),
    correspondence_address_block       VARCHAR(255),
    correspondence_address_unit        VARCHAR(255),
    correspondence_address_street      VARCHAR(500),
    correspondence_address_building    VARCHAR(500),
    correspondence_address_postal_code VARCHAR(10),
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
    KEY idx_applications_kva_status (kva_status),
    -- ★ Kaki Concierge v1.5 Phase 1 PR#5 Stage A
    KEY idx_applications_concierge (via_concierge_request_seq),
    CONSTRAINT fk_applications_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_applications_assigned_lew FOREIGN KEY (assigned_lew_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_applications_original FOREIGN KEY (original_application_seq) REFERENCES applications (application_seq),
    -- Phase 5: LEW 계정 삭제 시 확정자 참조는 NULL 로 (감사 로그에 원본 userSeq 보존)
    CONSTRAINT fk_applications_kva_confirmed_by FOREIGN KEY (kva_confirmed_by)
        REFERENCES users (user_seq) ON DELETE SET NULL,
    -- ★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 3: LOA 서명 업로더(Manager) FK
    CONSTRAINT fk_applications_loa_uploader FOREIGN KEY (loa_signature_uploaded_by)
        REFERENCES users (user_seq),
    -- Phase 5: kva_status 와 kva_source 일관성 (R7 대응)
    CONSTRAINT chk_applications_kva_status_source CHECK (
        kva_status = 'UNKNOWN' OR kva_source IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. 결제 로그
CREATE TABLE IF NOT EXISTS payments (
    payment_seq    BIGINT        NOT NULL AUTO_INCREMENT,
    -- ★ Kaki Concierge v1.5 Phase 1 PR#7: application_seq는 nullable로 완화
    -- (향후 CONCIERGE_REQUEST 결제는 application=null). 레거시 조회 편의를 위해 컬럼 보존.
    application_seq BIGINT,
    transaction_id VARCHAR(100),
    amount         DECIMAL(10,2) NOT NULL,
    payment_method VARCHAR(20)   DEFAULT 'CARD',
    status         VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    -- ★ PR#7: 다형 참조 (APPLICATION / CONCIERGE_REQUEST / SLD_ORDER)
    reference_type VARCHAR(30)   NOT NULL DEFAULT 'APPLICATION',
    reference_seq  BIGINT        NOT NULL,
    paid_at        DATETIME(6),
    updated_at     DATETIME(6),
    created_by     BIGINT,
    updated_by     BIGINT,
    deleted_at     DATETIME(6),
    PRIMARY KEY (payment_seq),
    KEY idx_payments_application_seq (application_seq),
    -- ★ PR#7: 다형 참조 조회 인덱스
    KEY idx_payment_reference (reference_type, reference_seq),
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

-- 5-pre. Document Type Catalog (Phase 2)
-- 신청서 첨부 표준 서류 카탈로그. document_request가 FK로 참조하므로 files 테이블 앞에 정의.
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

-- 5. 첨부 파일
CREATE TABLE IF NOT EXISTS files (
    file_seq               BIGINT       NOT NULL AUTO_INCREMENT,
    application_seq        BIGINT,
    sld_order_seq          BIGINT,
    lighting_order_seq     BIGINT,
    power_socket_order_seq BIGINT,
    lew_service_order_seq  BIGINT,
    file_type              VARCHAR(30)  NOT NULL,
    file_url               VARCHAR(500) NOT NULL,
    original_filename      VARCHAR(255),
    file_size              BIGINT,
    uploaded_at            DATETIME(6),
    updated_at             DATETIME(6),
    created_by             BIGINT,
    updated_by             BIGINT,
    deleted_at             DATETIME(6),
    PRIMARY KEY (file_seq),
    KEY idx_files_application_seq (application_seq),
    KEY idx_files_sld_order_seq (sld_order_seq),
    KEY idx_files_lighting_order_seq (lighting_order_seq),
    KEY idx_files_power_socket_order_seq (power_socket_order_seq),
    KEY idx_files_lew_service_order_seq (lew_service_order_seq),
    CONSTRAINT fk_files_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5-post. Document Request (Phase 2)
-- 신청서 단위 서류 요청/제출 레코드. files / document_type_catalog / users 이후 정의.
CREATE TABLE IF NOT EXISTS document_request (
    document_request_id  BIGINT       NOT NULL AUTO_INCREMENT,
    application_seq      BIGINT       NOT NULL,
    document_type_code   VARCHAR(40)  NOT NULL,
    custom_label         VARCHAR(200),
    lew_note             VARCHAR(1000),
    status               VARCHAR(20)  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
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
    CONSTRAINT fk_dr_application FOREIGN KEY (application_seq)    REFERENCES applications (application_seq),
    CONSTRAINT fk_dr_type        FOREIGN KEY (document_type_code) REFERENCES document_type_catalog (code),
    CONSTRAINT fk_dr_file        FOREIGN KEY (fulfilled_file_seq) REFERENCES files (file_seq),
    CONSTRAINT fk_dr_requested_by FOREIGN KEY (requested_by)      REFERENCES users (user_seq),
    CONSTRAINT fk_dr_reviewed_by  FOREIGN KEY (reviewed_by)       REFERENCES users (user_seq)
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
    sketch_file_seq  BIGINT,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    created_by       BIGINT,
    updated_by       BIGINT,
    deleted_at       DATETIME(6),
    PRIMARY KEY (sld_request_seq),
    KEY idx_sld_requests_application (application_seq),
    CONSTRAINT fk_sld_requests_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq),
    CONSTRAINT fk_sld_requests_file FOREIGN KEY (uploaded_file_seq) REFERENCES files (file_seq),
    CONSTRAINT fk_sld_requests_sketch_file FOREIGN KEY (sketch_file_seq) REFERENCES files (file_seq)
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

-- 13. ShedLock (스케줄러 분산 잠금)
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. Rate Limit 시도 기록 (DB 기반, 서버 다중화 대응)
CREATE TABLE IF NOT EXISTS rate_limit_attempts (
    attempt_seq   BIGINT       NOT NULL AUTO_INCREMENT,
    limiter_type  VARCHAR(20)  NOT NULL,
    identifier    VARCHAR(100) NOT NULL,
    attempted_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (attempt_seq),
    KEY idx_rate_limit_lookup (limiter_type, identifier, attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. 감사 로그 아카이브 (1년 초과 로그 보관, Privacy Policy 5년 보유)
CREATE TABLE IF NOT EXISTS audit_logs_archive (
    audit_log_seq    BIGINT       NOT NULL,
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
    created_at       DATETIME(6)  NOT NULL,
    archived_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (audit_log_seq),
    KEY idx_archive_created_at (created_at),
    KEY idx_archive_archived_at (archived_at),
    KEY idx_archive_category (action_category),
    KEY idx_archive_user (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16. SLD AI 채팅 메시지 (신청별 AI 대화 이력)
CREATE TABLE IF NOT EXISTS sld_chat_messages (
    sld_chat_message_seq  BIGINT       NOT NULL AUTO_INCREMENT,
    application_seq       BIGINT,
    sld_order_seq         BIGINT,
    user_seq              BIGINT       NOT NULL,
    role                  VARCHAR(10)  NOT NULL,
    content               TEXT         NOT NULL,
    metadata              JSON,
    created_at            DATETIME(6),
    PRIMARY KEY (sld_chat_message_seq),
    KEY idx_sld_chat_app (application_seq),
    KEY idx_sld_chat_sld_order (sld_order_seq),
    KEY idx_sld_chat_user (user_seq),
    CONSTRAINT fk_sld_chat_app FOREIGN KEY (application_seq) REFERENCES applications (application_seq),
    CONSTRAINT fk_sld_chat_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. SLD 전용 주문
CREATE TABLE IF NOT EXISTS sld_orders (
    sld_order_seq        BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq             BIGINT        NOT NULL,
    assigned_manager_seq BIGINT,
    address              VARCHAR(255),
    postal_code          VARCHAR(10),
    building_type        VARCHAR(50),
    selected_kva         INT,
    ampere               VARCHAR(30),
    applicant_note       TEXT,
    sketch_file_seq      BIGINT,
    status               VARCHAR(30)   NOT NULL DEFAULT 'PENDING_QUOTE',
    quote_amount         DECIMAL(10,2),
    quote_note           TEXT,
    manager_note         TEXT,
    uploaded_file_seq    BIGINT,
    revision_comment     TEXT,
    created_at           DATETIME(6),
    updated_at           DATETIME(6),
    created_by           BIGINT,
    updated_by           BIGINT,
    deleted_at           DATETIME(6),
    PRIMARY KEY (sld_order_seq),
    KEY idx_sld_orders_user (user_seq),
    KEY idx_sld_orders_status (status),
    KEY idx_sld_orders_manager (assigned_manager_seq),
    CONSTRAINT fk_sld_orders_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_sld_orders_manager FOREIGN KEY (assigned_manager_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18. SLD 전용 주문 결제
CREATE TABLE IF NOT EXISTS sld_order_payments (
    sld_order_payment_seq BIGINT        NOT NULL AUTO_INCREMENT,
    sld_order_seq         BIGINT        NOT NULL,
    transaction_id        VARCHAR(100),
    amount                DECIMAL(10,2) NOT NULL,
    payment_method        VARCHAR(20)   DEFAULT 'BANK_TRANSFER',
    status                VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    paid_at               DATETIME(6),
    updated_at            DATETIME(6),
    created_by            BIGINT,
    updated_by            BIGINT,
    deleted_at            DATETIME(6),
    PRIMARY KEY (sld_order_payment_seq),
    KEY idx_sld_order_payments_order (sld_order_seq),
    CONSTRAINT fk_sld_order_payments_order FOREIGN KEY (sld_order_seq) REFERENCES sld_orders (sld_order_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18-1. Lighting Layout 주문 (SLD 주문과 동일 구조)
CREATE TABLE IF NOT EXISTS lighting_orders (
    lighting_order_seq   BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq             BIGINT        NOT NULL,
    assigned_manager_seq BIGINT,
    address              VARCHAR(255),
    postal_code          VARCHAR(10),
    building_type        VARCHAR(50),
    selected_kva         INT,
    applicant_note       TEXT,
    sketch_file_seq      BIGINT,
    status               VARCHAR(30)   NOT NULL DEFAULT 'PENDING_QUOTE',
    quote_amount         DECIMAL(10,2),
    quote_note           TEXT,
    manager_note         TEXT,
    uploaded_file_seq    BIGINT,
    revision_comment     TEXT,
    created_at           DATETIME(6),
    updated_at           DATETIME(6),
    created_by           BIGINT,
    updated_by           BIGINT,
    deleted_at           DATETIME(6),
    PRIMARY KEY (lighting_order_seq),
    KEY idx_lighting_orders_user (user_seq),
    KEY idx_lighting_orders_status (status),
    KEY idx_lighting_orders_manager (assigned_manager_seq),
    CONSTRAINT fk_lighting_orders_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_lighting_orders_manager FOREIGN KEY (assigned_manager_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lighting_order_payments (
    lighting_order_payment_seq BIGINT        NOT NULL AUTO_INCREMENT,
    lighting_order_seq         BIGINT        NOT NULL,
    transaction_id             VARCHAR(100),
    amount                     DECIMAL(10,2) NOT NULL,
    payment_method             VARCHAR(20)   DEFAULT 'BANK_TRANSFER',
    status                     VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    paid_at                    DATETIME(6),
    updated_at                 DATETIME(6),
    created_by                 BIGINT,
    updated_by                 BIGINT,
    deleted_at                 DATETIME(6),
    PRIMARY KEY (lighting_order_payment_seq),
    KEY idx_lighting_order_payments_order (lighting_order_seq),
    CONSTRAINT fk_lighting_order_payments_order FOREIGN KEY (lighting_order_seq) REFERENCES lighting_orders (lighting_order_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18-2. Power Socket 주문
CREATE TABLE IF NOT EXISTS power_socket_orders (
    power_socket_order_seq BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq               BIGINT        NOT NULL,
    assigned_manager_seq   BIGINT,
    address                VARCHAR(255),
    postal_code            VARCHAR(10),
    building_type          VARCHAR(50),
    selected_kva           INT,
    applicant_note         TEXT,
    sketch_file_seq        BIGINT,
    status                 VARCHAR(30)   NOT NULL DEFAULT 'PENDING_QUOTE',
    quote_amount           DECIMAL(10,2),
    quote_note             TEXT,
    manager_note           TEXT,
    uploaded_file_seq      BIGINT,
    revision_comment       TEXT,
    created_at             DATETIME(6),
    updated_at             DATETIME(6),
    created_by             BIGINT,
    updated_by             BIGINT,
    deleted_at             DATETIME(6),
    PRIMARY KEY (power_socket_order_seq),
    KEY idx_power_socket_orders_user (user_seq),
    KEY idx_power_socket_orders_status (status),
    KEY idx_power_socket_orders_manager (assigned_manager_seq),
    CONSTRAINT fk_power_socket_orders_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_power_socket_orders_manager FOREIGN KEY (assigned_manager_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS power_socket_order_payments (
    power_socket_order_payment_seq BIGINT        NOT NULL AUTO_INCREMENT,
    power_socket_order_seq         BIGINT        NOT NULL,
    transaction_id                 VARCHAR(100),
    amount                         DECIMAL(10,2) NOT NULL,
    payment_method                 VARCHAR(20)   DEFAULT 'BANK_TRANSFER',
    status                         VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    paid_at                        DATETIME(6),
    updated_at                     DATETIME(6),
    created_by                     BIGINT,
    updated_by                     BIGINT,
    deleted_at                     DATETIME(6),
    PRIMARY KEY (power_socket_order_payment_seq),
    KEY idx_power_socket_order_payments_order (power_socket_order_seq),
    CONSTRAINT fk_power_socket_order_payments_order FOREIGN KEY (power_socket_order_seq) REFERENCES power_socket_orders (power_socket_order_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18-3. Request for LEW Service 주문
CREATE TABLE IF NOT EXISTS lew_service_orders (
    lew_service_order_seq BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq              BIGINT        NOT NULL,
    assigned_manager_seq  BIGINT,
    address               VARCHAR(255),
    postal_code           VARCHAR(10),
    building_type         VARCHAR(50),
    selected_kva          INT,
    applicant_note        TEXT,
    sketch_file_seq       BIGINT,
    status                VARCHAR(30)   NOT NULL DEFAULT 'PENDING_QUOTE',
    quote_amount          DECIMAL(10,2),
    quote_note            TEXT,
    manager_note          TEXT,
    uploaded_file_seq     BIGINT,
    revision_comment      TEXT,
    created_at            DATETIME(6),
    updated_at            DATETIME(6),
    created_by            BIGINT,
    updated_by            BIGINT,
    deleted_at            DATETIME(6),
    PRIMARY KEY (lew_service_order_seq),
    KEY idx_lew_service_orders_user (user_seq),
    KEY idx_lew_service_orders_status (status),
    KEY idx_lew_service_orders_manager (assigned_manager_seq),
    CONSTRAINT fk_lew_service_orders_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_lew_service_orders_manager FOREIGN KEY (assigned_manager_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lew_service_order_payments (
    lew_service_order_payment_seq BIGINT        NOT NULL AUTO_INCREMENT,
    lew_service_order_seq         BIGINT        NOT NULL,
    transaction_id                VARCHAR(100),
    amount                        DECIMAL(10,2) NOT NULL,
    payment_method                VARCHAR(20)   DEFAULT 'BANK_TRANSFER',
    status                        VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    paid_at                       DATETIME(6),
    updated_at                    DATETIME(6),
    created_by                    BIGINT,
    updated_by                    BIGINT,
    deleted_at                    DATETIME(6),
    PRIMARY KEY (lew_service_order_payment_seq),
    KEY idx_lew_service_order_payments_order (lew_service_order_seq),
    CONSTRAINT fk_lew_service_order_payments_order FOREIGN KEY (lew_service_order_seq) REFERENCES lew_service_orders (lew_service_order_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 19. SLD 템플릿 DB (샘플 SLD에서 추출한 도면 정보)
CREATE TABLE IF NOT EXISTS sld_templates (
    sld_template_seq  BIGINT        NOT NULL AUTO_INCREMENT,
    phase             VARCHAR(20)   NOT NULL COMMENT 'single_phase | three_phase',
    kva               DECIMAL(10,2)          COMMENT 'kVA 용량 (nullable: Cable Extension 등)',
    main_breaker_type VARCHAR(20)            COMMENT 'MCB | MCCB | ELCB',
    circuit_count     INT           NOT NULL DEFAULT 0 COMMENT '서브 회로 수',
    filename          VARCHAR(255)  NOT NULL COMMENT 'PDF 파일명',
    file_path         VARCHAR(500)  NOT NULL COMMENT '템플릿 PDF 상대 경로',
    detail_json       JSON          NOT NULL COMMENT '전체 도면 상세 정보 (JSON)',
    created_at        DATETIME(6)            DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)            DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sld_template_seq),
    UNIQUE KEY uk_sld_templates_filename (filename),
    KEY idx_sld_templates_phase (phase),
    KEY idx_sld_templates_kva (kva),
    KEY idx_sld_templates_breaker (main_breaker_type),
    KEY idx_sld_templates_phase_kva (phase, kva)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 20. 신청 동의/선언 감사 로그 (append-only, EMA ELISE 약관 + PDPA 동의 기록)
CREATE TABLE IF NOT EXISTS application_declaration_logs (
    declaration_log_seq BIGINT       NOT NULL AUTO_INCREMENT,
    application_seq     BIGINT       NOT NULL,
    user_seq            BIGINT       NOT NULL,
    consent_type        VARCHAR(60)  NOT NULL,
    document_version    VARCHAR(30),
    form_snapshot_hash  VARCHAR(64),
    ip_address          VARCHAR(45),
    user_agent          VARCHAR(500),
    declared_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (declaration_log_seq),
    KEY idx_decl_log_application (application_seq),
    KEY idx_decl_log_user (user_seq),
    CONSTRAINT fk_decl_log_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq),
    CONSTRAINT fk_decl_log_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 마이그레이션: sld_requests.sketch_file_seq — MySQL에서 직접 실행:
-- ALTER TABLE sld_requests ADD COLUMN sketch_file_seq BIGINT;

-- 마이그레이션 (기존 운영 DB용): files, sld_chat_messages 테이블에 sld_order_seq 추가
-- ALTER TABLE files ADD COLUMN sld_order_seq BIGINT;
-- ALTER TABLE files MODIFY application_seq BIGINT NULL;
-- ALTER TABLE files ADD CONSTRAINT fk_files_sld_order FOREIGN KEY (sld_order_seq) REFERENCES sld_orders (sld_order_seq);
-- ALTER TABLE sld_chat_messages ADD COLUMN sld_order_seq BIGINT;
-- ALTER TABLE sld_chat_messages MODIFY application_seq BIGINT NULL;
-- ALTER TABLE sld_chat_messages ADD CONSTRAINT fk_sld_chat_sld_order FOREIGN KEY (sld_order_seq) REFERENCES sld_orders (sld_order_seq);
-- 참고: CREATE TABLE 문에는 이미 sld_order_seq가 포함됨 (신규 DB는 자동 적용)

-- 마이그레이션 (기존 운영 DB용): name → first_name + last_name 분리
-- 주의: 이 마이그레이션은 DatabaseMigrationRunner.java에서 Java 코드로 실행됨
-- (MySQL의 DELIMITER/프로시저가 Spring ScriptUtils와 호환되지 않으므로)

-- 14. 샘플 파일 (카테고리별 1개)
CREATE TABLE IF NOT EXISTS sample_files (
    sample_file_seq   BIGINT       NOT NULL AUTO_INCREMENT,
    category_key      VARCHAR(30)  NOT NULL,
    file_url          VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255),
    file_size         BIGINT,
    uploaded_at       DATETIME(6),
    updated_at        DATETIME(6),
    created_by        BIGINT,
    updated_by        BIGINT,
    PRIMARY KEY (sample_file_seq),
    UNIQUE KEY uk_sample_files_category (category_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. 알림
CREATE TABLE IF NOT EXISTS notifications (
    notification_seq  BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_seq     BIGINT       NOT NULL,
    type              VARCHAR(50)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    message           VARCHAR(1000) NOT NULL,
    reference_type    VARCHAR(50),
    reference_id      BIGINT,
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at           DATETIME(6),
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_by        BIGINT,
    updated_by        BIGINT,
    deleted_at        DATETIME(6),
    PRIMARY KEY (notification_seq),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_seq) REFERENCES users (user_seq),
    INDEX idx_notification_recipient_read (recipient_seq, is_read, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16. 계정 활성화 토큰 (★ Kaki Concierge v1.5, Phase 1 PR#1)
-- 컨시어지 자동 생성 계정의 최초 비밀번호 설정용 일회성 토큰 (48h TTL)
CREATE TABLE IF NOT EXISTS account_setup_tokens (
    token_seq             BIGINT        NOT NULL AUTO_INCREMENT,
    token_uuid            VARCHAR(36)   NOT NULL,
    user_seq              BIGINT        NOT NULL,
    source                VARCHAR(40)   NOT NULL,
    expires_at            DATETIME(6)   NOT NULL,
    used_at               DATETIME(6),
    revoked_at            DATETIME(6),
    failed_attempts       INT           NOT NULL DEFAULT 0,
    locked_at             DATETIME(6),
    requesting_ip         VARCHAR(45),
    requesting_user_agent VARCHAR(500),
    created_at            DATETIME(6),
    updated_at            DATETIME(6),
    created_by            BIGINT,
    updated_by            BIGINT,
    deleted_at            DATETIME(6),
    PRIMARY KEY (token_seq),
    UNIQUE KEY uk_account_setup_tokens_uuid (token_uuid),
    CONSTRAINT fk_account_setup_tokens_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    INDEX idx_account_setup_tokens_user_active (user_seq, used_at, revoked_at, locked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. 컨시어지 신청 (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 2)
-- 화이트글러브 대행 서비스 신청 — 신청 시점 스냅샷 + 상태 머신 + 동의 4종 타임스탬프
CREATE TABLE IF NOT EXISTS concierge_requests (
    concierge_request_seq    BIGINT        NOT NULL AUTO_INCREMENT,
    public_code              VARCHAR(20)   NOT NULL,
    submitter_name           VARCHAR(100)  NOT NULL,
    submitter_email          VARCHAR(100)  NOT NULL,
    submitter_phone          VARCHAR(20)   NOT NULL,
    memo                     VARCHAR(2000),
    applicant_user_seq       BIGINT        NOT NULL,
    assigned_manager_seq     BIGINT,
    application_seq          BIGINT,
    payment_seq              BIGINT,
    status                   VARCHAR(40)   NOT NULL DEFAULT 'SUBMITTED',
    pdpa_consent_at          DATETIME(6)   NOT NULL,
    terms_consent_at         DATETIME(6)   NOT NULL,
    signup_consent_at        DATETIME(6)   NOT NULL,
    delegation_consent_at    DATETIME(6)   NOT NULL,
    marketing_opt_in         BOOLEAN       NOT NULL DEFAULT FALSE,
    assigned_at              DATETIME(6),
    first_contact_at         DATETIME(6),
    application_created_at   DATETIME(6),
    loa_requested_at         DATETIME(6),
    loa_signed_at            DATETIME(6),
    licence_paid_at          DATETIME(6),
    completed_at             DATETIME(6),
    cancelled_at             DATETIME(6),
    cancellation_reason      VARCHAR(500),
    -- ★ Phase 1.5 Quote Workflow — 통화 후 견적 이메일 발송 플로우
    call_scheduled_at        DATETIME(6),
    quoted_amount            DECIMAL(10,2),
    quote_sent_at            DATETIME(6),
    verification_phrase      VARCHAR(60),
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               DATETIME(6),
    updated_at               DATETIME(6),
    created_by               BIGINT,
    updated_by               BIGINT,
    deleted_at               DATETIME(6),
    PRIMARY KEY (concierge_request_seq),
    UNIQUE KEY uk_concierge_public_code (public_code),
    CONSTRAINT fk_concierge_applicant FOREIGN KEY (applicant_user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_concierge_manager FOREIGN KEY (assigned_manager_seq) REFERENCES users (user_seq),
    INDEX idx_concierge_status (status),
    INDEX idx_concierge_assigned (assigned_manager_seq, status),
    INDEX idx_concierge_submitter_email (submitter_email),
    INDEX idx_concierge_created (created_at),
    INDEX idx_concierge_applicant_user (applicant_user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18. 컨시어지 연락 기록 (담당자 노트)
CREATE TABLE IF NOT EXISTS concierge_notes (
    concierge_note_seq       BIGINT        NOT NULL AUTO_INCREMENT,
    concierge_request_seq    BIGINT        NOT NULL,
    author_user_seq          BIGINT        NOT NULL,
    channel                  VARCHAR(20)   NOT NULL,
    content                  VARCHAR(2000) NOT NULL,
    created_at               DATETIME(6),
    updated_at               DATETIME(6),
    created_by               BIGINT,
    updated_by               BIGINT,
    deleted_at               DATETIME(6),
    PRIMARY KEY (concierge_note_seq),
    CONSTRAINT fk_concierge_note_request FOREIGN KEY (concierge_request_seq) REFERENCES concierge_requests (concierge_request_seq),
    CONSTRAINT fk_concierge_note_author FOREIGN KEY (author_user_seq) REFERENCES users (user_seq),
    INDEX idx_concierge_note_request (concierge_request_seq, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 19. 사용자 동의 감사 로그 (PDPA 7년 보존 — soft delete 미적용, 모든 필드 불변)
CREATE TABLE IF NOT EXISTS user_consent_logs (
    consent_log_seq          BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq                 BIGINT        NOT NULL,
    consent_type             VARCHAR(40)   NOT NULL,
    action                   VARCHAR(20)   NOT NULL,
    document_version         VARCHAR(30),
    source_context           VARCHAR(40)   NOT NULL,
    ip_address               VARCHAR(45),
    user_agent               VARCHAR(500),
    created_at               DATETIME(6)   NOT NULL,
    PRIMARY KEY (consent_log_seq),
    CONSTRAINT fk_consent_log_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    INDEX idx_consent_log_user_type (user_seq, consent_type, created_at),
    INDEX idx_consent_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- role_metadata — UserRole enum 별 표시 라벨 및 노출/할당 여부 (sysadmin 관리)
-- ============================================
CREATE TABLE IF NOT EXISTS role_metadata (
    role_code       VARCHAR(32)   NOT NULL,
    display_label   VARCHAR(100)  NOT NULL,
    assignable      BOOLEAN       NOT NULL DEFAULT TRUE,
    filterable      BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order      INT           NOT NULL DEFAULT 0,
    created_at      DATETIME(6),
    updated_at      DATETIME(6),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_at      DATETIME(6),
    PRIMARY KEY (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
