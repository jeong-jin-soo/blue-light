import type { UserRole } from '../types';

/**
 * 역할 기반 라우트 prefix 반환
 * - ADMIN → /admin
 * - SYSTEM_ADMIN → /admin
 * - LEW → /lew
 * - SLD_MANAGER → /sld-manager
 * - APPLICANT → (empty)
 */
export const getBasePath = (role?: UserRole | string): string => {
  switch (role) {
    case 'LEW': return '/lew';
    case 'SLD_MANAGER': return '/sld-manager';
    case 'APPLICANT': return '';
    default: return '/admin';
  }
};
