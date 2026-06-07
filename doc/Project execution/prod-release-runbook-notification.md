# Production 배포 런북 — 알림 템플릿 + 누적 릴리스

작성: 2026-06-07 / 대상: develop → main (production)

## ⚠️ 0. 스코프 경고 (먼저 결정)
- prod 배포는 **`main` push 로 트리거**(`deploy-prod.yml`, 서버 2대 매트릭스, `production` 환경).
- **main 마지막 릴리스: 2026-05-06**. 현재 **develop 이 main 보다 104 커밋 앞섬**.
- 즉 이번 배포는 "알림 템플릿 기능"만이 아니라 **약 한 달치 누적 변경 전체 릴리스**다.
  - 알림 템플릿 관리/배선(PR-T·W), WhatsApp 알림 Phase 0, LEW 리뷰 동선, SLD 등 develop 의 모든 변경 포함.
- **결정 필요**: 전체 릴리스로 진행할지, 아니면 별도 릴리스 브랜치로 범위를 좁힐지(cherry-pick 은 104커밋 교차로 비현실적).

## 1. 현재 상태 (확인 완료 2026-06-07)
| 항목 | 상태 |
|------|------|
| prod 서버 a/b | running, 2026-04-12 이후 미재시작, SSH 22 OPEN, 웹 200 |
| prod IP | 자동 할당(EIP 아님) — 재시작 시 IP 변경 → `PROD_SERVER_HOST_1/2` 시크릿 stale 위험 |
| RDS | **단일 인스턴스 `bluelight-db`** 에 `bluelight`(dev) + `bluelight_prod`(prod) 두 DB 공존 |
| prod DB | `SQL_INIT_MODE=never`. 마이그레이션은 `DatabaseMigrationRunner`(@PostConstruct, 매 부팅) |
| **prod 알림 테이블** | **`bluelight_prod` 엔 `notifications`(레거시 인앱)만 존재. notification_templates/outbox/drafts/history/catalog 전부 없음(확인됨).** → 배포 부팅 시 `CREATE TABLE IF NOT EXISTS` 로 **완전한 스키마로 신규 생성** (dev 같은 드리프트 없음) |
| 사전 백업 | RDS 스냅샷 `bluelight-db-prerelease-notif-20260607` 생성됨 |

## 2. DB 마이그레이션 (자동 vs 수동)
- **자동**: `DatabaseMigrationRunner` 가 부팅 시 ① schema.sql 의 `CREATE TABLE IF NOT EXISTS` 전부 실행(신규 테이블 생성) ② 명시적 `ALTER TABLE ADD COLUMN` 마이그레이션(기존 테이블 컬럼 추가, idempotent).
- **리스크 — 기존 테이블 컬럼 드리프트**: 104커밋 중 *기존 테이블*에 컬럼을 schema.sql 에만 추가하고 runner ALTER 를 누락한 경우, prod 에서 컬럼 누락 → `Unknown column` 런타임 에러 (알림 테이블은 신규라 해당 없음, **그 외 테이블은 배포 후 로그 확인 필요**).
- **검증 단계**: 배포 직후 backend 로그에서 `Unknown column` / `SQLGrammarException` 모니터링.

## 3. 알림 템플릿 데이터 시드 (prod 핵심 작업)
prod 알림 테이블은 신규 생성되므로 **비어 있음**. dev 의 현재 콘텐츠(시드 142행 + 모든 카피 수정 + enabled 상태)를 prod 에 이식해야 함.
- 콘텐츠 수정 이력(전부 dev DB 에만 반영, 카피북 미반영분 포함):
  - mojibake 치환, on-site work 제거, manager→specialist 통일, optional 토큰 정규화
  - A-20(reviewer→License Lew), A-27(renew 문장), A-31(specialist)
  - enabled 상태: **A-20 + PR-W3a(A-31/A-33/A-36/C-01/M-03) 만 활성, 나머지 비활성**
