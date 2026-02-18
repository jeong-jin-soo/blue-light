import type { UserRole } from '../types';

/**
 * 역할 기반 라우트 prefix 반환
 * - ADMIN → /admin
 * - SYSTEM_ADMIN → /admin
 * - LEW → /lew
 * - 기타(APPLICANT 등) → /admin (fallback)
 */
export const getBasePath = (role?: UserRole | string): string =>
  role === 'LEW' ? '/lew' : '/admin';
