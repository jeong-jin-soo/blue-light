import { createBrowserRouter } from 'react-router-dom';
import Layout from '../components/common/Layout';
import ProtectedRoute from '../components/common/ProtectedRoute';
import NotFoundPage from '../pages/NotFoundPage';
import LandingPage from '../pages/LandingPage';

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

// Admin pages
import AdminDashboardPage from '../pages/admin/AdminDashboardPage';
import AdminApplicationListPage from '../pages/admin/AdminApplicationListPage';
import AdminApplicationDetailPage from '../pages/admin/AdminApplicationDetailPage';
import AdminUserListPage from '../pages/admin/AdminUserListPage';
import AdminPriceManagementPage from '../pages/admin/AdminPriceManagementPage';

// System Admin pages
import SystemSettingsPage from '../pages/admin/SystemSettingsPage';
import AuditLogPage from '../pages/admin/AuditLogPage';
import DataBreachPage from '../pages/admin/DataBreachPage';

// SLD Manager pages
import SldManagerDashboardPage from '../pages/sld-manager/SldManagerDashboardPage';
import SldManagerOrderListPage from '../pages/sld-manager/SldManagerOrderListPage';
import SldManagerOrderDetailPage from '../pages/sld-manager/SldManagerOrderDetailPage';

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
          { path: '/profile', element: <ProfilePage /> },
          { path: '/notifications', element: <NotificationsPage /> },
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
          { path: '/admin/applications', element: <AdminApplicationListPage /> },
          { path: '/admin/applications/:id', element: <AdminApplicationDetailPage /> },
          { path: '/admin/users', element: <AdminUserListPage /> },
          { path: '/admin/prices', element: <AdminPriceManagementPage /> },
          { path: '/admin/notifications', element: <NotificationsPage /> },
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
          { path: '/lew/applications/:id', element: <AdminApplicationDetailPage /> },
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
