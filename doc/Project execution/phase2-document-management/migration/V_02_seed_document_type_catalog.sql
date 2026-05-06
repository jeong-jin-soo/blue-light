-- ============================================
-- Phase 2 PR#1 — V_02: Document Type Catalog Seed (7종)
-- ============================================
-- 멱등성: INSERT ... ON DUPLICATE KEY UPDATE → 재실행 안전 (B-3 §10)
-- 의존성: V_01 선행 필수
-- 후속: V_03 (document_request, FK to document_type_catalog)
--
-- 시드 7종 (display_order 오름차순 = UI 표시 순서):
--   10  SP_ACCOUNT          📄  application/pdf                       10MB
--   20  LOA                 📝  application/pdf                       10MB
--   30  MAIN_BREAKER_PHOTO  📷  image/png,image/jpeg                   8MB
--   40  SLD_FILE            📐  application/pdf,image/png,image/jpeg  20MB
--   50  SKETCH              ✏️  application/pdf,image/png,image/jpeg  10MB
--   60  PAYMENT_RECEIPT     🧾  application/pdf,image/png,image/jpeg   5MB
--  999  OTHER               📎  application/pdf,image/png,image/jpeg  10MB
-- ============================================

INSERT INTO document_type_catalog
    (code, label_en, label_ko, description, help_text,
     accepted_mime, max_size_mb, icon_emoji, display_order, active, created_at, updated_at)
VALUES
    ('SP_ACCOUNT',         'SP Account Holder PDF', 'SP 계정 보유자 PDF',
     'Proof of SP Group account ownership for the premises',
     'Download the official PDF from SP Group portal and upload it here.',
     'application/pdf',                        10, '📄',  10, TRUE, NOW(), NOW()),
    ('LOA',                'Letter of Authorisation', '위임장 (LOA)',
     'Signed authorisation letter granting LEW to act on your behalf',
     'Use the system-generated LOA template, sign it, then re-upload.',
     'application/pdf',                        10, '📝',  20, TRUE, NOW(), NOW()),
    ('MAIN_BREAKER_PHOTO', 'Main Breaker Photo', '메인 차단기 사진',
     'Clear photo of the main circuit breaker nameplate',
     'Make sure the rating and brand are readable in the photo.',
     'image/png,image/jpeg',                    8, '📷',  30, TRUE, NOW(), NOW()),
    ('SLD_FILE',           'Single Line Diagram',     '단선도 (SLD)',
     'Single-line diagram of the electrical installation',
     'PDF preferred. Image accepted if PDF is unavailable.',
     'application/pdf,image/png,image/jpeg',   20, '📐',  40, TRUE, NOW(), NOW()),
    ('SKETCH',             'Sketch / Plan',           '평면 스케치',
     'Hand-drawn sketch or floor plan of the premises',
     NULL,
     'application/pdf,image/png,image/jpeg',   10, '✏️',  50, TRUE, NOW(), NOW()),
    ('PAYMENT_RECEIPT',    'Payment Receipt',         '결제 영수증',
     'Receipt evidencing payment for related fees',
     NULL,
     'application/pdf,image/png,image/jpeg',    5, '🧾',  60, TRUE, NOW(), NOW()),
    ('OTHER',              'Other',                   '기타',
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