- **권장 방식: 같은 인스턴스 내 크로스 DB 복사** (dev `bluelight` → prod `bluelight_prod`, 콘텐츠/enabled 정확 일치)
  - 배포로 prod 테이블 생성된 후 실행:
    ```sql
    INSERT INTO bluelight_prod.notification_templates
      (template_code, channel, locale, provider_template_name, subject, body_text,
       variables_json, enabled, version, catalog_meta_key, category, severity,
       recipient_roles, created_at, updated_at)
    SELECT template_code, channel, locale, provider_template_name, subject, body_text,
       variables_json, enabled, version, catalog_meta_key, category, severity,
       recipient_roles, NOW(6), NOW(6)
    FROM bluelight.notification_templates
    ON DUPLICATE KEY UPDATE subject=VALUES(subject), body_text=VALUES(body_text),
       variables_json=VALUES(variables_json), enabled=VALUES(enabled),
       category=VALUES(category), severity=VALUES(severity),
       recipient_roles=VALUES(recipient_roles), catalog_meta_key=VALUES(catalog_meta_key);
    -- notification_catalog 도 동일 패턴으로 복사
    ```
  - template_seq(PK)는 복사 제외 → prod 자동 증가. (template_code,channel,locale) 유니크로 중복 방지.
  - ⚠️ 연결은 `--default-character-set=utf8mb4` (이중 인코딩 사고 방지). 같은 인스턴스 utf8mb4 복사라 mojibake 위험 없음.
  - 콘텐츠 수정(reviewer→License Lew, manager→specialist, on-site 제거, optional 토큰 등)이 전부 dev 에 반영돼 있어 그대로 이식됨.

## 4. 배포 절차 (제안 순서)
1. **사전 점검**
   - [ ] develop CI 그린, dev 동작 정상 확인
   - [ ] `PROD_SERVER_HOST_1/2` 시크릿 == 현재 prod IP(43.209.205.207 / 43.210.100.80) 확인
   - [ ] prod RDS 백업(스냅샷) 생성
   - [ ] 알림 외 104커밋의 DB 변경 점검(runner ALTER 누락 여부 스폿체크)
2. **코드 릴리스**: develop → main 머지 → `deploy-prod` 자동 실행(빌드·이미지·2서버 배포, 부팅 시 runner 마이그레이션)
3. **배포 검증**: 두 서버 컨테이너 health, 웹 200, 로그 `Unknown column` 없음
4. **알림 시드**: prod RDS 에 notification_catalog + notification_templates 이식(utf8mb4)
5. **활성 확인**: A-20 등 enabled 상태 prod 반영 확인, test-send 로 렌더 검증
6. **모니터링**: 결제 확인(A-20) 실제 발송 정상, outbox 에러 없음

## 5. 리스크 레지스터
| 리스크 | 영향 | 완화 |
|--------|------|------|
| 104커밋 전체 릴리스 | 알림 외 변경도 prod 반영 | 전체 릴리스 동의 또는 범위 재조정 |
| 기존 테이블 컬럼 드리프트(runner ALTER 누락) | 런타임 Unknown column | 배포 후 로그 모니터 + 즉시 ALTER hotfix |
| prod IP 자동할당 | 재시작 시 배포 SSH 끊김 | prod 에 **Elastic IP 부여**(권장, 후속) |
| A-20 실발송 | 실제 고객에게 결제확인 메일 | 시드 후 test-send 선검증, 단계적 |
| dev 전용 DB 콘텐츠 미이식 | prod 카피가 구버전 | dev→prod dump 이식으로 해소 |

## 6. 롤백
- 코드: main 직전 커밋(6d2fbea)으로 revert push → deploy-prod 재실행(이전 이미지).
- 알림 테이블: 신규 생성이므로 비활성(enabled=FALSE) 일괄 토글로 발송 즉시 중단 가능.
- DB 스냅샷 복원(최후 수단).

## 7. 권장
1. 본 배포 전 **prod RDS 스냅샷** 필수.
2. **prod 서버 Elastic IP 부여**(dev 사고 재발 방지) — 별도 작업으로 선행 권장.
3. 알림은 **A-20 + 컨시어지(PR-W3a)만 활성** 상태로 시작, 나머지는 배선되며 점진 활성.
