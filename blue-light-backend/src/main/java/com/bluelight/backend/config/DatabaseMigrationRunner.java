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
     * 특정 테이블에 컬럼이 존재하는지 확인
     */
    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }
}
