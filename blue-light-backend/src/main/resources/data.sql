-- ============================================
-- Project LicenseKaki - Seed Data
-- 중복 방지: 데이터가 없을 때만 삽입
-- ============================================

-- Admin 계정 (password: admin1234 / BCrypt encoded, 이메일 인증 완료)
-- 이름을 'System Admin' 으로 두면 SYSTEM_ADMIN(이름 'System Administrator')과 헤더에서 혼동되므로
-- 'LicenseKaki Admin' 으로 명확화. (헤더 우측 상단은 role 이 아닌 first_name+last_name 을 표시)
INSERT INTO users (email, password, first_name, last_name, phone, role, email_verified, created_at, updated_at)
SELECT 'admin@licensekaki.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'LicenseKaki', 'Admin', '+65-0000-0000', 'ADMIN', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@licensekaki.sg');

-- 기존 DB(dev/운영)에 이미 'System Admin' 이름으로 생성된 ADMIN 계정 이름 교정 (idempotent — 매 부팅 시 안전).
-- 이메일이 아닌 role+이름 기준이라 어떤 이메일의 ADMIN 이든 'System Admin' 이면 교정됨. SYSTEM_ADMIN 은 미해당.
UPDATE users SET first_name = 'LicenseKaki', last_name = 'Admin'
WHERE role = 'ADMIN' AND first_name = 'System' AND last_name = 'Admin';

-- LEW 계정 (password: admin1234 / BCrypt encoded, 사전 승인됨, Grade 9, 이메일 인증 완료)
INSERT INTO users (email, password, first_name, last_name, phone, role, approved_status, lew_licence_no, lew_grade, email_verified, created_at, updated_at)
SELECT 'lew@licensekaki.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'LEW', 'Officer', '+65-0000-0001', 'LEW', 'APPROVED', 'LEW-2026-00001', 'GRADE_9', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'lew@licensekaki.sg');

-- System Admin 계정 (password: admin1234 / BCrypt encoded, 시스템 관리 전용)
INSERT INTO users (email, password, first_name, last_name, phone, role, email_verified, created_at, updated_at)
SELECT 'sysadmin@licensekaki.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'System', 'Administrator', '+65-0000-0099', 'SYSTEM_ADMIN', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'sysadmin@licensekaki.sg');

-- SLD Manager 계정 (password: admin1234 / BCrypt encoded, SLD 전용 주문 관리)
INSERT INTO users (email, password, first_name, last_name, phone, role, email_verified, created_at, updated_at)
SELECT 'sldmanager@licensekaki.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'SLD', 'Manager', '+65-0000-0002', 'SLD_MANAGER', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'sldmanager@licensekaki.sg');

-- Concierge Manager 계정 (password: admin1234 / BCrypt encoded, Kaki Concierge 대행 서비스)
-- ★ Kaki Concierge v1.5 Phase 1 PR#4 Stage A
INSERT INTO users (email, password, first_name, last_name, phone, role, status, signup_source, email_verified, created_at, updated_at)
SELECT 'conciergemanager@licensekaki.sg',
       '$2a$10$.QY0wEUfA7GCMfMER6OJaei/5MpW6NOOHiEGxREq6bqA.owWxrxzW',
       'Concierge', 'Manager', '+65-0000-0003', 'CONCIERGE_MANAGER', 'ACTIVE', 'DIRECT_SIGNUP', TRUE,
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'conciergemanager@licensekaki.sg');

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

-- AI SLD 생성 기능 활성화 여부 (기본: 활성화)
INSERT INTO system_settings (setting_key, setting_value, description, updated_at)
SELECT 'sld_ai_generation_enabled', 'true', 'Enable AI-powered SLD generation', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_settings WHERE setting_key = 'sld_ai_generation_enabled');


