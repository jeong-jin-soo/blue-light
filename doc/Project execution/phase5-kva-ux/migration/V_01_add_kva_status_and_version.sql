-- ============================================
-- Phase 5 PR#1 — kVA 확정 상태 + 낙관적 락 컬럼 추가
-- ============================================
-- 출처: doc/Project execution/phase5-kva-ux/01-spec.md §6, 03-security-review.md §1~§5
-- 대상 DB: MySQL 8.0 (dev/prod 공통)
-- idempotent: 각 ALTER 는 중복 실행 시 에러가 나도록 설계 — 실행 전 반드시
--             현재 스키마 상태 확인 필요. `\d applications` 또는 DESCRIBE applications.
-- 실행 순서:
--   1) 컬럼 추가 (NULL 허용 상태로 먼저)
--   2) 기존 레코드 백필 (kva_status='CONFIRMED', kva_source='USER_INPUT')
--   3) NOT NULL 및 CHECK 제약 추가
--   4) FK + 인덱스 추가
-- 롤백: 이 파일 하단 주석의 DOWN 블록 참조.

-- ────────────────────────────────────────────────
-- 1) 컬럼 추가
-- ────────────────────────────────────────────────
ALTER TABLE applications
    ADD COLUMN kva_status       VARCHAR(20) NULL COMMENT 'UNKNOWN | CONFIRMED',
    ADD COLUMN kva_source       VARCHAR(20) NULL COMMENT 'USER_INPUT | LEW_VERIFIED',
    ADD COLUMN kva_confirmed_by BIGINT      NULL,
    ADD COLUMN kva_confirmed_at DATETIME(6) NULL,
    ADD COLUMN version          BIGINT      NULL;

-- ────────────────────────────────────────────────
-- 2) 기존 레코드 백필
--    기존 9건(또는 N건) 레코드는 "사용자가 직접 kVA tier 를 선택한 상태"로 간주
--    → kva_status='CONFIRMED', kva_source='USER_INPUT'
--    → version=0 (낙관적 락 초기값)
-- ────────────────────────────────────────────────
UPDATE applications
   SET kva_status = 'CONFIRMED'
 WHERE kva_status IS NULL;

UPDATE applications
   SET kva_source = 'USER_INPUT'
 WHERE kva_source IS NULL
   AND kva_status = 'CONFIRMED';

UPDATE applications
   SET version = 0
 WHERE version IS NULL;

-- ────────────────────────────────────────────────
-- 3) NOT NULL + DEFAULT 확정
-- ────────────────────────────────────────────────
ALTER TABLE applications
    MODIFY COLUMN kva_status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED' COMMENT 'UNKNOWN | CONFIRMED',
    MODIFY COLUMN version    BIGINT      NOT NULL DEFAULT 0;

-- ────────────────────────────────────────────────
-- 4) FK + CHECK + 인덱스
--    FK: LEW 계정 soft-delete 시 확정자 참조는 NULL 로 (감사 로그에 원본 보존)
--    CHECK: CONFIRMED 일 때는 kva_source 가 반드시 채워져 있어야 함 (R7 대응)
-- ────────────────────────────────────────────────
ALTER TABLE applications
    ADD CONSTRAINT fk_applications_kva_confirmed_by
        FOREIGN KEY (kva_confirmed_by) REFERENCES users (user_seq) ON DELETE SET NULL,
    ADD CONSTRAINT chk_applications_kva_status_source
        CHECK (kva_status = 'UNKNOWN' OR kva_source IS NOT NULL),
    ADD KEY idx_applications_kva_status (kva_status);

-- ────────────────────────────────────────────────
-- 5) 사후 검증 쿼리 (운영자가 수동 실행)
--    기대값: 0
-- ────────────────────────────────────────────────
-- SELECT COUNT(*) FROM applications
--  WHERE kva_status IS NULL
--     OR (kva_status = 'CONFIRMED' AND kva_source IS NULL);

-- ────────────────────────────────────────────────
-- DOWN (롤백) — 주석 해제 후 수동 실행
-- ────────────────────────────────────────────────
-- ALTER TABLE applications
--     DROP FOREIGN KEY fk_applications_kva_confirmed_by,
--     DROP CHECK chk_applications_kva_status_source,
--     DROP INDEX idx_applications_kva_status,
--     DROP COLUMN version,
--     DROP COLUMN kva_confirmed_at,
--     DROP COLUMN kva_confirmed_by,
--     DROP COLUMN kva_source,
--     DROP COLUMN kva_status;
