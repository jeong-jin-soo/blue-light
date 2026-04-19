package com.bluelight.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;

/**
 * DB 스키마 마이그레이션 러너
 * - Spring Boot 시작 시 @PostConstruct로 Hibernate 초기화 이전에 실행
 * - 멱등성 보장: 이미 적용된 마이그레이션은 자동 스킵
 * - 새 마이그레이션 추가 시 migrateAll()에 메서드 호출 추가
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DatabaseMigrationRunner {

    private final DataSource dataSource;

    @PostConstruct
    public void runMigrations() {
        migrateAll();
    }

    private void migrateAll() {
        try (Connection conn = dataSource.getConnection()) {
            migrateUserNameSplit(conn);
            migrateApplicationsLoaColumns(conn);
            migrateSldTemplatesTable(conn);
            migrateSampleFilesTable(conn);
            migrateSampleFilesMultiFile(conn);
            migrateMasterPricesRenewalPrice(conn);
            migrateNotificationsTable(conn);
            // ★ Kaki Concierge Phase 1 PR#1
            migrateUsersAccountStatusColumns(conn);
            migrateAccountSetupTokensTable(conn);
            // ★ Kaki Concierge Phase 1 PR#1 Stage 2
            migrateConciergeRequestsTable(conn);
            migrateConciergeNotesTable(conn);
            migrateUserConsentLogsTable(conn);
            // ★ Kaki Concierge Phase 1 PR#1 Stage 3
            migrateApplicationsLoaSignatureSource(conn);
            seedSystemSettings(conn);
            // ★ Kaki Concierge Phase 1 PR#4 Stage A
            seedConciergeManager(conn);
            log.info("Database migration check completed");
        } catch (SQLException e) {
            log.error("Database migration failed", e);
            throw new RuntimeException("Database migration failed", e);
        }
    }

    /**
     * 마이그레이션: users.name → users.first_name + users.last_name
     * - name 컬럼이 존재하면 마이그레이션 실행
     * - first_name/last_name이 이미 있으면 스킵
     */
    private void migrateUserNameSplit(Connection conn) throws SQLException {
        if (!columnExists(conn, "users", "name")) {
            log.debug("Migration [user-name-split]: already applied, skipping");
            return;
        }

        log.info("Migration [user-name-split]: starting...");

        try (Statement stmt = conn.createStatement()) {
            // 1. first_name, last_name 컬럼 추가
            if (!columnExists(conn, "users", "first_name")) {
                stmt.executeUpdate(
                    "ALTER TABLE users ADD COLUMN first_name VARCHAR(50) NOT NULL DEFAULT '' AFTER password"
                );
                stmt.executeUpdate(
                    "ALTER TABLE users ADD COLUMN last_name VARCHAR(50) NOT NULL DEFAULT '' AFTER first_name"
                );
                log.info("Migration [user-name-split]: added first_name, last_name columns");
            }

            // 2. 데이터 마이그레이션: name → first_name + last_name
            int updated = stmt.executeUpdate(
                "UPDATE users SET " +
                "first_name = SUBSTRING_INDEX(name, ' ', 1), " +
                "last_name = TRIM(SUBSTR(name, LOCATE(' ', name) + 1)) " +
                "WHERE name IS NOT NULL AND first_name = ''"
            );
            log.info("Migration [user-name-split]: migrated {} user records", updated);

            // 이름에 공백이 없는 경우 first_name == last_name이 되므로 last_name 비우기
            stmt.executeUpdate(
                "UPDATE users SET last_name = '' WHERE first_name = last_name AND last_name != ''"
            );

            // 3. 기존 name 컬럼 삭제
            stmt.executeUpdate("ALTER TABLE users DROP COLUMN name");
            log.info("Migration [user-name-split]: dropped name column. Migration complete!");
        }
    }

    /**
     * 마이그레이션: applications 테이블에 LOA 서명 컬럼 추가
     * - loa_signature_url, loa_signed_at 컬럼이 없으면 추가
     */
    private void migrateApplicationsLoaColumns(Connection conn) throws SQLException {
        if (columnExists(conn, "applications", "loa_signature_url")) {
            log.debug("Migration [applications-loa-columns]: already applied, skipping");
            return;
        }

        log.info("Migration [applications-loa-columns]: starting...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN loa_signature_url VARCHAR(255) AFTER sld_option"
            );
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN loa_signed_at DATETIME(6) AFTER loa_signature_url"
            );
            log.info("Migration [applications-loa-columns]: added loa_signature_url, loa_signed_at columns");
        }
    }

    /**
     * 마이그레이션: sld_templates 테이블 생성
     * - SQL_INIT_MODE=never 환경(dev/prod)에서 schema.sql이 실행되지 않으므로
     *   여기서 직접 CREATE TABLE IF NOT EXISTS 실행
     */
    private void migrateSldTemplatesTable(Connection conn) throws SQLException {
        if (tableExists(conn, "sld_templates")) {
            log.debug("Migration [sld-templates-table]: already exists, skipping");
            return;
        }

        log.info("Migration [sld-templates-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE sld_templates (" +
                "  sld_template_seq  BIGINT        NOT NULL AUTO_INCREMENT," +
                "  phase             VARCHAR(20)   NOT NULL COMMENT 'single_phase | three_phase'," +
                "  kva               DECIMAL(10,2)          COMMENT 'kVA capacity (nullable: Cable Extension etc)'," +
                "  main_breaker_type VARCHAR(20)            COMMENT 'MCB | MCCB | ELCB'," +
                "  circuit_count     INT           NOT NULL DEFAULT 0 COMMENT 'Sub circuit count'," +
                "  filename          VARCHAR(255)  NOT NULL COMMENT 'PDF filename'," +
                "  file_path         VARCHAR(500)  NOT NULL COMMENT 'Template PDF relative path'," +
                "  detail_json       JSON          NOT NULL COMMENT 'Full drawing detail info (JSON)'," +
                "  created_at        DATETIME(6)            DEFAULT CURRENT_TIMESTAMP(6)," +
                "  updated_at        DATETIME(6)            DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)," +
                "  PRIMARY KEY (sld_template_seq)," +
                "  UNIQUE KEY uk_sld_templates_filename (filename)," +
                "  KEY idx_sld_templates_phase (phase)," +
                "  KEY idx_sld_templates_kva (kva)," +
                "  KEY idx_sld_templates_breaker (main_breaker_type)," +
                "  KEY idx_sld_templates_phase_kva (phase, kva)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [sld-templates-table]: table created");
        }
    }

    /**
     * 마이그레이션: sample_files 테이블 생성
     * - 카테고리별 샘플 파일 관리용 테이블
     */
    private void migrateSampleFilesTable(Connection conn) throws SQLException {
        if (tableExists(conn, "sample_files")) {
            log.debug("Migration [sample-files-table]: already exists, skipping");
            return;
        }

        log.info("Migration [sample-files-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE sample_files (" +
                "  sample_file_seq   BIGINT       NOT NULL AUTO_INCREMENT," +
                "  category_key      VARCHAR(30)  NOT NULL," +
                "  file_url          VARCHAR(500) NOT NULL," +
                "  original_filename VARCHAR(255)," +
                "  file_size         BIGINT," +
                "  uploaded_at       DATETIME(6)," +
                "  updated_at        DATETIME(6)," +
                "  created_by        BIGINT," +
                "  updated_by        BIGINT," +
                "  PRIMARY KEY (sample_file_seq)," +
                "  UNIQUE KEY uk_sample_files_category (category_key)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [sample-files-table]: table created");
        }
    }

    /**
     * 마이그레이션: sample_files 다중 파일 지원
     * - unique 제약 제거 (카테고리당 여러 파일 허용)
     * - sort_order 컬럼 추가
     */
    private void migrateSampleFilesMultiFile(Connection conn) throws SQLException {
        if (!tableExists(conn, "sample_files")) return;

        // sort_order 컬럼이 이미 있으면 마이그레이션 완료 상태
        if (columnExists(conn, "sample_files", "sort_order")) {
            log.debug("Migration [sample-files-multi]: already applied, skipping");
            return;
        }

        log.info("Migration [sample-files-multi]: enabling multi-file support...");
        try (Statement stmt = conn.createStatement()) {
            // 1. unique 제약 제거
            try {
                stmt.executeUpdate("ALTER TABLE sample_files DROP INDEX uk_sample_files_category");
                log.info("Migration [sample-files-multi]: dropped unique constraint");
            } catch (SQLException e) {
                log.debug("Migration [sample-files-multi]: unique constraint already absent");
            }

            // 2. sort_order 컬럼 추가
            stmt.executeUpdate(
                "ALTER TABLE sample_files ADD COLUMN sort_order INT NOT NULL DEFAULT 0"
            );

            // 3. category_key + sort_order 인덱스 추가
            stmt.executeUpdate(
                "CREATE INDEX idx_sample_files_category ON sample_files (category_key, sort_order)"
            );

            log.info("Migration [sample-files-multi]: completed");
        }
    }

    /**
     * 마이그레이션: master_prices에 renewal_price 컬럼 추가
     * - New License / Renewal 가격 분리
     */
    private void migrateMasterPricesRenewalPrice(Connection conn) throws SQLException {
        if (!tableExists(conn, "master_prices")) return;

        if (columnExists(conn, "master_prices", "renewal_price")) {
            log.debug("Migration [master-prices-renewal]: already applied, skipping");
            return;
        }

        log.info("Migration [master-prices-renewal]: adding renewal_price column...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE master_prices ADD COLUMN renewal_price DECIMAL(10,2) NOT NULL DEFAULT 0.00"
            );
            // 기존 데이터: renewal_price = price (동일 가격으로 초기화)
            stmt.executeUpdate(
                "UPDATE master_prices SET renewal_price = price WHERE renewal_price = 0.00 AND deleted_at IS NULL"
            );
            log.info("Migration [master-prices-renewal]: completed");
        }
    }

    /**
     * 마이그레이션: notifications 테이블 생성
     * - 인앱 알림 저장용 테이블
     */
    private void migrateNotificationsTable(Connection conn) throws SQLException {
        if (tableExists(conn, "notifications")) {
            log.debug("Migration [notifications-table]: already exists, skipping");
            return;
        }

        log.info("Migration [notifications-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE notifications (" +
                "  notification_seq  BIGINT       NOT NULL AUTO_INCREMENT," +
                "  recipient_seq     BIGINT       NOT NULL," +
                "  type              VARCHAR(50)  NOT NULL," +
                "  title             VARCHAR(200) NOT NULL," +
                "  message           VARCHAR(1000) NOT NULL," +
                "  reference_type    VARCHAR(50)," +
                "  reference_id      BIGINT," +
                "  is_read           BOOLEAN      NOT NULL DEFAULT FALSE," +
                "  read_at           DATETIME(6)," +
                "  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)," +
                "  updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)," +
                "  created_by        BIGINT," +
                "  updated_by        BIGINT," +
                "  deleted_at        DATETIME(6)," +
                "  PRIMARY KEY (notification_seq)," +
                "  CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_seq) REFERENCES users (user_seq)," +
                "  INDEX idx_notification_recipient_read (recipient_seq, is_read, deleted_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [notifications-table]: table created");
        }
    }

    /**
     * 마이그레이션: users 테이블에 계정 상태 + 가입 경로 + 동의 스냅샷 컬럼 8종 추가
     * (★ Kaki Concierge v1.4/v1.5, Phase 1 PR#1)
     * - status 컬럼 존재 시 스킵 (멱등성)
     * - 기존 유저는 status=ACTIVE로 backfill, activated_at=created_at 보강
     */
    private void migrateUsersAccountStatusColumns(Connection conn) throws SQLException {
        if (columnExists(conn, "users", "status")) {
            log.debug("Migration [users-account-status]: already applied, skipping");
            return;
        }

        log.info("Migration [users-account-status]: starting...");
        try (Statement stmt = conn.createStatement()) {
            // 1. 컬럼 8종 추가 — signature_url 뒤에 순서대로 배치
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' AFTER signature_url"
            );
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN activated_at DATETIME(6) AFTER status"
            );
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN first_logged_in_at DATETIME(6) AFTER activated_at"
            );
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN signup_source VARCHAR(30) NOT NULL DEFAULT 'DIRECT_SIGNUP' AFTER first_logged_in_at"
            );
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN signup_consent_at DATETIME(6) AFTER signup_source"
            );
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN terms_version VARCHAR(30) AFTER signup_consent_at"
            );
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN marketing_opt_in BOOLEAN NOT NULL DEFAULT FALSE AFTER terms_version"
            );
            stmt.executeUpdate(
                "ALTER TABLE users ADD COLUMN marketing_opt_in_at DATETIME(6) AFTER marketing_opt_in"
            );
            log.info("Migration [users-account-status]: added 8 columns");

            // 2. 상태 인덱스 (대시보드 필터링용) — 이미 존재하면 무시
            try {
                stmt.executeUpdate("CREATE INDEX idx_users_status ON users (status)");
                log.info("Migration [users-account-status]: created idx_users_status");
            } catch (SQLException e) {
                log.debug("Migration [users-account-status]: idx_users_status already exists, skipping");
            }

            // 3. Backfill: 기존 유저는 status=ACTIVE (DEFAULT로 이미 설정됐으나 명시적 보강),
            //    activated_at은 created_at 사용 (기존 유저의 "활성화 시점" 근사값)
            int updated = stmt.executeUpdate(
                "UPDATE users SET activated_at = created_at " +
                "WHERE activated_at IS NULL AND status = 'ACTIVE' AND deleted_at IS NULL"
            );
            log.info("Migration [users-account-status]: backfilled activated_at for {} users", updated);
        }
    }

    /**
     * 마이그레이션: account_setup_tokens 테이블 생성
     * (★ Kaki Concierge v1.5, Phase 1 PR#1)
     */
    private void migrateAccountSetupTokensTable(Connection conn) throws SQLException {
        if (tableExists(conn, "account_setup_tokens")) {
            log.debug("Migration [account-setup-tokens-table]: already exists, skipping");
            return;
        }

        log.info("Migration [account-setup-tokens-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE account_setup_tokens (" +
                "  token_seq             BIGINT       NOT NULL AUTO_INCREMENT," +
                "  token_uuid            VARCHAR(36)  NOT NULL," +
                "  user_seq              BIGINT       NOT NULL," +
                "  source                VARCHAR(40)  NOT NULL," +
                "  expires_at            DATETIME(6)  NOT NULL," +
                "  used_at               DATETIME(6)," +
                "  revoked_at            DATETIME(6)," +
                "  failed_attempts       INT          NOT NULL DEFAULT 0," +
                "  locked_at             DATETIME(6)," +
                "  requesting_ip         VARCHAR(45)," +
                "  requesting_user_agent VARCHAR(500)," +
                "  created_at            DATETIME(6)," +
                "  updated_at            DATETIME(6)," +
                "  created_by            BIGINT," +
                "  updated_by            BIGINT," +
                "  deleted_at            DATETIME(6)," +
                "  PRIMARY KEY (token_seq)," +
                "  UNIQUE KEY uk_account_setup_tokens_uuid (token_uuid)," +
                "  CONSTRAINT fk_account_setup_tokens_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)," +
                "  INDEX idx_account_setup_tokens_user_active (user_seq, used_at, revoked_at, locked_at, expires_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [account-setup-tokens-table]: table created");
        }
    }

    /**
     * 마이그레이션: concierge_requests 테이블 생성
     * (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 2)
     * 화이트글러브 대행 서비스 신청 + 상태 머신 + 동의 4종 타임스탬프 포함
     */
    private void migrateConciergeRequestsTable(Connection conn) throws SQLException {
        if (tableExists(conn, "concierge_requests")) {
            log.debug("Migration [concierge-requests-table]: already exists, skipping");
            return;
        }

        log.info("Migration [concierge-requests-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE concierge_requests (" +
                "  concierge_request_seq    BIGINT        NOT NULL AUTO_INCREMENT," +
                "  public_code              VARCHAR(20)   NOT NULL," +
                "  submitter_name           VARCHAR(100)  NOT NULL," +
                "  submitter_email          VARCHAR(100)  NOT NULL," +
                "  submitter_phone          VARCHAR(20)   NOT NULL," +
                "  memo                     VARCHAR(2000)," +
                "  applicant_user_seq       BIGINT        NOT NULL," +
                "  assigned_manager_seq     BIGINT," +
                "  application_seq          BIGINT," +
                "  payment_seq              BIGINT," +
                "  status                   VARCHAR(40)   NOT NULL DEFAULT 'SUBMITTED'," +
                "  pdpa_consent_at          DATETIME(6)   NOT NULL," +
                "  terms_consent_at         DATETIME(6)   NOT NULL," +
                "  signup_consent_at        DATETIME(6)   NOT NULL," +
                "  delegation_consent_at    DATETIME(6)   NOT NULL," +
                "  marketing_opt_in         BOOLEAN       NOT NULL DEFAULT FALSE," +
                "  assigned_at              DATETIME(6)," +
                "  first_contact_at         DATETIME(6)," +
                "  application_created_at   DATETIME(6)," +
                "  loa_requested_at         DATETIME(6)," +
                "  loa_signed_at            DATETIME(6)," +
                "  licence_paid_at          DATETIME(6)," +
                "  completed_at             DATETIME(6)," +
                "  cancelled_at             DATETIME(6)," +
                "  cancellation_reason      VARCHAR(500)," +
                "  version                  BIGINT        NOT NULL DEFAULT 0," +
                "  created_at               DATETIME(6)," +
                "  updated_at               DATETIME(6)," +
                "  created_by               BIGINT," +
                "  updated_by               BIGINT," +
                "  deleted_at               DATETIME(6)," +
                "  PRIMARY KEY (concierge_request_seq)," +
                "  UNIQUE KEY uk_concierge_public_code (public_code)," +
                "  CONSTRAINT fk_concierge_applicant FOREIGN KEY (applicant_user_seq) REFERENCES users (user_seq)," +
                "  CONSTRAINT fk_concierge_manager FOREIGN KEY (assigned_manager_seq) REFERENCES users (user_seq)," +
                "  INDEX idx_concierge_status (status)," +
                "  INDEX idx_concierge_assigned (assigned_manager_seq, status)," +
                "  INDEX idx_concierge_submitter_email (submitter_email)," +
                "  INDEX idx_concierge_created (created_at)," +
                "  INDEX idx_concierge_applicant_user (applicant_user_seq)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [concierge-requests-table]: table created");
        }
    }

    /**
     * 마이그레이션: concierge_notes 테이블 생성
     * (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 2)
     */
    private void migrateConciergeNotesTable(Connection conn) throws SQLException {
        if (tableExists(conn, "concierge_notes")) {
            log.debug("Migration [concierge-notes-table]: already exists, skipping");
            return;
        }

        log.info("Migration [concierge-notes-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE concierge_notes (" +
                "  concierge_note_seq       BIGINT        NOT NULL AUTO_INCREMENT," +
                "  concierge_request_seq    BIGINT        NOT NULL," +
                "  author_user_seq          BIGINT        NOT NULL," +
                "  channel                  VARCHAR(20)   NOT NULL," +
                "  content                  VARCHAR(2000) NOT NULL," +
                "  created_at               DATETIME(6)," +
                "  updated_at               DATETIME(6)," +
                "  created_by               BIGINT," +
                "  updated_by               BIGINT," +
                "  deleted_at               DATETIME(6)," +
                "  PRIMARY KEY (concierge_note_seq)," +
                "  CONSTRAINT fk_concierge_note_request FOREIGN KEY (concierge_request_seq) REFERENCES concierge_requests (concierge_request_seq)," +
                "  CONSTRAINT fk_concierge_note_author FOREIGN KEY (author_user_seq) REFERENCES users (user_seq)," +
                "  INDEX idx_concierge_note_request (concierge_request_seq, created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [concierge-notes-table]: table created");
        }
    }

    /**
     * 마이그레이션: user_consent_logs 테이블 생성
     * (★ Kaki Concierge v1.3, Phase 1 PR#1 Stage 2)
     * PDPA 7년 보존 요건 — soft delete 미적용, 모든 필드 불변
     */
    private void migrateUserConsentLogsTable(Connection conn) throws SQLException {
        if (tableExists(conn, "user_consent_logs")) {
            log.debug("Migration [user-consent-logs-table]: already exists, skipping");
            return;
        }

        log.info("Migration [user-consent-logs-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE user_consent_logs (" +
                "  consent_log_seq          BIGINT        NOT NULL AUTO_INCREMENT," +
                "  user_seq                 BIGINT        NOT NULL," +
                "  consent_type             VARCHAR(40)   NOT NULL," +
                "  action                   VARCHAR(20)   NOT NULL," +
                "  document_version         VARCHAR(30)," +
                "  source_context           VARCHAR(40)   NOT NULL," +
                "  ip_address               VARCHAR(45)," +
                "  user_agent               VARCHAR(500)," +
                "  created_at               DATETIME(6)   NOT NULL," +
                "  PRIMARY KEY (consent_log_seq)," +
                "  CONSTRAINT fk_consent_log_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)," +
                "  INDEX idx_consent_log_user_type (user_seq, consent_type, created_at)," +
                "  INDEX idx_consent_log_created (created_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [user-consent-logs-table]: table created");
        }
    }

    /**
     * 마이그레이션: applications 테이블에 LOA 서명 출처 컬럼 4종 추가
     * (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 3)
     * <p>
     * PRD §3.4a / §7.2.1-LOA 3-경로 모델 (APPLICANT_DIRECT / MANAGER_UPLOAD / REMOTE_LINK).
     * - loa_signature_source 컬럼 존재 시 스킵 (멱등성)
     * - 4개 ALTER TABLE + FK 제약 1개 (이미 존재 시 try-catch)
     */
    private void migrateApplicationsLoaSignatureSource(Connection conn) throws SQLException {
        if (columnExists(conn, "applications", "loa_signature_source")) {
            log.debug("Migration [applications-loa-signature-source]: already applied, skipping");
            return;
        }

        log.info("Migration [applications-loa-signature-source]: starting...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN loa_signature_source VARCHAR(30) AFTER loa_signed_at"
            );
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN loa_signature_uploaded_by BIGINT AFTER loa_signature_source"
            );
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN loa_signature_uploaded_at DATETIME(6) AFTER loa_signature_uploaded_by"
            );
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN loa_signature_source_memo VARCHAR(500) AFTER loa_signature_uploaded_at"
            );
            log.info("Migration [applications-loa-signature-source]: added 4 columns");

            // FK 제약 — 이미 존재 시 무시
            try {
                stmt.executeUpdate(
                    "ALTER TABLE applications ADD CONSTRAINT fk_applications_loa_uploader " +
                    "FOREIGN KEY (loa_signature_uploaded_by) REFERENCES users (user_seq)"
                );
                log.info("Migration [applications-loa-signature-source]: added FK fk_applications_loa_uploader");
            } catch (SQLException e) {
                log.debug("Migration [applications-loa-signature-source]: FK fk_applications_loa_uploader already exists, skipping");
            }
        }
    }

    /**
     * 시드 데이터: SQL_INIT_MODE=never 환경에서 data.sql이 실행되지 않으므로
     * 필수 system_settings 초기값을 여기서 INSERT (이미 존재하면 스킵)
     */
    private void seedSystemSettings(Connection conn) throws SQLException {
        String[][] settings = {
            // key, value, description
            {"sld_ai_generation_enabled", "true", "Enable AI-powered SLD generation"},
            {"chat_system_prompt", "", "AI Chatbot system prompt"},
            {"sld_system_prompt", "", "AI SLD generation system prompt"},
        };

        int seeded = 0;
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT 1 FROM system_settings WHERE setting_key = ?");
             PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO system_settings (setting_key, setting_value, description, updated_at) VALUES (?, ?, ?, NOW())")) {

            for (String[] s : settings) {
                check.setString(1, s[0]);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) continue; // already exists
                }
                insert.setString(1, s[0]);
                insert.setString(2, s[1]);
                insert.setString(3, s[2]);
                insert.executeUpdate();
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("Migration [seed-system-settings]: seeded {} new settings", seeded);
        } else {
            log.debug("Migration [seed-system-settings]: all settings exist, skipping");
        }
    }

    /**
     * 시드 데이터: CONCIERGE_MANAGER 계정 (★ Kaki Concierge Phase 1 PR#4 Stage A).
     * SQL_INIT_MODE=never 환경 대응 — data.sql이 실행되지 않을 때 여기서 INSERT.
     * 이미 존재하면 스킵 (멱등성).
     * <p>
     * 이메일: conciergemanager@licensekaki.sg / Password: admin1234 (BCrypt)
     */
    private void seedConciergeManager(Connection conn) throws SQLException {
        final String email = "conciergemanager@licensekaki.sg";
        // admin1234 BCrypt 해시 (다른 seed 계정과 동일)
        final String passwordHash = "$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW";

        try (PreparedStatement check = conn.prepareStatement(
                "SELECT 1 FROM users WHERE email = ?")) {
            check.setString(1, email);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    log.debug("Migration [seed-concierge-manager]: account exists, skipping");
                    return;
                }
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (email, password, first_name, last_name, phone, role, " +
                "status, signup_source, email_verified, created_at, updated_at) " +
                "VALUES (?, ?, 'Concierge', 'Manager', '+65-0000-0003', 'CONCIERGE_MANAGER', " +
                "'ACTIVE', 'DIRECT_SIGNUP', TRUE, NOW(6), NOW(6))")) {
            ps.setString(1, email);
            ps.setString(2, passwordHash);
            ps.executeUpdate();
            log.info("Migration [seed-concierge-manager]: created seed account {}", email);
        }
    }

    /**
     * 특정 테이블에 컬럼이 존재하는지 확인
     */
    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }

    /**
     * 특정 테이블이 존재하는지 확인
     */
    private boolean tableExists(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
