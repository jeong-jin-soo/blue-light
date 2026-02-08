import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import type { UserRole } from '../../types';

interface ProtectedRouteProps {
  allowedRoles?: UserRole[];
}

/**
 * 인증 및 역할 기반 라우트 가드
 * - 비로그인 시 /login으로 리다이렉트
 * - allowedRoles 지정 시 역할 불일치면 역할별 기본 페이지로 리다이렉트
 */
export default function ProtectedRoute({ allowedRoles }: ProtectedRouteProps) {
  const { isAuthenticated, user } = useAuthStore();

  if (!isAuthenticated || !user) {
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles && !allowedRoles.includes(user.role)) {
    // 역할 불일치 시 역할별 기본 페이지로 리다이렉트
    const redirectPath = user.role === 'ADMIN' || user.role === 'LEW' ? '/admin/dashboard' : '/dashboard';
    return <Navigate to={redirectPath} replace />;
  }

  return <Outlet />;
}
