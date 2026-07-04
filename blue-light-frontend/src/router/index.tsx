import { createBrowserRouter } from 'react-router-dom';
import Layout from '../components/common/Layout';
import ProtectedRoute from '../components/common/ProtectedRoute';
import NotFoundPage from '../pages/NotFoundPage';
import LandingPage from '../pages/LandingPage';
import ServicesPage from '../pages/ServicesPage';

// Auth pages
import LoginPage from '../pages/auth/LoginPage';
import SignupPage from '../pages/auth/SignupPage';
import ForgotPasswordPage from '../pages/auth/ForgotPasswordPage';
import ResetPasswordPage from '../pages/auth/ResetPasswordPage';
import LewPendingPage from '../pages/auth/LewPendingPage';
import EmailVerificationPendingPage from '../pages/auth/EmailVerificationPendingPage';
import VerifyEmailPage from '../pages/auth/VerifyEmailPage';
import AccountSetupPage from '../pages/auth/AccountSetupPage';

// Legal pages
import DisclaimerPage from '../pages/legal/DisclaimerPage';
import PrivacyPolicyPage from '../pages/legal/PrivacyPolicyPage';

// Concierge public pages (★ Kaki Concierge v1.5 Phase 1 PR#3)
import ConciergeRequestPage from '../pages/concierge/ConciergeRequestPage';
import ConciergeRequestSuccessPage from '../pages/concierge/ConciergeRequestSuccessPage';

// Applicant pages
import DashboardPage from '../pages/applicant/DashboardPage';
import ApplicationListPage from '../pages/applicant/ApplicationListPage';
import NewApplicationPage from '../pages/applicant/NewApplicationPage';
import ApplicationDetailPage from '../pages/applicant/ApplicationDetailPage';
import ProfilePage from '../pages/applicant/ProfilePage';
import SldOrderListPage from '../pages/applicant/SldOrderListPage';
import NewSldOrderPage from '../pages/applicant/NewSldOrderPage';
import SldOrderDetailPage from '../pages/applicant/SldOrderDetailPage';
import LightingOrderListPage from '../pages/applicant/LightingOrderListPage';
import NewLightingOrderPage from '../pages/applicant/NewLightingOrderPage';
import LightingOrderDetailPage from '../pages/applicant/LightingOrderDetailPage';
import PowerSocketOrderListPage from '../pages/applicant/PowerSocketOrderListPage';
import NewPowerSocketOrderPage from '../pages/applicant/NewPowerSocketOrderPage';
import PowerSocketOrderDetailPage from '../pages/applicant/PowerSocketOrderDetailPage';
import LewServiceOrderListPage from '../pages/applicant/LewServiceOrderListPage';
import NewLewServiceOrderPage from '../pages/applicant/NewLewServiceOrderPage';
import LewServiceOrderDetailPage from '../pages/applicant/LewServiceOrderDetailPage';
import ExpiredLicenseOrderListPage from '../pages/applicant/ExpiredLicenseOrderListPage';
import NewExpiredLicenseOrderPage from '../pages/applicant/NewExpiredLicenseOrderPage';
import ExpiredLicenseOrderDetailPage from '../pages/applicant/ExpiredLicenseOrderDetailPage';

// Admin pages
import AdminDashboardPage from '../pages/admin/AdminDashboardPage';
import AdminApplicationListPage from '../pages/admin/AdminApplicationListPage';
import AdminApplicationDetailPage from '../pages/admin/AdminApplicationDetailPage';
import AdminUserListPage from '../pages/admin/AdminUserListPage';
import AdminPriceManagementPage from '../pages/admin/AdminPriceManagementPage';
import AdminManualEmailPage from '../pages/admin/AdminManualEmailPage';
// PR-T6 — 알림 템플릿 관리
import AdminNotificationTemplateListPage from '../pages/admin/AdminNotificationTemplateListPage';
import AdminNotificationTemplateEditPage from '../pages/admin/AdminNotificationTemplateEditPage';
import AdminNotificationTemplateDraftReviewPage from '../pages/admin/AdminNotificationTemplateDraftReviewPage';

// System Admin pages
import SystemSettingsPage from '../pages/admin/SystemSettingsPage';
import SystemRolesPage from '../pages/admin/SystemRolesPage';
import AuditLogPage from '../pages/admin/AuditLogPage';
import DataBreachPage from '../pages/admin/DataBreachPage';

