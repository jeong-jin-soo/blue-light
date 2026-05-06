# LicenseKaki - Electrical Installation Licence Platform

Singapore 전기 설비 면허 신청/관리 플랫폼

## Tech Stack
- **Backend**: Spring Boot 4.0.2, Java 17, Gradle, JPA/Hibernate 7.2
- **Frontend**: React 19, Vite 7, TypeScript 5.9, Tailwind CSS 4, Zustand
- **DB**: MySQL 8.0 (port 3307, Docker Compose)
- **Auth**: JWT (HS512, 24h expiry)

## Development Commands

```bash
# Backend (port 8090)
cd blue-light-backend
docker compose up -d && ./gradlew bootRun

# Frontend (port 5174)
cd blue-light-frontend
npm install && npm run dev

# AI Service (port 8100) — 반드시 --reload 사용!
cd blue-light-ai
python -m uvicorn app.main:app --host 0.0.0.0 --port 8100 --reload
```

### AI Service 개발 주의사항
- **`--reload` 필수**: `--reload` 없이 실행하면 소스 변경이 실행 중인 프로세스에 반영되지 않음
  - `--reload` 미사용 시 디스크의 소스와 메모리의 코드가 달라져 디버깅이 극히 어려움
- **버전 확인**: `GET /api/version` 엔드포인트로 실행 중인 코드의 git commit, branch, dirty 상태 확인 가능
  - `curl http://localhost:8100/api/version`
- **개발서버**: `43.210.92.190:8100` (Docker 컨테이너 `bluelight-sld-agent`)

### 배포 전 로컬 검증 필수
- **반드시 로컬 PC에서 먼저 동작을 확인한 후 개발서버에 배포할 것**
- AI Service 변경 시 로컬 검증 절차:
  1. 로컬 AI 서비스 실행: `python -m uvicorn app.main:app --host 0.0.0.0 --port 8100 --reload`
  2. 변경된 기능 테스트 (SLD 생성 등)
  3. 출력 결과 확인 (PDF 다운로드, 브라우저 미리보기 등)
  4. 정상 동작 확인 후에만 commit → push → 개발서버 배포
- 문법 체크(`ast.parse`)만으로는 불충분 — 실제 기능 동작 확인 필수

## SLD 레이아웃 코드 수정 시 필수 참조
- **도메인 지식**: `blue-light-ai/data/sg-sld-domain-knowledge.md` — 싱가포르 SLD 컴포넌트 흐름 순서, 부품 역할, 전기 규정
- **흐름 순서 명세**: `sections.py:CT_METERING_SPINE_ORDER` — CT 계측 스파인 순서 상수 (자동 테스트로 검증)
- **자동 검증 테스트**: `tests/test_spine_flow_order.py` — 스파인 컴포넌트 배치 순서 검증
- **섹션 완전성 테스트**: `tests/test_section_completeness.py` — 입력 조합별 섹션 렌더링 여부 자동 검증
- **원칙**: 컴포넌트 배치 순서는 반드시 실제 전기적 흐름(전원→부하)을 따를 것. SP Group §6.9.6 참조.

## SLD 비교 분석 시 필수 절차
생성된 SLD와 LEW 레퍼런스를 비교할 때, 반드시 아래 14개 섹션을 **순서대로 전수 점검**해야 한다.
"눈에 띄는 차이"만 찾으면 렌더링되지 않은 섹션을 놓치게 된다.

**비교 순서** (하단 → 상단, 전원 → 부하):
1. INCOMING SUPPLY — supply 라벨, AC심볼/케이블+tick, 위상선
2. INCOMING CABLE — 케이블 사양, tick mark 위치
3. METER BOARD *(sp_meter일 때)* — 점선 박스, ISO→KWH→MCB 배치
4. UNIT ISOLATOR *(non-meter일 때)* — 심볼 형태(enclosed/open), 라벨 위치(좌/우), 등급
5. OUTGOING CABLE — 아이솔레이터→DB 사이 케이블
6. CT PRE-MCCB FUSE *(ct_meter일 때)* — 2A 퓨즈+표시등
7. MAIN BREAKER — 심볼 종류, 등급, 극수, 차단용량
8. CT METERING *(ct_meter일 때)* — CT hook, ELR, ASS/Ammeter, VSS/Voltmeter, kWh, BI CONNECTOR
9. ELCB/RCCB — 심볼, 등급, 감도
10. INTERNAL CABLE — 케이블 사양
11. MAIN BUSBAR — 명칭, 등급, DB 정보 박스
12. CIRCUIT BRANCHES — 심볼 종류(MCB/ISOLATOR 구분), 위상 그룹 간격, 라벨
13. DB BOX — 점선 박스 크기, DB 이름
14. EARTH BAR — 심볼, 도체 라벨

