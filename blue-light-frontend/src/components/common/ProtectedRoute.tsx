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

  // 이메일 미인증 사용자는 인증 대기 페이지로 리다이렉트
  if (!user.emailVerified) {
    return <Navigate to="/email-verification-pending" replace />;
  }

  // 미승인 LEW는 대기 페이지로 리다이렉트
  if (user.role === 'LEW' && !user.approved) {
    return <Navigate to="/lew-pending" replace />;
  }

  if (allowedRoles && !allowedRoles.includes(user.role)) {
    // 역할 불일치 시 역할별 기본 페이지로 리다이렉트
    // ★ PR-T7 (보안 감사 H-4) — NOTIFICATION_MANAGER 분기 추가, 무한 redirect 회피.
    const redirectPath = user.role === 'SYSTEM_ADMIN' ? '/admin/system'
      : user.role === 'ADMIN' ? '/admin/dashboard'
      : user.role === 'LEW' ? '/lew/dashboard'
      : user.role === 'SLD_MANAGER' ? '/sld-manager/dashboard'
      : user.role === 'CONCIERGE_MANAGER' ? '/concierge-manager/dashboard'
      : user.role === 'NOTIFICATION_MANAGER' ? '/admin/notification-templates'
      : '/dashboard';
    return <Navigate to={redirectPath} replace />;
  }

  return <Outlet />;
}
