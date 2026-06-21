package com.bluelight.backend.config;

import com.bluelight.backend.domain.user.UserRole;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            // ★ 새 CREATE TABLE이 schema.sql에 추가되어도 기존 DB에 자동 반영되도록,
            //   schema.sql의 모든 `CREATE TABLE IF NOT EXISTS` 문을 idempotent 하게 실행.
            //   ALTER는 기존 migrate*Columns() 메서드들이 계속 담당.
            syncCreateTablesFromSchemaSql(conn);
            migrateUserNameSplit(conn);
            migrateApplicationsLoaColumns(conn);
            // sld_option VARCHAR(20) → VARCHAR(40)
            // SldOption enum에 SUBMIT_WITHIN_3_MONTHS(22자)가 추가되어 기존 컬럼 폭을 초과.
            // 레거시 DB(dev RDS)가 VARCHAR(20)인 채로 있어 INSERT 시 Data truncation 발생.
            migrateApplicationsSldOptionWidth(conn);
            migrateSldTemplatesTable(conn);
            migrateSampleFilesTable(conn);
            migrateSampleFilesMultiFile(conn);
            migrateMasterPricesRenewalPrice(conn);
            migrateMasterPricesCalloutFee(conn);
            migrateApplicationsCalloutFee(conn);
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
            // ★ Kaki Concierge Phase 1 PR#5 Stage A
            migrateApplicationsViaConciergeColumn(conn);
            // ★ Kaki Concierge Phase 1 PR#7
            migratePaymentsReferenceColumns(conn);
            // files 테이블에 3개 신규 서비스 주문 FK 컬럼 추가
            migrateFilesServiceOrderColumns(conn);
            // ★ Expired License Order — files.expired_license_order_seq FK + file_type VARCHAR(40)
            migrateFilesExpiredLicenseColumn(conn);
            migrateFilesFileTypeWidth(conn);
            // ★ Kaki Concierge Phase 1.5 — Quote workflow (통화 후 견적 이메일)
            migrateConciergeRequestsQuoteColumns(conn);
            // sld_orders.ampere — 신청자가 주문 시 입력하는 ampere 정보
            migrateSldOrdersAmpereColumn(conn);
            // ★ LEW Service 방문형 리스키닝 PR 2 — 방문 일정 예약 컬럼
            migrateLewServiceOrdersVisitScheduleColumns(conn);
            // ★ LEW Service 방문형 리스키닝 PR 3 — 체크인/아웃 + 보고서 컬럼
            migrateLewServiceOrdersVisitColumns(conn);
            // ★ LEW Service 방문형 리스키닝 PR 3 — revision_comment → revisit_comment rename
            migrateLewServiceOrdersRevisitRename(conn);
            // ★ LEW Service 방문형 리스키닝 PR 3 — 상태 enum rename
            migrateLewServiceOrdersStatusRename(conn);
            // ★ LEW Service 방문형 리스키닝 PR 3 — visit_photos 테이블
            createLewServiceVisitPhotosTable(conn);
            // ── P1.1: EMA ELISE 필드 + Declaration 감사 로그 ──
            migrateApplicationsEmaFields(conn);
            // ── EMA 제출 추적 (ema-submission-tracking-spec.md §6) — 7컬럼 + OQ-1 backfill ──
            migrateApplicationsEmaSubmissionTracking(conn);
            migrateApplicationDeclarationLogsTable(conn);
            // ── C.1: Snapshot-at-submit — applications.loa_phone_snapshot, loa_email_snapshot ──
            migrateApplicationsLoaPhoneEmailSnapshots(conn);
            // ── LEW Review Form P1.B: applications 테이블에 신청자 hint 8 컬럼 ──
            migrateApplicationsApplicantHintColumns(conn);
            // ── 결제 후 kVA 사후 변경 (PR-1) ──
            // invoices 테이블 status/invalidated_reason/invalidated_at 컬럼 + uk_invoices_payment 제거
            migrateInvoicesStatusColumns(conn);
            // ── 결제 후 kVA 사후 변경 (PR-4) ──
            // kva_adjustment_record 테이블에 settled_at 컬럼 추가 (settlement 마킹 시각)
            migrateKvaAdjustmentRecordSettledAt(conn);
            // ── ADMIN Manual Email Dispatch (admin-manual-email-spec.md PR-1) ──
            // syncCreateTablesFromSchemaSql 가 IF NOT EXISTS 로 테이블을 만들지만,
            // 본 메서드는 명시적 멱등 가드를 두어 신규 테이블의 의도가 코드 리뷰에서 보이도록 한다.
            migrateManualEmailDispatchesTable(conn);
            // ── ADMIN Manual Email Dispatch (PR-2): MULTI 컬럼 + 멱등성 해시 보강 ──
            // PR-1 운영 DB 에 _json + recipient_hash 컬럼이 누락되어 있을 수 있으므로 idempotent ALTER.
            migrateManualEmailRecipientLists(conn);
            // ── ADMIN Manual Email Dispatch (PR-4): 인앱 동반 옵션 컬럼 추가 (D4=B) ──
            // 기존 row 는 default true 로 backfill — PR-1/2/3 동작 변경 없이 인앱 옵션이 뒤늦게 켜진 형태.
            migrateManualEmailInAppOptionColumn(conn);
            // ── ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-1 ──
            // D1=B 다중 역할 정규화: user_roles 테이블 + 기존 users.role 백필.
            migrateUserRolesTable(conn);
            // D2=B PaymentMethod enum + offline 기록: payments.payment_method 정정 + 신규 컬럼.
            migratePaymentsMethodColumns(conn);
            // D6=A LEW 셀프 할당: concierge_requests.assigned_lew_seq + lew_assigned_at + 인덱스.
            migrateConciergeRequestsLewAssignment(conn);
            seedSystemSettings(conn);
            // ── invoice_footer_note 브랜딩 추가 — 운영 DB row 1회 갱신 ──
            updateInvoiceFooterNoteBranding(conn);
            // ── Document Number Generator (공통 문서번호 채번) P1.1 + P1.3 ──
            createDocumentNumberTables(conn);
            seedDocumentNumberTypes(conn);
            // ★ Kaki Concierge Phase 1 PR#4 Stage A
            seedConciergeManager(conn);
            // role_metadata 싱크 — UserRole enum 값을 테이블에 upsert하고 enum에 없는 row는 삭제
            syncRoleMetadata(conn);
            // ★ Soft-deleted 계정 이메일 익명화 백필 (PDPA + uk_users_email 충돌 회피)
            // User.anonymize() 패치 이전에 삭제된 row는 원본 이메일을 점유하고 있어
            // 동일 이메일 재가입 시 INSERT가 unique 제약으로 실패한다.
            backfillDeletedUserEmails(conn);
            // ★ P0: lew_licence_no UNIQUE 제약 — 한 실물 LEW = 한 계정 (사칭/중복 가입 방지).
            //   soft-deleted 행의 면허번호를 NULL로 비운 뒤, 활성 중복이 없을 때만 멱등 추가.
            migrateUsersLewLicenceNoUnique(conn);
            // ── prod 패리티: dev/schema.sql 에만 반영되고 runner 에 누락됐던 컬럼 보정 ──
            //   (WhatsApp/전화/i18n users 컬럼 + kVA·snapshot·applicant_type·version applications 컬럼)
            //   bluelight_prod 드리프트 감사(2026-06)로 식별. 각 컬럼 columnExists 가드 → 멱등.
            migrateProdParityColumns(conn);
            // ── 알림 카탈로그 시드 (PR-T5) ──
            // notification_catalog 는 SQL_INIT_MODE=never 인 dev/prod 에서 data.sql 이 실행되지 않아
            // 비어 있을 수 있다(템플릿 142종은 별도 import 됐으나 카탈로그 시드 단계 누락 → Admin Edit
            // 화면의 "Triggered by"·카탈로그 설명 미표시). 풀 97종을 idempotent 하게 시드한다.
            seedNotificationCatalog(conn);
            // ── 결제 요청 알림 배선(A-17) 활성화 ──
            // 미사용 템플릿 일괄 비활성화 시 A-17(Payment requested)도 꺼졌을 수 있으나, 이제
            // LEW/ADMIN 결제 요청이 A-17 을 오케스트레이터로 발송하므로 EMAIL/IN_APP 을 멱등 활성화.
            enableWiredNotificationTemplates(conn);
            // ── 신청자 결제 알림 템플릿(A-17 결제요청 / A-20 결제확인) 본문 멱등 시드+활성 ──
            //    data.sql 은 dev/prod(SQL_INIT_MODE=never)에 미적용 → 행 누락 시 이메일 발송 실패 방지.
            seedApplicantPaymentNotificationTemplates(conn);
            // ── 결제 신호 ADMIN 알림 템플릿(A-55 증빙업로드 / A-56 확인요청) 멱등 시드+활성 ──
            seedPaymentSignalNotificationTemplates(conn);
            // ── LoA 폼 전달 → 신청자 알림 템플릿(A-57) 멱등 시드+활성 ──
            seedLoaFormSentNotificationTemplate(conn);
            // ── SLD 전환 추가요금 알림 템플릿(A-58 신청자 통보 / A-59 ADMIN 정산요청) 멱등 시드+활성 ──
            seedSldConversionNotificationTemplates(conn);
            // ── 신청자 신고 kVA(USER_INPUT) 가 LEW 미확정인데 CONFIRMED 로 저장돼 있던 레거시 행 보정 ──
            //   "신청자가 적었다고 LEW 확정 상태가 되면 안 됨" 규칙 적용. 결제 전 상태만 안전하게 UNKNOWN 으로.
            backfillUserDeclaredKvaToUnknown(conn);
            // ── 출장비(call-out fee) 소급: 결제 전 기존 NEW 신청에 출장비 반영 ──
            //   기능 도입 전 생성돼 callout_fee 가 NULL 인 결제 전 NEW 신청에 tier 출장비를 채우고
            //   quote_amount 에 가산. callout_fee IS NULL 가드로 멱등(1회만 적용).
            backfillCalloutFeeForPrePaymentApplications(conn);
            // ── 문서 카탈로그 LOA/SP_ACCOUNT 업로드 형식에 JPG/PNG 허용 ──
            //   document_type_catalog 는 data.sql(never 모드)로만 시드되어 dev/prod 기존 행이
            //   옛 PDF-전용으로 남을 수 있다. accepted_mime 를 멱등 갱신해 JPEG/PNG 를 연다.
            migrateDocumentCatalogAcceptedMime(conn);
            // ── 문서 요청 승인/반려 단계 제거 (2026-06-18) — 레거시 APPROVED/REJECTED 행을 UPLOADED 로 ──
            //   DocumentRequestStatus enum 에서 APPROVED/REJECTED 제거 → 기존 행 로드 시 enum 파싱 실패 방지.
            //   부팅 시 1회 적용 후 멱등(매칭 행 0).
            migrateDocumentRequestRemoveApproveReject(conn);
            // ── LoA 단계 2상태 축소 (2026-06-21) — FORM_SENT/APPLICANT_UPLOADED 제거 ──
            //   loaStage 가 LEW 최종본 트랙만 표현하도록 단순화. 기존 행 로드 시 enum 파싱 실패 방지를 위해
            //   FORM_SENT/APPLICANT_UPLOADED → NOT_STARTED 로 멱등 변환(매칭 행 0이면 no-op).
            migrateApplicationsLoaStageCollapse(conn);
            log.info("Database migration check completed");
        } catch (SQLException e) {
            log.error("Database migration failed", e);
            throw new RuntimeException("Database migration failed", e);
        }
    }

    /**
     * schema.sql의 모든 `CREATE TABLE IF NOT EXISTS ...` 문을 idempotent 하게 실행한다.
     * <p>
     * 배경: schema.sql에 신규 테이블을 추가할 때마다 DatabaseMigrationRunner에
     * 별도 {@code migrate*Table()} 메서드를 추가하는 방식은 누락 사고가 반복됨
     * (예: lew_service_orders, lighting_orders, power_socket_orders, role_metadata).
     * 이 메서드는 schema.sql 전체를 파싱하여 CREATE TABLE만 재실행하므로
     * IF NOT EXISTS 덕분에 기존 테이블은 영향 받지 않고 신규 테이블만 자동 생성된다.
     * <p>
     * ALTER/프로시저/트리거는 이 메서드가 건드리지 않는다 — 기존 migrate*Columns()
     * 메서드들이 계속 담당한다.
     */
    private void syncCreateTablesFromSchemaSql(Connection conn) {
        String sql;
        try (InputStream is = new ClassPathResource("schema.sql").getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("syncCreateTablesFromSchemaSql: schema.sql read failed — skipping: {}", e.getMessage());
            return;
        }

        // 주석(--) 제거 후 ';' 기준으로 문장 분리. 프로시저/트리거가 없으므로 이 단순 분리로 충분.
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.replaceAll("--.*$", "").trim();
            if (!trimmed.isEmpty()) cleaned.append(trimmed).append('\n');
        }
        String[] statements = cleaned.toString().split(";");

        int created = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String raw : statements) {
                String s = raw.trim();
                if (s.isEmpty()) continue;
                // CREATE TABLE IF NOT EXISTS만 실행 (대소문자 무시, 공백 관대)
                if (!s.toUpperCase().matches("(?s)^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+.*")) continue;
                try {
                    stmt.executeUpdate(s);
                    created++;
                } catch (SQLException e) {
                    // 이미 다른 스키마/버전이면 warn만 (다음 migration에서 해결)
                    log.warn("syncCreateTables statement warn: {} — err: {}",
                        s.substring(0, Math.min(80, s.length())), e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("syncCreateTables statement execution aborted: {}", e.getMessage());
        }
        if (created > 0) {
            log.info("Migration [sync-create-tables]: processed {} CREATE TABLE IF NOT EXISTS statements", created);
        } else {
            log.debug("Migration [sync-create-tables]: no statements processed");
        }
    }

    /**
     * 알림 카탈로그(notification_catalog) 풀 시드를 idempotent 하게 적용한다.
     * <p>
     * 배경: 운영/개발은 {@code SQL_INIT_MODE=never} 라 {@code data.sql}(샘플 8종)이 실행되지 않고,
     * 풀 카탈로그(97종)는 {@code scripts/import_notification_copy.py} 로 만든 SQL 을 수동 실행하도록
     * 되어 있었다. 이 수동 단계가 누락되면 카탈로그가 비어 Admin Edit 화면에서 "Triggered by"·
     * 카탈로그 설명·Lint 변수 화이트리스트가 모두 동작하지 않는다.
     * <p>
     * 시드 SQL({@code db/seed/notification_catalog_seed.sql})의 각 문장은 한 줄·
     * {@code INSERT ... SELECT ... WHERE NOT EXISTS} 형태라 재실행해도 중복 삽입되지 않는다.
     * 따라서 매 부팅 실행해도 안전하며, 누락분만 채운다. (trigger_ref 값에 ';' 가 포함될 수 있어
     * ';' 분리는 쓰지 않고 줄 단위로 파싱한다.)
     */
    private void seedNotificationCatalog(Connection conn) {
        String sql;
        try (InputStream is = new ClassPathResource("db/seed/notification_catalog_seed.sql").getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("seedNotificationCatalog: seed file read failed — skipping: {}", e.getMessage());
            return;
        }

        int inserted = 0;
        int processed = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String line : sql.split("\\R")) {
                String s = line.trim();
                // 빈 줄·전체 주석 줄 건너뜀 (값 내부 '--' 는 없음 — 생성기 보장)
                if (s.isEmpty() || s.startsWith("--")) continue;
                if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
                if (s.isEmpty()) continue;
                processed++;
                try {
                    inserted += stmt.executeUpdate(s);
                } catch (SQLException e) {
                    log.warn("seedNotificationCatalog statement warn: {} — err: {}",
                        s.substring(0, Math.min(80, s.length())), e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("seedNotificationCatalog execution aborted: {}", e.getMessage());
        }
        if (inserted > 0) {
            log.info("Migration [seed-notification-catalog]: inserted {} new catalog rows ({} statements)",
                inserted, processed);
        } else {
            log.debug("Migration [seed-notification-catalog]: catalog already seeded ({} statements, 0 new)",
                processed);
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
    /**
     * 마이그레이션: prod 패리티 — dev/schema.sql 에만 있고 runner 에 누락됐던 컬럼들.
     * <p>각 컬럼을 {@link #columnExists}로 가드해 개별 멱등 추가 (부분 적용 환경에도 안전).
     * 정의는 dev(`bluelight`) 실제 스키마에서 추출. 전부 additive(nullable 또는 안전 default).
     */
    private void migrateProdParityColumns(Connection conn) throws SQLException {
        // users — WhatsApp/전화/i18n (WhatsApp 알림 Phase 0)
        addColumnIfMissing(conn, "users", "phone_e164",          "ALTER TABLE users ADD COLUMN phone_e164 VARCHAR(20) NULL");
        addColumnIfMissing(conn, "users", "phone_verified",      "ALTER TABLE users ADD COLUMN phone_verified TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, "users", "phone_verified_at",   "ALTER TABLE users ADD COLUMN phone_verified_at DATETIME(6) NULL");
        addColumnIfMissing(conn, "users", "whatsapp_opt_in",     "ALTER TABLE users ADD COLUMN whatsapp_opt_in TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing(conn, "users", "whatsapp_opt_in_at",  "ALTER TABLE users ADD COLUMN whatsapp_opt_in_at DATETIME(6) NULL");
        addColumnIfMissing(conn, "users", "whatsapp_opt_out_at", "ALTER TABLE users ADD COLUMN whatsapp_opt_out_at DATETIME(6) NULL");
        addColumnIfMissing(conn, "users", "preferred_language",  "ALTER TABLE users ADD COLUMN preferred_language VARCHAR(10) NOT NULL DEFAULT 'en'");
        // users — LEW 본인 PayNow 수취 계정 (LEW 초대/가입 + PayNow 수집). nullable, 비-LEW/기존 row backfill 안전.
        addColumnIfMissing(conn, "users", "paynow_type",         "ALTER TABLE users ADD COLUMN paynow_type VARCHAR(20) NULL");
        addColumnIfMissing(conn, "users", "paynow_value",        "ALTER TABLE users ADD COLUMN paynow_value VARCHAR(20) NULL");
        // account_setup_tokens — 입력 검증 오류(면허/등급/PayNow) 전용 카운터(10회 잠금). 기존 토큰 backfill 0.
        addColumnIfMissing(conn, "account_setup_tokens", "input_validation_failures",
            "ALTER TABLE account_setup_tokens ADD COLUMN input_validation_failures INT NOT NULL DEFAULT 0");
        // applications — snapshot-at-submit + kVA 사후변경 + applicant_type + 낙관락 version
        addColumnIfMissing(conn, "applications", "applicant_name_snapshot", "ALTER TABLE applications ADD COLUMN applicant_name_snapshot VARCHAR(100) NULL");
        addColumnIfMissing(conn, "applications", "company_name_snapshot",   "ALTER TABLE applications ADD COLUMN company_name_snapshot VARCHAR(100) NULL");
        addColumnIfMissing(conn, "applications", "uen_snapshot",            "ALTER TABLE applications ADD COLUMN uen_snapshot VARCHAR(20) NULL");
        addColumnIfMissing(conn, "applications", "designation_snapshot",    "ALTER TABLE applications ADD COLUMN designation_snapshot VARCHAR(50) NULL");
        addColumnIfMissing(conn, "applications", "snapshot_backfilled_at",  "ALTER TABLE applications ADD COLUMN snapshot_backfilled_at DATETIME(6) NULL");
        addColumnIfMissing(conn, "applications", "applicant_type",          "ALTER TABLE applications ADD COLUMN applicant_type VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL'");
        addColumnIfMissing(conn, "applications", "kva_status",              "ALTER TABLE applications ADD COLUMN kva_status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'");
        addColumnIfMissing(conn, "applications", "kva_source",              "ALTER TABLE applications ADD COLUMN kva_source VARCHAR(20) NULL");
        addColumnIfMissing(conn, "applications", "kva_confirmed_by",        "ALTER TABLE applications ADD COLUMN kva_confirmed_by BIGINT NULL");
        addColumnIfMissing(conn, "applications", "kva_confirmed_at",        "ALTER TABLE applications ADD COLUMN kva_confirmed_at DATETIME(6) NULL");
        addColumnIfMissing(conn, "applications", "version",                 "ALTER TABLE applications ADD COLUMN version BIGINT NOT NULL DEFAULT 0");
        // LoA 교환 모델 (loa-exchange 재설계 PR3)
        addColumnIfMissing(conn, "applications", "loa_stage",               "ALTER TABLE applications ADD COLUMN loa_stage VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED'");
        addColumnIfMissing(conn, "applications", "loa_form_template_seq",   "ALTER TABLE applications ADD COLUMN loa_form_template_seq BIGINT NULL");
        // 인앱 알림 딥링크 — 클릭 시 처리 화면의 해당 위치로 이동(NotificationLinkResolver 생성)
        addColumnIfMissing(conn, "notifications", "link_url",               "ALTER TABLE notifications ADD COLUMN link_url VARCHAR(300) NULL");
        // 견적 조정 원장 일반화 (sld-lew-conversion-fee-spec.md): DEFAULT 'KVA_CHANGE' 가 기존 행 자동 백필.
        addColumnIfMissing(conn, "kva_adjustment_record", "adjustment_type",
            "ALTER TABLE kva_adjustment_record ADD COLUMN adjustment_type VARCHAR(20) NOT NULL DEFAULT 'KVA_CHANGE' AFTER application_seq");
    }

    /** 컬럼이 없을 때만 ADD COLUMN 실행 (멱등). */
    private void addColumnIfMissing(Connection conn, String table, String column, String ddl) throws SQLException {
        if (columnExists(conn, table, column)) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(ddl);
            log.info("Migration [prod-parity]: added {}.{}", table, column);
        }
    }

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
     * 마이그레이션: applications.sld_option VARCHAR(20) → VARCHAR(40)
     * <p>
     * 배경: SldOption enum에 {@code SUBMIT_WITHIN_3_MONTHS}(22자)가 추가되면서
     * 기존 컬럼 폭(20)을 초과하여 INSERT 시 MySQL이 Data truncation 에러를 발생.
     * schema.sql은 신규 DB에 대해 이미 VARCHAR(40)으로 수정되었으나,
     * 레거시 DB(dev RDS 등)는 ALTER로 확장해야 함.
     * <p>
     * 멱등성: information_schema에서 현재 크기를 조회하여 40 미만일 때만 실행.
     */
    private void migrateApplicationsSldOptionWidth(Connection conn) throws SQLException {
        Integer currentSize = getColumnCharLength(conn, "applications", "sld_option");
        if (currentSize == null) {
            log.debug("Migration [applications-sld-option-width]: column not found, skipping");
            return;
        }
        if (currentSize >= 40) {
            log.debug("Migration [applications-sld-option-width]: already {} chars, skipping", currentSize);
            return;
        }
        log.info("Migration [applications-sld-option-width]: widening sld_option {} → 40", currentSize);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE applications MODIFY COLUMN sld_option VARCHAR(40) DEFAULT 'SELF_UPLOAD'"
            );
            log.info("Migration [applications-sld-option-width]: done");
        }
    }

    /**
     * information_schema에서 VARCHAR 컬럼의 문자 길이를 조회한다.
     * 컬럼이 없으면 null.
     */
    private Integer getColumnCharLength(Connection conn, String table, String column) throws SQLException {
        String sql = "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long len = rs.getLong(1);
                return rs.wasNull() ? null : (int) len;
            }
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
     * 마이그레이션: master_prices에 callout_fee(출장비) 컬럼 추가
     * - New License 신청에만 가산되는 출장비. 기본값 200.
     * - 기존 row 는 DEFAULT 200.00 으로 채워진다.
     */
    private void migrateMasterPricesCalloutFee(Connection conn) throws SQLException {
        if (!tableExists(conn, "master_prices")) return;

        if (columnExists(conn, "master_prices", "callout_fee")) {
            log.debug("Migration [master-prices-callout]: already applied, skipping");
            return;
        }

        log.info("Migration [master-prices-callout]: adding callout_fee column...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE master_prices ADD COLUMN callout_fee DECIMAL(10,2) NOT NULL DEFAULT 200.00 AFTER endorsement_price"
            );
            log.info("Migration [master-prices-callout]: completed");
        }
    }

    /**
     * 마이그레이션: applications 테이블에 callout_fee(출장비) 스냅샷 컬럼 추가
     * - New License 신청에만 설정 (Renewal 은 null), nullable
     * - Application 엔티티가 callout_fee 를 매핑하므로 컬럼 누락 시 모든 신청 조회가
     *   "Unknown column 'callout_fee'" 로 500 실패한다. master_prices callout 과 함께 초기에 보정.
     */
    private void migrateApplicationsCalloutFee(Connection conn) throws SQLException {
        if (!tableExists(conn, "applications")) return;

        if (columnExists(conn, "applications", "callout_fee")) {
            log.debug("Migration [applications-callout]: already applied, skipping");
            return;
        }

        log.info("Migration [applications-callout]: adding callout_fee column...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN callout_fee DECIMAL(10,2) NULL"
            );
            log.info("Migration [applications-callout]: completed");
        }
    }

    /**
     * 소급 backfill: 결제 전 기존 NEW 신청에 출장비(call-out fee) 반영.
     * <p>기능 도입 전 생성돼 {@code callout_fee IS NULL} 인, 아직 결제 전(PENDING_REVIEW /
     * REVISION_REQUESTED / PENDING_PAYMENT) NEW 신청을 대상으로, 신청의 selected_kva 가 속한
     * 활성 tier 의 출장비를 {@code callout_fee} 에 채우고 {@code quote_amount} 에 가산한다.</p>
     * <p>멱등: {@code callout_fee IS NULL} 가드로 1회만 적용(이후 NOT NULL → 스킵). RENEWAL·결제 완료
     * 건·tier 미매칭 건은 제외. kVA 미확정 건은 LEW 확정 시 재계산되어 자기수정된다.</p>
     */
    private void backfillCalloutFeeForPrePaymentApplications(Connection conn) throws SQLException {
        if (!tableExists(conn, "applications")) return;
        if (!columnExists(conn, "applications", "callout_fee")) return;
        if (!tableExists(conn, "master_prices") || !columnExists(conn, "master_prices", "callout_fee")) return;

        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(
                "UPDATE applications a " +
                "JOIN master_prices mp ON a.selected_kva BETWEEN mp.kva_min AND mp.kva_max " +
                "  AND mp.deleted_at IS NULL AND mp.is_active = 1 " +
                "SET a.callout_fee = mp.callout_fee, " +
                "    a.quote_amount = a.quote_amount + mp.callout_fee, " +
                "    a.updated_at = NOW() " +
                "WHERE a.application_type = 'NEW' " +
                "  AND a.callout_fee IS NULL " +
                "  AND a.status IN ('PENDING_REVIEW','REVISION_REQUESTED','PENDING_PAYMENT') " +
                "  AND a.deleted_at IS NULL"
            );
            if (updated > 0) {
                log.info("Backfill [callout-fee-prepayment]: {} application(s) updated", updated);
            }
        }
    }

    /**
     * 마이그레이션: document_type_catalog 의 LOA/SP_ACCOUNT 업로드 형식에 JPG/PNG 허용.
     * <p>카탈로그는 data.sql(SQL_INIT_MODE=never)로만 시드되어 dev/prod 기존 행이 옛 PDF-전용으로
     * 남을 수 있다. accepted_mime 를 'application/pdf,image/jpeg,image/png' 로 멱등 갱신한다
     * (이미 목표값이면 0건 업데이트 → 멱등).</p>
     */
    private void migrateDocumentCatalogAcceptedMime(Connection conn) throws SQLException {
        if (!tableExists(conn, "document_type_catalog")) return;
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(
                "UPDATE document_type_catalog " +
                "SET accepted_mime = 'application/pdf,image/jpeg,image/png', updated_at = NOW() " +
                "WHERE code IN ('LOA','SP_ACCOUNT') " +
                "  AND accepted_mime <> 'application/pdf,image/jpeg,image/png'"
            );
            if (updated > 0) {
                log.info("Migration [doc-catalog-mime]: {} row(s) updated (LOA/SP_ACCOUNT → +JPG/PNG)", updated);
            }
            // SP_ACCOUNT 라벨이 옛 "...PDF" 면 갱신 (JPG/PNG 도 허용하므로 PDF 한정 표현 제거).
            int relabeled = stmt.executeUpdate(
                "UPDATE document_type_catalog " +
                "SET label_en = 'SP Account Holder Document', label_ko = 'SP Account Holder Document', updated_at = NOW() " +
                "WHERE code = 'SP_ACCOUNT' AND (label_en = 'SP Account Holder PDF' OR label_ko = 'SP Account Holder PDF')"
            );
            if (relabeled > 0) {
                log.info("Migration [doc-catalog-mime]: SP_ACCOUNT label updated (PDF → Document)");
            }
            // LOA 라벨 정정: "Letter of Authorisation" → "Letter of Appointment" (도메인 정본 용어).
            int loaRelabel = stmt.executeUpdate(
                "UPDATE document_type_catalog " +
                "SET label_en = 'Letter of Appointment', label_ko = 'Letter of Appointment', " +
                "    description = 'Signed letter appointing the LEW to act on your behalf', " +
                "    help_text = 'Upload the signed Letter of Appointment. PDF, JPG, or PNG accepted.', " +
                "    updated_at = NOW() " +
                "WHERE code = 'LOA' AND label_en = 'Letter of Authorisation'"
            );
            if (loaRelabel > 0) {
                log.info("Migration [doc-catalog-mime]: LOA label updated (Authorisation → Appointment)");
            }
        }
    }

    /**
     * 마이그레이션: 문서 요청 승인/반려 단계 제거 (2026-06-18).
     * <p>레거시 {@code document_request.status} 의 'APPROVED'/'REJECTED' 행을 'UPLOADED' 로 전환한다.
     * DocumentRequestStatus enum 에서 두 값을 제거했으므로, 잔존 행이 있으면 엔티티 로드 시 enum
     * 파싱이 실패한다. 부팅 시 1회 적용 후 매칭 행이 0이 되어 멱등하다.</p>
     */
    private void migrateDocumentRequestRemoveApproveReject(Connection conn) throws SQLException {
        if (!tableExists(conn, "document_request")) return;
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(
                "UPDATE document_request SET status = 'UPLOADED' " +
                "WHERE status IN ('APPROVED', 'REJECTED')"
            );
            if (updated > 0) {
                log.info("Migration [docreq-approve-reject-removal]: {} row(s) APPROVED/REJECTED → UPLOADED", updated);
            }
        }
    }

    /**
     * 마이그레이션: LoaStage 2상태 축소 — FORM_SENT/APPLICANT_UPLOADED → NOT_STARTED.
     * <p>loaStage 가 LEW 최종본 트랙(NOT_STARTED/FINAL_UPLOADED)만 표현하도록 단순화되면서,
     * 레거시 행에 남은 FORM_SENT/APPLICANT_UPLOADED 는 enum 파싱 실패를 유발한다. 부팅 시 1회 변환 후
     * 멱등(매칭 행 0). 신청자 LoA 파일(OWNER_AUTH_LETTER)·LEW 최종본(LOA_FINAL)은 그대로 보존되므로
     * 데이터 손실 없음.</p>
     */
    private void migrateApplicationsLoaStageCollapse(Connection conn) throws SQLException {
        if (!columnExists(conn, "applications", "loa_stage")) return;
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(
                "UPDATE applications SET loa_stage = 'NOT_STARTED' " +
                "WHERE loa_stage IN ('FORM_SENT', 'APPLICANT_UPLOADED')"
            );
            if (updated > 0) {
                log.info("Migration [loa-stage-collapse]: {} row(s) FORM_SENT/APPLICANT_UPLOADED → NOT_STARTED", updated);
            }
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
     * 마이그레이션: applications 테이블에 Concierge 대리 생성 연결 컬럼 추가
     * (★ Kaki Concierge v1.5, Phase 1 PR#5 Stage A)
     * <p>
     * Manager가 대리 생성한 Application은 {@code via_concierge_request_seq}에 해당
     * ConciergeRequest.seq를 기록. APPLICANT 직접 신청은 null. FK는 걸지 않음
     * (concierge_requests soft-delete와 상호작용 회피, 인덱스만).
     */
    /**
     * 마이그레이션: files 테이블에 lighting_order_seq / power_socket_order_seq /
     * lew_service_order_seq 컬럼 + 인덱스 추가.
     * 3개 신규 서비스 주문(Lighting / Power Socket / LEW Service)의 스케치 업로드
     * 기능이 sld_order_seq와 동일한 방식으로 FileEntity를 참조하게 한다.
     */
    private void migrateFilesServiceOrderColumns(Connection conn) throws SQLException {
        String[][] cols = {
            {"lighting_order_seq",     "sld_order_seq",          "idx_files_lighting_order_seq"},
            {"power_socket_order_seq", "lighting_order_seq",     "idx_files_power_socket_order_seq"},
            {"lew_service_order_seq",  "power_socket_order_seq", "idx_files_lew_service_order_seq"},
        };
        for (String[] col : cols) {
            String columnName = col[0];
            String afterColumn = col[1];
            String indexName = col[2];
            if (columnExists(conn, "files", columnName)) {
                continue;
            }
            log.info("Migration [files-service-order]: adding {}", columnName);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE files ADD COLUMN " + columnName + " BIGINT AFTER " + afterColumn
                );
                try {
                    stmt.executeUpdate("CREATE INDEX " + indexName + " ON files (" + columnName + ")");
                } catch (SQLException ignore) {
                    log.debug("Migration [files-service-order]: {} already exists", indexName);
                }
            }
        }
    }

    /**
     * 마이그레이션: files 테이블에 expired_license_order_seq 컬럼 + 인덱스 추가.
     */
    private void migrateFilesExpiredLicenseColumn(Connection conn) throws SQLException {
        if (columnExists(conn, "files", "expired_license_order_seq")) {
            log.debug("Migration [files-expired-license]: already applied, skipping");
            return;
        }
        log.info("Migration [files-expired-license]: adding expired_license_order_seq");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE files ADD COLUMN expired_license_order_seq BIGINT AFTER lew_service_order_seq"
            );
            try {
                stmt.executeUpdate(
                    "CREATE INDEX idx_files_expired_license_order_seq ON files (expired_license_order_seq)"
                );
            } catch (SQLException ignore) {
                log.debug("Migration [files-expired-license]: index already exists");
            }
        }
    }

    /**
     * 마이그레이션: files.file_type VARCHAR(30) → VARCHAR(40)
     * <p>Expired License 관련 enum 값이 30자에 근접 (EXPIRED_LICENSE_SUPPORTING_DOC = 30자) 하여
     * 향후 확장성 확보를 위해 40으로 확대. 멱등성 보장.
     */
    private void migrateFilesFileTypeWidth(Connection conn) throws SQLException {
        Integer currentSize = getColumnCharLength(conn, "files", "file_type");
        if (currentSize == null) {
            log.debug("Migration [files-file-type-width]: column not found, skipping");
            return;
        }
        if (currentSize >= 40) {
            log.debug("Migration [files-file-type-width]: already {} chars, skipping", currentSize);
            return;
        }
        log.info("Migration [files-file-type-width]: widening file_type {} → 40", currentSize);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE files MODIFY COLUMN file_type VARCHAR(40) NOT NULL"
            );
            log.info("Migration [files-file-type-width]: done");
        }
    }

    private void migrateApplicationsViaConciergeColumn(Connection conn) throws SQLException {
        if (columnExists(conn, "applications", "via_concierge_request_seq")) {
            log.debug("Migration [applications-via-concierge]: already applied, skipping");
            return;
        }

        log.info("Migration [applications-via-concierge]: starting...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE applications ADD COLUMN via_concierge_request_seq BIGINT " +
                "AFTER loa_signature_source_memo"
            );
            log.info("Migration [applications-via-concierge]: added via_concierge_request_seq column");

            try {
                stmt.executeUpdate(
                    "CREATE INDEX idx_applications_concierge ON applications (via_concierge_request_seq)"
                );
                log.info("Migration [applications-via-concierge]: created idx_applications_concierge");
            } catch (SQLException e) {
                log.debug("Migration [applications-via-concierge]: idx_applications_concierge already exists, skipping");
            }
        }
    }

    /**
     * 마이그레이션: payments 테이블에 reference_type/reference_seq 컬럼 추가 + application_seq nullable 전환
     * (★ Kaki Concierge v1.5, Phase 1 PR#7)
     * <p>
     * 전환 순서 (안전성 우선):
     * <ol>
     *   <li>reference_type, reference_seq 컬럼 추가 (nullable)</li>
     *   <li>기존 데이터 backfill: reference_type='APPLICATION', reference_seq=application_seq</li>
     *   <li>NOT NULL 제약 전환</li>
     *   <li>application_seq를 nullable로 완화 (Phase 2에서 CONCIERGE_REQUEST 결제는 NULL)</li>
     *   <li>복합 인덱스 idx_payment_reference 생성</li>
     * </ol>
     */
    private void migratePaymentsReferenceColumns(Connection conn) throws SQLException {
        if (columnExists(conn, "payments", "reference_type")) {
            log.debug("Migration [payments-reference]: already applied, skipping");
            return;
        }

        log.info("Migration [payments-reference]: starting...");
        try (Statement stmt = conn.createStatement()) {
            // 1. 컬럼 2종 추가 (우선 nullable)
            stmt.executeUpdate(
                "ALTER TABLE payments ADD COLUMN reference_type VARCHAR(30) " +
                "DEFAULT 'APPLICATION' AFTER application_seq"
            );
            stmt.executeUpdate(
                "ALTER TABLE payments ADD COLUMN reference_seq BIGINT AFTER reference_type"
            );
            log.info("Migration [payments-reference]: added reference_type, reference_seq columns");

            // 2. Backfill: 기존 Payment는 모두 APPLICATION 결제
            int updated = stmt.executeUpdate(
                "UPDATE payments " +
                "SET reference_type = 'APPLICATION', reference_seq = application_seq " +
                "WHERE reference_seq IS NULL AND application_seq IS NOT NULL"
            );
            log.info("Migration [payments-reference]: backfilled {} existing payment rows", updated);

            // 3. NOT NULL 제약 강화
            stmt.executeUpdate(
                "ALTER TABLE payments MODIFY COLUMN reference_type VARCHAR(30) NOT NULL"
            );
            stmt.executeUpdate(
                "ALTER TABLE payments MODIFY COLUMN reference_seq BIGINT NOT NULL"
            );

            // 4. application_seq nullable 전환 (Phase 2 CONCIERGE_REQUEST 결제 대비)
            stmt.executeUpdate(
                "ALTER TABLE payments MODIFY COLUMN application_seq BIGINT"
            );
            log.info("Migration [payments-reference]: relaxed application_seq to nullable");

            // 5. 복합 인덱스 (이미 존재 시 무시)
            try {
                stmt.executeUpdate(
                    "CREATE INDEX idx_payment_reference ON payments (reference_type, reference_seq)"
                );
                log.info("Migration [payments-reference]: created idx_payment_reference");
            } catch (SQLException e) {
                log.debug("Migration [payments-reference]: idx_payment_reference already exists, skipping");
            }
        }
    }

    /**
     * 마이그레이션: concierge_requests 에 견적 워크플로 컬럼 추가
     * (★ Kaki Concierge Phase 1.5 — 통화 후 이메일 견적 발송)
     * <p>
     * 추가 컬럼:
     * - call_scheduled_at: 통화에서 합의한 미팅/후속 약속 일정
     * - quoted_amount: 컨시어지 서비스 수수료 (매니저가 통화 후 확정)
     * - quote_sent_at: 견적 이메일 발송 타임스탬프
     * - verification_phrase: 피싱 방지용 4단어 토큰 (생성 시 세팅, 이메일·통화에 노출)
     */
    private void migrateConciergeRequestsQuoteColumns(Connection conn) throws SQLException {
        if (columnExists(conn, "concierge_requests", "quoted_amount")) {
            log.debug("Migration [concierge-requests-quote]: already applied, skipping");
            return;
        }

        log.info("Migration [concierge-requests-quote]: starting...");
        try (Statement stmt = conn.createStatement()) {
            if (!columnExists(conn, "concierge_requests", "call_scheduled_at")) {
                stmt.executeUpdate(
                    "ALTER TABLE concierge_requests ADD COLUMN call_scheduled_at DATETIME(6) AFTER cancellation_reason"
                );
            }
            stmt.executeUpdate(
                "ALTER TABLE concierge_requests ADD COLUMN quoted_amount DECIMAL(10,2) AFTER call_scheduled_at"
            );
            stmt.executeUpdate(
                "ALTER TABLE concierge_requests ADD COLUMN quote_sent_at DATETIME(6) AFTER quoted_amount"
            );
            if (!columnExists(conn, "concierge_requests", "verification_phrase")) {
                stmt.executeUpdate(
                    "ALTER TABLE concierge_requests ADD COLUMN verification_phrase VARCHAR(60) AFTER quote_sent_at"
                );
            }
            log.info("Migration [concierge-requests-quote]: added 4 columns");
        }
    }

    /**
     * sld_orders.ampere — 신청자가 주문 시 선택적으로 입력하는 암페어 값 (VARCHAR, 단위 자유입력).
     */
    private void migrateSldOrdersAmpereColumn(Connection conn) throws SQLException {
        if (!tableExists(conn, "sld_orders")) {
            log.debug("Migration [sld-orders-ampere]: sld_orders table not found, skipping");
            return;
        }
        if (columnExists(conn, "sld_orders", "ampere")) {
            log.debug("Migration [sld-orders-ampere]: already applied, skipping");
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE sld_orders ADD COLUMN ampere VARCHAR(30) AFTER selected_kva"
            );
            log.info("Migration [sld-orders-ampere]: added ampere column");
        }
    }

    /**
     * 마이그레이션: lew_service_orders 에 방문 일정 예약 컬럼 2종 추가
     * (★ LEW Service 방문형 리스키닝 PR 2)
     * <p>
     * 추가 컬럼:
     * - visit_scheduled_at: 합의된 방문 예정 일시
     * - visit_schedule_note: 방문 일정 관련 메모 (도어벨 고장 등)
     * <p>
     * 상태 전이는 변경하지 않음 — 기존 row 는 NULL 로 두고 무해하게 동작.
     */
    private void migrateLewServiceOrdersVisitScheduleColumns(Connection conn) throws SQLException {
        if (!tableExists(conn, "lew_service_orders")) {
            log.debug("Migration [lew-service-visit-schedule]: lew_service_orders table not found, skipping");
            return;
        }
        boolean hasScheduledAt = columnExists(conn, "lew_service_orders", "visit_scheduled_at");
        boolean hasScheduleNote = columnExists(conn, "lew_service_orders", "visit_schedule_note");
        if (hasScheduledAt && hasScheduleNote) {
            log.debug("Migration [lew-service-visit-schedule]: already applied, skipping");
            return;
        }

        log.info("Migration [lew-service-visit-schedule]: starting...");
        try (Statement stmt = conn.createStatement()) {
            if (!hasScheduledAt) {
                stmt.executeUpdate(
                    "ALTER TABLE lew_service_orders ADD COLUMN visit_scheduled_at DATETIME(6) NULL AFTER revision_comment"
                );
                log.info("Migration [lew-service-visit-schedule]: added visit_scheduled_at column");
            }
            if (!hasScheduleNote) {
                String after = hasScheduledAt ? "revision_comment" : "visit_scheduled_at";
                stmt.executeUpdate(
                    "ALTER TABLE lew_service_orders ADD COLUMN visit_schedule_note TEXT NULL AFTER " + after
                );
                log.info("Migration [lew-service-visit-schedule]: added visit_schedule_note column");
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

            // ── E-Invoice 회사/결제 정보 (invoice-spec.md §3) ──
            {"invoice_company_name", "HanVision holdings Private Ltd.", "E-Invoice company name"},
            {"invoice_company_alias", "Licensekaki", "E-Invoice company brand alias"},
            {"invoice_company_uen", "202627777H", "E-Invoice company UEN"},
            {"invoice_company_address_line1", "12 WOODLANDS SQUARE", "E-Invoice company address line 1"},
            {"invoice_company_address_line2", "#13-79 WOODS SQUARE TOWER ONE,", "E-Invoice company address line 2"},
            {"invoice_company_address_line3", "SINGAPORE 737715", "E-Invoice company address line 3"},
            {"invoice_company_email", "Admin@licensekaki.com", "E-Invoice company email"},
            {"invoice_company_website", "Licensekaki.com", "E-Invoice company website"},
            {"invoice_paynow_uen", "202627777H", "E-Invoice PayNow UEN"},
            {"invoice_paynow_qr_file_seq", "", "E-Invoice PayNow QR FileEntity seq (empty = not configured)"},
            // @Deprecated 2026-04: DocumentNumberService로 대체됨. Phase 2에서 row 제거 예정.
            {"invoice_number_prefix", "IN", "[DEPRECATED 2026-04] Replaced by DocumentNumberService — see document-number-generator-spec.md"},
            {"invoice_currency", "SGD", "E-Invoice default currency"},
            {"invoice_footer_note",
             "LicenseKaki by HanVision · No electronic signature is necessary, as this document serves as an official E-Invoice.",
             "E-Invoice footer note"},

            // ── ADMIN Manual Email Dispatch (admin-manual-email-spec.md §13.3) — PR-4 ──
            // D5=B: ADMIN 1인당 일 발송 한도. SGT 자정 기준 윈도우. SYSTEM_ADMIN 도 동일 cap (감사·운영 일관성).
            {"admin_manual_email_daily_cap", "100",
             "Daily manual email recipient cap per ADMIN (resets at 00:00 SGT)"},
            // D4=B (스펙 §13.3): Compose UI 카테고리 추천 드롭다운 옵션 (CSV). 자유 입력은 항상 허용.
            {"admin_manual_email_category_suggestions", "PAYMENT_NOTICE,MAINTENANCE,INFO,MISC",
             "Comma-separated category tag suggestions for manual email Compose UI"},

            // ── EMA 제출 추적 (ema-submission-tracking-spec.md §5.4) — 운영 가변 값(설정 우선 원칙) ──
            // 하드코딩 금지: 서비스가 system_settings 에서 조회한다(EmaSubmissionSettings).
            {"ema.reminder.days", "3",
             "Reminder threshold N days after EMA SUBMITTED/RESUBMITTED with no change"},
            {"ema.ack.required", "false",
             "Require EMA_ACK attachment on EMA submit-class transitions (T1/T3/T10)"},
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
     * 운영 DB의 invoice_footer_note row에 "LicenseKaki by HanVision · " 브랜딩 prefix를 1회 추가.
     * seedSystemSettings()는 INSERT IGNORE 패턴이라 기존 row 갱신을 못 한다 — 별도 idempotent UPDATE.
     * 이미 "LicenseKaki by HanVision"이 포함된 row는 건드리지 않으므로 여러 번 실행해도 안전.
     */
    private void updateInvoiceFooterNoteBranding(Connection conn) throws SQLException {
        final String oldValue = "No electronic signature is necessary, as this document serves as an official E-Invoice.";
        final String newValue = "LicenseKaki by HanVision · " + oldValue;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE system_settings SET setting_value = ? "
                        + "WHERE setting_key = 'invoice_footer_note' AND setting_value = ?")) {
            ps.setString(1, newValue);
            ps.setString(2, oldValue);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                log.info("Migration [invoice-footer-branding]: updated invoice_footer_note ({} row).", updated);
            }
        }
    }

    // ===================================================================
    // Document Number Generator (공통 문서번호 채번 엔진)
    // 스펙: doc/Project Analysis/document-number-generator-spec.md
    // ===================================================================

    /**
     * 문서번호 관련 테이블 생성 (멱등). schema.sql 시드와 동일한 DDL을 런타임에도 실행하여,
     * SQL_INIT_MODE=never 환경(운영 DB) 대응.
     */
    private void createDocumentNumberTables(Connection conn) throws SQLException {
        final String createTypes = """
                CREATE TABLE IF NOT EXISTS document_number_types (
                    code            VARCHAR(40)   NOT NULL,
                    prefix          VARCHAR(10)   NOT NULL,
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

        final String createSequence = """
                CREATE TABLE IF NOT EXISTS document_number_sequence (
                    doc_type_code    VARCHAR(40)   NOT NULL,
                    issue_date       DATE          NOT NULL,
                    next_value       INT           NOT NULL DEFAULT 1,
                    last_issued_at   DATETIME(6),
                    last_issued_by   BIGINT,
                    created_at       DATETIME(6),
                    updated_at       DATETIME(6),
                    PRIMARY KEY (doc_type_code, issue_date),
                    CONSTRAINT fk_docnumseq_type FOREIGN KEY (doc_type_code)
                        REFERENCES document_number_types (code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createTypes);
            stmt.executeUpdate(createSequence);
            log.debug("Migration [document-number-tables]: verified (idempotent)");
        }
    }

    /**
     * 문서 타입 카탈로그 시드 — P1에서는 RECEIPT 하나만. Phase 2에서 Admin UI를 통해 확장.
     * 멱등성: 이미 존재하면 스킵.
     */
    private void seedDocumentNumberTypes(Connection conn) throws SQLException {
        // {code, prefix, label_ko, label_en, description, display_order}
        final String[][] types = {
            {"RECEIPT", "RCP", "영수증", "Receipt",
             "결제 영수증 (E-Invoice) — 기존 Invoice 엔티티의 번호 생성에 사용", "10"},
        };

        int seeded = 0;
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT 1 FROM document_number_types WHERE code = ?");
             PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO document_number_types "
              + "(code, prefix, label_ko, label_en, description, active, display_order, created_at, updated_at) "
              + "VALUES (?, ?, ?, ?, ?, TRUE, ?, NOW(), NOW())")) {

            for (String[] t : types) {
                check.setString(1, t[0]);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) continue;
                }
                insert.setString(1, t[0]);
                insert.setString(2, t[1]);
                insert.setString(3, t[2]);
                insert.setString(4, t[3]);
                insert.setString(5, t[4]);
                insert.setInt(6, Integer.parseInt(t[5]));
                insert.executeUpdate();
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("Migration [seed-document-number-types]: seeded {} new types", seeded);
        } else {
            log.debug("Migration [seed-document-number-types]: all types exist, skipping");
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
     * role_metadata 싱크:
     * - UserRole enum 값 중 테이블에 없는 것은 기본값으로 INSERT (멱등)
     * - 테이블에 있으나 enum에 없는 row 는 DELETE (enum 이 축소된 경우 정리)
     * - 기존 row 는 sysadmin 이 수정한 값이 있을 수 있으므로 건드리지 않음
     */
    private void syncRoleMetadata(Connection conn) throws SQLException {
        if (!tableExists(conn, "role_metadata")) {
            log.warn("Migration [sync-role-metadata]: role_metadata table not found, skipping");
            return;
        }

        // 기본값: (label, assignable, filterable, sortOrder)
        // ADMIN/SYSTEM_ADMIN 은 UI 에서 assign 불가. SYSTEM_ADMIN 은 필터에도 노출하지 않음.
        // ★ PR-T7 (보안 감사 H-3) — NOTIFICATION_MANAGER 신규 시드. CLAUDE.md §1 설정 우선 원칙.
        Object[][] defaults = {
            {UserRole.APPLICANT,            "Applicant",            true,  true,  10},
            {UserRole.LEW,                  "LEW",                  true,  true,  20},
            {UserRole.SLD_MANAGER,          "SLD Manager",          true,  true,  30},
            {UserRole.CONCIERGE_MANAGER,    "Concierge Manager",    true,  true,  40},
            {UserRole.NOTIFICATION_MANAGER, "Notification Manager", true,  true,  45},
            {UserRole.ADMIN,                "Administrator",        false, true,  50},
            {UserRole.SYSTEM_ADMIN,         "System Admin",         false, false, 60},
        };

        int inserted = 0;
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT 1 FROM role_metadata WHERE role_code = ?");
             PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO role_metadata (role_code, display_label, assignable, filterable, sort_order, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))")) {
            for (Object[] row : defaults) {
                String code = ((UserRole) row[0]).name();
                check.setString(1, code);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) continue;
                }
                insert.setString(1, code);
                insert.setString(2, (String) row[1]);
                insert.setBoolean(3, (Boolean) row[2]);
                insert.setBoolean(4, (Boolean) row[3]);
                insert.setInt(5, (Integer) row[4]);
                insert.executeUpdate();
                inserted++;
            }
        }

        Set<String> validCodes = new HashSet<>();
        for (UserRole r : UserRole.values()) validCodes.add(r.name());

        List<String> stale = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT role_code FROM role_metadata");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String code = rs.getString(1);
                if (!validCodes.contains(code)) stale.add(code);
            }
        }
        if (!stale.isEmpty()) {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM role_metadata WHERE role_code = ?")) {
                for (String c : stale) {
                    del.setString(1, c);
                    del.executeUpdate();
                }
            }
            log.info("Migration [sync-role-metadata]: removed stale rows {}", stale);
        }

        if (inserted > 0) {
            log.info("Migration [sync-role-metadata]: inserted {} new role rows", inserted);
        } else if (stale.isEmpty()) {
            log.debug("Migration [sync-role-metadata]: in sync with UserRole enum");
        }
    }

    /**
     * 마이그레이션 (P1.1): applications 테이블에 EMA ELISE 확장 컬럼 16개 추가.
     * 각 컬럼을 개별적으로 columnExists 로 체크해 멱등성 보장.
     * 데이터 저장소 준비만 담당 — 추후 P1.2 에서 DTO/Service 에 전파한다.
     */
    private void migrateApplicationsEmaFields(Connection conn) throws SQLException {
        if (!tableExists(conn, "applications")) return;

        String[][] columns = {
                {"installation_name",                  "VARCHAR(200)"},
                {"premises_type",                      "VARCHAR(30)"},
                {"is_rental_premises",                 "TINYINT(1)"},
                {"landlord_ei_licence_no",             "VARCHAR(255)"},
                {"renewal_company_name_changed",       "TINYINT(1)"},
                {"renewal_address_changed",            "TINYINT(1)"},
                {"installation_address_block",         "VARCHAR(20)"},
                {"installation_address_unit",          "VARCHAR(20)"},
                {"installation_address_street",        "VARCHAR(200)"},
                {"installation_address_building",      "VARCHAR(200)"},
                {"installation_address_postal_code",   "VARCHAR(10)"},
                {"correspondence_address_block",       "VARCHAR(255)"},
                {"correspondence_address_unit",        "VARCHAR(255)"},
                {"correspondence_address_street",      "VARCHAR(500)"},
                {"correspondence_address_building",    "VARCHAR(500)"},
                {"correspondence_address_postal_code", "VARCHAR(10)"}
        };

        int added = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String[] c : columns) {
                if (!columnExists(conn, "applications", c[0])) {
                    stmt.executeUpdate("ALTER TABLE applications ADD COLUMN " + c[0] + " " + c[1]);
                    added++;
                }
            }
        }
        if (added > 0) {
            log.info("Migration [applications-ema-fields]: added {} column(s)", added);
        } else {
            log.debug("Migration [applications-ema-fields]: all columns exist, skipping");
        }
    }

    /**
     * 마이그레이션 (EMA 제출 추적): applications 테이블에 EMA 제출 추적 7컬럼 추가 + OQ-1 backfill.
     * <p>스펙: {@code doc/Project Analysis/ema-submission-tracking-spec.md} §6. P1.1
     * {@link #migrateApplicationsEmaFields} 와 동일 패턴(컬럼별 {@link #columnExists} 가드 → 멱등).
     *
     * <h3>OQ-1 배포 호환 backfill (grandfathering)</h3>
     * 이미 IN_PROGRESS 인 기존 신청은 새 종료 게이트(ema=APPROVED 필수)에 걸려 발급 불가가 된다.
     * 이를 막기 위해 컬럼 신규 추가가 발생한 "이번 마이그레이션에서만" IN_PROGRESS 행을 APPROVED 로 일괄 세팅.
     * <ul>
     *   <li>(a) {@code added>0} 인 첫 실행에서만 backfill — 재실행 시 컬럼이 이미 있어 added=0 → 스킵.</li>
     *   <li>(b) backfill UPDATE 자체도 {@code status='IN_PROGRESS' AND ema_submission_status='NOT_SUBMITTED'}
     *       조건이라, 만에 하나 재실행돼도 이미 진행/전이된 행을 덮어쓰지 않는다(이중 가드).</li>
     * </ul>
     * grandfathering 은 "EMA 가 실제 승인됐다"는 단언이 아니라 신규 게이트로부터의 소급 면제다.
     * LICENSE_PDF 게이트(§4)는 여전히 적용되므로 PDF 미첨부면 종료 시점에 막힌다(§11 R3).
     */
    private void migrateApplicationsEmaSubmissionTracking(Connection conn) throws SQLException {
        if (!tableExists(conn, "applications")) return;

        String[][] columns = {
                {"ema_submission_status",     "VARCHAR(30) NOT NULL DEFAULT 'NOT_SUBMITTED'"},
                {"ema_submitted_at",          "DATETIME(6)"},
                {"ema_reference_no",          "VARCHAR(60)"},
                {"ema_submitted_by_user_seq", "BIGINT"},
                {"ema_decision_at",           "DATETIME(6)"},
                {"ema_query_note",            "VARCHAR(1000)"},
                {"ema_status_before_decision", "VARCHAR(30)"},  // 허점#1 — Revert 복원 슬롯
                {"ema_reminder_notified_at",  "DATETIME(6)"}    // PR-E5 — 리마인더 중복 발송 가드(1일 1회 멱등)
        };

        int added = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String[] c : columns) {
                if (!columnExists(conn, "applications", c[0])) {
                    stmt.executeUpdate("ALTER TABLE applications ADD COLUMN " + c[0] + " " + c[1]);
                    added++;
                }
            }
        }
        if (added > 0) {
            log.info("Migration [applications-ema-submission]: added {} column(s)", added);
        } else {
            log.debug("Migration [applications-ema-submission]: all columns exist, skipping");
        }

        // ── OQ-1 backfill — 컬럼 신규 추가가 발생한 첫 실행에서만 (이중 멱등 가드) ──
        if (added > 0) {
            try (Statement stmt = conn.createStatement()) {
                int backfilled = stmt.executeUpdate(
                        "UPDATE applications " +
                                "SET ema_submission_status = 'APPROVED' " +
                                "WHERE status = 'IN_PROGRESS' " +
                                "  AND ema_submission_status = 'NOT_SUBMITTED' " +
                                "  AND deleted_at IS NULL");   // soft-delete 행 제외 (프로젝트 패턴)
                if (backfilled > 0) {
                    log.info("Migration [applications-ema-submission]: backfilled {} IN_PROGRESS row(s) to APPROVED",
                            backfilled);
                }
            }
        }
    }

    /**
     * 마이그레이션 (P1.1): application_declaration_logs 테이블 생성.
     * 신청 동의/선언 append-only 감사 로그.
     */
    private void migrateApplicationDeclarationLogsTable(Connection conn) throws SQLException {
        if (tableExists(conn, "application_declaration_logs")) {
            log.debug("Migration [application-declaration-logs]: already exists, skipping");
            return;
        }

        log.info("Migration [application-declaration-logs]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE application_declaration_logs (" +
                            "  declaration_log_seq BIGINT       NOT NULL AUTO_INCREMENT," +
                            "  application_seq     BIGINT       NOT NULL," +
                            "  user_seq            BIGINT       NOT NULL," +
                            "  consent_type        VARCHAR(60)  NOT NULL," +
                            "  document_version    VARCHAR(30)," +
                            "  form_snapshot_hash  VARCHAR(64)," +
                            "  ip_address          VARCHAR(45)," +
                            "  user_agent          VARCHAR(500)," +
                            "  declared_at         DATETIME(6)  NOT NULL," +
                            "  PRIMARY KEY (declaration_log_seq)," +
                            "  KEY idx_decl_log_application (application_seq)," +
                            "  KEY idx_decl_log_user (user_seq)," +
                            "  CONSTRAINT fk_decl_log_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq)," +
                            "  CONSTRAINT fk_decl_log_user FOREIGN KEY (user_seq) REFERENCES users (user_seq)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [application-declaration-logs]: table created");
        }
    }

    /**
     * 마이그레이션 (C.1): applications 테이블에 loa_phone_snapshot / loa_email_snapshot 컬럼 추가.
     * Snapshot-at-submit 정책 — LOA 스냅샷 4개 컬럼과 같이 신청 시점 phone/email을 불변 기록.
     * 엔티티 레벨 {@code @Column(updatable=false)}로 UPDATE 차단.
     */
    private void migrateApplicationsLoaPhoneEmailSnapshots(Connection conn) throws SQLException {
        if (!tableExists(conn, "applications")) return;

        String[][] cols = {
                {"loa_phone_snapshot", "VARCHAR(20)"},
                {"loa_email_snapshot", "VARCHAR(100)"}
        };

        int added = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String[] c : cols) {
                if (!columnExists(conn, "applications", c[0])) {
                    stmt.executeUpdate("ALTER TABLE applications ADD COLUMN " + c[0] + " " + c[1]);
                    added++;
                }
            }
        }
        if (added > 0) {
            log.info("Migration [loa-phone-email-snapshot]: added {} column(s)", added);
        } else {
            log.debug("Migration [loa-phone-email-snapshot]: all columns exist, skipping");
        }
    }

    /**
     * 마이그레이션 (LEW Review Form P1.B): applications 테이블에 신청자 hint 컬럼 8개 추가.
     * <p>LEW Review Form Step 2에서 CoF Draft 초기값으로 prefill되는 용도. 형식 오류는 경고 수준이며
     * 어떤 CHECK 제약도 걸지 않는다(스펙 §5.3). 기존 `sp_account_no` 컬럼은 legacy 병행 유지.</p>
     */
    private void migrateApplicationsApplicantHintColumns(Connection conn) throws SQLException {
        if (!tableExists(conn, "applications")) return;

        String[][] cols = {
                {"applicant_mssl_hint_enc",         "VARCHAR(255)"},
                {"applicant_mssl_hint_hmac",        "CHAR(64)"},
                {"applicant_mssl_hint_last4",       "VARCHAR(4)"},
                {"applicant_supply_voltage_hint",   "INT"},
                {"applicant_consumer_type_hint",    "VARCHAR(20)"},
                {"applicant_retailer_hint",         "VARCHAR(32)"},
                {"applicant_has_generator_hint",    "TINYINT(1)"},
                {"applicant_generator_capacity_hint", "INT"}
        };

        int added = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String[] c : cols) {
                if (!columnExists(conn, "applications", c[0])) {
                    stmt.executeUpdate("ALTER TABLE applications ADD COLUMN " + c[0] + " " + c[1]);
                    added++;
                }
            }
        }
        if (added > 0) {
            log.info("Migration [applicant-hint-columns]: added {} column(s)", added);
        } else {
            log.debug("Migration [applicant-hint-columns]: all columns exist, skipping");
        }
    }

    /**
     * 마이그레이션 (LEW Service 방문형 리스키닝 PR 3): lew_service_orders 에 체크인/아웃/보고서 컬럼 추가.
     * <p>추가 컬럼: check_in_at, check_out_at, visit_report_file_seq.
     * <p>visit_report_file_seq 는 기존 uploaded_file_seq 가 있는 경우 값을 복사하여 이관한다
     * (uploaded_file_seq 는 하위호환을 위해 DROP 하지 않음).
     */
    private void migrateLewServiceOrdersVisitColumns(Connection conn) throws SQLException {
        if (!tableExists(conn, "lew_service_orders")) {
            log.debug("Migration [lew-service-visit-columns]: lew_service_orders not found, skipping");
            return;
        }
        boolean hasCheckIn = columnExists(conn, "lew_service_orders", "check_in_at");
        boolean hasCheckOut = columnExists(conn, "lew_service_orders", "check_out_at");
        boolean hasVisitReport = columnExists(conn, "lew_service_orders", "visit_report_file_seq");
        if (hasCheckIn && hasCheckOut && hasVisitReport) {
            log.debug("Migration [lew-service-visit-columns]: already applied, skipping");
            return;
        }
        log.info("Migration [lew-service-visit-columns]: starting...");
        try (Statement stmt = conn.createStatement()) {
            if (!hasCheckIn) {
                stmt.executeUpdate(
                    "ALTER TABLE lew_service_orders ADD COLUMN check_in_at DATETIME(6) NULL AFTER visit_schedule_note"
                );
                log.info("Migration [lew-service-visit-columns]: added check_in_at");
            }
            if (!hasCheckOut) {
                stmt.executeUpdate(
                    "ALTER TABLE lew_service_orders ADD COLUMN check_out_at DATETIME(6) NULL AFTER check_in_at"
                );
                log.info("Migration [lew-service-visit-columns]: added check_out_at");
            }
            if (!hasVisitReport) {
                stmt.executeUpdate(
                    "ALTER TABLE lew_service_orders ADD COLUMN visit_report_file_seq BIGINT NULL AFTER check_out_at"
                );
                log.info("Migration [lew-service-visit-columns]: added visit_report_file_seq");
                // Backfill from legacy uploaded_file_seq
                if (columnExists(conn, "lew_service_orders", "uploaded_file_seq")) {
                    int copied = stmt.executeUpdate(
                        "UPDATE lew_service_orders SET visit_report_file_seq = uploaded_file_seq " +
                        "WHERE visit_report_file_seq IS NULL AND uploaded_file_seq IS NOT NULL"
                    );
                    log.info("Migration [lew-service-visit-columns]: copied {} uploaded_file_seq → visit_report_file_seq", copied);
                }
            }
        }
    }

    /**
     * 마이그레이션 (LEW Service 방문형 리스키닝 PR 3): revision_comment → revisit_comment rename.
     * <p>두 컬럼이 모두 없거나 revisit_comment 가 이미 있으면 스킵.
     * <p>둘 다 있는 경우: revision_comment 값을 revisit_comment 로 복사 (revisit_comment 가 비어있을 때만).
     * <p>revision_comment 만 있는 경우: revisit_comment 추가 후 데이터 복사.
     * <p>revision_comment DROP 은 별도 PR 에서 수행 (하위호환 유지).
     */
    private void migrateLewServiceOrdersRevisitRename(Connection conn) throws SQLException {
        if (!tableExists(conn, "lew_service_orders")) return;
        boolean hasRevision = columnExists(conn, "lew_service_orders", "revision_comment");
        boolean hasRevisit = columnExists(conn, "lew_service_orders", "revisit_comment");
        if (hasRevisit && !hasRevision) {
            log.debug("Migration [lew-service-revisit-rename]: already applied, skipping");
            return;
        }
        log.info("Migration [lew-service-revisit-rename]: starting (hasRevision={}, hasRevisit={})",
                hasRevision, hasRevisit);
        try (Statement stmt = conn.createStatement()) {
            if (!hasRevisit) {
                // 새 컬럼 추가 — revision_comment 뒤에
                String after = hasRevision ? "revision_comment" : "manager_note";
                stmt.executeUpdate(
                    "ALTER TABLE lew_service_orders ADD COLUMN revisit_comment TEXT NULL AFTER " + after
                );
                log.info("Migration [lew-service-revisit-rename]: added revisit_comment");
            }
            if (hasRevision) {
                // 데이터 복사 (revisit_comment 가 비어있을 때만)
                int copied = stmt.executeUpdate(
                    "UPDATE lew_service_orders SET revisit_comment = revision_comment " +
                    "WHERE revisit_comment IS NULL AND revision_comment IS NOT NULL"
                );
                log.info("Migration [lew-service-revisit-rename]: copied {} rows revision_comment → revisit_comment", copied);
            }
        }
    }

    /**
     * 마이그레이션 (LEW Service 방문형 리스키닝 PR 3): status enum 값 rename.
     * <p>MySQL 상 컬럼은 VARCHAR(30) 이므로 DDL 변경 불필요, 기존 row 만 UPDATE.
     * <ul>
     *   <li>IN_PROGRESS → VISIT_SCHEDULED</li>
     *   <li>SLD_UPLOADED → VISIT_COMPLETED</li>
     *   <li>REVISION_REQUESTED → REVISIT_REQUESTED</li>
     * </ul>
     */
    private void migrateLewServiceOrdersStatusRename(Connection conn) throws SQLException {
        if (!tableExists(conn, "lew_service_orders")) return;
        try (Statement stmt = conn.createStatement()) {
            int inProgress = stmt.executeUpdate(
                "UPDATE lew_service_orders SET status = 'VISIT_SCHEDULED' WHERE status = 'IN_PROGRESS'"
            );
            int sldUploaded = stmt.executeUpdate(
                "UPDATE lew_service_orders SET status = 'VISIT_COMPLETED' WHERE status = 'SLD_UPLOADED'"
            );
            int revRequested = stmt.executeUpdate(
                "UPDATE lew_service_orders SET status = 'REVISIT_REQUESTED' WHERE status = 'REVISION_REQUESTED'"
            );
            int total = inProgress + sldUploaded + revRequested;
            if (total > 0) {
                log.info("Migration [lew-service-status-rename]: renamed {} rows " +
                        "(IN_PROGRESS→VISIT_SCHEDULED={}, SLD_UPLOADED→VISIT_COMPLETED={}, " +
                        "REVISION_REQUESTED→REVISIT_REQUESTED={})",
                        total, inProgress, sldUploaded, revRequested);
            } else {
                log.debug("Migration [lew-service-status-rename]: no rows to rename");
            }
        }
    }

    /**
     * 마이그레이션 (LEW Service 방문형 리스키닝 PR 3): lew_service_visit_photos 테이블 생성.
     */
    private void createLewServiceVisitPhotosTable(Connection conn) throws SQLException {
        if (tableExists(conn, "lew_service_visit_photos")) {
            log.debug("Migration [lew-service-visit-photos-table]: already exists, skipping");
            return;
        }
        log.info("Migration [lew-service-visit-photos-table]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE lew_service_visit_photos (" +
                "  photo_seq   BIGINT       NOT NULL AUTO_INCREMENT," +
                "  order_seq   BIGINT       NOT NULL," +
                "  file_seq    BIGINT       NOT NULL," +
                "  caption     TEXT         NULL," +
                "  uploaded_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)," +
                "  deleted_at  DATETIME(6)  NULL," +
                "  PRIMARY KEY (photo_seq)," +
                "  KEY idx_lew_visit_photos_order (order_seq)," +
                "  KEY idx_lew_visit_photos_file  (file_seq)," +
                "  CONSTRAINT fk_lew_visit_photos_order " +
                "    FOREIGN KEY (order_seq) REFERENCES lew_service_orders (lew_service_order_seq)," +
                "  CONSTRAINT fk_lew_visit_photos_file " +
                "    FOREIGN KEY (file_seq) REFERENCES files (file_seq)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [lew-service-visit-photos-table]: table created");
        }
    }

    /**
     * 마이그레이션: soft-deleted 계정의 원본 이메일을 익명화 형식으로 일괄 치환.
     * <p>
     * 배경: User.anonymize() 패치(2026-04-30) 이전에 PDPA 삭제된 row는
     * email이 원본 그대로 남아 uk_users_email UNIQUE 제약을 점유한다.
     * @SQLRestriction("deleted_at IS NULL")이 existsByEmail()를 가리므로
     * 재가입 시 중복 검사를 통과한 뒤 INSERT 단계에서 unique 제약 충돌로 500 발생.
     * <p>
     * 익명화 형식은 도메인 anonymize()와 동일: deleted-{user_seq}@deleted.licensekaki.sg
     * 멱등성: 이미 익명화된 row(LIKE 패턴 매칭)는 제외한다.
     */
    /**
     * P0: users.lew_licence_no 에 UNIQUE 제약을 멱등 추가한다.
     * <p>
     * - soft-deleted 행이 면허번호를 점유하면 신규 등록과 충돌하므로 먼저 NULL 로 비운다
     *   (uk_users_email 의 익명화 전략과 동일).
     * - 활성(deleted_at IS NULL) 중복이 남아 있으면 인덱스 생성이 실패하므로, 중복이 있으면
     *   경고만 남기고 인덱스 추가를 건너뛴다(부팅 실패 방지 — 앱 레벨 검사가 이후 가입은 막는다).
     */
    private void migrateUsersLewLicenceNoUnique(Connection conn) throws SQLException {
        if (!columnExists(conn, "users", "lew_licence_no")) {
            log.debug("Migration [lew-licence-unique]: users.lew_licence_no not present, skipping");
            return;
        }
        if (indexExists(conn, "users", "uk_users_lew_licence_no")) {
            log.debug("Migration [lew-licence-unique]: already applied, skipping");
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            // 1) soft-deleted 행의 면허번호 해제 (재등록 충돌 회피)
            if (columnExists(conn, "users", "deleted_at")) {
                int freed = stmt.executeUpdate(
                    "UPDATE users SET lew_licence_no = NULL " +
                    "WHERE deleted_at IS NOT NULL AND lew_licence_no IS NOT NULL");
                if (freed > 0) {
                    log.info("Migration [lew-licence-unique]: freed {} soft-deleted licence numbers", freed);
                }
            }
            // 2) 활성 중복 검사 — 있으면 인덱스 생성 보류 (부팅 보호)
            int dupGroups = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT lew_licence_no, COUNT(*) c FROM users " +
                    "WHERE deleted_at IS NULL AND lew_licence_no IS NOT NULL " +
                    "GROUP BY lew_licence_no HAVING c > 1")) {
                while (rs.next()) {
                    dupGroups++;
                    log.warn("Migration [lew-licence-unique]: duplicate active licence '{}' x{}",
                            rs.getString(1), rs.getInt(2));
                }
            }
            if (dupGroups > 0) {
                log.warn("Migration [lew-licence-unique]: {} duplicate licence group(s) found — " +
                        "skipping UNIQUE index. Resolve duplicates then restart.", dupGroups);
                return;
            }
            // 3) UNIQUE 인덱스 추가
            stmt.executeUpdate(
                "ALTER TABLE users ADD UNIQUE INDEX uk_users_lew_licence_no (lew_licence_no)");
            log.info("Migration [lew-licence-unique]: added UNIQUE index uk_users_lew_licence_no");
        }
    }

    private void backfillDeletedUserEmails(Connection conn) throws SQLException {
        if (!columnExists(conn, "users", "deleted_at")) {
            log.debug("Migration [backfill-deleted-emails]: users.deleted_at not present, skipping");
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(
                "UPDATE users " +
                "SET email = CONCAT('deleted-', user_seq, '@deleted.licensekaki.sg') " +
                "WHERE deleted_at IS NOT NULL " +
                "  AND email NOT LIKE 'deleted-%@deleted.licensekaki.sg'"
            );
            if (updated > 0) {
                log.info("Migration [backfill-deleted-emails]: anonymized {} legacy soft-deleted emails", updated);
            } else {
                log.debug("Migration [backfill-deleted-emails]: no legacy rows to backfill");
            }
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
     * 마이그레이션: 결제 후 kVA 사후 변경 (PR-1).
     * <p>
     * invoices 테이블 변경:
     * <ul>
     *   <li>status / invalidated_reason / invalidated_at 컬럼 추가 (멱등).</li>
     *   <li>uk_invoices_payment UNIQUE 제거 + idx_invoices_payment 일반 인덱스 추가
     *       — INVALIDATED 후 같은 payment_seq 의 신규 영수증 발행 허용.</li>
     * </ul>
     * 스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §10 D3.
     */
    private void migrateInvoicesStatusColumns(Connection conn) throws SQLException {
        if (!tableExists(conn, "invoices")) {
            log.debug("Migration [invoices-status-columns]: table missing, skipping");
            return;
        }

        // 1) status 컬럼 추가 (멱등)
        if (!columnExists(conn, "invoices", "status")) {
            log.info("Migration [invoices-status-columns]: adding status column...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE invoices ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER pdf_file_seq"
                );
                stmt.executeUpdate(
                    "ALTER TABLE invoices ADD COLUMN invalidated_reason VARCHAR(200) NULL AFTER status"
                );
                stmt.executeUpdate(
                    "ALTER TABLE invoices ADD COLUMN invalidated_at DATETIME(6) NULL AFTER invalidated_reason"
                );
                log.info("Migration [invoices-status-columns]: added status/invalidated_reason/invalidated_at");
            }
        }

        // 2) uk_invoices_payment UNIQUE 제거 + idx_invoices_payment 추가 (멱등)
        // ★ FK fk_invoices_payment 가 payment_seq 인덱스에 의존하므로, UNIQUE 를 바로 DROP 하면
        //   "needed in a foreign key constraint" 에러. 대체 일반 인덱스를 먼저 생성한 뒤 UNIQUE 만 DROP.
        if (indexExists(conn, "invoices", "uk_invoices_payment")) {
            log.info("Migration [invoices-status-columns]: replacing uk_invoices_payment UNIQUE with regular indexes...");
            try (Statement stmt = conn.createStatement()) {
                if (!indexExists(conn, "invoices", "idx_invoices_payment")) {
                    stmt.executeUpdate("ALTER TABLE invoices ADD INDEX idx_invoices_payment (payment_seq)");
                }
                if (!indexExists(conn, "invoices", "idx_invoices_payment_status")) {
                    stmt.executeUpdate(
                        "ALTER TABLE invoices ADD INDEX idx_invoices_payment_status (payment_seq, status)"
                    );
                }
                if (!indexExists(conn, "invoices", "idx_invoices_application_status")) {
                    stmt.executeUpdate(
                        "ALTER TABLE invoices ADD INDEX idx_invoices_application_status (application_seq, status)"
                    );
                }
                // FK 가 의존하는 인덱스가 만들어진 다음에야 UNIQUE 를 안전하게 제거할 수 있다.
                stmt.executeUpdate("ALTER TABLE invoices DROP INDEX uk_invoices_payment");
                log.info("Migration [invoices-status-columns]: UNIQUE replaced with regular indexes");
            }
        }
    }

    /**
     * 마이그레이션: 결제 후 kVA 사후 변경 (PR-4).
     * <p>
     * kva_adjustment_record 테이블에 settled_at 컬럼을 멱등 추가한다.
     * <ul>
     *   <li>PR-1~3 시점에는 schema.sql 신규 생성 + JPA 엔티티에서 settled_at 미정의.</li>
     *   <li>PR-4 에서 settlement 마킹 엔드포인트({@code PATCH .../settlement}) 도입과 함께 추가.</li>
     *   <li>이미 PR-1~3 으로 운영 중인 DB 에 본 컬럼이 누락되어 있을 수 있으므로 idempotent ALTER 보강.</li>
     * </ul>
     * 스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.3 / PR-4.
     */
    private void migrateKvaAdjustmentRecordSettledAt(Connection conn) throws SQLException {
        if (!tableExists(conn, "kva_adjustment_record")) {
            log.debug("Migration [kva-adj-settled-at]: table missing, skipping");
            return;
        }
        if (columnExists(conn, "kva_adjustment_record", "settled_at")) {
            return;
        }
        log.info("Migration [kva-adj-settled-at]: adding settled_at column...");
        try (Statement stmt = conn.createStatement()) {
            // admin_adjustment_at 다음에 두어 정산 관련 컬럼이 시간순으로 인접.
            // 기존 행은 NULL — settlement 가 아직 마킹되지 않은 상태로 자연스럽게 동작.
            stmt.executeUpdate(
                "ALTER TABLE kva_adjustment_record "
                + "ADD COLUMN settled_at DATETIME(6) NULL AFTER admin_adjustment_at"
            );
            log.info("Migration [kva-adj-settled-at]: added settled_at column");
        }
    }

    /**
     * ADMIN Manual Email Dispatch 테이블 idempotent 생성.
     *
     * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §13.1.</p>
     *
     * <p>{@code syncCreateTablesFromSchemaSql} 가 schema.sql 의 모든 CREATE TABLE IF NOT EXISTS 를
     * 자동 실행하므로, 본 메서드는 사실상 중복이지만 다음 두 이유로 명시한다:
     * <ol>
     *   <li>코드 리뷰에서 신규 테이블 도입의 의도가 분명히 드러난다 (kVA PR-1 패턴 동일).</li>
     *   <li>schema.sql 파싱이 어떤 이유로 실패했을 때(예: 주석 형태 변경)의 fallback.</li>
     * </ol></p>
     */
    private void migrateManualEmailDispatchesTable(Connection conn) throws SQLException {
        if (tableExists(conn, "manual_email_dispatches")) {
            log.debug("Migration [manual-email-dispatches]: table exists, skipping");
            return;
        }
        log.info("Migration [manual-email-dispatches]: creating table...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS manual_email_dispatches (" +
                "  dispatch_seq             BIGINT         NOT NULL AUTO_INCREMENT," +
                "  sender_user_seq          BIGINT         NOT NULL," +
                "  recipient_type           VARCHAR(20)    NOT NULL," +
                "  recipient_user_seq       BIGINT         NULL," +
                "  recipient_email          VARCHAR(254)   NOT NULL," +
                // PR-2: MULTI 컬럼 + 멱등성 해시 — 새 DB 도 즉시 보유하도록 본 CREATE 에 포함.
                "  recipient_user_seqs_json TEXT           NULL," +
                "  recipient_emails_json    TEXT           NULL," +
                "  recipient_hash           VARCHAR(64)    NULL," +
                "  related_application_seq  BIGINT         NULL," +
                "  subject                  VARCHAR(200)   NOT NULL," +
                "  body_text                TEXT           NOT NULL," +
                "  body_format              VARCHAR(20)    NOT NULL DEFAULT 'PLAIN_TEXT'," +
                "  category_tag             VARCHAR(50)    NULL," +
                "  dispatch_status          VARCHAR(20)    NOT NULL," +
                "  sent_count               INT            NOT NULL DEFAULT 0," +
                "  failed_count             INT            NOT NULL DEFAULT 0," +
                "  failed_reason            TEXT           NULL," +
                "  dispatched_at            DATETIME(6)    NULL," +
                // PR-4: 인앱 알림 동반 옵션 (D4=B). 기본 ON.
                "  also_create_in_app_notification TINYINT(1) NOT NULL DEFAULT 1," +
                "  created_at               DATETIME(6)," +
                "  updated_at               DATETIME(6)," +
                "  created_by               BIGINT," +
                "  updated_by               BIGINT," +
                "  deleted_at               DATETIME(6)," +
                "  PRIMARY KEY (dispatch_seq)," +
                "  KEY idx_manual_email_sender (sender_user_seq, dispatched_at DESC)," +
                "  KEY idx_manual_email_dispatched (dispatched_at DESC)," +
                "  KEY idx_manual_email_status (dispatch_status, dispatched_at DESC)," +
                "  KEY idx_manual_email_application (related_application_seq)," +
                "  KEY idx_manual_email_recipient_hash (sender_user_seq, recipient_hash, created_at DESC)," +
                "  CONSTRAINT fk_manual_email_sender FOREIGN KEY (sender_user_seq) REFERENCES users (user_seq)," +
                "  CONSTRAINT fk_manual_email_recipient_user FOREIGN KEY (recipient_user_seq) REFERENCES users (user_seq)," +
                "  CONSTRAINT fk_manual_email_application FOREIGN KEY (related_application_seq) REFERENCES applications (application_seq)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            );
            log.info("Migration [manual-email-dispatches]: table created");
        }
    }

    /**
     * 마이그레이션: ADMIN Manual Email Dispatch — PR-2 MULTI 컬럼 + 멱등성 해시.
     *
     * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §4 / PR-2.</p>
     *
     * <ul>
     *   <li>{@code recipient_user_seqs_json} TEXT NULL — MULTI 시 시스템 사용자 user_seq 목록 (JSON).</li>
     *   <li>{@code recipient_emails_json} TEXT NULL — MULTI 시 이메일 목록 (JSON).</li>
     *   <li>{@code recipient_hash} VARCHAR(64) NULL — 정렬된 수신자 + subject + body 의 SHA-256 hex.</li>
     *   <li>{@code idx_manual_email_recipient_hash} 인덱스 — 멱등성 lookup 가속.</li>
     * </ul>
     *
     * <p>PR-1 운영 DB 에 본 컬럼들이 누락되어 있을 수 있으므로 idempotent ALTER. 기존 PR-1 row 들은
     * 단일 수신자 기반 backfill 로 {@code recipient_hash} 를 채워둔다 — MySQL SHA2 함수 + 정규화된
     * 입력으로 Java 측 {@link com.bluelight.backend.api.admin.manualemail.ManualEmailRecipientHasher}
     * 와 동일한 해시를 산출. (동일성 보장: 단일 수신자라 정렬 불필요, 소문자 + trim 만 일치하면 OK.)</p>
     */
    private void migrateManualEmailRecipientLists(Connection conn) throws SQLException {
        if (!tableExists(conn, "manual_email_dispatches")) {
            log.debug("Migration [manual-email-pr2]: table missing, skipping (will be created in schema.sql)");
            return;
        }

        boolean userSeqsJsonExists = columnExists(conn, "manual_email_dispatches", "recipient_user_seqs_json");
        boolean emailsJsonExists = columnExists(conn, "manual_email_dispatches", "recipient_emails_json");
        boolean hashExists = columnExists(conn, "manual_email_dispatches", "recipient_hash");

        if (!userSeqsJsonExists) {
            log.info("Migration [manual-email-pr2]: adding recipient_user_seqs_json column...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE manual_email_dispatches "
                    + "ADD COLUMN recipient_user_seqs_json TEXT NULL AFTER recipient_email"
                );
            }
        }
        if (!emailsJsonExists) {
            log.info("Migration [manual-email-pr2]: adding recipient_emails_json column...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE manual_email_dispatches "
                    + "ADD COLUMN recipient_emails_json TEXT NULL AFTER recipient_user_seqs_json"
                );
            }
        }
        if (!hashExists) {
            log.info("Migration [manual-email-pr2]: adding recipient_hash column...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE manual_email_dispatches "
                    + "ADD COLUMN recipient_hash VARCHAR(64) NULL AFTER recipient_emails_json"
                );
            }
        }

        // 멱등성 lookup 인덱스 — 컬럼 추가 후에 별도 가드.
        if (!indexExists(conn, "manual_email_dispatches", "idx_manual_email_recipient_hash")) {
            log.info("Migration [manual-email-pr2]: adding idx_manual_email_recipient_hash...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE manual_email_dispatches "
                    + "ADD INDEX idx_manual_email_recipient_hash (sender_user_seq, recipient_hash, created_at DESC)"
                );
            }
        }

        // PR-1 row backfill — recipient_hash 가 NULL 인 row 만 단일 수신자 기반으로 채운다.
        // Java 의 ManualEmailRecipientHasher 와 동일한 입력 정규화를 SQL 로 재현:
        //   - 단일 수신자: LOWER(TRIM(recipient_email))
        //   - "" (Unit Separator) 구분자: CONCAT(recipients, CHAR(31), subject, CHAR(31), body_text)
        //   - SHA2(..., 256) → 64자 hex (소문자)
        // 단일 수신자라 정렬 불필요 (해시 입력에 단일 항목만 등장).
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(
                "UPDATE manual_email_dispatches "
                + "SET recipient_hash = LOWER(SHA2("
                + "  CONCAT(LOWER(TRIM(recipient_email)), CHAR(31), "
                + "         IFNULL(subject, ''), CHAR(31), "
                + "         IFNULL(body_text, ''))"
                + ", 256)) "
                + "WHERE recipient_hash IS NULL"
            );
            if (updated > 0) {
                log.info("Migration [manual-email-pr2]: backfilled recipient_hash for {} rows", updated);
            }
        }
    }

    /**
     * 마이그레이션: ADMIN Manual Email Dispatch — PR-4 인앱 동반 옵션 컬럼 추가.
     *
     * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8.5 / D4=B.</p>
     *
     * <ul>
     *   <li>{@code also_create_in_app_notification} TINYINT(1) NOT NULL DEFAULT 1 —
     *       시스템 사용자 수신자에게 인앱 알림 동반 생성 여부. 기존 row 는 default 1
     *       (true) 로 backfill — PR-1/2/3 동작 변경 없이 인앱 옵션이 뒤늦게 ON 된 형태.</li>
     * </ul>
     *
     * <p>idempotent — 컬럼 존재 시 스킵. 기존 PR-1/2/3 row 의 값은 default 1 이 그대로 들어가며,
     * AFTER_COMMIT 리스너는 row 가 보관된 후 새로 처리되는 발송에만 알림을 보낸다 (이미 처리된
     * 과거 row 는 listener 가 다시 발화하지 않음 — DB row 단순 backfill 만 영향).</p>
     */
    private void migrateManualEmailInAppOptionColumn(Connection conn) throws SQLException {
        if (!tableExists(conn, "manual_email_dispatches")) {
            log.debug("Migration [manual-email-pr4]: table missing, skipping (will be created in schema.sql)");
            return;
        }
        if (columnExists(conn, "manual_email_dispatches", "also_create_in_app_notification")) {
            log.debug("Migration [manual-email-pr4]: also_create_in_app_notification column exists, skipping");
            return;
        }
        log.info("Migration [manual-email-pr4]: adding also_create_in_app_notification column...");
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE manual_email_dispatches "
                + "ADD COLUMN also_create_in_app_notification TINYINT(1) NOT NULL DEFAULT 1 "
                + "AFTER dispatched_at"
            );
        }
        log.info("Migration [manual-email-pr4]: column added (default 1 backfilled)");
    }

    /**
     * ★ Concierge 강화 + 별도 수금 PR-1 (D1=B 다중 역할 정규화).
     * <p>
     * {@code user_roles} 테이블 생성 + 기존 {@code users.role} 백필.
     * <ul>
     *   <li>테이블이 없으면 CREATE (schema.sql 의 CREATE TABLE IF NOT EXISTS 와 동일).</li>
     *   <li>users 테이블의 모든 active row(role 컬럼)에 대해 user_roles 에 INSERT IGNORE
     *       — 동일 row 가 이미 있으면 무시 (PK 가 (user_seq, role) 이므로 충돌 없음).</li>
     *   <li>soft-deleted 사용자도 백필 대상 — soft delete 는 조회 필터일 뿐 실제 row 는 보존.
     *       따라서 그들의 primary role 도 user_roles 에 들어간다.</li>
     * </ul>
     * idempotent: 여러 번 실행해도 INSERT IGNORE 가 중복을 무시한다.
     */
    private void migrateUserRolesTable(Connection conn) throws SQLException {
        // 1) 테이블 생성 (멱등). syncCreateTablesFromSchemaSql 가 이미 처리하지만
        //    명시적으로 한 번 더 — 코드 리뷰 시 의도 가시성 + schema.sql 파싱 fallback.
        if (!tableExists(conn, "user_roles")) {
            log.info("Migration [user-roles]: creating table...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS user_roles (" +
                    "  user_seq BIGINT       NOT NULL," +
                    "  role     VARCHAR(40)  NOT NULL," +
                    "  PRIMARY KEY (user_seq, role)," +
                    "  KEY idx_user_roles_user (user_seq)," +
                    "  CONSTRAINT fk_user_roles_user FOREIGN KEY (user_seq) " +
                    "    REFERENCES users (user_seq) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                );
            }
            log.info("Migration [user-roles]: table created");
        }

        // 2) 백필: 기존 users.role row 를 user_roles 에 1건씩 복제.
        //    INSERT IGNORE 로 PK 충돌 무시 (재실행 시 중복 추가 방지).
        try (Statement stmt = conn.createStatement()) {
            int inserted = stmt.executeUpdate(
                "INSERT IGNORE INTO user_roles (user_seq, role) " +
                "SELECT user_seq, role FROM users WHERE role IS NOT NULL"
            );
            if (inserted > 0) {
                log.info("Migration [user-roles]: backfilled {} primary role rows", inserted);
            } else {
                log.debug("Migration [user-roles]: no rows to backfill (already in sync)");
            }
        }
    }

    /**
     * ★ Concierge 강화 + 별도 수금 PR-1 (D2=B PaymentMethod enum + offline 기록 컬럼).
     * <p>
     * 변경 요약:
     * <ul>
     *   <li>{@code payment_method} VARCHAR(20) → VARCHAR(40), 기본값 'CARD' → 'PAYNOW_ONLINE',
     *       NOT NULL 강화. 기존 'CARD' row 는 'PAYNOW_ONLINE' 으로 갱신.</li>
     *   <li>{@code recorded_by_user_seq} BIGINT NULL — offline 기록자 user_seq.</li>
     *   <li>{@code recorded_at} DATETIME(6) NULL — 기록 시점.</li>
     * </ul>
     * idempotent: 컬럼 폭/기본값/NOT NULL 모두 멱등 ALTER. 백필 UPDATE 는 'CARD' row 만 영향.
     */
    private void migratePaymentsMethodColumns(Connection conn) throws SQLException {
        if (!tableExists(conn, "payments")) {
            log.debug("Migration [payments-method]: payments table missing, skipping");
            return;
        }

        // 1) payment_method 컬럼 폭/기본값/NOT NULL 정정. 컬럼 자체는 PR#7 이전부터 존재.
        //    MySQL 은 같은 정의로 MODIFY 호출해도 안전 (no-op). 따라서 무조건 1회 적용해도 멱등.
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "ALTER TABLE payments " +
                "MODIFY COLUMN payment_method VARCHAR(40) NOT NULL DEFAULT 'PAYNOW_ONLINE'"
            );
            log.info("Migration [payments-method]: payment_method column normalized to VARCHAR(40) NOT NULL DEFAULT 'PAYNOW_ONLINE'");
        }

        // 2) 백필: 'CARD' / NULL row 를 PAYNOW_ONLINE 으로 갱신.
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(
                "UPDATE payments SET payment_method = 'PAYNOW_ONLINE' " +
                "WHERE payment_method IS NULL OR payment_method = 'CARD'"
            );
            if (updated > 0) {
                log.info("Migration [payments-method]: backfilled {} legacy rows ('CARD'/NULL → 'PAYNOW_ONLINE')", updated);
            }
        }

        // 3) recorded_by_user_seq, recorded_at 컬럼 추가 (멱등).
        if (!columnExists(conn, "payments", "recorded_by_user_seq")) {
            log.info("Migration [payments-method]: adding recorded_by_user_seq column...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE payments ADD COLUMN recorded_by_user_seq BIGINT NULL AFTER paid_at"
                );
            }
        }
        if (!columnExists(conn, "payments", "recorded_at")) {
            log.info("Migration [payments-method]: adding recorded_at column...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE payments ADD COLUMN recorded_at DATETIME(6) NULL AFTER recorded_by_user_seq"
                );
            }
        }
    }

    /**
     * ★ Concierge 강화 + 별도 수금 PR-1 (D6=A 셀프 할당) — concierge_requests LEW 배정 컬럼.
     * <p>
     * 추가 컬럼:
     * <ul>
     *   <li>{@code assigned_lew_seq} BIGINT NULL — 배정된 LEW user_seq.</li>
     *   <li>{@code lew_assigned_at} DATETIME(6) NULL — 배정 시점.</li>
     * </ul>
     * 인덱스: {@code idx_concierge_assigned_lew (assigned_lew_seq)}.
     */
    private void migrateConciergeRequestsLewAssignment(Connection conn) throws SQLException {
        if (!tableExists(conn, "concierge_requests")) {
            log.debug("Migration [concierge-lew-assignment]: concierge_requests table missing, skipping");
            return;
        }

        if (!columnExists(conn, "concierge_requests", "assigned_lew_seq")) {
            log.info("Migration [concierge-lew-assignment]: adding assigned_lew_seq column...");
            try (Statement stmt = conn.createStatement()) {
                // verification_phrase 다음에 배치 — 기존 PR#1.5 컬럼들과 시간순 인접.
                stmt.executeUpdate(
                    "ALTER TABLE concierge_requests ADD COLUMN assigned_lew_seq BIGINT NULL AFTER verification_phrase"
                );
            }
        }
        if (!columnExists(conn, "concierge_requests", "lew_assigned_at")) {
            log.info("Migration [concierge-lew-assignment]: adding lew_assigned_at column...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE concierge_requests ADD COLUMN lew_assigned_at DATETIME(6) NULL AFTER assigned_lew_seq"
                );
            }
        }
        if (!indexExists(conn, "concierge_requests", "idx_concierge_assigned_lew")) {
            log.info("Migration [concierge-lew-assignment]: adding idx_concierge_assigned_lew index...");
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                    "ALTER TABLE concierge_requests ADD INDEX idx_concierge_assigned_lew (assigned_lew_seq)"
                );
            }
        }
    }

    /**
     * 특정 테이블에 특정 이름의 인덱스가 존재하는지 확인 (멱등 마이그레이션 가드용).
     */
    private boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.STATISTICS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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

    /**
     * 실제 코드에 배선된 알림 템플릿을 멱등 활성화한다.
     * 현재: A-17(Payment requested) — LEW/ADMIN 결제 요청이 오케스트레이터로 발송하므로 EMAIL/IN_APP 필요.
     * (E2/E3 등 추가 배선 시 여기에 코드 추가.)
     */
    private void enableWiredNotificationTemplates(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            if (!tableExists(conn, "notification_templates")) {
                return;
            }
            int n = stmt.executeUpdate(
                    "UPDATE notification_templates SET enabled = TRUE " +
                    "WHERE template_code = 'A-17' AND channel IN ('EMAIL','IN_APP') AND enabled = FALSE");
            if (n > 0) {
                log.info("Migration: enabled {} wired notification template rows (A-17)", n);
            }
        } catch (SQLException e) {
            log.warn("enableWiredNotificationTemplates skipped: {}", e.getMessage());
        }
    }

    /**
     * 결제 신호 ADMIN 알림 템플릿(A-55 증빙업로드 / A-56 확인요청)을 멱등 시드 + 활성화.
     * 기존 DB(SQL_INIT_MODE=never)엔 data.sql 미적용이라 INSERT...WHERE NOT EXISTS 로 주입.
     */
    private void seedPaymentSignalNotificationTemplates(Connection conn) {
        if (!tableExistsSafe(conn)) {
            return;
        }
        // {code, channel, subject, body}
        String[][] rows = {
            {"A-55", "EMAIL",
                "[LicenseKaki] Payment evidence uploaded · #{{publicCode}}",
                "<div style=\"font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px\"><div style=\"border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px\"><span style=\"font-size:18px;font-weight:700;color:#0F766E\">LicenseKaki</span><br><span style=\"font-size:12px;color:#888\">Admin notification</span></div><h1 style=\"font-size:18px;margin:0 0 16px\">Payment evidence uploaded</h1><p style=\"margin:0 0 16px\">Applicant <strong>{{applicantName}}</strong> uploaded payment evidence for application <strong>#{{publicCode}}</strong> (SGD {{amount}}). Please review and confirm the payment.</p><p style=\"margin:24px 0\"><a href=\"{{ctaUrl}}\" style=\"display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600\">Open application</a></p><hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0\"><p style=\"margin:0;font-size:12px;color:#888\">LicenseKaki internal admin notification.</p></div>"},
            {"A-55", "IN_APP",
                "Payment evidence uploaded on #{{publicCode}}",
                "{{applicantName}} uploaded payment evidence (SGD {{amount}}). Review and confirm."},
            {"A-56", "EMAIL",
                "[LicenseKaki] Payment confirmation requested · #{{publicCode}}",
                "<div style=\"font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px\"><div style=\"border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px\"><span style=\"font-size:18px;font-weight:700;color:#0F766E\">LicenseKaki</span><br><span style=\"font-size:12px;color:#888\">Admin notification</span></div><h1 style=\"font-size:18px;margin:0 0 16px\">Payment confirmation requested</h1><p style=\"margin:0 0 16px\">Applicant <strong>{{applicantName}}</strong> indicated they have completed payment for application <strong>#{{publicCode}}</strong> (SGD {{amount}}) and is requesting confirmation. Please verify and confirm the payment.</p><p style=\"margin:24px 0\"><a href=\"{{ctaUrl}}\" style=\"display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600\">Open application</a></p><hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0\"><p style=\"margin:0;font-size:12px;color:#888\">LicenseKaki internal admin notification.</p></div>"},
            {"A-56", "IN_APP",
                "Applicant requested payment confirmation on #{{publicCode}}",
                "{{applicantName}} says payment is done (SGD {{amount}}). Verify and confirm."},
        };
        final String variablesJson = "[\"applicantName\",\"publicCode\",\"amount\",\"ctaUrl\"]";

        String insertSql =
            "INSERT INTO notification_templates " +
            "(template_code, channel, locale, provider_template_name, subject, body_text, " +
            " variables_json, catalog_meta_key, category, severity, recipient_roles, enabled, " +
            " created_at, updated_at) " +
            "SELECT ?, ?, 'en', NULL, ?, ?, ?, ?, 'PAYMENT', 'IMPORTANT', 'ADMIN', TRUE, NOW(6), NOW(6) " +
            "FROM DUAL WHERE NOT EXISTS (" +
            "  SELECT 1 FROM notification_templates t " +
            "  WHERE t.template_code = ? AND t.channel = ? AND t.locale = 'en')";
        String enableSql =
            "UPDATE notification_templates SET enabled = TRUE, deleted_at = NULL, updated_at = NOW(6) " +
            "WHERE template_code = ? AND channel = ? AND locale = 'en' AND (enabled = FALSE OR deleted_at IS NOT NULL)";

        int inserted = 0;
        try (PreparedStatement insertPs = conn.prepareStatement(insertSql);
             PreparedStatement enablePs = conn.prepareStatement(enableSql)) {
            for (String[] r : rows) {
                String code = r[0], channel = r[1], subject = r[2], body = r[3];
                try {
                    insertPs.setString(1, code);
                    insertPs.setString(2, channel);
                    insertPs.setString(3, subject);
                    insertPs.setString(4, body);
                    insertPs.setString(5, variablesJson);
                    insertPs.setString(6, code);
                    insertPs.setString(7, code);
                    insertPs.setString(8, channel);
                    inserted += insertPs.executeUpdate();
                    enablePs.setString(1, code);
                    enablePs.setString(2, channel);
                    enablePs.executeUpdate();
                } catch (SQLException e) {
                    log.warn("seedPaymentSignalNotificationTemplates {} {} warn: {}", code, channel, e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("seedPaymentSignalNotificationTemplates aborted: {}", e.getMessage());
            return;
        }
        if (inserted > 0) {
            log.info("Migration: seeded {} payment-signal template rows (A-55/A-56)", inserted);
        }
    }

    /**
     * SLD 전환 추가요금 알림 템플릿을 멱등 시드+활성.
     * (sld-lew-conversion-fee-spec.md §11) A-58: 신청자 통보(APPLICANT), A-59: ADMIN 정산요청(ADMIN). EMAIL+IN_APP.
     */
    private void seedSldConversionNotificationTemplates(Connection conn) {
        if (!tableExistsSafe(conn)) {
            return;
        }
        String a58Email = "<div style=\"font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px\">"
                + "<div style=\"border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px\"><span style=\"font-size:18px;font-weight:700;color:#0F766E\">LicenseKaki</span></div>"
                + "<h1 style=\"font-size:18px;margin:0 0 16px\">Your LEW will prepare the SLD</h1>"
                + "<p style=\"margin:0 0 16px\">Hi {{applicantName}}, for application <strong>#{{publicCode}}</strong> your LEW will prepare the Single Line Diagram. "
                + "An additional SLD fee of <strong>SGD {{sldFee}}</strong> applies and will be collected separately.</p>"
                + "<p style=\"margin:24px 0\"><a href=\"{{ctaUrl}}\" style=\"display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600\">Open application</a></p>"
                + "<hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0\"><p style=\"margin:0;font-size:12px;color:#888\">LicenseKaki</p></div>";
        String a59Email = "<div style=\"font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px\">"
                + "<div style=\"border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px\"><span style=\"font-size:18px;font-weight:700;color:#0F766E\">LicenseKaki</span><br><span style=\"font-size:12px;color:#888\">Admin notification</span></div>"
                + "<h1 style=\"font-size:18px;margin:0 0 16px\">SLD fee pending settlement</h1>"
                + "<p style=\"margin:0 0 16px\">Application <strong>#{{publicCode}}</strong> ({{applicantName}}) switched to LEW-created SLD after payment. "
                + "An additional SLD fee of <strong>SGD {{sldFee}}</strong> must be collected and settled.</p>"
                + "<p style=\"margin:24px 0\"><a href=\"{{ctaUrl}}\" style=\"display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600\">Open application</a></p>"
                + "<hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0\"><p style=\"margin:0;font-size:12px;color:#888\">LicenseKaki internal admin notification.</p></div>";
        // {code, channel, subject, body, recipientRoles}
        String[][] rows = {
            {"A-58", "EMAIL", "[LicenseKaki] SLD will be prepared by your LEW · #{{publicCode}}", a58Email, "APPLICANT"},
            {"A-58", "IN_APP", "SLD fee added on #{{publicCode}}",
                "Your LEW will prepare the SLD. Additional SLD fee SGD {{sldFee}} applies.", "APPLICANT"},
            {"A-59", "EMAIL", "[LicenseKaki] SLD fee pending settlement · #{{publicCode}}", a59Email, "ADMIN"},
            {"A-59", "IN_APP", "SLD fee pending settlement on #{{publicCode}}",
                "{{applicantName}} switched to LEW-created SLD. Collect SGD {{sldFee}} and settle.", "ADMIN"},
        };
        final String variablesJson = "[\"applicantName\",\"publicCode\",\"sldFee\",\"ctaUrl\"]";
        String insertSql =
            "INSERT INTO notification_templates " +
            "(template_code, channel, locale, provider_template_name, subject, body_text, " +
            " variables_json, catalog_meta_key, category, severity, recipient_roles, enabled, " +
            " created_at, updated_at) " +
            "SELECT ?, ?, 'en', NULL, ?, ?, ?, ?, 'PAYMENT', 'IMPORTANT', ?, TRUE, NOW(6), NOW(6) " +
            "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM notification_templates t " +
            "  WHERE t.template_code = ? AND t.channel = ? AND t.locale = 'en')";
        String enableSql =
            "UPDATE notification_templates SET enabled = TRUE, deleted_at = NULL, updated_at = NOW(6) " +
            "WHERE template_code = ? AND channel = ? AND locale = 'en' AND (enabled = FALSE OR deleted_at IS NOT NULL)";
        int inserted = 0;
        try (PreparedStatement insertPs = conn.prepareStatement(insertSql);
             PreparedStatement enablePs = conn.prepareStatement(enableSql)) {
            for (String[] r : rows) {
                try {
                    insertPs.setString(1, r[0]); insertPs.setString(2, r[1]);
                    insertPs.setString(3, r[2]); insertPs.setString(4, r[3]);
                    insertPs.setString(5, variablesJson); insertPs.setString(6, r[0]);
                    insertPs.setString(7, r[4]);
                    insertPs.setString(8, r[0]); insertPs.setString(9, r[1]);
                    inserted += insertPs.executeUpdate();
                    enablePs.setString(1, r[0]); enablePs.setString(2, r[1]);
                    enablePs.executeUpdate();
                } catch (SQLException e) {
                    log.warn("seedSldConversionNotificationTemplates {} {} warn: {}", r[0], r[1], e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("seedSldConversionNotificationTemplates aborted: {}", e.getMessage());
            return;
        }
        if (inserted > 0) {
            log.info("Migration: seeded {} SLD-conversion template rows (A-58/A-59)", inserted);
        }
    }

    /**
     * LoA 폼 전달 → 신청자 알림 템플릿(A-57, EMAIL+IN_APP, recipient APPLICANT)을 멱등 시드+활성.
     * send-form 하드코딩 직접발송을 오케스트레이터로 전환하며 도입.
     */
    private void seedLoaFormSentNotificationTemplate(Connection conn) {
        if (!tableExistsSafe(conn)) {
            return;
        }
        String emailBody =
            "<div style=\"font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px\">"
            + "<div style=\"border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px\"><span style=\"font-size:18px;font-weight:700;color:#0F766E\">LicenseKaki</span><br>"
            + "<span style=\"font-size:12px;color:#888\">Singapore Electrical Installation Licence Platform</span></div>"
            + "<h1 style=\"font-size:20px;margin:0 0 16px\">Your LoA form is ready</h1>"
            + "<p style=\"margin:0 0 16px\">Hello {{applicantName}},</p>"
            + "<p style=\"margin:0 0 16px\">Your assigned Licensed Electrical Worker has shared the Letter of Appointment (LoA) form for application <strong>#{{publicCode}}</strong>. "
            + "Please download the form, sign it offline, and upload the signed copy on your application page.</p>"
            + "<p style=\"margin:24px 0\"><a href=\"{{ctaUrl}}\" style=\"display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600\">Open application</a></p>"
            + "<hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0\">"
            + "<p style=\"margin:0;font-size:12px;color:#888\">Anti-phishing: our only sender domain is @licensekaki.sg. We never ask for your password, OTP, or PayNow PIN by email.</p></div>";
        String[][] rows = {
            {"A-57", "EMAIL", "[LicenseKaki] Your LoA form is ready · #{{publicCode}}", emailBody},
            {"A-57", "IN_APP", "LoA form ready on #{{publicCode}}",
                "Your LEW shared the LoA form. Download, sign offline, and upload the signed copy."},
        };
        final String variablesJson = "[\"applicantName\",\"publicCode\",\"ctaUrl\"]";
        String insertSql =
            "INSERT INTO notification_templates " +
            "(template_code, channel, locale, provider_template_name, subject, body_text, " +
            " variables_json, catalog_meta_key, category, severity, recipient_roles, enabled, " +
            " created_at, updated_at) " +
            "SELECT ?, ?, 'en', NULL, ?, ?, ?, ?, 'STATUS', 'IMPORTANT', 'APPLICANT', TRUE, NOW(6), NOW(6) " +
            "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM notification_templates t " +
            "  WHERE t.template_code = ? AND t.channel = ? AND t.locale = 'en')";
        String enableSql =
            "UPDATE notification_templates SET enabled = TRUE, deleted_at = NULL, updated_at = NOW(6) " +
            "WHERE template_code = ? AND channel = ? AND locale = 'en' AND (enabled = FALSE OR deleted_at IS NOT NULL)";
        int inserted = 0;
        try (PreparedStatement insertPs = conn.prepareStatement(insertSql);
             PreparedStatement enablePs = conn.prepareStatement(enableSql)) {
            for (String[] r : rows) {
                try {
                    insertPs.setString(1, r[0]); insertPs.setString(2, r[1]);
                    insertPs.setString(3, r[2]); insertPs.setString(4, r[3]);
                    insertPs.setString(5, variablesJson); insertPs.setString(6, r[0]);
                    insertPs.setString(7, r[0]); insertPs.setString(8, r[1]);
                    inserted += insertPs.executeUpdate();
                    enablePs.setString(1, r[0]); enablePs.setString(2, r[1]);
                    enablePs.executeUpdate();
                } catch (SQLException e) {
                    log.warn("seedLoaFormSentNotificationTemplate {} {} warn: {}", r[0], r[1], e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("seedLoaFormSentNotificationTemplate aborted: {}", e.getMessage());
            return;
        }
        if (inserted > 0) {
            log.info("Migration: seeded {} LoA-form-sent template rows (A-57)", inserted);
        }
    }

    /**
     * 신청자 결제 알림 템플릿(A-17 결제 요청 / A-20 결제 확인)을 EMAIL+IN_APP 멱등 시드+활성.
     *
     * <p>이 본문은 그동안 {@code data.sql} 에만 존재했는데, 운영/개발 RDS 는
     * {@code SQL_INIT_MODE=never} 라 data.sql 이 적용되지 않는다. 따라서 결제 요청(A-17)
     * 이메일이 {@code TEMPLATE_NOT_FOUND} 로 영구 실패할 수 있었다(인앱은 별도 경로).
     * A-55/56/57 과 동일하게 {@code INSERT ... WHERE NOT EXISTS} 로 누락 행만 주입하므로
     * 관리자가 편집한 기존 행은 보존한다. EMAIL CTA 의 상대경로 ctaUrl 은
     * {@code EmailChannelAdapter} 가 절대 URL 로 변환한다.</p>
     */
    private void seedApplicantPaymentNotificationTemplates(Connection conn) {
        if (!tableExistsSafe(conn)) {
            return;
        }
        final String a17Email =
            "<div style=\"font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px\">"
            + "<div style=\"border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px\"><span style=\"font-size:18px;font-weight:700;color:#0F766E\">LicenseKaki</span><br>"
            + "<span style=\"font-size:12px;color:#888\">Singapore Electrical Installation Licence Platform</span></div>"
            + "<h1 style=\"font-size:20px;margin:0 0 16px\">Your application is approved. Please complete payment to start work.</h1>"
            + "<p style=\"margin:0 0 16px\">Hello {{applicantName}},</p>"
            + "<p style=\"margin:0 0 16px\">Good news — your Licensed Electrical Worker has confirmed the scope of work for application <strong>#{{publicCode}}</strong> ({{kvaLabel}}). To begin the work, please settle the payment below by <strong>{{deadline}}</strong>.</p>"
            + "<p style=\"margin:0 0 16px\"><strong>Amount due</strong>: SGD {{amount}}<br><strong>PayNow UEN</strong>: {{paynowUen}}<br><strong>Payee name</strong>: {{paynowAccountName}}<br><strong>Reference (must include)</strong>: {{paynowReference}}</p>"
            + "<p style=\"margin:0 0 16px\">Including the reference code lets us match your payment automatically — usually within 1 business hour.</p>"
            + "<p style=\"margin:24px 0\"><a href=\"{{ctaUrl}}\" style=\"display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600\">Pay via PayNow</a></p>"
            + "<hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0\">"
            + "<p style=\"margin:0;font-size:12px;color:#888\">This is a transactional email from LicenseKaki. You are receiving it because your application is awaiting payment.</p>"
            + "<p style=\"margin:8px 0 0;font-size:12px;color:#888\">Anti-phishing: our only sender domain is @licensekaki.sg. We never ask for your password, OTP, or PayNow PIN by email. Verify any link before clicking.</p>"
            + "<p style=\"margin:8px 0 0;font-size:12px;color:#888\">LicenseKaki Pte Ltd · PDPA enquiries: dpo@licensekaki.sg</p></div>";
        final String a20Email =
            "<div style=\"font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px\">"
            + "<div style=\"border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px\"><span style=\"font-size:18px;font-weight:700;color:#0F766E\">LicenseKaki</span><br>"
            + "<span style=\"font-size:12px;color:#888\">Singapore Electrical Installation Licence Platform</span></div>"
            + "<h1 style=\"font-size:20px;margin:0 0 16px\">Payment received. Work is starting.</h1>"
            + "<p style=\"margin:0 0 16px\">Hello {{applicantName}},</p>"
            + "<p style=\"margin:0 0 16px\">We've received your PayNow payment of <strong>SGD {{amount}}</strong> for application <strong>#{{publicCode}}</strong> on <strong>{{paidAtDisplay}}</strong>. Thank you.</p>"
            + "<p style=\"margin:0 0 16px\">Your reviewer <strong>{{lewName}}</strong> will now coordinate the work and submit the licence to authorities. We'll keep you posted as the status changes.</p>"
            + "<p style=\"margin:24px 0\"><a href=\"{{ctaUrl}}\" style=\"display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600\">View application</a></p>"
            + "<hr style=\"border:none;border-top:1px solid #ddd;margin:24px 0\">"
            + "<p style=\"margin:0;font-size:12px;color:#888\">This is a transactional email from LicenseKaki. You are receiving it because your payment was confirmed.</p>"
            + "<p style=\"margin:8px 0 0;font-size:12px;color:#888\">Anti-phishing: our only sender domain is @licensekaki.sg. We never ask for your password, OTP, or PayNow PIN by email. Verify any link before clicking.</p>"
            + "<p style=\"margin:8px 0 0;font-size:12px;color:#888\">LicenseKaki Pte Ltd · PDPA enquiries: dpo@licensekaki.sg</p></div>";
        final String a17Vars =
            "[\"applicantName\",\"publicCode\",\"kvaLabel\",\"amount\",\"paynowUen\",\"paynowAccountName\",\"paynowReference\",\"deadline\",\"ctaUrl\"]";
        final String a20Vars =
            "[\"applicantName\",\"publicCode\",\"amount\",\"paidAtDisplay\",\"lewName\",\"ctaUrl\"]";

        // {code, channel, subject, body, variablesJson, severity}
        String[][] rows = {
            {"A-17", "EMAIL", "[LicenseKaki] Payment requested · #{{publicCode}}", a17Email, a17Vars, "CRITICAL"},
            {"A-17", "IN_APP", "Payment requested on #{{publicCode}}",
                "Pay SGD {{amount}} via PayNow by {{deadline}} to start work.", a17Vars, "CRITICAL"},
            {"A-20", "EMAIL", "[LicenseKaki] Payment received · #{{publicCode}}", a20Email, a20Vars, "IMPORTANT"},
            {"A-20", "IN_APP", "Payment confirmed on #{{publicCode}}",
                "SGD {{amount}} received. {{lewName}} will start work shortly.", a20Vars, "IMPORTANT"},
        };
        String insertSql =
            "INSERT INTO notification_templates " +
            "(template_code, channel, locale, provider_template_name, subject, body_text, " +
            " variables_json, catalog_meta_key, category, severity, recipient_roles, enabled, " +
            " created_at, updated_at) " +
            "SELECT ?, ?, 'en', NULL, ?, ?, ?, ?, 'PAYMENT', ?, 'APPLICANT', TRUE, NOW(6), NOW(6) " +
            "FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM notification_templates t " +
            "  WHERE t.template_code = ? AND t.channel = ? AND t.locale = 'en')";
        String enableSql =
            "UPDATE notification_templates SET enabled = TRUE, deleted_at = NULL, updated_at = NOW(6) " +
            "WHERE template_code = ? AND channel = ? AND locale = 'en' AND (enabled = FALSE OR deleted_at IS NOT NULL)";
        int inserted = 0;
        try (PreparedStatement insertPs = conn.prepareStatement(insertSql);
             PreparedStatement enablePs = conn.prepareStatement(enableSql)) {
            for (String[] r : rows) {
                try {
                    insertPs.setString(1, r[0]); insertPs.setString(2, r[1]);
                    insertPs.setString(3, r[2]); insertPs.setString(4, r[3]);
                    insertPs.setString(5, r[4]); insertPs.setString(6, r[0]);
                    insertPs.setString(7, r[5]);
                    insertPs.setString(8, r[0]); insertPs.setString(9, r[1]);
                    inserted += insertPs.executeUpdate();
                    enablePs.setString(1, r[0]); enablePs.setString(2, r[1]);
                    enablePs.executeUpdate();
                } catch (SQLException e) {
                    log.warn("seedApplicantPaymentNotificationTemplates {} {} warn: {}", r[0], r[1], e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("seedApplicantPaymentNotificationTemplates aborted: {}", e.getMessage());
            return;
        }
        if (inserted > 0) {
            log.info("Migration: seeded {} applicant payment template rows (A-17/A-20)", inserted);
        }
    }

    /**
     * 신청자 신고 kVA(kva_source=USER_INPUT)인데 kva_status=CONFIRMED 로 저장된 결제 전 레거시 행을
     * UNKNOWN(LEW 미확정)으로 보정한다. "신청자가 kVA 를 적어 올렸다"고 LEW 확정 상태가 되면 안 된다는
     * 규칙을 기존 데이터에도 적용. 결제 이후(PAID/IN_PROGRESS/COMPLETED/EXPIRED)는 동선이 진행됐으므로
     * 손대지 않는다(되돌리면 인플라이트 결제·정산과 모순). LEW_VERIFIED 행도 당연히 제외.
     * 멱등 — 한 번 보정되면 대상 행이 사라진다.
     */
    private void backfillUserDeclaredKvaToUnknown(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            if (!tableExists(conn, "applications")) {
                return;
            }
            int n = stmt.executeUpdate(
                    "UPDATE applications SET kva_status = 'UNKNOWN' " +
                    "WHERE kva_status = 'CONFIRMED' AND kva_source = 'USER_INPUT' " +
                    "AND status IN ('PENDING_REVIEW','REVISION_REQUESTED')");
            if (n > 0) {
                log.info("Migration [kva-user-declared-unknown]: reverted {} pre-payment rows to UNKNOWN", n);
            }
        } catch (SQLException e) {
            log.warn("backfillUserDeclaredKvaToUnknown skipped: {}", e.getMessage());
        }
    }

    /** notification_templates 존재 여부 — SQLException 삼킴(시드는 비치명적). */
    private boolean tableExistsSafe(Connection conn) {
        try {
            return tableExists(conn, "notification_templates");
        } catch (SQLException e) {
            return false;
        }
    }
}
