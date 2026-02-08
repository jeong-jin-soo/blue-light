# Project Blue Light - MVP Implementation Tasks

> 각 Phase는 독립적으로 실행 가능한 단위입니다.
> Phase 완료 후 동작 확인 → 다음 Phase 진행 순서로 작업합니다.

---

## Phase 1: Database & Project Foundation
> Backend DB 초기화 + Frontend 프로젝트 기본 골격

### 1-1. Database Schema & Seed Data
- [ ] `schema.sql` 작성 (6개 테이블: users, applications, payments, inspections, files, master_prices)
- [ ] `data.sql` 작성 (MasterPrice kVA 티어 시드 데이터 + Admin 계정)
- [ ] `application.yaml`에 SQL 초기화 설정 추가 (`spring.sql.init`)
- [ ] Docker MySQL 기동 후 테이블 생성 및 시드 데이터 확인

### 1-2. Frontend Project Skeleton
- [ ] `react-router-dom` 라우터 설정 (`router/index.tsx`)
- [ ] 공통 레이아웃 컴포넌트 (`components/common/Layout.tsx` - 사이드바 + 헤더 + 콘텐츠 영역)
- [ ] 인증 가드 컴포넌트 (`components/common/ProtectedRoute.tsx` - 역할 기반 라우트 보호)
- [ ] `App.tsx` 교체 (기존 Vite 보일러플레이트 → Router Provider)
- [ ] 빈 페이지 placeholder 생성 (LoginPage, SignupPage, DashboardPage 등)

### ✅ Phase 1 완료 확인
- `docker compose up -d` → MySQL 테이블/시드 데이터 확인
- `./gradlew bootRun` → 백엔드 정상 기동 (schema validate 통과)
- `npm run dev` → 프론트엔드 기동, `/login` 라우트 접근 확인

---

## Phase 2: Authentication UI
> 기존 백엔드 Auth API를 활용한 프론트엔드 인증 화면

### 2-1. Login & Signup Pages
- [ ] `LoginPage.tsx` (이메일/비밀번호 폼 + 에러 메시지 + 회원가입 링크)
- [ ] `SignupPage.tsx` (이메일/비밀번호/이름/전화번호 폼 + 유효성 검증)
- [ ] 기존 `authStore` + `authApi` 연동
- [ ] 로그인 성공 시 역할별 리다이렉트 (APPLICANT → `/dashboard`, ADMIN → `/admin/dashboard`)
- [ ] 로그아웃 처리 (토큰 삭제 + `/login`으로 이동)

### ✅ Phase 2 완료 확인
- 회원가입 → 로그인 → 대시보드 진입 확인
- 잘못된 자격증명 시 에러 메시지 표시 확인
- 비로그인 상태에서 보호 라우트 접근 시 `/login` 리다이렉트 확인

---

## Phase 3: Backend - Application & Price APIs
> 핵심 비즈니스 로직 백엔드 API 구현

### 3-1. Price API (Public)
- [ ] `PriceService.java` (활성 가격 목록 조회, kVA 기반 가격 계산)
- [ ] `PriceController.java` (`GET /api/prices`, `GET /api/prices/calculate?kva=`)
- [ ] `PriceResponse.java` DTO
- [ ] SecurityConfig에 `/api/prices/**` public 접근 허용 확인

### 3-2. Application API (Applicant)
- [ ] `CreateApplicationRequest.java` DTO (address, postalCode, buildingType, selectedKva + validation)
- [ ] `ApplicationResponse.java` DTO (상세 응답)
- [ ] `ApplicationListResponse.java` DTO (목록 응답)
- [ ] `ApplicationSummaryResponse.java` DTO (대시보드 카운트)
- [ ] `ApplicationService.java` (생성, 내 목록 조회, 상세 조회, 요약 통계)
- [ ] `ApplicationController.java` (`POST`, `GET /api/applications`, `GET /:id`, `GET /summary`)

### 3-3. Application Admin API
- [ ] `UpdateStatusRequest.java` DTO
- [ ] `CompleteApplicationRequest.java` DTO (licenseNumber, licenseExpiryDate)
- [ ] `AdminApplicationController.java` (`GET /api/admin/applications`, `GET /:id`, `PATCH /:id/status`, `POST /:id/complete`)
- [ ] `AdminApplicationService.java` (전체 목록 페이지네이션, 상태 변경, 완료 처리)
- [ ] SecurityConfig에 `/api/admin/**` ADMIN 역할 제한 추가

