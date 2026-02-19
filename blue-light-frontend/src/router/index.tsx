import { createBrowserRouter, Navigate } from 'react-router-dom';
import Layout from '../components/common/Layout';
import ProtectedRoute from '../components/common/ProtectedRoute';
import NotFoundPage from '../pages/NotFoundPage';

// Auth pages
import LoginPage from '../pages/auth/LoginPage';
import SignupPage from '../pages/auth/SignupPage';
import ForgotPasswordPage from '../pages/auth/ForgotPasswordPage';
import ResetPasswordPage from '../pages/auth/ResetPasswordPage';
import LewPendingPage from '../pages/auth/LewPendingPage';
import EmailVerificationPendingPage from '../pages/auth/EmailVerificationPendingPage';
import VerifyEmailPage from '../pages/auth/VerifyEmailPage';

// Legal pages
import DisclaimerPage from '../pages/legal/DisclaimerPage';
import PrivacyPolicyPage from '../pages/legal/PrivacyPolicyPage';

// Applicant pages
import DashboardPage from '../pages/applicant/DashboardPage';
import ApplicationListPage from '../pages/applicant/ApplicationListPage';
import NewApplicationPage from '../pages/applicant/NewApplicationPage';
import ApplicationDetailPage from '../pages/applicant/ApplicationDetailPage';
import ProfilePage from '../pages/applicant/ProfilePage';

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
          { path: '/admin/applications', element: <AdminApplicationListPage /> },
          { path: '/admin/applications/:id', element: <AdminApplicationDetailPage /> },
          { path: '/admin/users', element: <AdminUserListPage /> },
          { path: '/admin/prices', element: <AdminPriceManagementPage /> },
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
        ],
      },
    ],
  },

  // Root redirect
  {
    path: '/',
    element: <Navigate to="/login" replace />,
  },

  // 404 catch-all
  {
    path: '*',
    element: <NotFoundPage />,
  },
]);

export default router;
