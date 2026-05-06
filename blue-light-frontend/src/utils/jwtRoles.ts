/**
 * ★ Concierge 강화 + 별도 수금 PR-4 — JWT 토큰 roles claim 디코딩 유틸.
 *
 * <p>D1=B(다중 역할) 도입에 따라, 한 사용자가 primary role 외에 secondary roles 를 동시에
 * 가질 수 있다 (예: CONCIERGE_MANAGER + LEW). 백엔드는 JWT 토큰의 {@code roles} claim 에
 * effective roles 를 모두 실어 보낸다.</p>
 *
 * <p>{@code authStore} 의 {@code user.role} 은 primary role 만 보존하므로, 다중 역할 인지가
 * 필요한 UI 분기(예: 셀프 LEW 할당 체크박스, "내 컨시어지 요청" 메뉴) 에서는 토큰을 직접 디코딩하여
 * roles claim 을 읽어야 한다.</p>
 *
 * <p><b>주의</b>: JWT payload 의 검증은 백엔드가 수행하므로 본 유틸은 단순 디코딩일 뿐이다.
 * 권한 결정의 source of truth 는 백엔드의 @PreAuthorize / SecurityConfig 다.</p>
 */

import { tokenUtils } from '../api/axiosClient';
import type { UserRole } from '../types';

/**
 * 현재 토큰의 roles claim 을 디코딩하여 반환.
 * 토큰이 없거나 디코딩 실패 시 빈 배열.
 */
export function getCurrentRoles(): UserRole[] {
  const token = tokenUtils.getToken();
  if (!token) return [];
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    // PR-1 (D1=B) 백엔드가 roles claim 을 발급. legacy 토큰엔 role(string) 만 있을 수 있다.
    const roles = payload.roles;
    if (Array.isArray(roles) && roles.length > 0) {
      return roles.filter(
        (r: unknown): r is UserRole => typeof r === 'string',
      ) as UserRole[];
    }
    // Fallback: legacy 토큰 — 단일 role
    if (typeof payload.role === 'string') {
      return [payload.role as UserRole];
    }
    return [];
  } catch {
    return [];
  }
}

/**
 * 현재 사용자가 특정 role 을 보유하는지 (primary 또는 secondary 어디든) 판정.
 *
 * <p>예: 매니저가 셀프 LEW 할당 가능한지 체크 시 {@code hasRole('LEW')}.</p>
 */
export function hasRole(role: UserRole): boolean {
  return getCurrentRoles().includes(role);
}
