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
    -- LEW 본인 PayNow 수취 계정 (LEW만 사용, 택1). system_settings PayNow(플랫폼 계좌)와 무관.
    paynow_type     VARCHAR(20),
    paynow_value    VARCHAR(20),
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
    -- ★ WhatsApp 알림 인프라 (PR-0A) — phone_e164 가 발송 정본, phone 은 표시용 원본 유지.
    -- 옵트인은 채널×용도(transactional/marketing) 가 ConsentType 으로 분리되며, 본 컬럼은 ON/OFF 토글 + 최신 변경 시각만 보관.
    phone_e164                VARCHAR(20),
    phone_verified            BOOLEAN       NOT NULL DEFAULT FALSE,
    phone_verified_at         DATETIME(6),
    whatsapp_opt_in           BOOLEAN       NOT NULL DEFAULT FALSE,
    whatsapp_opt_in_at        DATETIME(6),
    whatsapp_opt_out_at       DATETIME(6),
    preferred_language        VARCHAR(10)   NOT NULL DEFAULT 'en',
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    created_by     BIGINT,
    updated_by     BIGINT,
    deleted_at     DATETIME(6),
    PRIMARY KEY (user_seq),
    UNIQUE KEY uk_users_email (email),
    -- LEW 면허번호 중복 방지 (한 실물 LEW = 한 계정). 비-LEW는 NULL이며 MySQL은 다중 NULL 허용.
    -- soft-delete 시 anonymize()가 lew_licence_no를 NULL로 비워 재등록 충돌을 회피(uk_users_email과 동일 전략).
    UNIQUE KEY uk_users_lew_licence_no (lew_licence_no),
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
    license_status     VARCHAR(20),  -- 발급된 라이선스 유효성: ACTIVE | EXPIRED (발급 전 NULL). 신청 status 와 분리.
    license_number     VARCHAR(50),
    license_expiry_date DATE,
    license_issued_at   DATETIME(6),  -- 라이선스 발급 시각 (SLD 미제출 리마인더 발급-경과 기준)
    sld_reminder_notified_at DATETIME(6),  -- SLD 미제출 리마인더 마지막 발송 시각 (주1회 중복 가드)
    review_comment     TEXT,
    assigned_lew_seq   BIGINT,
    sp_account_no            VARCHAR(30),
    application_type         VARCHAR(10)   NOT NULL DEFAULT 'NEW',
    applicant_type           VARCHAR(20)   NOT NULL DEFAULT 'INDIVIDUAL' COMMENT 'INDIVIDUAL | CORPORATE',
    sld_fee                  DECIMAL(10,2),
    callout_fee              DECIMAL(10,2),
    original_application_seq BIGINT,
    existing_licence_no      VARCHAR(50),
    renewal_reference_no     VARCHAR(50),
    existing_expiry_date     DATE,
    renewal_period_months    INT,
    ema_fee                  DECIMAL(10,2),
    sld_option               VARCHAR(40)   DEFAULT 'SELF_UPLOAD',
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
    -- LoA 교환 모델 (loa-exchange 재설계 PR3)
    loa_stage                VARCHAR(30)   NOT NULL DEFAULT 'NOT_STARTED',
    loa_form_template_seq    BIGINT        NULL,
    -- C.1 Snapshot-at-submit: 신청 시점 phone/email (SMS + EMA 양식용, updatable=false)
    loa_phone_snapshot       VARCHAR(20)   NULL,
    loa_email_snapshot       VARCHAR(100)  NULL,
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
    -- ── LEW Review Form — Applicant Hint 컬럼 (P1.B, lew-review-form-spec.md §5.3) ──
    -- 신청자 "알면 입력" 선택 필드. 모두 nullable, CHECK 제약 없음. 형식 오류는 경고 수준.
    applicant_mssl_hint_enc            VARCHAR(255),
    applicant_mssl_hint_hmac           CHAR(64),
    applicant_mssl_hint_last4          VARCHAR(4),
    applicant_supply_voltage_hint      INT,
    applicant_consumer_type_hint       VARCHAR(20),
    applicant_retailer_hint            VARCHAR(32),
    applicant_has_generator_hint       TINYINT(1),
    applicant_generator_capacity_hint  INT,
    -- ── EMA ELISE 제출 추적 (ema-submission-tracking-spec.md §5.2) — IN_PROGRESS 서브-상태 기계 ──
    ema_submission_status              VARCHAR(30)  NOT NULL DEFAULT 'NOT_SUBMITTED',
    ema_submitted_at                   DATETIME(6),
    ema_reference_no                   VARCHAR(60),
    ema_submitted_by_user_seq          BIGINT,
    ema_decision_at                    DATETIME(6),
    ema_query_note                     VARCHAR(1000),
    ema_status_before_decision         VARCHAR(30),  -- 허점#1: Revert(T9) 복원 슬롯
    ema_reminder_notified_at           DATETIME(6),  -- PR-E5: 리마인더 중복 발송 가드(1일 1회 멱등)
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
    KEY idx_applications_ema_status (ema_submission_status),
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
    -- ★ Concierge 강화 + 별도 수금 PR-1 (D2=B): VARCHAR(20) → VARCHAR(40),
    -- 기본값 'CARD' → 'PAYNOW_ONLINE'. PaymentMethod enum 키 그대로 저장.
    -- 백필: 마이그레이션이 기존 'CARD' row 를 'PAYNOW_ONLINE' 으로 갱신한다.
    payment_method VARCHAR(40)   NOT NULL DEFAULT 'PAYNOW_ONLINE',
    status         VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    -- ★ PR#7: 다형 참조 (APPLICATION / CONCIERGE_REQUEST / SLD_ORDER)
    reference_type VARCHAR(30)   NOT NULL DEFAULT 'APPLICATION',
    reference_seq  BIGINT        NOT NULL,
    paid_at        DATETIME(6),
    updated_at     DATETIME(6),
    -- ★ Concierge 강화 + 별도 수금 PR-1: offline 결제(별도 수금) 기록자 + 시점.
    -- 온라인(PAYNOW_ONLINE) 결제는 NULL. PR-2 의 별도 수금 엔드포인트가 채운다.
    recorded_by_user_seq BIGINT,
    recorded_at          DATETIME(6),
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
    application_seq          BIGINT,
    sld_order_seq            BIGINT,
    lighting_order_seq       BIGINT,
    power_socket_order_seq   BIGINT,
    lew_service_order_seq    BIGINT,
    expired_license_order_seq BIGINT,
    file_type                VARCHAR(40)  NOT NULL,
    file_url                 VARCHAR(500) NOT NULL,
    original_filename        VARCHAR(255),
    file_size                BIGINT,
    uploaded_at              DATETIME(6),
    updated_at               DATETIME(6),
    created_by               BIGINT,
    updated_by               BIGINT,
    deleted_at               DATETIME(6),
    PRIMARY KEY (file_seq),
    KEY idx_files_application_seq (application_seq),
    KEY idx_files_sld_order_seq (sld_order_seq),
    KEY idx_files_lighting_order_seq (lighting_order_seq),
    KEY idx_files_power_socket_order_seq (power_socket_order_seq),
    KEY idx_files_lew_service_order_seq (lew_service_order_seq),
    KEY idx_files_expired_license_order_seq (expired_license_order_seq),
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
-- sld_price        : LEW가 SLD 도면을 그려주는 기본 비용
-- endorsement_price: SLD에 LEW 인증 도장(endorsement)까지 포함할 때 가산되는 비용
CREATE TABLE IF NOT EXISTS master_prices (
    master_price_seq  BIGINT        NOT NULL AUTO_INCREMENT,
    description       VARCHAR(50),
    kva_min           INT           NOT NULL,
    kva_max           INT           NOT NULL,
    price             DECIMAL(10,2) NOT NULL,
    renewal_price     DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    sld_price         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    endorsement_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    callout_fee       DECIMAL(10,2) NOT NULL DEFAULT 200.00,
    is_active         TINYINT(1)    DEFAULT 1,
    created_at        DATETIME(6),
    updated_at        DATETIME(6),
    created_by        BIGINT,
    updated_by        BIGINT,
    deleted_at        DATETIME(6),
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

-- 11b. (제거됨) Certificate of Fitness — 서비스에서 CoF 기능 제거 (2026-06).
-- 기존 운영 DB는 schema.sql 하단 "운영 DB 적용 가이드"의 DROP TABLE 구문을 1회 적용.

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
    application_seq  BIGINT,
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
    KEY idx_audit_logs_application (application_seq, created_at),
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
-- endorsement_requested: 신청자가 LEW 인증 도장(endorsement) 포함 여부 선택 (default true)
-- sld_fee / endorsement_fee: 견적 제안 시 매니저가 분해 입력. 합계가 quote_amount
CREATE TABLE IF NOT EXISTS sld_orders (
    sld_order_seq         BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq              BIGINT        NOT NULL,
    assigned_manager_seq  BIGINT,
    address               VARCHAR(255),
    postal_code           VARCHAR(10),
    building_type         VARCHAR(50),
    selected_kva          INT,
    ampere                VARCHAR(30),
    applicant_note        TEXT,
    sketch_file_seq       BIGINT,
    endorsement_requested TINYINT(1)    NOT NULL DEFAULT 1,
    status                VARCHAR(30)   NOT NULL DEFAULT 'PENDING_QUOTE',
    quote_amount          DECIMAL(10,2),
    sld_fee               DECIMAL(10,2),
    endorsement_fee       DECIMAL(10,2),
    quote_note            TEXT,
    manager_note          TEXT,
    uploaded_file_seq     BIGINT,
    revision_comment      TEXT,
    created_at            DATETIME(6),
    updated_at            DATETIME(6),
    created_by            BIGINT,
    updated_by            BIGINT,
    deleted_at            DATETIME(6),
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
    revisit_comment       TEXT,
    visit_scheduled_at    DATETIME(6) NULL,
    visit_schedule_note   TEXT NULL,
    check_in_at           DATETIME(6) NULL,
    check_out_at          DATETIME(6) NULL,
    visit_report_file_seq BIGINT      NULL,
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

-- 18-3-b. LEW Service 방문 사진 (PR 3)
CREATE TABLE IF NOT EXISTS lew_service_visit_photos (
    photo_seq   BIGINT       NOT NULL AUTO_INCREMENT,
    order_seq   BIGINT       NOT NULL,
    file_seq    BIGINT       NOT NULL,
    caption     TEXT         NULL,
    uploaded_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at  DATETIME(6)  NULL,
    PRIMARY KEY (photo_seq),
    KEY idx_lew_visit_photos_order (order_seq),
    KEY idx_lew_visit_photos_file  (file_seq),
    CONSTRAINT fk_lew_visit_photos_order FOREIGN KEY (order_seq) REFERENCES lew_service_orders (lew_service_order_seq),
    CONSTRAINT fk_lew_visit_photos_file  FOREIGN KEY (file_seq) REFERENCES files (file_seq)
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

-- 18-4. Expired License 주문 (LEW Service 와 동일한 생애주기, 다중 참고 문서 업로드)
CREATE TABLE IF NOT EXISTS expired_license_orders (
    expired_license_order_seq BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq                  BIGINT        NOT NULL,
    assigned_manager_seq      BIGINT,
    address                   VARCHAR(255),
    postal_code               VARCHAR(10),
    building_type             VARCHAR(50),
    selected_kva              INT,
    applicant_note            TEXT,
    status                    VARCHAR(30)   NOT NULL DEFAULT 'PENDING_QUOTE',
    quote_amount              DECIMAL(10,2),
    quote_note                TEXT,
    manager_note              TEXT,
    revisit_comment           TEXT,
    visit_scheduled_at        DATETIME(6) NULL,
    visit_schedule_note       TEXT NULL,
    check_in_at               DATETIME(6) NULL,
    check_out_at              DATETIME(6) NULL,
    visit_report_file_seq     BIGINT      NULL,
    created_at                DATETIME(6),
    updated_at                DATETIME(6),
    created_by                BIGINT,
    updated_by                BIGINT,
    deleted_at                DATETIME(6),
    PRIMARY KEY (expired_license_order_seq),
    KEY idx_expired_license_orders_user (user_seq),
    KEY idx_expired_license_orders_status (status),
    KEY idx_expired_license_orders_manager (assigned_manager_seq),
    CONSTRAINT fk_expired_license_orders_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_expired_license_orders_manager FOREIGN KEY (assigned_manager_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS expired_license_visit_photos (
    photo_seq   BIGINT       NOT NULL AUTO_INCREMENT,
    order_seq   BIGINT       NOT NULL,
    file_seq    BIGINT       NOT NULL,
    caption     TEXT         NULL,
    uploaded_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at  DATETIME(6)  NULL,
    PRIMARY KEY (photo_seq),
    KEY idx_expired_license_visit_photos_order (order_seq),
    KEY idx_expired_license_visit_photos_file  (file_seq),
    CONSTRAINT fk_expired_license_visit_photos_order FOREIGN KEY (order_seq) REFERENCES expired_license_orders (expired_license_order_seq),
    CONSTRAINT fk_expired_license_visit_photos_file  FOREIGN KEY (file_seq) REFERENCES files (file_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS expired_license_order_payments (
    expired_license_order_payment_seq BIGINT        NOT NULL AUTO_INCREMENT,
    expired_license_order_seq         BIGINT        NOT NULL,
    transaction_id                    VARCHAR(100),
    amount                            DECIMAL(10,2) NOT NULL,
    payment_method                    VARCHAR(20)   DEFAULT 'BANK_TRANSFER',
    status                            VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    paid_at                           DATETIME(6),
    updated_at                        DATETIME(6),
    created_by                        BIGINT,
    updated_by                        BIGINT,
    deleted_at                        DATETIME(6),
    PRIMARY KEY (expired_license_order_payment_seq),
    KEY idx_expired_license_order_payments_order (expired_license_order_seq),
    CONSTRAINT fk_expired_license_order_payments_order FOREIGN KEY (expired_license_order_seq) REFERENCES expired_license_orders (expired_license_order_seq)
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

-- 21. E-Invoice (결제 확인 후 자동 발행되는 영수증). 스펙: doc/Project Analysis/invoice-spec.md
-- 스냅샷 컬럼은 JPA @Column(updatable=false) 로 불변 보장 (Immutability 원칙).
CREATE TABLE IF NOT EXISTS invoices (
    invoice_seq                         BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_number                      VARCHAR(30)   NOT NULL,
    payment_seq                         BIGINT        NOT NULL,
    reference_type                      VARCHAR(30)   NOT NULL,
    reference_seq                       BIGINT        NOT NULL,
    application_seq                     BIGINT        NULL,
    recipient_user_seq                  BIGINT        NOT NULL,
    issued_by_user_seq                  BIGINT        NULL,
    issued_at                           DATETIME(6)   NOT NULL,
    total_amount                        DECIMAL(12,2) NOT NULL,
    qty_snapshot                        INT           NOT NULL DEFAULT 1,
    rate_amount_snapshot                DECIMAL(12,2) NOT NULL,
    currency_snapshot                   VARCHAR(5)    NOT NULL DEFAULT 'SGD',
    company_name_snapshot               VARCHAR(150)  NOT NULL,
    company_alias_snapshot              VARCHAR(80),
    company_uen_snapshot                VARCHAR(30)   NOT NULL,
    company_address_line1_snapshot      VARCHAR(200),
    company_address_line2_snapshot      VARCHAR(200),
    company_address_line3_snapshot      VARCHAR(200),
    company_email_snapshot              VARCHAR(120),
    company_website_snapshot            VARCHAR(120),
    billing_recipient_name_snapshot     VARCHAR(150)  NOT NULL,
    billing_recipient_company_snapshot  VARCHAR(200),
    billing_address_line1_snapshot      VARCHAR(300),
    billing_address_line2_snapshot      VARCHAR(300),
    billing_address_line3_snapshot      VARCHAR(300),
    billing_address_line4_snapshot      VARCHAR(300),
    installation_name_snapshot          VARCHAR(200),
    installation_address_line1_snapshot VARCHAR(300),
    installation_address_line2_snapshot VARCHAR(300),
    installation_address_line3_snapshot VARCHAR(300),
    installation_address_line4_snapshot VARCHAR(300),
    description_snapshot                TEXT          NOT NULL,
    paynow_uen_snapshot                 VARCHAR(30),
    paynow_qr_file_seq_snapshot         BIGINT,
    footer_note_snapshot                VARCHAR(500),
    pdf_file_seq                        BIGINT        NOT NULL,
    -- ★ kva-postpayment-adjustment-spec.md §10 D3 — 결제 후 kVA 변경 시 INVALIDATED 마킹.
    --   같은 payment_seq 의 신규 영수증 발행 가능. 활성 영수증 1건 보장은 서비스 레이어에서 담당.
    status                              VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    invalidated_reason                  VARCHAR(200),
    invalidated_at                      DATETIME(6),
    created_at                          DATETIME(6),
    updated_at                          DATETIME(6),
    created_by                          BIGINT,
    updated_by                          BIGINT,
    deleted_at                          DATETIME(6),
    PRIMARY KEY (invoice_seq),
    UNIQUE KEY uk_invoices_number (invoice_number),
    -- ★ payment_seq 의 UNIQUE 제약 제거 (INVALIDATED 후 신규 발행 허용). 조회용 일반 인덱스만 유지.
    KEY idx_invoices_payment (payment_seq),
    KEY idx_invoices_payment_status (payment_seq, status),
    KEY idx_invoices_ref (reference_type, reference_seq),
    KEY idx_invoices_application (application_seq),
    KEY idx_invoices_application_status (application_seq, status),
    KEY idx_invoices_recipient (recipient_user_seq),
    CONSTRAINT fk_invoices_payment   FOREIGN KEY (payment_seq)                 REFERENCES payments (payment_seq),
    CONSTRAINT fk_invoices_pdf       FOREIGN KEY (pdf_file_seq)                REFERENCES files (file_seq),
    CONSTRAINT fk_invoices_paynow_qr FOREIGN KEY (paynow_qr_file_seq_snapshot) REFERENCES files (file_seq),
    CONSTRAINT fk_invoices_recipient FOREIGN KEY (recipient_user_seq)          REFERENCES users (user_seq),
    CONSTRAINT fk_invoices_issuer    FOREIGN KEY (issued_by_user_seq)          REFERENCES users (user_seq)
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
    link_url          VARCHAR(300),
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
    input_validation_failures INT       NOT NULL DEFAULT 0,
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
    -- ★ Concierge 강화 + 별도 수금 PR-1 (D6=A 셀프 할당) — LEW 배정 트랙.
    -- assigned_manager_seq 와 분리된 트랙 (매니저=고객 응대, LEW=실무).
    assigned_lew_seq         BIGINT,
    lew_assigned_at          DATETIME(6),
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
    INDEX idx_concierge_applicant_user (applicant_user_seq),
    INDEX idx_concierge_assigned_lew (assigned_lew_seq)
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
-- lew_paynow_change_logs — LEW 본인 PayNow 변경 이력 (append-only, D-PN3/D-PN8)
-- 정산 민감정보의 old→new 변경을 시계열 보존. user_consent_logs 와 동일 불변 패턴.
-- ============================================
CREATE TABLE IF NOT EXISTS lew_paynow_change_logs (
    paynow_change_log_seq    BIGINT        NOT NULL AUTO_INCREMENT,
    user_seq                 BIGINT        NOT NULL,
    old_type                 VARCHAR(20),
    old_value                VARCHAR(20),
    new_type                 VARCHAR(20)   NOT NULL,
    new_value                VARCHAR(20)   NOT NULL,
    changed_by               BIGINT        NOT NULL,
    source_context           VARCHAR(40)   NOT NULL,
    ip_address               VARCHAR(45),
    user_agent               VARCHAR(500),
    created_at               DATETIME(6)   NOT NULL,
    PRIMARY KEY (paynow_change_log_seq),
    CONSTRAINT fk_paynow_log_user FOREIGN KEY (user_seq) REFERENCES users (user_seq),
    INDEX idx_paynow_log_user (user_seq, created_at)
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

-- ============================================
-- Document Number Generator — 공통 문서번호 채번 엔진
-- 스펙: doc/Project Analysis/document-number-generator-spec.md
-- 형식: LK-{DOC_PREFIX}-YYYYMMDD-NNNN  (예: LK-RCP-20260423-0001)
-- ============================================

-- 문서 타입 마스터 (설정 우선 원칙 준수 — hardcoding 금지)
CREATE TABLE IF NOT EXISTS document_number_types (
    code            VARCHAR(40)   NOT NULL,          -- 논리 식별자 (예: RECEIPT)
    prefix          VARCHAR(10)   NOT NULL,          -- 번호의 2차 접두어 (예: RCP)
    label_ko        VARCHAR(120)  NOT NULL,
    label_en        VARCHAR(120)  NOT NULL,
    description     VARCHAR(500),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    display_order   INT           NOT NULL DEFAULT 0,
    created_at      DATETIME(6),
    updated_at      DATETIME(6),
    created_by      BIGINT,
    updated_by      BIGINT,
    deleted_at      DATETIME(6),
    PRIMARY KEY (code),
    UNIQUE KEY uk_document_number_types_prefix (prefix),
    CONSTRAINT ck_docnumtypes_prefix_fmt CHECK (prefix REGEXP '^[A-Z]{2,5}$'),
    CONSTRAINT ck_docnumtypes_code_fmt   CHECK (code REGEXP '^[A-Z_]{3,40}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 일별 시퀀스 카운터 (type × issue_date 복합 PK, SELECT ... FOR UPDATE 로 원자적 증가)
CREATE TABLE IF NOT EXISTS document_number_sequence (
    doc_type_code    VARCHAR(40)   NOT NULL,
    issue_date       DATE          NOT NULL,
    next_value       INT           NOT NULL DEFAULT 1,  -- 다음 발번될 시퀀스 (1부터 시작)
    last_issued_at   DATETIME(6),
    last_issued_by   BIGINT,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    PRIMARY KEY (doc_type_code, issue_date),
    CONSTRAINT fk_docnumseq_type FOREIGN KEY (doc_type_code)
        REFERENCES document_number_types (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 결제 후 kVA 사후 변경 + 수기 정산 ledger (PR-1)
-- 스펙: doc/Project Analysis/kva-postpayment-adjustment-spec.md §5.1, §13.2
-- 정책: 감사 무결성 — soft delete 미적용 (deleted_at 컬럼은 BaseEntity 호환을 위해 보존만, 사용 금지)
-- ============================================
CREATE TABLE IF NOT EXISTS kva_adjustment_record (
    adjustment_seq            BIGINT         NOT NULL AUTO_INCREMENT,
    application_seq           BIGINT         NOT NULL,
    -- AdjustmentType: KVA_CHANGE | SLD_ADDED (견적 조정 원장 일반화). 기본 KVA_CHANGE.
    adjustment_type           VARCHAR(20)    NOT NULL DEFAULT 'KVA_CHANGE',
    -- LEW 요청 row(PR-3) 연결. ADMIN 단독 변경(PR-1)은 항상 NULL.
    lew_request_seq           BIGINT         NULL,
    previous_kva              INT            NOT NULL,
    new_kva                   INT            NULL,
    proposed_kva              INT            NULL,
    reason                    VARCHAR(1000)  NOT NULL,
    -- KvaAdjustmentStatus: PENDING_ADMIN_REVIEW | APPLIED | RESOLVED_BY_ADMIN_OVERRIDE | REJECTED | CANCELLED
    status                    VARCHAR(30)    NOT NULL,
    -- ChangedByRole: LEW | ADMIN
    changed_by_role           VARCHAR(20)    NOT NULL,
    changed_by_user_seq       BIGINT         NULL,
    previous_quote_amount     DECIMAL(10,2)  NULL,
    new_quote_amount          DECIMAL(10,2)  NULL,
    amount_difference         DECIMAL(10,2)  NULL,
    master_price_seq_used     BIGINT         NULL,
    admin_memo                VARCHAR(2000)  NULL,
    -- AdminPaymentAdjustment: PENDING | PAID_DIFFERENCE | REFUNDED | WAIVED
    admin_payment_adjustment  VARCHAR(20)    NULL,
    settled_amount            DECIMAL(10,2)  NULL,
    receipt_reference_number  VARCHAR(100)   NULL,
    settlement_memo           VARCHAR(1000)  NULL,
    admin_adjustment_at       DATETIME(6)    NULL,
    -- PR-4: settlement 마킹 시각. PAID_DIFFERENCE/REFUNDED/WAIVED 로 finalize 될 때 한번만 기록.
    settled_at                DATETIME(6)    NULL,
    -- (제거됨) cof_reissue_triggered — CoF 기능 제거 (2026-06). 기존 DB는 하단 가이드의 DROP COLUMN 적용.
    -- BaseEntity audit (deleted_at 은 보존만, soft delete 미적용)
    created_at                DATETIME(6),
    updated_at                DATETIME(6),
    created_by                BIGINT,
    updated_by                BIGINT,
    deleted_at                DATETIME(6),
    PRIMARY KEY (adjustment_seq),
    KEY idx_kva_adj_application (application_seq),
    KEY idx_kva_adj_status (status),
    KEY idx_kva_adj_created_at (created_at),
    CONSTRAINT fk_kva_adj_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq),
    -- self-FK (LEW 요청 row → ADMIN row 연결, PR-3)
    CONSTRAINT fk_kva_adj_lew_request FOREIGN KEY (lew_request_seq) REFERENCES kva_adjustment_record (adjustment_seq),
    CONSTRAINT fk_kva_adj_master_price FOREIGN KEY (master_price_seq_used) REFERENCES master_prices (master_price_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- ADMIN Manual Email Dispatch (admin-manual-email-spec.md §4 + §13.1) — PR-1 + PR-2
-- 정책: 감사 무결성 — soft delete 미적용 (deleted_at 컬럼은 BaseEntity 호환을 위해 보존만, 사용 금지)
-- PR-1 단일 수신자 전용 — recipient_user_seq 또는 recipient_email 중 하나가 채워진다.
-- PR-2 MULTI 활성화 — recipient_user_seqs_json / recipient_emails_json / recipient_hash 컬럼 추가.
-- ============================================
CREATE TABLE IF NOT EXISTS manual_email_dispatches (
    dispatch_seq             BIGINT         NOT NULL AUTO_INCREMENT,
    sender_user_seq          BIGINT         NOT NULL,
    -- RecipientType: APPLICANT | LEW | EXTERNAL | MULTI
    recipient_type           VARCHAR(20)    NOT NULL,
    -- 시스템 사용자 단일 수신 시 user_seq. EXTERNAL/MULTI 일 때는 NULL.
    recipient_user_seq       BIGINT         NULL,
    -- 발송 시점의 이메일 스냅샷 (사용자 이메일 변경/삭제와 무관하게 이력 정본 보존).
    -- MULTI 시: 첫 번째(대표) 이메일을 저장 — 단일 수신자 코드 호환성. 전체 목록은 _json 컬럼에.
    recipient_email          VARCHAR(254)   NOT NULL,
    -- PR-2 MULTI: 시스템 사용자 user_seq 목록 (JSON 배열). 단일 발송 시 NULL.
    recipient_user_seqs_json TEXT           NULL,
    -- PR-2 MULTI: 전체 발송 대상 이메일 목록 (JSON 배열, 정렬+중복제거). 단일 발송 시 NULL.
    recipient_emails_json    TEXT           NULL,
    -- PR-2 멱등성: 정렬된 수신자 + subject + body 의 SHA-256 hex (64자).
    -- 단일/다수 통합 멱등성 비교 키.
    recipient_hash           VARCHAR(64)    NULL,
    related_application_seq  BIGINT         NULL,
    subject                  VARCHAR(200)   NOT NULL,
    body_text                TEXT           NOT NULL,
    -- BodyFormat: PLAIN_TEXT | HTML (PR-1 은 PLAIN_TEXT 만 허용)
    body_format              VARCHAR(20)    NOT NULL DEFAULT 'PLAIN_TEXT',
    category_tag             VARCHAR(50)    NULL,
    -- DispatchStatus: PENDING | SENT | PARTIAL_FAILED | FAILED
    dispatch_status          VARCHAR(20)    NOT NULL,
    sent_count               INT            NOT NULL DEFAULT 0,
    failed_count             INT            NOT NULL DEFAULT 0,
    failed_reason            TEXT           NULL,
    -- 실제 SMTP 시도 시각 (AFTER_COMMIT 단계). PENDING 상태에서는 NULL.
    dispatched_at            DATETIME(6)    NULL,
    -- PR-4 (D4=B): 시스템 사용자 수신자 인앱 알림 동반 생성 여부. 기본 ON.
    -- EXTERNAL 만 있는 발송에는 무관 (시스템 계정이 없으므로 listener 가 자동 스킵).
    also_create_in_app_notification TINYINT(1) NOT NULL DEFAULT 1,
    -- BaseEntity audit (deleted_at 은 보존만, soft delete 미적용)
    created_at               DATETIME(6),
    updated_at               DATETIME(6),
    created_by               BIGINT,
    updated_by               BIGINT,
    deleted_at               DATETIME(6),
    PRIMARY KEY (dispatch_seq),
    KEY idx_manual_email_sender (sender_user_seq, dispatched_at DESC),
    KEY idx_manual_email_dispatched (dispatched_at DESC),
    KEY idx_manual_email_status (dispatch_status, dispatched_at DESC),
    KEY idx_manual_email_application (related_application_seq),
    KEY idx_manual_email_recipient_hash (sender_user_seq, recipient_hash, created_at DESC),
    CONSTRAINT fk_manual_email_sender FOREIGN KEY (sender_user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_manual_email_recipient_user FOREIGN KEY (recipient_user_seq) REFERENCES users (user_seq),
    CONSTRAINT fk_manual_email_application FOREIGN KEY (related_application_seq) REFERENCES applications (application_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- ★ LoA(Letter of Appointment) 폼 템플릿 버전 관리 (LoA 교환 동선 재설계 PR2)
-- 스펙: doc/Project Analysis/loa-exchange-redesign-spec.md §2.1
-- - active 단일성은 서비스 레벨 보장 (MySQL 8.0 부분 유니크 인덱스 미지원).
-- - soft delete 표준 (deleted_at + @SQLRestriction).
-- - DatabaseMigrationRunner.syncCreateTablesFromSchemaSql 가 부팅 시 자동 반영.
-- ============================================
CREATE TABLE IF NOT EXISTS loa_form_templates (
    loa_form_template_seq   BIGINT       NOT NULL AUTO_INCREMENT,
    -- 운영용 표시 라벨 (예: "EMA NEW LoA v2026.06")
    label                   VARCHAR(150) NOT NULL,
    -- files.file_seq FK (저장된 폼 PDF)
    file_seq                BIGINT       NOT NULL,
    -- 현재 active 폼 여부. 동시 active 1건은 서비스 레벨 보장.
    is_active               TINYINT(1)   NOT NULL DEFAULT 0,
    -- 업로더 user_seq (users.user_seq FK)
    uploaded_by             BIGINT       NOT NULL,
    uploaded_at             DATETIME(6)  NOT NULL,
    -- BaseEntity audit + soft delete
    created_at              DATETIME(6),
    updated_at              DATETIME(6),
    created_by              BIGINT,
    updated_by              BIGINT,
    deleted_at              DATETIME(6),
    PRIMARY KEY (loa_form_template_seq),
    KEY idx_loa_form_active (is_active),
    KEY idx_loa_form_uploaded_at (uploaded_at DESC),
    CONSTRAINT fk_loa_form_file FOREIGN KEY (file_seq) REFERENCES files (file_seq),
    CONSTRAINT fk_loa_form_uploader FOREIGN KEY (uploaded_by) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-1 (D1=B 다중 역할 정규화 1:N)
-- ============================================
-- user_roles — User 와 1:N. primary role 은 users.role 컬럼에 그대로 두고,
-- 본 테이블은 추가 역할(보조 역할)을 포함한 모든 effective role 을 보관한다.
-- 마이그레이션이 기존 users.role row 를 INSERT IGNORE 로 백필한다 (멱등).
CREATE TABLE IF NOT EXISTS user_roles (
    user_seq BIGINT       NOT NULL,
    role     VARCHAR(40)  NOT NULL,
    PRIMARY KEY (user_seq, role),
    KEY idx_user_roles_user (user_seq),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_seq) REFERENCES users (user_seq) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- ★ 알림 인프라 일반화 (PR-0A) — WhatsApp 도입 사전 기반
-- ----------------------------------------------------------------
-- 이메일/인앱 단일 채널 발송을 ① 채널 어댑터 ② Outbox 패턴 ③ 사용자 환경설정 ④ 템플릿 카탈로그
-- 로 일반화한다. 본 PR 은 스키마/엔티티만 추가하며 행위 변경은 없다 (PR-0B/0C 에서 적용).
-- 참고: doc/Project Analysis/whatsapp-notification-plan.md (예정)
-- ============================================

-- 18. 알림 환경설정 — 사용자 × 이벤트 × 채널 enable/disable.
-- 행이 없으면 system_settings 의 채널 기본값을 따른다 (Single Source of Truth, CLAUDE.md §설계 원칙).
CREATE TABLE IF NOT EXISTS notification_preferences (
    preference_seq   BIGINT       NOT NULL AUTO_INCREMENT,
    user_seq         BIGINT       NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,   -- NotificationType enum 값
    channel          VARCHAR(20)  NOT NULL,   -- IN_APP | EMAIL | WHATSAPP
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    created_by       BIGINT,
    updated_by       BIGINT,
    deleted_at       DATETIME(6),
    PRIMARY KEY (preference_seq),
    UNIQUE KEY uk_notif_pref (user_seq, event_type, channel),
    KEY idx_notif_pref_user (user_seq),
    CONSTRAINT fk_notif_pref_user FOREIGN KEY (user_seq) REFERENCES users (user_seq) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 19. 알림 템플릿 카탈로그 — (event_type, channel, locale) 단위. 코드 하드코딩 금지.
-- WhatsApp 은 BSP/Meta 측 사전 승인된 template name 을 provider_template_name 컬럼이 매핑한다.
-- PR-T1: version(낙관락) + catalog_meta_key/category/severity/recipient_roles (admin 콘솔 메타) 추가.
CREATE TABLE IF NOT EXISTS notification_templates (
    template_seq            BIGINT       NOT NULL AUTO_INCREMENT,
    template_code           VARCHAR(80)  NOT NULL,   -- 예: PAYMENT_REQUEST_APPLICANT
    channel                 VARCHAR(20)  NOT NULL,   -- IN_APP | EMAIL | WHATSAPP
    locale                  VARCHAR(10)  NOT NULL,   -- en | ko | zh-Hans
    provider_template_name  VARCHAR(120),            -- Meta/BSP 등록명 (WhatsApp 필수)
    subject                 VARCHAR(200),            -- EMAIL 전용
    body_text               TEXT         NOT NULL,   -- 미리보기 또는 fallback 본문
    variables_json          TEXT,                    -- {{1}} {{2}} 변수 메타 (검증용 JSON 배열)
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    version                 BIGINT       NOT NULL DEFAULT 0,    -- @Version 낙관락 (ETag/If-Match)
    catalog_meta_key        VARCHAR(60),                         -- 예: 'A-17' (카피북 §0 식별자)
    category                VARCHAR(30),                         -- NotificationCategory enum
    severity                VARCHAR(20),                         -- NotificationSeverity enum
    recipient_roles         VARCHAR(200),                        -- 'APPLICANT,LEW' D-5 read 필터
    created_at              DATETIME(6),
    updated_at              DATETIME(6),
    created_by              BIGINT,
    updated_by              BIGINT,
    deleted_at              DATETIME(6),
    PRIMARY KEY (template_seq),
    UNIQUE KEY uk_notif_template (template_code, channel, locale),
    KEY idx_notif_template_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 20. 알림 Outbox — manual_email_dispatches 패턴을 모든 채널로 일반화.
-- 도메인 트랜잭션 안에서 PENDING row 적재 → AFTER_COMMIT 단계에서 채널 어댑터가 외부 호출.
-- 중복 발송 차단은 idempotency_key UNIQUE 가 1차 가드, BSP 측 dedup id 가 2차 가드.
-- Soft delete 미적용 (감사 무결성 — ManualEmailDispatch 와 동일).
-- PR-T1: source/is_test/render_warnings_json 컬럼 추가 — admin 테스트 발송 격리 + 렌더 경고 가시화.
CREATE TABLE IF NOT EXISTS notification_outbox (
    outbox_seq            BIGINT        NOT NULL AUTO_INCREMENT,
    idempotency_key       VARCHAR(160)  NOT NULL,    -- {eventType}:{refType}:{refId}:{userSeq}:{channel}
    user_seq              BIGINT        NOT NULL,
    channel               VARCHAR(20)   NOT NULL,    -- IN_APP | EMAIL | WHATSAPP
    event_type            VARCHAR(60)   NOT NULL,    -- NotificationType enum 값
    template_code         VARCHAR(80)   NOT NULL,
    locale                VARCHAR(10)   NOT NULL DEFAULT 'en',
    payload_json          TEXT          NOT NULL,    -- 렌더링 변수
    reference_type        VARCHAR(50),
    reference_id          BIGINT,
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                                                     -- PENDING | SENDING | SENT | FAILED | DEAD | SKIPPED
    attempt_count         INT           NOT NULL DEFAULT 0,
    next_attempt_at       DATETIME(6),
    last_error            TEXT,
    sent_at               DATETIME(6),
    source                VARCHAR(20)   NOT NULL DEFAULT 'PRODUCTION',
                                                     -- PRODUCTION | ADMIN_TEST (인박스 unread_count 격리)
    is_test               BOOLEAN       NOT NULL DEFAULT FALSE,  -- source=ADMIN_TEST 와 1:1, 필터 편의
    render_warnings_json  TEXT,                      -- {"missingKeys":["foo"]} 비치명적 렌더 이슈
    -- BaseEntity audit (deleted_at 보존만, soft delete 미적용)
    created_at            DATETIME(6),
    updated_at            DATETIME(6),
    created_by            BIGINT,
    updated_by            BIGINT,
    deleted_at            DATETIME(6),
    PRIMARY KEY (outbox_seq),
    UNIQUE KEY uk_outbox_idem (idempotency_key),
    KEY idx_outbox_due (status, next_attempt_at),
    KEY idx_outbox_ref (reference_type, reference_id),
    KEY idx_outbox_user (user_seq, created_at DESC),
    CONSTRAINT fk_outbox_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 21. WhatsApp 발송 로그 — 채널 특화 메타데이터 (BSP message id, 배달 상태 등).
-- 본문 저장 금지 (PDPA 최소화). payload_json 은 변수 슬롯만.
-- Soft delete 미적용 (감사 무결성).
CREATE TABLE IF NOT EXISTS whatsapp_message_log (
    log_seq             BIGINT       NOT NULL AUTO_INCREMENT,
    outbox_seq          BIGINT       NOT NULL,
    user_seq            BIGINT       NOT NULL,
    phone_e164          VARCHAR(20)  NOT NULL,
    template_code       VARCHAR(80)  NOT NULL,
    template_locale     VARCHAR(10)  NOT NULL,
    payload_json        TEXT         NOT NULL,
    provider            VARCHAR(20)  NOT NULL,    -- META | MOCK (BSP 추가 시 확장)
    provider_message_id VARCHAR(120),
    status              VARCHAR(20)  NOT NULL,    -- QUEUED | SENT | DELIVERED | READ | FAILED
    error_code          VARCHAR(60),
    error_message       TEXT,
    sent_at             DATETIME(6),
    delivered_at        DATETIME(6),
    read_at             DATETIME(6),
    created_at          DATETIME(6),
    updated_at          DATETIME(6),
    created_by          BIGINT,
    updated_by          BIGINT,
    deleted_at          DATETIME(6),
    PRIMARY KEY (log_seq),
    KEY idx_wa_provider_msg (provider_message_id),
    KEY idx_wa_user_status (user_seq, status, created_at DESC),
    KEY idx_wa_outbox (outbox_seq),
    CONSTRAINT fk_wa_outbox FOREIGN KEY (outbox_seq) REFERENCES notification_outbox (outbox_seq),
    CONSTRAINT fk_wa_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- ★ 알림 템플릿 관리 (PR-T1) — Admin 콘솔에서 카피 편집·publish 2-step·history·롤백
-- ----------------------------------------------------------------
-- 스펙: doc/Project Analysis/notification-template-manager-spec.md §5
-- 기존 notification_templates / notification_outbox 컬럼 추가는 본 파일 하단 ALTER 가이드 참조.
-- ============================================

-- 22. 알림 카탈로그 메타 — template_code 단위로 허용 변수·기본 카테고리·강제 토큰 정의.
-- TemplateLinter(L1) 가 본 테이블의 allowed_variables_json 을 SSOT 로 사용한다.
CREATE TABLE IF NOT EXISTS notification_catalog (
    catalog_seq               BIGINT       NOT NULL AUTO_INCREMENT,
    template_code             VARCHAR(80)  NOT NULL,   -- 예: 'A-17' 또는 NotificationType enum 값
    allowed_variables_json    TEXT         NOT NULL,   -- ["applicantName","amount","publicCode"]
    default_category          VARCHAR(30)  NOT NULL,   -- NotificationCategory enum
    default_severity          VARCHAR(20)  NOT NULL,   -- NotificationSeverity enum
    default_recipient_roles   VARCHAR(200) NOT NULL,   -- 'APPLICANT,LEW' comma-separated
    description               VARCHAR(500),
    required_tokens_json      TEXT,                    -- ["{{paynowUen}}","{{optOutUrl}}"] 카테고리별 강제
    trigger_ref               VARCHAR(255),            -- 발송 트리거(기능/호출부) — 예: 'AdminPaymentService.confirmPayment'
    created_at                DATETIME(6),
    updated_at                DATETIME(6),
    created_by                BIGINT,
    updated_by                BIGINT,
    deleted_at                DATETIME(6),
    PRIMARY KEY (catalog_seq),
    UNIQUE KEY uk_notif_catalog_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 기존 DB(운영/dev RDS) 적용 가이드 — trigger_ref 컬럼 ★ 1회만 ★ 수동 실행:
--   ALTER TABLE notification_catalog ADD COLUMN trigger_ref VARCHAR(255);

-- 23. 알림 템플릿 Draft — 2-step publish 워크플로 staging row.
-- NM 이 편집·submit → SYSTEM_ADMIN approve → 본 테이블(notification_templates) 반영.
-- template_seq=NULL 이면 신규 템플릿 draft, non-null 이면 기존 row 수정 draft.
CREATE TABLE IF NOT EXISTS notification_template_drafts (
    draft_seq               BIGINT       NOT NULL AUTO_INCREMENT,
    template_seq            BIGINT,                   -- FK to notification_templates (nullable)
    template_code           VARCHAR(80)  NOT NULL,
    channel                 VARCHAR(20)  NOT NULL,
    locale                  VARCHAR(10)  NOT NULL,
    subject                 VARCHAR(200),
    body_text               TEXT         NOT NULL,
    variables_json          TEXT,
    provider_template_name  VARCHAR(120),
    category                VARCHAR(30),
    severity                VARCHAR(20),
    recipient_roles         VARCHAR(200),
    submitted_by            BIGINT       NOT NULL,    -- NM user_seq
    submitted_at            DATETIME(6)  NOT NULL,
    submission_note         VARCHAR(500),
    status                  VARCHAR(20)  NOT NULL,    -- PENDING | APPROVED | REJECTED | WITHDRAWN
    reviewed_by             BIGINT,                   -- SYSTEM_ADMIN user_seq
    reviewed_at             DATETIME(6),
    review_note             VARCHAR(500),
    created_at              DATETIME(6),
    updated_at              DATETIME(6),
    created_by              BIGINT,
    updated_by              BIGINT,
    deleted_at              DATETIME(6),
    PRIMARY KEY (draft_seq),
    KEY idx_draft_status (status, submitted_at),
    KEY idx_draft_template (template_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 24. 알림 템플릿 변경 이력 — append-only 감사 로그 + 롤백 진입점.
-- BaseEntity 미상속 (감사 무결성). soft delete 미적용.
CREATE TABLE IF NOT EXISTS notification_template_history (
    history_seq             BIGINT       NOT NULL AUTO_INCREMENT,
    template_seq            BIGINT       NOT NULL,
    change_type             VARCHAR(20)  NOT NULL,    -- CREATE | PUBLISH | ENABLE | DISABLE | ROLLBACK
    diff_json               TEXT         NOT NULL,    -- {before:{...}, after:{...}} 변경 필드만
    before_snapshot_json    TEXT         NOT NULL,    -- 전체 row 스냅샷 (롤백용)
    after_snapshot_json     TEXT         NOT NULL,
    change_reason           VARCHAR(500),             -- SECURITY/PAYMENT/MARKETING 은 서비스에서 필수 검증
    actor_user_seq          BIGINT       NOT NULL,
    actor_ip                VARCHAR(45),
    changed_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (history_seq),
    KEY idx_history_template (template_seq, changed_at DESC),
    KEY idx_history_actor (actor_user_seq, changed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================
-- 운영 DB 적용 가이드 (PR-0A) — schema.sql 의 sql.init.mode=always 는 매 부트마다 재실행되므로,
-- ALTER 구문은 본 파일에 포함시키지 않는다 (MySQL 8.0 은 ADD COLUMN IF NOT EXISTS 미지원).
-- 이미 users 테이블이 존재하는 환경에서는 아래 ALTER 를 ★ 1회만 ★ 수동 실행한다.
-- 신규 DB 는 위 CREATE TABLE 정의로 컬럼이 함께 생성되므로 ALTER 불필요.
--
-- ALTER TABLE users
--   ADD COLUMN phone_e164          VARCHAR(20),
--   ADD COLUMN phone_verified      BOOLEAN     NOT NULL DEFAULT FALSE,
--   ADD COLUMN phone_verified_at   DATETIME(6),
--   ADD COLUMN whatsapp_opt_in     BOOLEAN     NOT NULL DEFAULT FALSE,
--   ADD COLUMN whatsapp_opt_in_at  DATETIME(6),
--   ADD COLUMN whatsapp_opt_out_at DATETIME(6),
--   ADD COLUMN preferred_language  VARCHAR(10) NOT NULL DEFAULT 'en';
--
-- ============================================
-- 운영 DB 적용 가이드 (PR-T1) — notification_templates / notification_outbox 컬럼 추가
-- 신규 DB(local·CI)는 위 CREATE TABLE 정의에 컬럼이 포함되어 자동 생성됨.
-- 이미 운영 DB 에 두 테이블이 존재하는 환경에서는 아래 ALTER 를 ★ 1회만 ★ 수동 실행한다.
--
-- ALTER TABLE notification_templates
--   ADD COLUMN version           BIGINT       NOT NULL DEFAULT 0,
--   ADD COLUMN catalog_meta_key  VARCHAR(60),
--   ADD COLUMN category          VARCHAR(30),
--   ADD COLUMN severity          VARCHAR(20),
--   ADD COLUMN recipient_roles   VARCHAR(200);
--
-- ALTER TABLE notification_outbox
--   ADD COLUMN source                VARCHAR(20)  NOT NULL DEFAULT 'PRODUCTION',
--   ADD COLUMN is_test               BOOLEAN      NOT NULL DEFAULT FALSE,
--   ADD COLUMN render_warnings_json  TEXT;
-- ============================================
-- 운영 DB 적용 가이드 (CoF 제거, 2026-06) — Certificate of Fitness 기능 전체 제거.
-- 신규 DB(local·CI)는 위 CREATE/컬럼 정의에서 이미 빠져 있으므로 조치 불필요.
-- 이미 certificate_of_fitness 테이블 / kva_adjustment_record.cof_reissue_triggered 컬럼이
-- 존재하는 운영·개발 RDS 에서는 아래를 ★ 1회만 ★ 수동 실행한다.
--
-- ALTER TABLE kva_adjustment_record DROP COLUMN cof_reissue_triggered;
-- DROP TABLE IF EXISTS certificate_of_fitness;
-- ============================================

-- ============================================
-- web_event — 1st-party 유입/문의 텔레메트리 (2026-07)
-- 공개 페이지 방문(PAGE_VIEW) 및 WhatsApp 문의 클릭(WHATSAPP_CLICK)을 우리 서버에만 기록.
-- 제3자 분석/광고 트래커·쿠키 미사용. 개인정보 최소수집(IP·전체 URL 미저장,
-- referrer 는 host 만, session_id 는 클라이언트 sessionStorage 랜덤값 — 쿠키 아님).
-- 마케팅 채널 효과(UTM 출처별 문의) 집계 목적. 보관 최소화 권장.
-- ============================================
CREATE TABLE IF NOT EXISTS web_event (
    event_seq     BIGINT       NOT NULL AUTO_INCREMENT,
    event_type    VARCHAR(32)  NOT NULL,            -- PAGE_VIEW | WHATSAPP_CLICK
    path          VARCHAR(255),                     -- 예: '/', '/services', '/about'
    utm_source    VARCHAR(64),
    utm_medium    VARCHAR(64),
    utm_campaign  VARCHAR(128),
    utm_content   VARCHAR(128),
    referrer_host VARCHAR(255),                      -- host 만 (전체 URL 미저장)
    service       VARCHAR(64),                       -- WHATSAPP_CLICK: 어떤 서비스 문의인지
    session_id    VARCHAR(40),                       -- 클라이언트 랜덤 세션ID (쿠키 아님)
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_seq),
    INDEX idx_web_event_type_time (event_type, created_at),
    INDEX idx_web_event_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
