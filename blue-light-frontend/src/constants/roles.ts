export const USER_ROLES = [
  'APPLICANT',
  'LEW',
  'SLD_MANAGER',
  'CONCIERGE_MANAGER',
  'ADMIN',
  'SYSTEM_ADMIN',
] as const;

export type UserRole = typeof USER_ROLES[number];

export const ROLE_LABELS: Record<UserRole, string> = {
  APPLICANT: 'Applicant',
  LEW: 'LEW',
  SLD_MANAGER: 'SLD Manager',
  CONCIERGE_MANAGER: 'Concierge Manager',
  ADMIN: 'Administrator',
  SYSTEM_ADMIN: 'System Admin',
};

// Admin이 UI에서 직접 할당 가능한 역할 (백엔드가 ADMIN/SYSTEM_ADMIN 할당을 차단)
export const ASSIGNABLE_ROLES: readonly UserRole[] = [
  'APPLICANT',
  'LEW',
  'SLD_MANAGER',
  'CONCIERGE_MANAGER',
];

// Users 목록 필터 드롭다운 노출 역할
export const FILTERABLE_ROLES: readonly UserRole[] = [
  'APPLICANT',
  'LEW',
  'SLD_MANAGER',
  'CONCIERGE_MANAGER',
  'ADMIN',
];
