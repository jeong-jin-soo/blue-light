-- Phase 4 i18n — English labels migration
-- Purpose: bring existing document_type_catalog rows in line with the
-- now-English label_ko column values set in data.sql.
-- Safe to re-run: WHERE clause is a no-op once synchronised.
--
-- Scope: operational DBs that were seeded while label_ko still held Korean
-- strings (SP 계정 보유자 PDF, 위임장 (LOA), 메인 차단기 사진, 단선도 (SLD),
-- 평면 스케치, 결제 영수증, 기타).

UPDATE document_type_catalog
SET label_ko = label_en
WHERE label_ko <> label_en;
