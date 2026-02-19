-- ============================================
-- Project Blue Light - Seed Data
-- 중복 방지: 데이터가 없을 때만 삽입
-- ============================================

-- Admin 계정 (password: admin1234 / BCrypt encoded, 이메일 인증 완료)
INSERT INTO users (email, password, name, phone, role, email_verified, created_at, updated_at)
SELECT 'admin@bluelight.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'System Admin', '+65-0000-0000', 'ADMIN', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@bluelight.sg');

-- LEW 계정 (password: admin1234 / BCrypt encoded, 사전 승인됨, Grade 9, 이메일 인증 완료)
INSERT INTO users (email, password, name, phone, role, approved_status, lew_licence_no, lew_grade, email_verified, created_at, updated_at)
SELECT 'lew@bluelight.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'LEW Officer', '+65-0000-0001', 'LEW', 'APPROVED', 'LEW-2026-00001', 'GRADE_9', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'lew@bluelight.sg');

-- System Admin 계정 (password: admin1234 / BCrypt encoded, 시스템 관리 전용)
INSERT INTO users (email, password, name, phone, role, email_verified, created_at, updated_at)
SELECT 'sysadmin@bluelight.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'System Administrator', '+65-0000-0099', 'SYSTEM_ADMIN', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'sysadmin@bluelight.sg');

-- 시스템 설정 초기값
INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'lew_registration_open', 'true', 'LEW 가입 허용 여부', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'lew_registration_open');


-- 결제 수취 정보 (PayNow)
INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'payment_paynow_uen', '202401234A', 'PayNow UEN number', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'payment_paynow_uen');

INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'payment_paynow_name', 'LicenseKaki Pte Ltd', 'PayNow recipient name', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'payment_paynow_name');

-- PayNow QR 이미지 경로 (Admin이 업로드, 파일 경로 저장)
INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'payment_paynow_qr', '', 'PayNow QR code image file path', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'payment_paynow_qr');

-- 결제 수취 정보 (Bank Transfer)
INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'payment_bank_name', 'DBS Bank', 'Bank name for transfer', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'payment_bank_name');

INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'payment_bank_account', '012-345678-9', 'Bank account number', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'payment_bank_account');

INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'payment_bank_account_name', 'LicenseKaki Pte Ltd', 'Bank account holder name', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'payment_bank_account_name');

-- 이메일 인증 기능 활성화 여부 (기본: 비활성화 — 로컬 개발 환경 대응)
INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'email_verification_enabled', 'false', 'Enable email verification on signup', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'email_verification_enabled');


-- kVA 단가표 (싱가포르 시장 기준 placeholder)
-- master_prices 테이블이 비어 있을 때만 삽입
-- sld_price: LEW에게 SLD 작성을 요청할 때의 추가 비용
INSERT INTO master_prices (description, kva_min, kva_max, price, sld_price, is_active, created_at, updated_at)
SELECT '45 kVA',              45,   45,   350.00,  150.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '46 - 100 kVA',        46,  100,   500.00,  200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '101 - 200 kVA',      101,  200,   750.00,  300.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '201 - 500 kVA',      201,  500,  1200.00,  450.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '501 - 1000 kVA',     501, 1000,  1800.00,  600.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '1001 - 2000 kVA',   1001, 2000,  2500.00,  800.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '2001 kVA and above', 2001, 9999,  3500.00, 1000.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1);
