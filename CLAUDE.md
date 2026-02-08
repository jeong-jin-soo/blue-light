# Blue Light - Electrical Installation Licence Platform

Singapore EMA 전기 설비 면허 신청/관리 플랫폼 (싱가포르 에너지시장청)

## Project Structure (Monorepo)

```
blue-light/
├── blue-light-backend/   # Spring Boot 4.0.2, Java 17, Gradle
├── blue-light-frontend/  # React 19, Vite 7, TypeScript 5.9, Tailwind CSS 4
└── doc/                  # 프로젝트 문서 (분석, 기획, 실행)
```

## Documentation Structure (doc/)

```
doc/
├── Project requester/     # 프로젝트 요청자로부터 받은 참고 자료
│   ├── 20260207/          # 2026-02-07에 취합된 자료 (Step by Step Guide, 텔레그램 등)
│   ├── Sample for Q&A 4feb2026/  # EMA 샘플 문서 (면허, 영수증, 검사보고서 등)
│   ├── Commercial Utilities Guide Book.pdf
│   ├── Drawing1.pdf
│   ├── General Frequently Asked Questions - EMA.pdf
│   ├── Project Blue Light.pdf
│   ├── Q&A 4feb2026.rtf
│   └── kVA in Singapore and AMP.xlsx
├── Project Analysis/      # 프로젝트 분석 문서 (요구사항, MVP 명세)
│   ├── Project Blue Light - Analysis (EN).html/pdf
│   ├── Project Blue Light - Analysis.html/pdf
│   └── Project Blue Light - MVP Specification.html/pdf
└── Project execution/     # 프로젝트 실행/진행 내역
    └── MVP Implementation Tasks.md  # 13단계 구현 태스크 정의
```

**폴더 규칙**:
- `Project requester/` → 요청자(클라이언트)로부터 받은 원본 자료
- `Project Analysis/` → 프로젝트 분석·기획 산출물
- `Project execution/` → 개발 진행 내역·태스크 관리
- 각 폴더 하위 `yyyymmdd/` 형식 폴더 → 해당 일자에 취합된 자료 묶음

## Backend Architecture

- **Port**: 8090
- **DB**: MySQL 8.0 (port 3307, Docker Compose)
- **Auth**: JWT (HS512, 24h expiry)
- **ORM**: JPA/Hibernate 7.2, Soft Delete 패턴 (`@SQLDelete` + `@SQLRestriction`)
- **Audit**: BaseEntity (createdAt, updatedAt, createdBy, updatedBy)

### Package Structure
```
com.bluelight.backend/
├── api/          # Controllers + Services + DTOs (auth, application, file, price, user, admin)
├── domain/       # JPA Entities + Repositories (user, application, payment, file, inspection, price)
├── config/       # SecurityConfig, JpaAuditingConfig
├── security/     # JwtTokenProvider, JwtAuthenticationFilter
└── common/       # GlobalExceptionHandler, BusinessException
```

### Domain Entities
- **users** → applications (1:N)
- **applications** → payments, files, inspections (1:N)
- **master_prices** - kVA 가격 티어 (7단계)

### Application Status Flow
```
PENDING_PAYMENT → PAID → IN_PROGRESS → COMPLETED (or EXPIRED)
```

### API Patterns
- Public: `/api/auth/**`, `/api/prices/**`
- Applicant: `/api/applications/**`, `/api/users/**`, `/api/files/**`
- Admin: `/api/admin/**` (hasRole('ADMIN'))
- Ownership 검증: Service 레이어에서 userSeq 비교

## Frontend Architecture

- **Dev Server**: port 5174
- **State**: Zustand (authStore, toastStore)
- **API Client**: Axios + interceptors (자동 JWT 헤더, 401 처리)
- **Routing**: React Router DOM 7 (role-based ProtectedRoute)

### Component Structure
```
src/
├── api/          # Axios API 모듈 (authApi, applicationApi, fileApi, priceApi, userApi, adminApi)
├── types/        # TypeScript 인터페이스 (index.ts)
├── stores/       # Zustand stores (authStore, toastStore)
├── router/       # 라우트 정의 + ProtectedRoute
├── components/
│   ├── ui/       # Button, Input, Select, Card, Badge, Modal, Toast, LoadingSpinner, EmptyState
│   ├── data/     # DataTable, Pagination
│   ├── domain/   # StatusBadge, DashboardCard, StepTracker, FileUpload
│   └── common/   # Layout, AuthLayout, ProtectedRoute
└── pages/
    ├── auth/     # LoginPage, SignupPage
    ├── applicant/ # DashboardPage, ApplicationListPage, ApplicationDetailPage, NewApplicationPage, ProfilePage
    └── admin/    # AdminDashboardPage, AdminApplicationListPage, AdminApplicationDetailPage, AdminUserListPage
```

### Design System
- Tailwind CSS v4 `@theme` 토큰 (index.css)
- Primary: #1a3a5c (navy blue), Inter font
- Button variants: primary, secondary, outline, ghost

## Development Commands

```bash
# Backend
cd blue-light-backend
docker compose up -d          # MySQL 시작
./gradlew bootRun             # 서버 시작 (port 8090)

# Frontend
cd blue-light-frontend
npm install
npm run dev                   # Dev 서버 (port 5174)
npm run build                 # Production 빌드
```

## Key Conventions

- 한국어 커밋 메시지 사용
- Soft delete 패턴 (deleted_at)
- DTO 패턴: Request/Response 분리
- File storage: LocalFileStorageService (S3 인터페이스 대비)
- Seed data: admin@bluelight.sg / admin1234

## Environment Variables

```
# Backend
DB_USERNAME=user
DB_PASSWORD=password
JWT_SECRET=bluelight-jwt-secret-key-for-development-minimum-256-bits-required
FILE_UPLOAD_DIR=./uploads

# Frontend (.env)
VITE_API_BASE_URL=http://localhost:8090/api
```
