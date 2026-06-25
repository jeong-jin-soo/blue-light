import { useState } from 'react';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';
import { KvaConfirmModal } from './KvaConfirmModal';
import { KvaPostPaymentOverrideModal } from './KvaPostPaymentOverrideModal';
import { useAuthStore } from '../../stores/authStore';
import type { AdminApplication } from '../../types';

/**
 * Phase 5 PR#3 — Admin 상세 페이지 kVA 섹션.
 *
 * <ul>
 *   <li>UNKNOWN: amber Card + [Confirm kVA] 버튼.</li>
 *   <li>CONFIRMED: 값 + source 배지 + [Confirm kVA](USER_INPUT 검토) / [Change kVA](LEW_VERIFIED 변경) 버튼.</li>
 *   <li>권한 (결제 전):
 *     <ul>
 *       <li>ADMIN/SYSTEM_ADMIN: 확인·확정/변경·재확정 가능 (이미 LEW 확정값 변경 시 force=true 감사).</li>
 *       <li>LEW (assigned): 신청자 입력값 확인·확정, 변경·재확정 모두 가능 (force 불필요).</li>
 *       <li>LEW (unassigned): 조회 + "Not assigned" 안내만.</li>
 *     </ul>
 *   </li>
 *   <li>PRE-PAYMENT(PENDING_REVIEW/REVISION_REQUESTED/PENDING_PAYMENT): {@link KvaConfirmModal}.</li>
 *   <li>POST-PAYMENT(PAID/IN_PROGRESS/COMPLETED): ADMIN 전용 [Override (post-payment)] 버튼 노출.
 *       {@link KvaPostPaymentOverrideModal} 호출 — 별도 엔드포인트.
 *       스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.1.</li>
 *   <li>EXPIRED: 어떤 변경도 불가.</li>
 * </ul>
 */
interface KvaSectionProps {
  application: AdminApplication;
  onUpdated: () => void;
}

const PRE_PAYMENT_LOCKED_STATUSES = new Set(['PAID', 'IN_PROGRESS', 'COMPLETED']);
const POST_PAYMENT_STATUSES = new Set(['PAID', 'IN_PROGRESS', 'COMPLETED']);

export function KvaSection({ application, onUpdated }: KvaSectionProps) {
  const { user } = useAuthStore();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SYSTEM_ADMIN';
  const isLew = user?.role === 'LEW';
  const isAssignedLew =
    isLew &&
    !!application.assignedLewSeq &&
    application.assignedLewSeq === user?.userSeq;

  const [modalOpen, setModalOpen] = useState(false);
  const [postPaymentModalOpen, setPostPaymentModalOpen] = useState(false);

  // PR3 PRE-PAYMENT 가드 (기존 confirm/override 동선)
  const locked = PRE_PAYMENT_LOCKED_STATUSES.has(application.status);
  const kvaStatus = application.kvaStatus ?? 'CONFIRMED';
  const kvaSource = application.kvaSource;

  // 권한 가드 — 결제 전에는 배정 LEW/ADMIN 모두 kVA 확인·확정/변경·재확정 가능.
  // 신청자 입력값(CONFIRMED/USER_INPUT)도 LEW 가 검토 후 그대로 확정하거나 변경할 수 있고,
  // 확정(LEW_VERIFIED) 뒤에도 다시 변경·재확정 가능. (결제 후는 아래 post-payment 전용 경로.)
  const canAct = !locked && (isAdmin || isAssignedLew);
  const canConfirm = canAct && kvaStatus === 'UNKNOWN';
  const canChange = canAct && kvaStatus === 'CONFIRMED';

  // PR-1: 결제 후 kVA 사후 변경 — ADMIN 전용, PAID/IN_PROGRESS/COMPLETED 에서만 노출.
  const canOverridePostPayment =
    isAdmin &&
    POST_PAYMENT_STATUSES.has(application.status);

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
              {kvaSource === 'USER_INPUT' ? (
                <p className="text-xs text-warning-700 mt-1">
                  Applicant declared <strong>{application.selectedKva} kVA</strong>. Verify it and
                  confirm (or change the value before confirming) — it is not LEW-confirmed yet.
                </p>
              ) : (
                <p className="text-xs text-warning-700 mt-1">
                  Applicant didn't provide the capacity. Determine and confirm the kVA
                  (placeholder value: {application.selectedKva} kVA).
                </p>
              )}
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
          <div className="flex flex-col items-end gap-2">
            {canChange && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setModalOpen(true)}
              >
                {/* 신청자 입력값(USER_INPUT)은 'Confirm'(검토 확정), 이미 LEW 확정값은 'Change'(변경/재확정) */}
                {kvaSource === 'LEW_VERIFIED' ? 'Change kVA' : 'Confirm kVA'}
              </Button>
            )}
            {canOverridePostPayment && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setPostPaymentModalOpen(true)}
              >
                Override (post-payment)
              </Button>
            )}
          </div>
        </div>
      </Card>
      {canChange && (
        <KvaConfirmModal
          isOpen={modalOpen}
          application={application}
          onClose={() => setModalOpen(false)}
          onSuccess={onUpdated}
        />
      )}
      {canOverridePostPayment && (
        <KvaPostPaymentOverrideModal
          isOpen={postPaymentModalOpen}
          application={application}
          onClose={() => setPostPaymentModalOpen(false)}
          onSuccess={onUpdated}
        />
      )}
    </>
  );
}

export default KvaSection;