### ✅ Phase 3 완료 확인
- Postman/curl로 전체 API 테스트:
  - `GET /api/prices` → kVA 가격 목록 반환
  - `POST /api/applications` → 신청 생성 (PENDING_PAYMENT)
  - `GET /api/applications` → 내 신청 목록
  - `GET /api/admin/applications` → 전체 신청 목록 (ADMIN 토큰)
  - `PATCH /api/admin/applications/:id/status` → 상태 변경

---

## Phase 4: Backend - File & Payment APIs
> 파일 업로드/다운로드 + 결제 확인 API

### 4-1. File Service & API
- [ ] `FileStorageService.java` 인터페이스 정의 (store, load, delete)
- [ ] `LocalFileStorageService.java` 구현 (로컬 디스크 저장, 설정 가능한 업로드 경로)
- [ ] `FileService.java` (업로드, 목록 조회, 다운로드, 삭제)
- [ ] `FileController.java` (`POST /api/applications/:id/files`, `GET .../files`, `GET /api/files/:id/download`, `DELETE`)
- [ ] `FileResponse.java` DTO
- [ ] `application.yaml`에 파일 업로드 경로 설정 추가
- [ ] Multipart 파일 크기 제한 설정 (max 10MB)

### 4-2. Payment API
- [ ] `ConfirmPaymentRequest.java` DTO (transactionId, amount, paymentMethod)
- [ ] `PaymentResponse.java` DTO
- [ ] `PaymentService.java` (결제 확인 처리, 신청 건별 결제 조회)
- [ ] `PaymentController.java` (`GET /api/applications/:id/payments`, `POST /api/admin/applications/:id/payments/confirm`)

### ✅ Phase 4 완료 확인
- 파일 업로드 (PDF, JPG) → 로컬 디스크 저장 확인
- 파일 다운로드 → 원본 파일명으로 다운로드 확인
- 관리자 결제 확인 → Payment(SUCCESS) 생성 + Application status → PAID 확인

---

## Phase 5: Backend - User API
> 프로필 관리 + 관리자 사용자 목록

### 5-1. User API
- [ ] `UpdateProfileRequest.java` DTO (name, phone)
- [ ] `ChangePasswordRequest.java` DTO (currentPassword, newPassword)
- [ ] `UserResponse.java` DTO
- [ ] `UserService.java` (프로필 조회/수정, 비밀번호 변경, 관리자용 전체 목록)
- [ ] `UserController.java` (`GET /api/users/me`, `PUT /api/users/me`, `PUT /api/users/me/password`)
- [ ] `AdminUserController.java` (`GET /api/admin/users`)

### ✅ Phase 5 완료 확인
- `GET /api/users/me` → 현재 로그인 사용자 정보 반환
- `PUT /api/users/me` → 이름/전화번호 수정 확인
- `GET /api/admin/users` → 전체 사용자 목록 (ADMIN 토큰)

---

## Phase 6: Frontend - API Layer & Common Components
> 프론트엔드 API 모듈 + 재사용 공통 컴포넌트

### 6-1. API Modules
- [ ] `applicationApi.ts` (create, list, getById, getSummary)
- [ ] `priceApi.ts` (list, calculate)
- [ ] `fileApi.ts` (upload, listByApplication, download, delete)
- [ ] `paymentApi.ts` (listByApplication)
- [ ] `userApi.ts` (getMe, updateMe, changePassword)
- [ ] `adminApi.ts` (applications: list/detail/updateStatus/complete, payments: confirm, users: list)

### 6-2. Common Components
- [ ] `StatusBadge.tsx` (상태별 컬러 배지)
- [ ] `LoadingSpinner.tsx` (로딩 인디케이터)
- [ ] `EmptyState.tsx` (데이터 없음 표시)
- [ ] `DataTable.tsx` (페이지네이션 테이블)
- [ ] `StepTracker.tsx` (신청 진행 단계 표시기)
- [ ] `FileUpload.tsx` (드래그앤드롭 파일 업로드)

### ✅ Phase 6 완료 확인
- 각 API 모듈의 타입 안전성 확인 (TypeScript 컴파일 에러 없음)
- 공통 컴포넌트 단독 렌더링 테스트

