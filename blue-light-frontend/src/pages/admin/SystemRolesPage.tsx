import { useEffect, useState } from 'react';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { useToastStore } from '../../stores/toastStore';
import { useRoleStore } from '../../stores/roleStore';
import type { UserRole } from '../../constants/roles';

interface EditableRow {
  roleCode: UserRole;
  displayLabel: string;
  assignable: boolean;
  filterable: boolean;
  sortOrder: number;
  dirty: boolean;
}

/**
 * SYSTEM_ADMIN 전용 — 역할 메타데이터 관리
 * - 라벨, 할당 가능 여부, 필터 노출 여부, 정렬 순서 편집
 * - roleCode 는 UserRole enum 이므로 추가/삭제는 코드 변경 필요 (UI 에서는 편집만)
 */
export default function SystemRolesPage() {
  const toast = useToastStore();
  const storeRoles = useRoleStore((s) => s.roles);
  const loadRolesAdmin = useRoleStore((s) => s.loadRolesAdmin);
  const updateRole = useRoleStore((s) => s.updateRole);

  const [rows, setRows] = useState<EditableRow[]>([]);
  const [savingCode, setSavingCode] = useState<UserRole | null>(null);

  useEffect(() => {
    loadRolesAdmin().catch((err: { message?: string }) => {
      toast.error(err.message || 'Failed to load role metadata');
    });
  }, [loadRolesAdmin, toast]);

  useEffect(() => {
    setRows(storeRoles.map((r) => ({
      roleCode: r.roleCode,
      displayLabel: r.displayLabel,
      assignable: r.assignable,
      filterable: r.filterable,
      sortOrder: r.sortOrder,
      dirty: false,
    })));
  }, [storeRoles]);

  const patchRow = (code: UserRole, patch: Partial<EditableRow>) => {
    setRows((prev) => prev.map((r) => r.roleCode === code ? { ...r, ...patch, dirty: true } : r));
  };

  const handleSave = async (row: EditableRow) => {
    setSavingCode(row.roleCode);
    try {
      await updateRole(row.roleCode, {
        displayLabel: row.displayLabel,
        assignable: row.assignable,
        filterable: row.filterable,
        sortOrder: row.sortOrder,
      });
      toast.success(`${row.roleCode} 업데이트 완료`);
    } catch (err) {
      const msg = (err as { message?: string })?.message || 'Update failed';
      toast.error(msg);
    } finally {
      setSavingCode(null);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-primary">Role Metadata</h1>
        <p className="text-sm text-gray-500 mt-1">
          역할별 표시 라벨과 노출 여부를 관리합니다. 역할 추가·삭제는 코드 배포가 필요합니다.
        </p>
      </div>

      <Card>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-gray-500 uppercase border-b border-gray-200">
                <th className="py-3 pr-4">Role Code</th>
                <th className="py-3 pr-4">Display Label</th>
                <th className="py-3 pr-4 text-center">Assignable</th>
                <th className="py-3 pr-4 text-center">Filterable</th>
                <th className="py-3 pr-4">Sort</th>
                <th className="py-3 pr-4"></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.roleCode} className="border-b border-gray-100">
                  <td className="py-3 pr-4 font-mono text-xs text-gray-700">{row.roleCode}</td>
                  <td className="py-3 pr-4 min-w-[200px]">
                    <Input
                      value={row.displayLabel}
                      onChange={(e) => patchRow(row.roleCode, { displayLabel: e.target.value })}
                      aria-label={`Display label for ${row.roleCode}`}
                    />
                  </td>
                  <td className="py-3 pr-4 text-center">
                    <input
                      type="checkbox"
                      className="w-4 h-4 cursor-pointer accent-primary"
                      checked={row.assignable}
                      onChange={(e) => patchRow(row.roleCode, { assignable: e.target.checked })}
                      aria-label={`${row.roleCode} assignable`}
                    />
                  </td>
                  <td className="py-3 pr-4 text-center">
                    <input
                      type="checkbox"
                      className="w-4 h-4 cursor-pointer accent-primary"
                      checked={row.filterable}
                      onChange={(e) => patchRow(row.roleCode, { filterable: e.target.checked })}
                      aria-label={`${row.roleCode} filterable`}
                    />
                  </td>
                  <td className="py-3 pr-4 w-24">
                    <Input
                      type="number"
                      value={String(row.sortOrder)}
                      onChange={(e) => patchRow(row.roleCode, { sortOrder: Number(e.target.value) || 0 })}
                      aria-label={`${row.roleCode} sort order`}
                    />
                  </td>
                  <td className="py-3 pr-4">
                    <Button
                      size="sm"
                      disabled={!row.dirty || savingCode === row.roleCode}
                      onClick={() => handleSave(row)}
                    >
                      {savingCode === row.roleCode ? 'Saving…' : 'Save'}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