-- kVA 단가표 (싱가포르 시장 기준 placeholder)
-- master_prices 테이블이 비어 있을 때만 삽입
-- sld_price        : LEW가 SLD 도면을 그려주는 비용
-- endorsement_price: SLD에 LEW 인증 도장(endorsement)을 추가할 때 가산되는 비용
-- callout_fee      : 출장비 — New License 신청에만 가산 (Renewal 미적용), 기본 200
INSERT INTO master_prices (description, kva_min, kva_max, price, renewal_price, sld_price, endorsement_price, callout_fee, is_active, created_at, updated_at)
SELECT '45 kVA',              45,   45,   350.00,  350.00,  150.00,  50.00, 200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '46 - 100 kVA',        46,  100,   500.00,  500.00,  200.00,  80.00, 200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '101 - 200 kVA',      101,  200,   750.00,  750.00,  300.00, 120.00, 200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '201 - 500 kVA',      201,  500,  1200.00, 1200.00,  450.00, 180.00, 200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '501 - 1000 kVA',     501, 1000,  1800.00, 1800.00,  600.00, 250.00, 200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '1001 - 2000 kVA',   1001, 2000,  2500.00, 2500.00,  800.00, 350.00, 200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1)
UNION ALL
SELECT '2001 kVA and above', 2001, 9999,  3500.00, 3500.00, 1000.00, 450.00, 200.00, 1, NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_prices LIMIT 1);


-- ============================================
-- Document Type Catalog (Phase 2)
-- B-3 §10: 재실행 안전성 위해 INSERT ... ON DUPLICATE KEY UPDATE 사용.
-- 라벨/MIME/크기 변경이 있을 경우 다음 배포 시 자동 반영된다.
-- ============================================
INSERT INTO document_type_catalog
    (code, label_en, label_ko, description, help_text,
     accepted_mime, max_size_mb, icon_emoji, display_order, active, created_at, updated_at)
