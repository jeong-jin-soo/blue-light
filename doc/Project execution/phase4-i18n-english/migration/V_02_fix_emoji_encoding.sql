-- ============================================
-- Phase 4 핫픽스 — icon_emoji UTF-8 4-byte 복원
-- ============================================
-- 원인: Phase 2 PR#1 초기 seed 실행 시 mysql CLI의 default character set이
--       utf8mb3로 매핑되어 4-byte 이모지가 '?' (0x3F)로 저장됨.
--       (✏️ SKETCH는 2-byte + variation selector라 영향 없었음)
-- 해결: SET NAMES utf8mb4 로 강제 후 UPDATE.
-- 적용: 2026-04-19 운영 RDS에 적용 완료.
-- 재발 방지: mysql CLI 호출 시 반드시 --default-character-set=utf8mb4 사용.
-- ============================================

SET NAMES utf8mb4;

UPDATE document_type_catalog SET icon_emoji = '📄' WHERE code = 'SP_ACCOUNT';
UPDATE document_type_catalog SET icon_emoji = '📝' WHERE code = 'LOA';
UPDATE document_type_catalog SET icon_emoji = '📷' WHERE code = 'MAIN_BREAKER_PHOTO';
UPDATE document_type_catalog SET icon_emoji = '📐' WHERE code = 'SLD_FILE';
UPDATE document_type_catalog SET icon_emoji = '🧾' WHERE code = 'PAYMENT_RECEIPT';
UPDATE document_type_catalog SET icon_emoji = '📎' WHERE code = 'OTHER';

-- 검증
SELECT code, icon_emoji, HEX(icon_emoji) AS hex, CHAR_LENGTH(icon_emoji) AS chars
  FROM document_type_catalog ORDER BY display_order;
