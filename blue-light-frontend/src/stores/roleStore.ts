import { create } from 'zustand';
import { USER_ROLES, ROLE_LABELS as DEFAULT_LABELS, type UserRole } from '../constants/roles';
import {
  getRoles,
  getRolesAdmin,
  updateRoleMetadata,
  type RoleMetadata,
  type UpdateRoleMetadataRequest,
} from '../api/roleApi';

interface RoleState {
  roles: RoleMetadata[];
  loaded: boolean;
  loading: boolean;
  loadRoles: () => Promise<void>;
  loadRolesAdmin: () => Promise<void>;
  updateRole: (code: UserRole, req: UpdateRoleMetadataRequest) => Promise<RoleMetadata>;
}

// 서버 응답 오기 전에도 UI 가 동작하도록 상수 기반 기본값을 초기값으로 사용한다.
// 서버 데이터가 도착하면 이 기본값을 덮어쓴다.
const defaultRoles: RoleMetadata[] = USER_ROLES.map((code, idx) => ({
  roleCode: code,
  displayLabel: DEFAULT_LABELS[code],
  assignable: code !== 'ADMIN' && code !== 'SYSTEM_ADMIN',
  filterable: code !== 'SYSTEM_ADMIN',
  sortOrder: (idx + 1) * 10,
}));

export const useRoleStore = create<RoleState>()((set, get) => ({
  roles: defaultRoles,
  loaded: false,
  loading: false,

  loadRoles: async () => {
    if (get().loading || get().loaded) return;
    set({ loading: true });
    try {
      const data = await getRoles();
      set({ roles: data.sort((a, b) => a.sortOrder - b.sortOrder), loaded: true });
    } catch (e) {
      console.error('Failed to load role metadata', e);
    } finally {
      set({ loading: false });
    }
  },

  loadRolesAdmin: async () => {
    set({ loading: true });
    try {
      const data = await getRolesAdmin();
      set({ roles: data.sort((a, b) => a.sortOrder - b.sortOrder), loaded: true });
    } finally {
      set({ loading: false });
    }
  },

  updateRole: async (code, req) => {
    const updated = await updateRoleMetadata(code, req);
    set((state) => ({
      roles: state.roles
        .map((r) => (r.roleCode === code ? updated : r))
        .sort((a, b) => a.sortOrder - b.sortOrder),
    }));
    return updated;
  },
}));

// ── 파생 셀렉터 ─────────────────────────────────────────────
export const selectRoleLabels = (state: RoleState): Record<UserRole, string> => {
  const labels = { ...DEFAULT_LABELS };
  for (const r of state.roles) labels[r.roleCode] = r.displayLabel;
  return labels;
};

export const selectAssignableRoles = (state: RoleState): UserRole[] =>
  state.roles.filter((r) => r.assignable).map((r) => r.roleCode);

export const selectFilterableRoles = (state: RoleState): UserRole[] =>
  state.roles.filter((r) => r.filterable).map((r) => r.roleCode);
