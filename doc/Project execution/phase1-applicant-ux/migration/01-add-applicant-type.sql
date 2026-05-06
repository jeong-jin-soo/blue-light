-- ============================================
-- Phase 1 PR#2 — Application.applicantType 컬럼 추가
-- ============================================
-- 스펙: 01-spec.md §5 마이그레이션 DDL, AC-C2
-- 실행 대상: 운영/개발 MySQL (기존 데이터 존재하는 DB)
-- 신규 DB는 schema.sql의 CREATE TABLE 정의에 이미 포함되어 자동 적용됨.
--
-- 실행 순서 (3단계):
--   1) NULL 허용으로 컬럼 추가 (무중단)
--   2) 기존 전체 레코드를 INDIVIDUAL로 백필 (AC-C2)
--   3) NOT NULL + DEFAULT 'INDIVIDUAL'로 제약 강화
--
-- 롤백: ALTER TABLE applications DROP COLUMN applicant_type;
-- ============================================

-- 1) 컬럼 추가 (NULL 허용)
ALTER TABLE applications
  ADD COLUMN applicant_type VARCHAR(20) NULL COMMENT 'INDIVIDUAL | CORPORATE';

-- 2) 기존 레코드 백필 (전체 INDIVIDUAL — 01-spec.md §8 의사결정 (a))
UPDATE applications
  SET applicant_type = 'INDIVIDUAL'
  WHERE applicant_type IS NULL;

-- 3) NOT NULL + 기본값 제약 강화
ALTER TABLE applications
  MODIFY COLUMN applicant_type VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL';