// LEW pages
import LewReviewFormPage from '../pages/lew/LewReviewFormPage';
import LewApplicationDetailPage from '../pages/lew/LewApplicationDetailPage';
// ★ Concierge 강화 PR-4 — LEW 컨시어지 페이지
import LewConciergeRequestListPage from '../pages/lew/LewConciergeRequestListPage';
import LewConciergeRequestDetailPage from '../pages/lew/LewConciergeRequestDetailPage';

// SLD Manager pages
import SldManagerDashboardPage from '../pages/sld-manager/SldManagerDashboardPage';
import SldManagerOrderListPage from '../pages/sld-manager/SldManagerOrderListPage';
import SldManagerOrderDetailPage from '../pages/sld-manager/SldManagerOrderDetailPage';

// Lighting Manager pages
import LightingManagerDashboardPage from '../pages/lighting-manager/LightingManagerDashboardPage';
import LightingManagerOrderListPage from '../pages/lighting-manager/LightingManagerOrderListPage';
import LightingManagerOrderDetailPage from '../pages/lighting-manager/LightingManagerOrderDetailPage';

// Power Socket Manager pages
import PowerSocketManagerDashboardPage from '../pages/power-socket-manager/PowerSocketManagerDashboardPage';
import PowerSocketManagerOrderListPage from '../pages/power-socket-manager/PowerSocketManagerOrderListPage';
import PowerSocketManagerOrderDetailPage from '../pages/power-socket-manager/PowerSocketManagerOrderDetailPage';

// LEW Service Manager pages
import LewServiceManagerDashboardPage from '../pages/lew-service-manager/LewServiceManagerDashboardPage';
import LewServiceManagerOrderListPage from '../pages/lew-service-manager/LewServiceManagerOrderListPage';
import LewServiceManagerOrderDetailPage from '../pages/lew-service-manager/LewServiceManagerOrderDetailPage';

// Expired License Manager pages
import ExpiredLicenseManagerDashboardPage from '../pages/expired-license-manager/ExpiredLicenseManagerDashboardPage';
import ExpiredLicenseManagerOrderListPage from '../pages/expired-license-manager/ExpiredLicenseManagerOrderListPage';
import ExpiredLicenseManagerOrderDetailPage from '../pages/expired-license-manager/ExpiredLicenseManagerOrderDetailPage';

// Concierge Manager pages (★ Kaki Concierge v1.5 Phase 1 PR#4 Stage B)
import ConciergeManagerDashboardPage from '../pages/concierge-manager/ConciergeManagerDashboardPage';
import ConciergeRequestListPage from '../pages/concierge-manager/ConciergeRequestListPage';
import ConciergeRequestDetailPage from '../pages/concierge-manager/ConciergeRequestDetailPage';

// Common pages
import NotificationsPage from '../pages/NotificationsPage';

/**
 * 애플리케이션 라우터 설정
 */
