/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — LEW 배정 모달.
 *
 * <p>스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §5.3, §10 AC-L1~L4.</p>
 *
 * <h3>UI 동작</h3>
 * <ul>
 *   <li>ADMIN/SYSTEM_ADMIN 로그인 시: GET /api/admin/lews 로 활성 LEW 목록 자동 로드 → 드롭다운</li>
 *   <li>본인이 LEW role(primary 또는 secondary) 보유 시: "Assign to myself" 체크박스 (D6=A 셀프 할당)</li>
 *   <li>CONCIERGE_MANAGER 단독 + LEW 검색 권한 없음 (admin/lews 403): 안내 메시지 + 셀프 할당만 노출</li>
 *   <li>현재 배정된 LEW 가 있으면 "Currently assigned: X. Reassigning..." 경고 노출</li>
 * </ul>
 *
 * <p>응답 selfAssigned=true 면 부모 컴포넌트가 toast 메시지를 다르게 표시 ("You assigned to yourself").</p>
 */

import { useEffect, useState } from 'react';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../ui/Modal';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { useAuthStore } from '../../stores/authStore';
import { hasRole } from '../../utils/jwtRoles';
import { fullName } from '../../utils/formatName';
import { getAvailableLews } from '../../api/adminLewApi';
import type { LewSummary } from '../../types';
import type { AssignLewRequestPayload } from '../../types/concierge';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (payload: AssignLewRequestPayload) => Promise<void>;
  /** 현재 배정된 LEW 표시명 — null 이면 첫 배정. */
  currentAssigneeName?: string | null;
  /** 현재 배정된 LEW seq — selfAssign 체크박스 자동 비활성화에도 사용 */
  currentAssigneeSeq?: number | null;
  loading?: boolean;
}

