-- =====================================================================
-- Phase 2 PR#4 — LOA 스냅샷 컬럼 추가 + 기존 데이터 백필
-- =====================================================================
-- 본 프로젝트에는 별도 `loa` 테이블이 존재하지 않는다.
-- LOA 상태(서명 URL/서명 시각)는 `applications` 테이블에 저장되므로
-- 스냅샷 컬럼도 동일 테이블에 추가한다.
--
-- 참고:
--   - 01-spec.md §3-3: 스냅샷 4컬럼 (applicant_name / company_name / uen / designation)
--   - 03-security-review.md §10 B-5: @Column(updatable=false) + 불변 정책
--   - 03-security-review.md R-2: snapshot_backfilled_at 플래그로 백필 row 식별
-- =====================================================================

-- 1) 컬럼 추가 (최초에는 nullable — 백필 후 NOT NULL로 변경)
ALTER TABLE applications
    ADD COLUMN applicant_name_snapshot VARCHAR(100) NULL AFTER loa_signed_at,
    ADD COLUMN company_name_snapshot   VARCHAR(100) NULL AFTER applicant_name_snapshot,
    ADD COLUMN uen_snapshot            VARCHAR(20)  NULL AFTER company_name_snapshot,
    ADD COLUMN designation_snapshot    VARCHAR(50)  NULL AFTER uen_snapshot,
    ADD COLUMN snapshot_backfilled_at  DATETIME(6)  NULL AFTER designation_snapshot;

-- 2) 백필: 기존 LOA 서명/생성 이력이 있는 application에 대해 현재 User 값을 복사
--    idempotent 보장 — applicant_name_snapshot IS NULL 조건으로 재실행 안전.
--    (R5 보완: 백필 당시 User 값과 실제 LOA 발급 시점 User 값이 다를 수 있으므로
--     snapshot_backfilled_at 에 NOW()를 기록하여 법적 쟁송 시 원본 시점 vs 백필 시점 구분)
UPDATE applications a
    JOIN users u ON u.user_seq = a.user_seq
   SET a.applicant_name_snapshot = TRIM(CONCAT(IFNULL(u.first_name, ''), ' ', IFNULL(u.last_name, ''))),
       a.company_name_snapshot   = u.company_name,
       a.uen_snapshot            = u.uen,
       a.designation_snapshot    = u.designation,
       a.snapshot_backfilled_at  = NOW(6)
 WHERE a.applicant_name_snapshot IS NULL
   AND (a.loa_signature_url IS NOT NULL
        OR EXISTS (
            SELECT 1 FROM files f
             WHERE f.application_seq = a.application_seq
               AND f.file_type = 'OWNER_AUTH_LETTER'
        ));

-- 3) 백필 대상이 아닌 나머지 row는 빈 문자열로 채운다 — NOT NULL 제약을 걸기 위한 준비.
--    (LOA가 아예 없는 application은 법적 스냅샷 대상이 아니지만 컬럼은 NOT NULL 필요)
UPDATE applications
   SET applicant_name_snapshot = ''
 WHERE applicant_name_snapshot IS NULL;

-- 4) applicant_name_snapshot NOT NULL 확정 (신규 LOA는 항상 값 기록)
ALTER TABLE applications
    MODIFY COLUMN applicant_name_snapshot VARCHAR(100) NOT NULL DEFAULT '';
