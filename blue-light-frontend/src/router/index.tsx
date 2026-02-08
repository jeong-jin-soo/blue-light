import { createBrowserRouter, Navigate } from 'react-router-dom';
import Layout from '../components/common/Layout';
import ProtectedRoute from '../components/common/ProtectedRoute';
import NotFoundPage from '../pages/NotFoundPage';

// Auth pages
import LoginPage from '../pages/auth/LoginPage';
import SignupPage from '../pages/auth/SignupPage';

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

  // Admin routes (ADMIN role)
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