**각 섹션에서 확인할 것**: ① 존재 여부 ② 심볼 형태 ③ 텍스트 내용 ④ 라벨 위치 ⑤ 간격·비율

## Key Conventions
- 한국어 커밋 메시지 사용
- Soft delete 패턴 (deleted_at, @SQLDelete + @SQLRestriction)
- DTO 패턴: Request/Response 분리
- Audit: BaseEntity (createdAt, updatedAt, createdBy, updatedBy)
- File storage: LocalFileStorageService (S3 인터페이스 대비)
- API: Public `/api/auth/**`, `/api/prices/**` | Applicant `/api/applications/**` | Admin `/api/admin/**`

## 🟢 설계 원칙 — 절대 위반 금지

### 1. "설정 우선" 원칙 (Single Source of Truth)
관리자가 설정한 값(master_prices, system_settings, role_metadata, master data 테이블 등)이 존재하는 도메인 정보는 **UI·서비스·검증 어디에서든 반드시 그 설정을 소비**해야 한다. **하드코딩 금지**.

대표 사례:
- **kVA tier 목록** → `master_prices` 테이블(`priceApi.getPrices()`) 사용. UI 옵션 하드코딩 금지. (KvaConfirmModal.tsx 참고)
- **권한·역할 라벨** → `role_metadata` / `constants/roles.ts` 사용. 하드코딩된 역할 배열 금지.
- **PayNow 계좌·수수료 등 정산 정보** → `system_settings`에서 조회.
- **Retailer/Consumer Type 등 마스터 데이터** → 관리자 설정 테이블을 만들어 거기서 읽는다.

검토 체크리스트:
- [ ] 새 드롭다운/옵션/상수를 만들 때: 이미 admin 설정에 있는가?
- [ ] 있다면 그 설정을 API로 로드하는가?
- [ ] 하드코딩 상수는 오직 **JIT·보안·법적 고정 값**(예: JWT 만료, 암호 최소 길이)일 때만 허용.

특수 예외 (기록 필수):
- 하드코딩이 불가피하면 **코드에 `// 설정 우선 원칙 예외: <이유>` 주석** + `doc/Project Analysis/`에 사유 기재.

### 2. JIT 정보 수집
신청자에게서 받을 정보는 "반드시 필요한 시점"에만 요청. 이미 받은 정보는 재요청 금지. 상세: `doc/Project Analysis/ema-field-jit-plan.md`, `doc/Project Analysis/jit-reask-audit.md` §9 Layer 정의 (Application Layer B = 신청 당시 정본 / User Layer A = 기본값 제공자).

## Environment Variables

```
# Backend (blue-light-backend/.env — Git에서 제외됨)
DB_USERNAME=user  |  DB_PASSWORD=password
JWT_SECRET=bluelight-jwt-secret-key-for-development-minimum-256-bits-required
FILE_UPLOAD_DIR=./uploads
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174
FILE_ENCRYPTION_KEY=<Base64 AES-256 키>  # .env 파일에 설정, bootRun 시 자동 로드

# Frontend (.env)
VITE_API_BASE_URL=http://localhost:8090/api
```

### 파일 암호화 키 (FILE_ENCRYPTION_KEY)
- **로컬/개발**: `blue-light-backend/.env`에 설정 (Gradle bootRun이 자동 로드)
- **운영**: 서버 환경변수 또는 Secrets Manager에 별도 설정
- 키 분실 시 암호화된 파일 복구 불가 — 안전한 곳에 백업 필수

## Application Status Flow
```
PENDING_REVIEW → REVISION_REQUESTED ↔ PENDING_REVIEW → PENDING_PAYMENT → PAID → IN_PROGRESS → COMPLETED (or EXPIRED)
```

## Documentation (doc/)
- `Project requester/` → 클라이언트 원본 자료
- `Project Analysis/` → 분석·기획 산출물
- `Project execution/` → 개발 진행·태스크 관리
- `manual/` → 사용자 메뉴얼 (역할별 분리)
  - `applicant/` — 신청자용 메뉴얼
  - `lew/` — LEW용 메뉴얼
  - `admin/` — 관리자용 메뉴얼
  - 각 디렉토리: `index.html` + `screenshots/`
  - 기존 통합본(`user-manual.html`, `user-manual-ko.html`)은 레거시