---

## Phase 7: Frontend - Applicant Dashboard & Application Creation
> 신청자 핵심 화면: 대시보드 + 신규 신청

### 7-1. Applicant Dashboard
- [ ] `DashboardPage.tsx` (메인 대시보드 페이지)
- [ ] `DashboardCards.tsx` (요약 카드: 전체/결제대기/진행중/완료)
- [ ] 최근 5건 신청 목록 표시
- [ ] "신규 신청" CTA 버튼 → `/applications/new`로 이동

### 7-2. New Application Form (Multi-Step)
- [ ] `NewApplicationPage.tsx` (멀티스텝 폼 컨테이너)
- [ ] `Step1Address.tsx` (주소 + 우편번호 + 건물유형)
- [ ] `Step2KvaPrice.tsx` (kVA 선택 드롭다운 + 자동 가격 표시)
- [ ] `Step3Review.tsx` (입력 내용 검토 + 제출)
- [ ] 제출 성공 시 결제 안내 표시 (PayNow 정보) + 신청 상세로 이동

### ✅ Phase 7 완료 확인
- 대시보드에서 요약 카드 + 최근 목록 표시 확인
- 신규 신청: Step1 → Step2 (kVA 선택 시 가격 자동 표시) → Step3 → 제출 → PENDING_PAYMENT 상태 확인

---

## Phase 8: Frontend - Application List & Detail
> 신청자: 내 신청 목록 + 상세 페이지 + 파일 업로드/다운로드

### 8-1. Application List
- [ ] `ApplicationListPage.tsx` (신청 목록)
- [ ] `ApplicationCard.tsx` (개별 신청 카드: 주소, kVA, 금액, 상태 배지, 생성일)
- [ ] 상태별 필터 탭 (전체 / 결제대기 / 진행중 / 완료)

### 8-2. Application Detail
- [ ] `ApplicationDetailPage.tsx` (신청 상세)
- [ ] StepTracker 연동 (현재 상태에 따른 진행 단계 표시)
- [ ] 업로드된 파일 목록 + 다운로드 링크
- [ ] SLD 파일 업로드 영역 (FileUpload 컴포넌트 연동)
- [ ] 결제 이력 섹션
- [ ] COMPLETED 상태일 때 라이선스 번호/만료일 표시

### ✅ Phase 8 완료 확인
- 신청 목록에서 상태별 필터 동작 확인
- 상세 페이지에서 파일 업로드 → 목록 반영 확인
- 파일 다운로드 동작 확인

---

## Phase 9: Frontend - Admin Pages
> 관리자: 대시보드 + 신청 관리 + 결제 확인 + 사용자 관리

### 9-1. Admin Dashboard
- [ ] `AdminDashboardPage.tsx` (관리자 대시보드)
- [ ] 전체 통계 카드 (총 신청/결제대기/진행중/완료/사용자수)
- [ ] 최근 신청 목록 + 상태별 바로가기 링크

### 9-2. Admin Application Management
- [ ] `AdminApplicationListPage.tsx` (전체 신청 목록 + 검색/필터/페이지네이션)
- [ ] `AdminApplicationDetailPage.tsx` (신청 상세 + 관리 기능)
- [ ] `ApplicationStatusManager.tsx` (상태 변경 드롭다운/버튼)
- [ ] `PaymentConfirmation.tsx` (결제 확인 모달: transactionId, amount, method 입력)
- [ ] 라이선스 완료 처리 폼 (licenseNumber + expiryDate 입력)
- [ ] 관리자 파일 업로드 (LICENSE_PDF, REPORT_PDF 타입 선택)

### 9-3. Admin User Management
- [ ] `AdminUserListPage.tsx` (전체 사용자 목록 + 검색)
- [ ] `UserTable.tsx` (사용자 테이블: 이메일, 이름, 역할, 가입일, 신청 건수)

### ✅ Phase 9 완료 확인
- 관리자 로그인 → 대시보드 통계 확인
- 신청 목록에서 특정 건 선택 → 결제 확인 → 상태 변경 → 완료 처리 전체 흐름
- 사용자 목록 조회 + 검색 동작 확인

---

## Phase 10: Frontend - Profile Page
> 신청자 프로필 관리