const router = createBrowserRouter([
  // Public routes
  {
    // Page 2 — 서비스 상세 (랜딩 카드가 /services#<slug> 앵커로 연결)
    path: '/services',
    element: <ServicesPage />,
  },
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/signup',
    element: <SignupPage />,
  },
  {
    path: '/forgot-password',
    element: <ForgotPasswordPage />,
  },
  {
    path: '/reset-password',
    element: <ResetPasswordPage />,
  },
  {
    path: '/disclaimer',
    element: <DisclaimerPage />,
  },
  {
    path: '/privacy',
    element: <PrivacyPolicyPage />,
  },
  {
    path: '/privacy-policy',
    element: <PrivacyPolicyPage />,
  },
  {
    path: '/lew-pending',
    element: <LewPendingPage />,
  },
  {
    path: '/email-verification-pending',
    element: <EmailVerificationPendingPage />,
  },
  {
    path: '/verify-email',
    element: <VerifyEmailPage />,
  },

  // Concierge public routes (★ Kaki Concierge v1.5 Phase 1 PR#3)
  {
    path: '/concierge/request',
    element: <ConciergeRequestPage />,
  },
  {
    path: '/concierge/request/success',
    element: <ConciergeRequestSuccessPage />,
  },
  {
    // AccountSetupPage — Stage C
    path: '/setup-account/:token',
    element: <AccountSetupPage />,
  },

  // Applicant routes (APPLICANT role)
  {
    element: <ProtectedRoute allowedRoles={['APPLICANT']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/dashboard', element: <DashboardPage /> },
          { path: '/applications', element: <ApplicationListPage /> },
          { path: '/applications/new', element: <NewApplicationPage /> },
          { path: '/applications/:id', element: <ApplicationDetailPage /> },
          { path: '/sld-orders', element: <SldOrderListPage /> },
          { path: '/sld-orders/new', element: <NewSldOrderPage /> },
          { path: '/sld-orders/:id', element: <SldOrderDetailPage /> },
          { path: '/lighting-orders', element: <LightingOrderListPage /> },
          { path: '/lighting-orders/new', element: <NewLightingOrderPage /> },
          { path: '/lighting-orders/:id', element: <LightingOrderDetailPage /> },
          { path: '/power-socket-orders', element: <PowerSocketOrderListPage /> },
          { path: '/power-socket-orders/new', element: <NewPowerSocketOrderPage /> },
          { path: '/power-socket-orders/:id', element: <PowerSocketOrderDetailPage /> },
          { path: '/lew-service-orders', element: <LewServiceOrderListPage /> },
          { path: '/lew-service-orders/new', element: <NewLewServiceOrderPage /> },
          { path: '/lew-service-orders/:id', element: <LewServiceOrderDetailPage /> },
          { path: '/expired-license-orders', element: <ExpiredLicenseOrderListPage /> },
          { path: '/expired-license-orders/new', element: <NewExpiredLicenseOrderPage /> },
          { path: '/expired-license-orders/:id', element: <ExpiredLicenseOrderDetailPage /> },
          { path: '/notifications', element: <NotificationsPage /> },
        ],
      },
    ],
  },

  // My Profile — 전 역할 공용 (비밀번호 변경 포함)
  // APPLICANT 외 ADMIN/LEW/SYSTEM_ADMIN 도 접근 가능. 신청자 전용 섹션(서명·PDPA 등)은
  // ProfilePage 내부에서 역할별로 분기 렌더링.
  {
    element: <ProtectedRoute allowedRoles={['APPLICANT', 'ADMIN', 'LEW', 'SYSTEM_ADMIN']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/profile', element: <ProfilePage /> },
        ],
      },
    ],
  },

  // Admin routes (ADMIN only)
  {
    element: <ProtectedRoute allowedRoles={['ADMIN']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/admin/dashboard', element: <AdminDashboardPage /> },
          { path: '/admin/users', element: <AdminUserListPage /> },
          { path: '/admin/prices', element: <AdminPriceManagementPage /> },
          { path: '/admin/notifications', element: <NotificationsPage /> },
        ],
      },
    ],
  },

  // ADMIN + SYSTEM_ADMIN shared routes
  // - Manual Email Dispatch: 메뉴 노출은 ADMIN 만 (Layout 에서 처리), 직접 URL 진입은 SYSTEM_ADMIN 도 허용
  //   (admin-manual-email-spec.md §2.1, §7.1 / PR-3)
  {
    element: <ProtectedRoute allowedRoles={['ADMIN', 'SYSTEM_ADMIN']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/admin/manual-emails', element: <AdminManualEmailPage /> },
          // 동선 재설계 A: 신청 목록·상세를 SYSTEM_ADMIN 에도 개방 (LoA 생성 포함).
          // 백엔드 /api/admin/** 는 이미 ADMIN/LEW/SYSTEM_ADMIN 허용.
          { path: '/admin/applications', element: <AdminApplicationListPage /> },
          { path: '/admin/applications/:id', element: <AdminApplicationDetailPage /> },
        ],
      },
    ],
  },

  // ★ PR-T6 — 알림 템플릿 관리.
  // ADMIN 단독 운영: ADMIN 은 작성·편집·발송·승인까지 full (NM 역할 미사용). SYSTEM_ADMIN 도 full.
  // LEW/SLD_MANAGER/CONCIERGE_MANAGER 는 read-only 로 접근 가능 (D-5 — recipient_roles 필터).
  {
    element: (
      <ProtectedRoute
        allowedRoles={[
          'NOTIFICATION_MANAGER',
          'SYSTEM_ADMIN',
          'ADMIN',
          'LEW',
          'SLD_MANAGER',
          'CONCIERGE_MANAGER',
        ]}
      />
    ),
    children: [
      {
        element: <Layout />,
        children: [
          {
            path: '/admin/notification-templates',
            element: <AdminNotificationTemplateListPage />,
          },
          {
            // 편집은 직접 저장(2단계 없음). 이 큐는 XLIFF/CSV 번역 일괄 import 가 만든
            // draft 검토·게시 전용으로 유지 (메인 메뉴에서는 비노출).
            path: '/admin/notification-templates/drafts',
            element: <AdminNotificationTemplateDraftReviewPage />,
          },
          {
            path: '/admin/notification-templates/:id',
            element: <AdminNotificationTemplateEditPage />,
          },
        ],
      },
    ],
  },

  // System Admin routes (SYSTEM_ADMIN — system settings only)
  {
    element: <ProtectedRoute allowedRoles={['SYSTEM_ADMIN']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/admin/system', element: <SystemSettingsPage /> },
          { path: '/admin/roles', element: <SystemRolesPage /> },
          { path: '/admin/audit-logs', element: <AuditLogPage /> },
          { path: '/admin/data-breaches', element: <DataBreachPage /> },
          { path: '/admin/notifications', element: <NotificationsPage /> },
        ],
      },
    ],
  },

  // LEW routes (LEW only)
  {
    element: <ProtectedRoute allowedRoles={['LEW']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/lew/dashboard', element: <AdminDashboardPage /> },
          { path: '/lew/applications', element: <AdminApplicationListPage /> },
          { path: '/lew/applications/:id', element: <LewApplicationDetailPage /> },
          // LEW Review Form — Documents/kVA/SLD/LOA 탭
          { path: '/lew/applications/:id/review', element: <LewReviewFormPage /> },
          // ★ Concierge 강화 PR-4 — LEW 컨시어지 워크스페이스
          { path: '/lew/concierge-requests', element: <LewConciergeRequestListPage /> },
          { path: '/lew/concierge-requests/:id', element: <LewConciergeRequestDetailPage /> },
          { path: '/lew/notifications', element: <NotificationsPage /> },
        ],
      },
    ],
  },

  // SLD Manager routes (SLD_MANAGER only)
  {
    element: <ProtectedRoute allowedRoles={['SLD_MANAGER']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/sld-manager/dashboard', element: <SldManagerDashboardPage /> },
          { path: '/sld-manager/orders', element: <SldManagerOrderListPage /> },
          { path: '/sld-manager/orders/:id', element: <SldManagerOrderDetailPage /> },
          { path: '/sld-manager/notifications', element: <NotificationsPage /> },
        ],
      },
    ],
  },

  // Lighting / Power Socket / LEW Service Manager routes
  // Backend allows SLD_MANAGER / ADMIN / SYSTEM_ADMIN (see LightingManagerController etc.)
  {
    element: <ProtectedRoute allowedRoles={['SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/lighting-manager/dashboard', element: <LightingManagerDashboardPage /> },
          { path: '/lighting-manager/orders', element: <LightingManagerOrderListPage /> },
          { path: '/lighting-manager/orders/:id', element: <LightingManagerOrderDetailPage /> },
          { path: '/lighting-manager/notifications', element: <NotificationsPage /> },

          { path: '/power-socket-manager/dashboard', element: <PowerSocketManagerDashboardPage /> },
          { path: '/power-socket-manager/orders', element: <PowerSocketManagerOrderListPage /> },
          { path: '/power-socket-manager/orders/:id', element: <PowerSocketManagerOrderDetailPage /> },
          { path: '/power-socket-manager/notifications', element: <NotificationsPage /> },

          { path: '/lew-service-manager/dashboard', element: <LewServiceManagerDashboardPage /> },
          { path: '/lew-service-manager/orders', element: <LewServiceManagerOrderListPage /> },
          { path: '/lew-service-manager/orders/:id', element: <LewServiceManagerOrderDetailPage /> },
          { path: '/lew-service-manager/notifications', element: <NotificationsPage /> },

          { path: '/expired-license-manager/dashboard', element: <ExpiredLicenseManagerDashboardPage /> },
          { path: '/expired-license-manager/orders', element: <ExpiredLicenseManagerOrderListPage /> },
          { path: '/expired-license-manager/orders/:id', element: <ExpiredLicenseManagerOrderDetailPage /> },
          { path: '/expired-license-manager/notifications', element: <NotificationsPage /> },
        ],
      },
    ],
  },

  // Concierge Manager routes (★ Kaki Concierge v1.5 Phase 1 PR#4 Stage B)
  // ADMIN/SYSTEM_ADMIN도 접근 가능 (backend Stage A SecurityConfig와 일치)
  {
    element: <ProtectedRoute allowedRoles={['CONCIERGE_MANAGER', 'ADMIN', 'SYSTEM_ADMIN']} />,
    children: [
      {
        element: <Layout />,
        children: [
          { path: '/concierge-manager/dashboard', element: <ConciergeManagerDashboardPage /> },
          { path: '/concierge-manager/requests', element: <ConciergeRequestListPage /> },
          { path: '/concierge-manager/requests/:id', element: <ConciergeRequestDetailPage /> },
          { path: '/concierge-manager/notifications', element: <NotificationsPage /> },
        ],
      },
    ],
  },

  // Landing page
  {
    path: '/',
    element: <LandingPage />,
  },

  // 404 catch-all
  {
    path: '*',
    element: <NotFoundPage />,
  },
]);

export default router;
