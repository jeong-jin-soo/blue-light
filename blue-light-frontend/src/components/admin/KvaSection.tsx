import { useState } from 'react';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';
import { KvaConfirmModal } from './KvaConfirmModal';
import { useAuthStore } from '../../stores/authStore';
import type { AdminApplication } from '../../types';

/**
 * Phase 5 PR#3 — Admin 상세 페이지 kVA 섹션.
 *
 * <ul>
 *   <li>UNKNOWN: amber Card + [Confirm kVA] 버튼.</li>
 *   <li>CONFIRMED: 값 + source 배지 + (ADMIN) Override 버튼.</li>
 *   <li>권한:
 *     <ul>
 *       <li>ADMIN/SYSTEM_ADMIN: 항상 확정 + override 가능.</li>
 *       <li>LEW (assigned): UNKNOWN 시에만 확정 가능. override 버튼 숨김.</li>
 *       <li>LEW (unassigned): 조회 + "Not assigned" 안내만.</li>
 *     </ul>
 *   </li>
 *   <li>PAID/IN_PROGRESS/COMPLETED/EXPIRED: 서버가 KVA_LOCKED_AFTER_PAYMENT 로 차단하므로 UI 도 버튼 비활성.</li>
 * </ul>
 */
interface KvaSectionProps {
  application: AdminApplication;
  onUpdated: () => void;
}

const LOCKED_STATUSES = new Set(['PAID', 'IN_PROGRESS', 'COMPLETED', 'EXPIRED']);

export function KvaSection({ application, onUpdated }: KvaSectionProps) {
  const { user } = useAuthStore();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SYSTEM_ADMIN';
  const isLew = user?.role === 'LEW';
  const isAssignedLew =
    isLew &&
    !!application.assignedLewSeq &&
    application.assignedLewSeq === user?.userSeq;

  const [modalOpen, setModalOpen] = useState(false);

  const locked = LOCKED_STATUSES.has(application.status);
  const kvaStatus = application.kvaStatus ?? 'CONFIRMED';
  const kvaSource = application.kvaSource;

  // 권한 가드 — 버튼 노출 여부
  const canConfirm =
    !locked &&
    kvaStatus === 'UNKNOWN' &&
    (isAdmin || isAssignedLew);

  const canOverride =
    !locked &&
    kvaStatus === 'CONFIRMED' &&
    isAdmin;

  const confirmedAt = application.kvaConfirmedAt
    ? new Date(application.kvaConfirmedAt).toLocaleDateString()
    : null;

  if (kvaStatus === 'UNKNOWN') {
    return (
      <>
        <Card className="bg-warning-50 border border-warning-200">
          <div className="flex items-start gap-3">
            <span className="text-lg" aria-hidden>⏱</span>
            <div className="flex-1">
              <h3 className="text-sm font-semibold text-warning-800">
                kVA confirmation required
              </h3>
              <p className="text-xs text-warning-700 mt-1">
                Applicant is waiting for LEW to confirm the electrical capacity.
                Placeholder value: {application.selectedKva} kVA.
              </p>
              {isLew && !isAssignedLew && (
                <p className="text-xs text-warning-700 mt-2 italic">
                  This application is not assigned to you — contact the admin to request assignment.
                </p>
              )}
              {locked && (
                <p className="text-xs text-warning-700 mt-2 italic">
                  Status is {application.status}; kVA is locked.
                </p>
              )}
            </div>
            {canConfirm && (
              <Button size="sm" onClick={() => setModalOpen(true)}>
                Confirm kVA
              </Button>
            )}
          </div>
        </Card>
        {canConfirm && (
          <KvaConfirmModal
            isOpen={modalOpen}
            application={application}
            onClose={() => setModalOpen(false)}
            onSuccess={onUpdated}
          />
        )}
      </>
    );
  }

  // CONFIRMED
  return (
    <>
      <Card>
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1">
            <h3 className="text-sm font-semibold text-gray-800">Electrical capacity</h3>
            <div className="mt-2 flex items-center gap-2 flex-wrap">
              <span className="text-lg font-semibold text-gray-900">
                {application.selectedKva} kVA
              </span>
              {kvaSource === 'LEW_VERIFIED' ? (
                <Badge variant="success">LEW verified</Badge>
              ) : (
                <Badge variant="gray">User input</Badge>
              )}
              {confirmedAt && kvaSource === 'LEW_VERIFIED' && (
                <span className="text-xs text-gray-500">
                  Confirmed on {confirmedAt}
                </span>
              )}
            </div>
          </div>
          {canOverride && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => setModalOpen(true)}
            >
              Override
            </Button>
          )}
        </div>
      </Card>
      {canOverride && (
        <KvaConfirmModal
          isOpen={modalOpen}
          application={application}
          onClose={() => setModalOpen(false)}
          onSuccess={onUpdated}
        />
      )}
    </>
  );
}

export default KvaSection;
