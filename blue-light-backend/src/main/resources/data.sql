-- ============================================
-- Project Blue Light - Seed Data
-- 중복 방지: 데이터가 없을 때만 삽입
-- ============================================

-- Admin 계정 (password: admin1234 / BCrypt encoded)
INSERT INTO users (email, password, name, phone, role, created_at, updated_at)
SELECT 'admin@bluelight.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'System Admin', '+65-0000-0000', 'ADMIN',
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@bluelight.sg');

-- LEW 계정 (password: admin1234 / BCrypt encoded, 사전 승인됨)
INSERT INTO users (email, password, name, phone, role, approved_status, created_at, updated_at)
SELECT 'lew@bluelight.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'LEW Officer', '+65-0000-0001', 'LEW', 'APPROVED',
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'lew@bluelight.sg');

-- 시스템 설정 초기값
INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'lew_registration_open', 'true', 'LEW 가입 허용 여부', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'lew_registration_open');

-- kVA 단가표 (싱가포르 시장 기준 placeholder)
-- master_prices 테이블이 비어 있을 때만 삽입
INSERT INTO master_prices (description, kva_min, kva_max, price, is_active, created_at, updated_at)
SELECT '45 kVA',              45,   45,   350.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '46 - 100 kVA',        46,  100,   500.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '101 - 200 kVA',      101,  200,   750.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '201 - 500 kVA',      201,  500,  1200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '501 - 1000 kVA',     501, 1000,  1800.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '1001 - 2000 kVA',   1001, 2000,  2500.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '2001 kVA and above', 2001, 9999,  3500.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1);