export function AssignLewModal({
  isOpen,
  onClose,
  onSubmit,
  currentAssigneeName,
  currentAssigneeSeq,
  loading = false,
}: Props) {
  const { user } = useAuthStore();

  const [availableLews, setAvailableLews] = useState<LewSummary[]>([]);
  const [lewLoadError, setLewLoadError] = useState<string | null>(null);
  const [lewLoading, setLewLoading] = useState(false);

  const [selectedLewSeq, setSelectedLewSeq] = useState<number | null>(null);
  const [selfAssignChecked, setSelfAssignChecked] = useState<boolean>(false);
  const [manualLewSeq, setManualLewSeq] = useState<string>('');
  const [errMsg, setErrMsg] = useState<string | null>(null);

  // 본인이 LEW role 을 보유 (primary 또는 secondary) — 셀프 할당 가능 여부.
  const canSelfAssign = !!user && hasRole('LEW');
  // ADMIN 권한이 있으면 /admin/lews 로 LEW 목록 조회 가능. CONCIERGE_MANAGER 단독은 403.
  const canListLews = !!user && (user.role === 'ADMIN' || user.role === 'SYSTEM_ADMIN');

  // controlled-modal prop-driven reset + async LEW 목록 fetch.
  // react-hooks/set-state-in-effect 는 prop-driven reset 패턴을 잘못 진단 — 첫 setter 만 disable.
  useEffect(() => {
    if (!isOpen) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedLewSeq(null);
    setSelfAssignChecked(false);
    setManualLewSeq('');
    setErrMsg(null);

    if (canListLews) {
      setLewLoading(true);
      setLewLoadError(null);
      getAvailableLews()
        .then((lews: LewSummary[]) => setAvailableLews(lews))
        .catch((err: unknown) => {
          const msg = err && typeof err === 'object' && 'message' in err
            ? String((err as { message?: unknown }).message)
            : 'Failed to load LEW list';
          setLewLoadError(msg);
          setAvailableLews([]);
        })
        .finally(() => setLewLoading(false));
    } else {
      setAvailableLews([]);
      setLewLoadError(null);
    }
  }, [isOpen, canListLews]);

  // 셀프 할당 체크 토글 시 selectedLewSeq 자동 채우기.
  useEffect(() => {
    if (selfAssignChecked && user) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSelectedLewSeq(user.userSeq);
      setManualLewSeq('');
    }
  }, [selfAssignChecked, user]);

  const isReassign = currentAssigneeSeq != null;
  // 최종 lewUserSeq 결정 우선순위: 셀프 할당 → 드롭다운 → manual 입력.
  const resolvedLewSeq: number | null = selfAssignChecked && user
    ? user.userSeq
    : selectedLewSeq != null
      ? selectedLewSeq
      : Number(manualLewSeq) > 0
        ? Number(manualLewSeq)
        : null;

  const isSameAsCurrent = resolvedLewSeq != null
    && currentAssigneeSeq != null
    && resolvedLewSeq === currentAssigneeSeq;

  const canSubmit = resolvedLewSeq != null && !loading;

  const handleSubmit = async () => {
    setErrMsg(null);
    if (!canSubmit || resolvedLewSeq == null) return;
    try {
      await onSubmit({ lewUserSeq: resolvedLewSeq });
    } catch (err) {
      const msg = err && typeof err === 'object' && 'message' in err
        ? String((err as { message?: unknown }).message)
        : 'Failed to assign LEW';
      setErrMsg(msg);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="md" ariaLabelledBy="assign-lew-title">
      <ModalHeader title={isReassign ? 'Reassign LEW' : 'Assign LEW'} onClose={onClose}>
        <h3 id="assign-lew-title" className="text-lg font-semibold text-gray-800">
          {isReassign ? 'Reassign LEW' : 'Assign LEW'}
        </h3>
      </ModalHeader>
      <ModalBody className="space-y-4">
        {isReassign && currentAssigneeName && (
          <div className="rounded-md bg-warning-50 border border-warning-200 p-3 text-sm">
            <p className="font-medium text-warning-800">Currently assigned: {currentAssigneeName}</p>
            <p className="mt-0.5 text-xs text-warning-700">
              The current LEW will receive an unassign notification when you reassign.
            </p>
          </div>
        )}

        {/* 셀프 할당 체크박스 (본인이 LEW role 을 보유한 경우) */}
        {canSelfAssign && user && (
          <div className="rounded-md bg-primary-50 border border-primary-200 p-3">
            <label className="flex items-start gap-2 text-sm cursor-pointer">
              <input
                type="checkbox"
                className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                checked={selfAssignChecked}
                onChange={(e) => setSelfAssignChecked(e.target.checked)}
                disabled={loading}
              />
              <span>
                <span className="font-medium text-primary-800">Assign to myself</span>
                <span className="block text-xs text-primary-700 mt-0.5">
                  You hold the LEW role. Self-assigning will skip the LEW notification (audited).
                </span>
              </span>
            </label>
          </div>
        )}

        {/* LEW 드롭다운 (ADMIN/SYSTEM_ADMIN 만) */}
        {canListLews && !selfAssignChecked && (
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1" htmlFor="al-select">
              Select LEW
            </label>
            {lewLoading ? (
              <p className="text-xs text-gray-500">Loading LEW list...</p>
            ) : lewLoadError ? (
              <p className="text-xs text-error-600">{lewLoadError}</p>
            ) : availableLews.length === 0 ? (
              <p className="text-xs text-gray-500">No active LEWs found.</p>
            ) : (
              <select
                id="al-select"
                className="block w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:bg-gray-100"
                value={selectedLewSeq ?? ''}
                onChange={(e) => {
                  const v = e.target.value;
                  setSelectedLewSeq(v ? Number(v) : null);
                }}
                disabled={loading}
              >
                <option value="">— Select a LEW —</option>
                {availableLews.map((lew) => (
                  <option key={lew.userSeq} value={lew.userSeq}>
                    {fullName(lew.firstName, lew.lastName)} · {lew.email}
                    {lew.lewLicenceNo ? ` · ${lew.lewLicenceNo}` : ''}
                  </option>
                ))}
              </select>
            )}
          </div>
        )}

        {/* CONCIERGE_MANAGER 단독 fallback — 직접 LEW seq 입력 */}
        {!canListLews && !selfAssignChecked && (
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1" htmlFor="al-manual">
              LEW user ID
            </label>
            <Input
              id="al-manual"
              type="number"
              min="1"
              value={manualLewSeq}
              onChange={(e) => setManualLewSeq(e.target.value)}
              placeholder="LEW user_seq"
              disabled={loading}
            />
            <p className="mt-1 text-xs text-gray-500">
              Concierge managers do not have access to the LEW directory. Enter the LEW user ID
              directly, or ask an administrator to perform the assignment.
            </p>
          </div>
        )}

        {/* 동일 LEW 안내 (재할당 멱등) */}
        {isSameAsCurrent && (
          <div className="rounded-md bg-gray-50 border border-gray-200 p-2 text-xs text-gray-600">
            This LEW is already assigned to the request. Submitting will refresh the assignment timestamp.
          </div>
        )}

        {errMsg && (
          <div role="alert" className="rounded-md bg-error-50 border border-error-200 p-3 text-sm text-error-700">
            {errMsg}
          </div>
        )}
      </ModalBody>
      <ModalFooter>
        <Button variant="ghost" onClick={onClose} disabled={loading}>
          Cancel
        </Button>
        <Button
          variant="primary"
          onClick={handleSubmit}
          disabled={!canSubmit}
          loading={loading}
        >
          {isReassign ? 'Reassign' : 'Assign'}
        </Button>
      </ModalFooter>
    </Modal>
  );
}
