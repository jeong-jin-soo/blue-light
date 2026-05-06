# Phase 1 배포 Runbook — Applicant UX

**대상 환경**: 개발서버 (`43.210.92.190`)
**범위**: `schema.sql` 업데이트 + `application.applicant_type` 컬럼 추가
**방식**: Flyway 미도입. 코드는 CI/CD 자동 배포, DB는 수동 SQL 실행.
**관련 문서**: `01-spec.md` §5, `03-security-review.md` B-3

---

## 1. 사전 점검

배포 시작 전 반드시 확인:

- [ ] **DB 백업**: `mysqldump` 스냅샷 생성 및 S3 또는 로컬 저장
- [ ] **현재 레코드 수 파악**: `SELECT COUNT(*) FROM application;` 결과 기록
- [ ] **서비스 상태**: 백엔드(8090), 프론트(443), MySQL(3307) 정상 동작
- [ ] **진행 중 신청 확인**: `PENDING_REVIEW` 상태 레코드 수 — 배포 직후 영향 예측
- [ ] **점검 공지**: 배포 예정 시각을 관리자/LEW에게 사전 공지 (최소 1시간 전)
- [ ] **로컬 검증 완료**: 로컬 DB에서 SQL 3단계 스크립트를 먼저 실행해 성공 확인

```bash
# 백업 예시
ssh -i ~/.ssh/bluelight-key.pem ec2-user@43.210.92.190
docker exec bluelight-mysql mysqldump -uuser -ppassword bluelight application \
  > /tmp/application_backup_$(date +%Y%m%d_%H%M%S).sql
```

---

## 2. 배포 단계 (순서 엄수)

### 단계 1 — 코드 배포 (CI/CD)

1. `develop` 브랜치에 커밋 push → GitHub Actions 자동 실행 → EC2 배포
2. 배포 로그 확인: `docker logs -f bluelight-backend`
3. `ddl-auto: none` 이므로 `schema.sql`은 **기동 시 자동 적용되지 않음** → 단계 2에서 수동 실행
4. 백엔드 기동 완료(`Started BluelightApplication`) 확인 후 다음 단계

### 단계 2 — DB 마이그레이션 (수동)

```bash
ssh -i ~/.ssh/bluelight-key.pem ec2-user@43.210.92.190
docker exec -it bluelight-mysql mysql -uuser -ppassword bluelight
```

**2-1. 컬럼 추가 (NULL 허용)**
```sql
ALTER TABLE application
  ADD COLUMN applicant_type VARCHAR(20) NULL COMMENT 'INDIVIDUAL | CORPORATE';

-- 검증
SHOW COLUMNS FROM application LIKE 'applicant_type';
```

**2-2. 기존 레코드 백필**
```sql
UPDATE application SET applicant_type = 'INDIVIDUAL' WHERE applicant_type IS NULL;

-- 검증 (0이어야 함)
SELECT COUNT(*) FROM application WHERE applicant_type IS NULL;
```

**2-3. NOT NULL + 기본값 적용**
```sql
ALTER TABLE application
  MODIFY COLUMN applicant_type VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL';

-- 검증
DESCRIBE application;
```

### 단계 3 — 배포 후 확인

```sql
SELECT COUNT(*) FROM application WHERE applicant_type IS NULL;  -- 0
SELECT applicant_type, COUNT(*) FROM application GROUP BY applicant_type;
```

- [ ] Smoke test (§6) 수행
- [ ] 로그에 ERROR 없음 확인: `docker logs bluelight-backend --since 10m | grep ERROR`

---

## 3. 롤백 절차

### 단계 2-1 실패 (컬럼 추가 오류)
```sql
-- 중복 컬럼 확인
SHOW COLUMNS FROM application LIKE 'applicant_type';
-- 롤백
ALTER TABLE application DROP COLUMN applicant_type;
```

### 단계 2-2 실패 (UPDATE 실패)
- 롤백 불필요. 원인 분석 후 재실행.

### 단계 2-3 실패 (NOT NULL 변환 실패)
- 원인: NULL 잔존 → 2-2 재실행 후 재시도
- 완전 롤백: `ALTER TABLE application DROP COLUMN applicant_type;` 후 처음부터

### 앱 레벨 롤백
```bash
git revert <commit-sha>
git push origin develop   # CI/CD 자동 재배포
```
DB 롤백은 자동화되지 않음 — 위 SQL을 수동 실행.

---

## 4. 장애 시나리오별 대응

| 시나리오 | 원인 | 대응 |
|---|---|---|
| MySQL 접속 실패 | 컨테이너 다운 | `docker ps` 확인 → `docker start bluelight-mysql` |
| ALTER 락 대기 | 장시간 트랜잭션 | `SHOW PROCESSLIST;` → 원인 세션 `KILL <id>` |
| UPDATE 지연 | 레코드 수 과다 | 배치 처리: `UPDATE ... LIMIT 1000;` 반복 |
| 앱 500 에러 | 스키마-코드 불일치 | 단계 2 완료 여부 재확인, 필요 시 앱 롤백 |

UPDATE 배치 예시:
```sql
UPDATE application SET applicant_type = 'INDIVIDUAL'
 WHERE applicant_type IS NULL LIMIT 1000;
```

---

## 5. LEW 공지 절차

**대시보드 배너**: 배포 직후 노출, 2주간 유지(Phase 2 배포 시 자동 숨김 또는 수동 off).

**이메일 템플릿 (한국어)**
> 제목: [LicenseKaki] 신청 프로세스 개선 안내
>
> 안녕하세요, LEW님.
> 신청자 구분(개인/법인) 선택 기능이 추가되었습니다. 기존 신청 건은 자동으로 개인(Individual)으로 분류되며, 추가 조치는 필요하지 않습니다.
> 문의: support@licensekaki.com

**이메일 템플릿 (English)**
> Subject: [LicenseKaki] Application Flow Update
>
> Dear LEW,
> We've added an Applicant Type (Individual/Corporate) selection step. Existing applications are auto-classified as Individual — no action required.
> Contact: support@licensekaki.com

---

## 6. Smoke Test (배포 후 5분 내)

1. `https://dev.licensekaki.com` 접속 → 정상 렌더
2. 신규 가입 페이지 → Phone/Company 필드 **없음** 확인
3. 로그인 → 신청 Step 0 진입 → 파일 업로드 UI **0개** 확인
4. Individual 선택 → 다음 단계 이동 성공
5. Corporate 선택 → 회사 정보 입력 → 다음 단계 이동 성공
6. 관리자(`admin@bluelight.sg`) 로그인 → 기존 신청 목록 정상 조회
7. LEW(`lew@bluelight.sg`) 로그인 → 할당 신청 정상 조회

실패 항목 1개 이상 시 §3 롤백 절차 개시.

---

## 7. 책임자 & 연락 체계

| 역할 | 담당 | 비고 |
|---|---|---|
| 배포 실행 (코드) | 개발자 | develop push & CI/CD 모니터링 |
| DB SQL 실행 | 개발자 / DevOps | SSH + MySQL 접근 권한자 |
| Smoke Test | 개발자 | §6 체크리스트 |
| 장애 1차 대응 | 개발자 | 10분 내 롤백 판단 |
| 에스컬레이션 | 프로젝트 리드 → 클라이언트 | 30분 내 복구 불가 시 |

**배포 창 권장**: 평일 오전(트래픽 적은 시간). 금요일 오후·주말 배포 금지.
