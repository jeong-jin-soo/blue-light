-- Phase 3 PR#1 — B-1 블로커 해결: DocumentRequest @Version 컬럼 추가
-- 동시 승인/반려 race 방지를 위한 낙관적 락 기반 컬럼.
-- MySQL 8.0 InnoDB 에서 ALGORITHM=INSTANT 로 즉시 완료된다.
--
-- 적용 시점: Phase 3 PR#1 백엔드 배포 직전.
-- 롤백: 파일 하단 주석 블록 참조.

ALTER TABLE document_request
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0
    AFTER status;

-- -----------------------------------------------------------------------------
-- Rollback (참고 — 운영에선 수동 실행)
-- -----------------------------------------------------------------------------
-- ALTER TABLE document_request DROP COLUMN version;
