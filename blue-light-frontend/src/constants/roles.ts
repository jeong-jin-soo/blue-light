// UserRole enum 상수 — 백엔드 UserRole enum 과 동기화 필요.
// 라벨/할당 가능 여부/필터 노출 여부는 sysadmin 이 역할 메타데이터 페이지에서 관리한다.
// 이 파일은 타입과 default 라벨만 유지하며, 런타임 값은 `stores/roleStore.ts` 를 참조.
export const USER_ROLES = [
  'APPLICANT',
  'LEW',
  'SLD_MANAGER',
  'CONCIERGE_MANAGER',
  'ADMIN',
  'SYSTEM_ADMIN',
] as const;

export type UserRole = typeof USER_ROLES[number];

// 서버 응답 도착 전에도 UI 가 동작하도록 기본 라벨을 노출.
// 실제 렌더링 값은 roleStore 를 통해 서버 데이터로 덮어써진다.
export const ROLE_LABELS: Record<UserRole, string> = {
  APPLICANT: 'Applicant',
  LEW: 'LEW',
  SLD_MANAGER: 'SLD Manager',
  CONCIERGE_MANAGER: 'Concierge Manager',
  ADMIN: 'Administrator',
  SYSTEM_ADMIN: 'System Admin',
};
