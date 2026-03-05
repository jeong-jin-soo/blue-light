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

## Key Conventions
- 한국어 커밋 메시지 사용
- Soft delete 패턴 (deleted_at, @SQLDelete + @SQLRestriction)
- DTO 패턴: Request/Response 분리
- Audit: BaseEntity (createdAt, updatedAt, createdBy, updatedBy)
- File storage: LocalFileStorageService (S3 인터페이스 대비)
- API: Public `/api/auth/**`, `/api/prices/**` | Applicant `/api/applications/**` | Admin `/api/admin/**`

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