VALUES
    ('SP_ACCOUNT',         'SP Account Holder Document', 'SP Account Holder Document',
     'Proof of SP Group account ownership for the premises',
     'Download the official PDF from SP Group portal and upload it here. A clear JPG/PNG photo is also accepted.',
     'application/pdf,image/jpeg,image/png',   10, '📄',  10, TRUE, NOW(), NOW()),
    ('LOA',                'Letter of Authorisation', 'Letter of Authorisation',
     'Signed authorisation letter granting LEW to act on your behalf',
     'Upload the signed Letter of Authorisation. PDF, JPG, or PNG accepted.',
     'application/pdf,image/jpeg,image/png',   10, '📝',  20, TRUE, NOW(), NOW()),
    ('MAIN_BREAKER_PHOTO', 'Main Breaker Photo', 'Main Breaker Photo',
     'Clear photo of the main circuit breaker nameplate',
     'Make sure the rating and brand are readable in the photo.',
     'image/png,image/jpeg',                    8, '📷',  30, TRUE, NOW(), NOW()),
    ('SLD_FILE',           'Single Line Diagram',     'Single Line Diagram',
     'Single-line diagram of the electrical installation',
     'PDF preferred. Image accepted if PDF is unavailable.',
     'application/pdf,image/png,image/jpeg',   20, '📐',  40, TRUE, NOW(), NOW()),
    ('SKETCH',             'Sketch / Plan',           'Sketch / Plan',
     'Hand-drawn sketch or floor plan of the premises',
     NULL,
     'application/pdf,image/png,image/jpeg',   10, '✏️',  50, TRUE, NOW(), NOW()),
    ('PAYMENT_RECEIPT',    'Payment Receipt',         'Payment Receipt',
     'Receipt evidencing payment for related fees',
     NULL,
     'application/pdf,image/png,image/jpeg',    5, '🧾',  60, TRUE, NOW(), NOW()),
    ('OTHER',              'Other',                   'Other',
     'Any other supporting document not listed above',
     'Provide a short label so reviewers know what this file is.',
     'application/pdf,image/png,image/jpeg',   10, '📎', 999, TRUE, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    label_en          = VALUES(label_en),
    label_ko          = VALUES(label_ko),
    description       = VALUES(description),
    help_text         = VALUES(help_text),
    accepted_mime     = VALUES(accepted_mime),
    max_size_mb       = VALUES(max_size_mb),
    icon_emoji        = VALUES(icon_emoji),
    display_order     = VALUES(display_order),
    active            = VALUES(active),
    updated_at        = NOW();

-- ============================================
-- PR-W0: 알림 발송 본문 시드 (notification_templates)
-- 카탈로그 코드(A-NN) = template_code (결정 #1). 변수명 = payload 키 — 호출부가 도메인 데이터를
-- 카피북 변수 슬롯에 매핑한다(예: Application 은 publicCode 가 없으므로 applicationSeq 를
-- "publicCode" 키로 전달). 렌더러는 footer 자동주입 없음 → 본문은 자기완결형 HTML.
-- 본문 SSOT: doc/Project Analysis/notification-copy-templates.en.md
-- 생성: scripts/seed_notification_templates.py  (A-20 카나리는 결제 payload 정렬 위해 수기 관리)
-- ============================================

-- 카나리 코드 정합화(결정 #1): 구 PAYMENT_CONFIRMED_APPLICANT → A-20 hard-replace. 구 row 제거.
-- data.sql 은 매 부팅 재실행되므로(SQL_INIT_MODE=always) dev RDS 도 다음 배포 시 자동 정리됨.
DELETE FROM notification_templates WHERE template_code = 'PAYMENT_CONFIRMED_APPLICANT';

-- A-20 Payment confirmed (PAID) — AdminPaymentService.confirmPayment
-- 변수: {{applicantName}} {{publicCode}} {{amount}} {{paidAtDisplay}} {{lewName}} {{ctaUrl}}
INSERT INTO notification_templates
    (template_code, channel, locale, provider_template_name, subject, body_text,
     variables_json, catalog_meta_key, category, severity, recipient_roles, enabled,
     created_at, updated_at)
VALUES
    ('A-20', 'IN_APP', 'en', NULL, 'Payment confirmed on #{{publicCode}}',
     'SGD {{amount}} received. {{lewName}} will start work shortly.',
     '["applicantName","publicCode","amount","paidAtDisplay","lewName","ctaUrl"]',
     'A-20', 'PAYMENT', 'IMPORTANT', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-20', 'EMAIL', 'en', NULL, '[LicenseKaki] Payment received · #{{publicCode}}',
     '<div style="font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px"><div style="border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px"><span style="font-size:18px;font-weight:700;color:#0F766E">LicenseKaki</span><br><span style="font-size:12px;color:#888">Singapore Electrical Installation Licence Platform</span></div><h1 style="font-size:20px;margin:0 0 16px">Payment received. Work is starting.</h1><p style="margin:0 0 16px">Hello {{applicantName}},</p><p style="margin:0 0 16px">We''ve received your PayNow payment of <strong>SGD {{amount}}</strong> for application <strong>#{{publicCode}}</strong> on <strong>{{paidAtDisplay}}</strong>. Thank you.</p><p style="margin:0 0 16px">Your reviewer <strong>{{lewName}}</strong> will now coordinate the work and submit the licence to authorities. We''ll keep you posted as the status changes.</p><p style="margin:24px 0"><a href="{{ctaUrl}}" style="display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600">View application</a></p><hr style="border:none;border-top:1px solid #ddd;margin:24px 0"><p style="margin:0;font-size:12px;color:#888">This is a transactional email from LicenseKaki. You are receiving it because your payment was confirmed.</p><p style="margin:8px 0 0;font-size:12px;color:#888">Anti-phishing: our only sender domain is @licensekaki.sg. We never ask for your password, OTP, or PayNow PIN by email. Verify any link before clicking.</p><p style="margin:8px 0 0;font-size:12px;color:#888">LicenseKaki Pte Ltd · PDPA enquiries: dpo@licensekaki.sg</p></div>',
     '["applicantName","publicCode","amount","paidAtDisplay","lewName","ctaUrl"]',
     'A-20', 'PAYMENT', 'IMPORTANT', 'APPLICANT', TRUE, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    subject = VALUES(subject), body_text = VALUES(body_text),
    variables_json = VALUES(variables_json), catalog_meta_key = VALUES(catalog_meta_key),
    category = VALUES(category), severity = VALUES(severity),
    recipient_roles = VALUES(recipient_roles), enabled = VALUES(enabled),
    deleted_at = NULL, updated_at = NOW();

-- W1 대상 본문 (A-10/A-15/A-17/A-22) — PR-W1 이 호출부를 이벤트로 이관할 때 소비.
-- 생성: python3 scripts/seed_notification_templates.py --codes A-10,A-15,A-17,A-22
INSERT INTO notification_templates
    (template_code, channel, locale, provider_template_name, subject, body_text,
     variables_json, catalog_meta_key, category, severity, recipient_roles, enabled,
     created_at, updated_at)
VALUES
    ('A-10', 'EMAIL', 'en', NULL, '[LicenseKaki] Your reviewer has been assigned · #{{publicCode}}', '<div style="font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px"><div style="border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px"><span style="font-size:18px;font-weight:700;color:#0F766E">LicenseKaki</span><br><span style="font-size:12px;color:#888">Singapore Electrical Installation Licence Platform</span></div><h1 style="font-size:20px;margin:0 0 16px">Your Licensed Electrical Worker is on the case.</h1><p style="margin:0 0 16px">Hello {{applicantName}},</p><p style="margin:0 0 16px">Good news — <strong>{{lewName}}</strong> ({{lewGradeLabel}}) has been assigned as the Licensed Electrical Worker (LEW) for application <strong>#{{publicCode}}</strong>.</p><p style="margin:0 0 16px"><strong>What happens next</strong>: {{expectedNextStepText}}. You''ll be notified each time the status changes — there''s nothing for you to do right now.</p><p style="margin:24px 0"><a href="{{ctaUrl}}" style="display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600">View application</a></p><hr style="border:none;border-top:1px solid #ddd;margin:24px 0"><p style="margin:0;font-size:12px;color:#888">This is a transactional email from LicenseKaki. You are receiving it because a reviewer was assigned to your application.</p><p style="margin:8px 0 0;font-size:12px;color:#888">LicenseKaki Pte Ltd · PDPA enquiries: dpo@licensekaki.sg</p></div>', '["applicantName","publicCode","lewName","lewGradeLabel","expectedNextStepText","ctaUrl"]', 'A-10', 'STATUS', 'IMPORTANT', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-10', 'IN_APP', 'en', NULL, 'Reviewer assigned: {{lewName}}', 'Your LEW will review #{{publicCode}} within the next 48 hours.', '["applicantName","publicCode","lewName","lewGradeLabel","expectedNextStepText","ctaUrl"]', 'A-10', 'STATUS', 'IMPORTANT', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-15', 'EMAIL', 'en', NULL, '[LicenseKaki] Your application needs a revision · #{{publicCode}}', '<div style="font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px"><div style="border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px"><span style="font-size:18px;font-weight:700;color:#0F766E">LicenseKaki</span><br><span style="font-size:12px;color:#888">Singapore Electrical Installation Licence Platform</span></div><h1 style="font-size:20px;margin:0 0 16px">Your reviewer requested a revision.</h1><p style="margin:0 0 16px">Hello {{applicantName}},</p><p style="margin:0 0 16px">Your Licensed Electrical Worker has reviewed application <strong>#{{publicCode}}</strong> and asked for revisions before approval. Their notes:</p><blockquote style="margin:0 0 16px;padding:8px 16px;border-left:3px solid #ccc;color:#555">{{revisionNotes}}</blockquote><p style="margin:0 0 16px">Please make the changes and resubmit by <strong>{{deadline}}</strong> to avoid auto-cancellation on D+7.</p><p style="margin:24px 0"><a href="{{ctaUrl}}" style="display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600">Edit my application</a></p><hr style="border:none;border-top:1px solid #ddd;margin:24px 0"><p style="margin:0;font-size:12px;color:#888">This is a transactional email from LicenseKaki. You are receiving it because your reviewer requested changes to your application.</p><p style="margin:8px 0 0;font-size:12px;color:#888">LicenseKaki Pte Ltd · PDPA enquiries: dpo@licensekaki.sg</p></div>', '["applicantName","publicCode","revisionNotes","deadline","ctaUrl"]', 'A-15', 'STATUS', 'CRITICAL', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-15', 'IN_APP', 'en', NULL, 'Revision requested on #{{publicCode}}', 'Make the requested changes by {{deadline}} to keep your application active.', '["applicantName","publicCode","revisionNotes","deadline","ctaUrl"]', 'A-15', 'STATUS', 'CRITICAL', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-17', 'EMAIL', 'en', NULL, '[LicenseKaki] Payment requested · #{{publicCode}}', '<div style="font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px"><div style="border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px"><span style="font-size:18px;font-weight:700;color:#0F766E">LicenseKaki</span><br><span style="font-size:12px;color:#888">Singapore Electrical Installation Licence Platform</span></div><h1 style="font-size:20px;margin:0 0 16px">Your application is approved. Please complete payment to start work.</h1><p style="margin:0 0 16px">Hello {{applicantName}},</p><p style="margin:0 0 16px">Good news — your Licensed Electrical Worker has confirmed the scope of work for application <strong>#{{publicCode}}</strong> ({{kvaLabel}}). To begin the work, please settle the payment below by <strong>{{deadline}}</strong>.</p><p style="margin:0 0 16px"><strong>Amount due</strong>: SGD {{amount}}<br><strong>PayNow UEN</strong>: {{paynowUen}}<br><strong>Payee name</strong>: {{paynowAccountName}}<br><strong>Reference (must include)</strong>: {{paynowReference}}</p><p style="margin:0 0 16px">Including the reference code lets us match your payment automatically — usually within 1 business hour.</p><p style="margin:24px 0"><a href="{{ctaUrl}}" style="display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600">Pay via PayNow</a></p><hr style="border:none;border-top:1px solid #ddd;margin:24px 0"><p style="margin:0;font-size:12px;color:#888">This is a transactional email from LicenseKaki. You are receiving it because your application is awaiting payment.</p><p style="margin:8px 0 0;font-size:12px;color:#888">Anti-phishing: our only sender domain is @licensekaki.sg. We never ask for your password, OTP, or PayNow PIN by email. Verify any link before clicking.</p><p style="margin:8px 0 0;font-size:12px;color:#888">LicenseKaki Pte Ltd · PDPA enquiries: dpo@licensekaki.sg</p></div>', '["applicantName","publicCode","kvaLabel","amount","paynowUen","paynowAccountName","paynowReference","deadline","ctaUrl"]', 'A-17', 'PAYMENT', 'CRITICAL', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-17', 'IN_APP', 'en', NULL, 'Payment requested on #{{publicCode}}', 'Pay SGD {{amount}} via PayNow by {{deadline}} to start work.', '["applicantName","publicCode","kvaLabel","amount","paynowUen","paynowAccountName","paynowReference","deadline","ctaUrl"]', 'A-17', 'PAYMENT', 'CRITICAL', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-22', 'EMAIL', 'en', NULL, '[LicenseKaki] Your installation licence has been issued · #{{publicCode}}', '<div style="font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;max-width:600px;margin:0 auto;padding:0 16px"><div style="border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px"><span style="font-size:18px;font-weight:700;color:#0F766E">LicenseKaki</span><br><span style="font-size:12px;color:#888">Singapore Electrical Installation Licence Platform</span></div><h1 style="font-size:20px;margin:0 0 16px">Congratulations — your installation licence is live.</h1><p style="margin:0 0 16px">Hello {{applicantName}},</p><p style="margin:0 0 16px">Your electrical installation licence has been issued by the Energy Market Authority.</p><p style="margin:0 0 16px"><strong>Licence number</strong>: {{licenceNumber}}<br><strong>Valid until</strong>: {{licenceExpiryDate}}</p><p style="margin:0 0 16px">A signed PDF is available in your dashboard for your records. We''ll send renewal reminders 90, 60, 30, and 7 days before expiry — no need to set a calendar reminder.</p><p style="margin:24px 0"><a href="{{ctaUrl}}" style="display:inline-block;background:#0F766E;color:#fff;text-decoration:none;padding:10px 20px;border-radius:6px;font-weight:600">View licence</a></p><hr style="border:none;border-top:1px solid #ddd;margin:24px 0"><p style="margin:0;font-size:12px;color:#888">This is a transactional email from LicenseKaki. You are receiving it because your installation licence has been issued.</p><p style="margin:8px 0 0;font-size:12px;color:#888">LicenseKaki Pte Ltd · PDPA enquiries: dpo@licensekaki.sg</p></div>', '["applicantName","publicCode","licenceNumber","licenceExpiryDate","ctaUrl","shortUrl","licencePdfUrl"]', 'A-22', 'STATUS', 'CRITICAL', 'APPLICANT', TRUE, NOW(), NOW()),
    ('A-22', 'IN_APP', 'en', NULL, 'Licence issued for #{{publicCode}}', 'Valid until {{licenceExpiryDate}}. View or download your licence PDF.', '["applicantName","publicCode","licenceNumber","licenceExpiryDate","ctaUrl","shortUrl","licencePdfUrl"]', 'A-22', 'STATUS', 'CRITICAL', 'APPLICANT', TRUE, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    subject = VALUES(subject), body_text = VALUES(body_text),
    variables_json = VALUES(variables_json), catalog_meta_key = VALUES(catalog_meta_key),
    category = VALUES(category), severity = VALUES(severity),
    recipient_roles = VALUES(recipient_roles), enabled = VALUES(enabled),
    deleted_at = NULL, updated_at = NOW();

-- ============================================
-- notification_catalog 카탈로그 메타 sample 시드 (PR-T5)
-- ----------------------------------------------------------------
-- 카탈로그 SSOT: doc/Project Analysis/notification-catalog.md
-- 풀 97종 시드는 다음으로 생성하여 운영/CI 에 별도 실행:
--   $ python3 scripts/import_notification_copy.py > /tmp/catalog_seed.sql
--   $ mysql -h <host> -u <user> -p bluelight < /tmp/catalog_seed.sql
-- 로컬 개발 시 Lint L1(변수 화이트리스트) + Admin UI 트리거 표시가 즉시 동작하도록 대표 코드 시드.
-- trigger_ref = 발송 트리거(기능/호출부) — 카피북 각 카드 Trigger 필드. Admin Edit 화면에 표시.
-- ON DUPLICATE KEY UPDATE 로 기존 row 의 trigger_ref 도 백필. 풀 카탈로그는 import_notification_copy.py.
-- ============================================
INSERT INTO notification_catalog
    (template_code, allowed_variables_json, default_category, default_severity,
     default_recipient_roles, description, required_tokens_json, trigger_ref, created_at, updated_at)
VALUES
    ('A-04', '["applicantName","changedAtDisplay","requestIp","supportUrl"]',
     'SECURITY', 'CRITICAL', 'APPLICANT', 'Password change confirmation', '[]',
     'AuthService.resetPassword', NOW(6), NOW(6)),
    ('A-10', '["applicantName","publicCode","lewName","lewGradeLabel","expectedNextStepText","ctaUrl"]',
     'STATUS', 'IMPORTANT', 'APPLICANT', 'LEW assigned', '[]',
     'AdminLewService.assignLew', NOW(6), NOW(6)),
    ('A-15', '["applicantName","publicCode","revisionNotes","deadline","ctaUrl"]',
     'STATUS', 'CRITICAL', 'APPLICANT', 'Revision requested (REVISION_REQUESTED)', '[]',
     'AdminApplicationService.requestRevision', NOW(6), NOW(6)),
    ('A-17', '["applicantName","publicCode","kvaLabel","amount","paynowUen","paynowAccountName","paynowReference","deadline","ctaUrl"]',
     'PAYMENT', 'CRITICAL', 'APPLICANT', 'Payment requested (PENDING_PAYMENT)', '[]',
     'AdminApplicationService.approveForPayment', NOW(6), NOW(6)),
    ('A-20', '["applicantName","publicCode","amount","paidAtDisplay","lewName","ctaUrl"]',
     'PAYMENT', 'IMPORTANT', 'APPLICANT', 'Payment confirmed (PAID)', '[]',
     'AdminPaymentService.confirmPayment', NOW(6), NOW(6)),
    ('A-22', '["applicantName","publicCode","licenceNumber","licenceExpiryDate","ctaUrl","shortUrl","licencePdfUrl"]',
     'STATUS', 'CRITICAL', 'APPLICANT', 'Licence issued (COMPLETED)', '[]',
     'AdminApplicationService.completeApplication', NOW(6), NOW(6)),
    ('L-01', '["lewName","ctaUrl"]',
     'STATUS', 'CRITICAL', 'LEW', 'LEW signup approved', '[]',
     'AdminUserController.approveLew', NOW(6), NOW(6)),
    ('M-01', '["publicCode","kvaLabel"]',
     'OPS', 'IMPORTANT', 'ADMIN', 'New application received', '[]',
     'ApplicationService.createApplication', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE
    allowed_variables_json = VALUES(allowed_variables_json),
    default_category = VALUES(default_category), default_severity = VALUES(default_severity),
    default_recipient_roles = VALUES(default_recipient_roles), description = VALUES(description),
    required_tokens_json = VALUES(required_tokens_json), trigger_ref = VALUES(trigger_ref),
    updated_at = NOW(6);