### 10-1. Profile Page
- [ ] `ProfilePage.tsx` (프로필 조회/수정)
- [ ] 이름, 전화번호 수정 폼
- [ ] 비밀번호 변경 폼 (현재 비밀번호 + 새 비밀번호 + 확인)
- [ ] 이메일은 읽기 전용

### ✅ Phase 10 완료 확인
- 프로필 수정 → 저장 → 반영 확인
- 비밀번호 변경 → 재로그인 확인

---

## Phase 11: E2E Integration Testing & Bug Fixes
> 전체 흐름 통합 테스트 + 버그 수정

### 11-1. E2E Flow Verification
- [ ] **신청자 플로우**: 가입 → 로그인 → 대시보드 → 신규 신청 → SLD 업로드 → 목록 확인
- [ ] **관리자 플로우**: 로그인 → 신청 목록 → 결제 확인 → IN_PROGRESS → 파일 업로드 → 완료 처리
- [ ] **신청자 확인**: 완료 건 상세 → 라이선스 정보 확인 → PDF 다운로드
- [ ] 발견된 버그 수정

### 11-2. Edge Cases
- [ ] 빈 목록 상태 (EmptyState 표시)
- [ ] 네트워크 에러 처리
- [ ] 폼 유효성 검증 에러 메시지
- [ ] JWT 만료 시 자동 로그아웃 + 리다이렉트

### ✅ Phase 11 완료 확인
- 전체 E2E 플로우 3회 반복 테스트 통과

---

## Phase 12: Responsive Design & UX Polish
> 모바일 대응 + UX 개선

### 12-1. Responsive Design
- [ ] 모바일 뷰포트 사이드바 토글 (햄버거 메뉴)
- [ ] 테이블 → 카드 레이아웃 전환 (모바일)
- [ ] 폼 모바일 최적화
- [ ] 주요 뷰포트 테스트 (375px, 768px, 1024px, 1440px)

### 12-2. UX Improvements
- [ ] 로딩 스켈레톤 / 스피너
- [ ] 토스트 알림 (성공/에러)
- [ ] 확인 다이얼로그 (상태 변경, 삭제 등)
- [ ] 폼 제출 중 버튼 비활성화

### ✅ Phase 12 완료 확인
- 모바일 시뮬레이터에서 전체 플로우 동작 확인
- 모든 사용자 액션에 적절한 피드백 (로딩/성공/에러) 확인

---

## Phase 13: Security Review & Documentation
> 보안 점검 + 프로젝트 문서화

### 13-1. Security Review
- [ ] 모든 `/api/admin/**` 엔드포인트 ADMIN 역할 검증 확인
- [ ] 신청자가 타인의 신청 데이터 접근 불가 확인
- [ ] 파일 다운로드 시 권한 검증 확인
- [ ] CORS 설정 검토
- [ ] 콘솔 로그 제거

### 13-2. Documentation
- [ ] README.md 업데이트 (셋업 가이드, 실행 방법)
- [ ] API 엔드포인트 목록 문서
- [ ] 환경 변수 가이드

### ✅ Phase 13 완료 확인
- 보안 체크리스트 전체 통과
- README만으로 새 개발자가 프로젝트 셋업 가능

---

## Summary

| Phase | 내용 | 예상 범위 |
|-------|------|----------|
| **1** | Database & Project Foundation | Backend DB + Frontend 골격 |
| **2** | Authentication UI | 로그인/회원가입 화면 |
| **3** | Backend: Application & Price APIs | 핵심 비즈니스 API |
| **4** | Backend: File & Payment APIs | 파일 + 결제 API |
| **5** | Backend: User API | 프로필 + 사용자관리 API |
| **6** | Frontend: API Layer & Common Components | API 모듈 + 공통 컴포넌트 |
| **7** | Frontend: Dashboard & Application Creation | 대시보드 + 신규 신청 |
| **8** | Frontend: Application List & Detail | 신청 목록 + 상세 + 파일 |
| **9** | Frontend: Admin Pages | 관리자 전체 화면 |
| **10** | Frontend: Profile | 프로필 관리 |
| **11** | E2E Testing & Bug Fixes | 통합 테스트 + 버그 수정 |
| **12** | Responsive & UX Polish | 모바일 대응 + UX 개선 |
| **13** | Security & Documentation | 보안 점검 + 문서화 |
