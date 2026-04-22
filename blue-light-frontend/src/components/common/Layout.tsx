import { useEffect, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useShallow } from 'zustand/react/shallow';
import { useAuthStore } from '../../stores/authStore';
import { useRoleStore, selectRoleLabels } from '../../stores/roleStore';
import licensekakiLogo from '../../assets/logo-licensekaki-dark.png';
import { ErrorBoundary } from './ErrorBoundary';
import { NotificationBell } from './NotificationBell';
import Footer from './Footer';

/**
 * 공통 레이아웃: 사이드바 + 헤더 + 메인 콘텐츠
 */
export default function Layout() {
  const { user, logout } = useAuthStore();
  const location = useLocation();
  const navigate = useNavigate();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const loadRoles = useRoleStore((s) => s.loadRoles);
  // useShallow: selectRoleLabels가 매 호출마다 새 객체({...})를 반환하므로
  // 얕은 비교로 래핑하지 않으면 Layout이 무한 리렌더 → React error #185.
  const roleLabels = useRoleStore(useShallow(selectRoleLabels));

  // 인증된 사용자가 Layout 에 진입하는 순간 1회 역할 메타데이터 로드
  useEffect(() => {
    loadRoles();
  }, [loadRoles]);

  const isAdmin = user?.role === 'ADMIN';
  const isSystemAdmin = user?.role === 'SYSTEM_ADMIN';
  const isLew = user?.role === 'LEW' && user?.approved;
  const isSldManager = user?.role === 'SLD_MANAGER';
  const isConciergeManager = user?.role === 'CONCIERGE_MANAGER';

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  // 메뉴 항목 정의
  const applicantMenu = [
    { path: '/dashboard', label: 'Dashboard', icon: '📊' },
    { path: '/applications', label: 'My Applications', icon: '📋' },
    { path: '/applications/new', label: 'New Application', icon: '➕' },
    { path: '/sld-orders', label: 'SLD Orders', icon: '📐' },
    { path: '/lighting-orders', label: 'Lighting Layout', icon: '💡' },
    { path: '/power-socket-orders', label: 'Power Socket', icon: '🔌' },
    { path: '/lew-service-orders', label: 'LEW Service', icon: '⚡' },
    { path: '/profile', label: 'My Profile', icon: '👤' },
  ];

  const adminMenu = [
    { path: '/admin/dashboard', label: 'Dashboard', icon: '📊' },
    { path: '/admin/applications', label: 'Applications', icon: '📋' },
    { path: '/admin/prices', label: 'Settings', icon: '⚙️' },
    { path: '/admin/users', label: 'Users', icon: '👥' },
  ];

  // SYSTEM_ADMIN: 시스템 설정 전용
  const systemAdminMenu = [
    { path: '/admin/system', label: 'System', icon: '🔧' },
    { path: '/admin/roles', label: 'Roles', icon: '🗝️' },
    { path: '/admin/audit-logs', label: 'Audit Logs', icon: '📋' },
    { path: '/admin/data-breaches', label: 'Data Breach', icon: '🛡️' },
  ];

  const lewMenu = [
    { path: '/lew/dashboard', label: 'Dashboard', icon: '📊' },
    { path: '/lew/applications', label: 'Applications', icon: '📋' },
  ];

  const sldManagerMenu = [
    { path: '/sld-manager/dashboard', label: 'SLD Dashboard', icon: '📊' },
    { path: '/sld-manager/orders', label: 'SLD Orders', icon: '📐' },
    { path: '/lighting-manager/dashboard', label: 'Lighting Dashboard', icon: '📊' },
    { path: '/lighting-manager/orders', label: 'Lighting Orders', icon: '💡' },
    { path: '/power-socket-manager/dashboard', label: 'Power Socket Dashboard', icon: '📊' },
    { path: '/power-socket-manager/orders', label: 'Power Socket Orders', icon: '🔌' },
    { path: '/lew-service-manager/dashboard', label: 'LEW Service Dashboard', icon: '📊' },
    { path: '/lew-service-manager/orders', label: 'LEW Service Orders', icon: '⚡' },
  ];

  // ★ Kaki Concierge v1.5 Phase 1 PR#4 Stage B
  const conciergeManagerMenu = [
    { path: '/concierge-manager/dashboard', label: 'Dashboard', icon: '📊' },
    { path: '/concierge-manager/requests', label: 'Requests', icon: '🤝' },
  ];

  const menuItems = isSystemAdmin ? systemAdminMenu
    : isAdmin ? adminMenu
    : isLew ? lewMenu
    : isSldManager ? sldManagerMenu
    : isConciergeManager ? conciergeManagerMenu
    : applicantMenu;

  const isActive = (path: string) => location.pathname === path;

  // 승인 대기 LEW는 Applicant UI로 노출하는 기존 동작을 유지 (isLew는 approved일 때만 true)
  const roleLabel = isSystemAdmin ? roleLabels.SYSTEM_ADMIN
    : isAdmin ? roleLabels.ADMIN
    : isLew ? roleLabels.LEW
    : isSldManager ? roleLabels.SLD_MANAGER
    : isConciergeManager ? roleLabels.CONCIERGE_MANAGER
    : roleLabels.APPLICANT;

  const homePath = isSystemAdmin ? '/admin/system'
    : isAdmin ? '/admin/dashboard'
    : isLew ? '/lew/dashboard'
    : isSldManager ? '/sld-manager/dashboard'
    : isConciergeManager ? '/concierge-manager/dashboard'
    : '/dashboard';

  return (
    <div className="min-h-screen bg-gray-50 flex">
      {/* Mobile Overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-20 lg:hidden"
          onClick={() => setSidebarOpen(false)}
          role="button"
          tabIndex={0}
          aria-label="Close menu"
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSidebarOpen(false); } }}
        />
      )}

      {/* Sidebar */}
      <aside
        className={`fixed lg:static inset-y-0 left-0 z-30 w-64 bg-primary text-white transform transition-transform duration-200 ease-in-out
          ${sidebarOpen ? 'translate-x-0' : '-translate-x-full'} lg:translate-x-0`}
      >
        {/* Logo */}
        <div className="h-16 flex items-center px-6 border-b border-white/10">
          <Link to={homePath} className="flex items-center">
            <img src={licensekakiLogo} alt="LicenseKaki" className="h-6" />
          </Link>
        </div>

        {/* Navigation */}
        <nav className="mt-4 px-3 space-y-1">
          {menuItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              onClick={() => setSidebarOpen(false)}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors
                ${isActive(item.path)
                  ? 'bg-white/15 text-white'
                  : 'text-white/70 hover:bg-white/10 hover:text-white'
                }`}
            >
              <span>{item.icon}</span>
              <span>{item.label}</span>
            </Link>
          ))}
        </nav>

        {/* User Info (sidebar bottom) */}
        <div className="absolute bottom-0 left-0 right-0 p-4 border-t border-white/10">
          <div className="text-sm text-white/60 truncate">{user?.email}</div>
          <div className="text-xs text-white/40 mt-0.5">
            {roleLabel}
          </div>
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Top Header */}
        <header className="h-16 bg-white border-b border-gray-200 flex items-center justify-between px-4 lg:px-6 sticky top-0 z-10">
          {/* Mobile menu button */}
          <button
            onClick={() => setSidebarOpen(true)}
            className="lg:hidden p-2 rounded-md text-gray-500 hover:bg-gray-100"
            aria-label="Open menu"
          >
            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>

          <div className="flex-1" />

          {/* User menu */}
          <div className="flex items-center gap-4">
            <NotificationBell />
            <span className="text-sm text-gray-600 hidden sm:block">{[user?.firstName, user?.lastName].filter(Boolean).join(' ')}</span>
            <button
              onClick={handleLogout}
              className="text-sm text-gray-500 hover:text-red-600 transition-colors cursor-pointer"
            >
              Logout
            </button>
          </div>
        </header>

        {/* Page Content */}
        <main className="flex-1 p-4 lg:p-6">
          <ErrorBoundary>
            <Outlet />
          </ErrorBoundary>
        </main>

        {/* Footer */}
        <Footer />
      </div>
    </div>
  );
}
