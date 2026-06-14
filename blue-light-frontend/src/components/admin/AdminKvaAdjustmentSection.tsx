import { useCallback, useEffect, useMemo, useState } from 'react';
import { Card } from '../ui/Card';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { LoadingSpinner } from '../ui/LoadingSpinner';
import { useAuthStore } from '../../stores/authStore';
import { useToastStore } from '../../stores/toastStore';
import {
  getKvaAdjustments,
  type KvaAdjustmentHistoryItem,
  type KvaAdjustmentStatus,
  type KvaAdjustmentChangedByRole,
  type KvaPaymentAdjustment,
} from '../../api/adminApplicationApi';
import KvaSettlementModal from './KvaSettlementModal';

/**
 * 결제 후 kVA 사후 변경 이력 섹션 (PR-4).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §8 PR-4.</p>
 *
 * <h3>표시 조건</h3>
 * application.status 가 PAID/IN_PROGRESS/COMPLETED 일 때만 카드를 노출한다 — 결제 전 신청은 본 섹션
 * 의 의미가 없다. 단, 빈 이력(0건) 도 카드는 노출하여 ADMIN 이 향후 변경 시 시각적으로 발견할 수 있게.
 *
 * <h3>그룹화</h3>
 * ADMIN 변경 row 의 {@code lewRequestSeq} 가 어떤 LEW 요청 row 를 가리키면, 두 row 를 한 카드 그룹
 * 으로 묶어 표시 (들여쓰기 + 연결선). 묶이지 않은 row 는 단독 카드.
 *
 * <h3>Settlement 버튼</h3>
 * row.status 가 APPLIED 또는 RESOLVED_BY_ADMIN_OVERRIDE 이고 paymentAdjustment 가 PENDING/null
 * 인 row 에만 "Mark settlement" 버튼 노출. 이미 finalize 된 row 는 정산 결과 배지만.
 *
 * <h3>권한</h3>
 * GET 엔드포인트는 ADMIN 또는 assigned LEW 에 허용. settlement 버튼은 ADMIN/SYSTEM_ADMIN 전용.
 */

interface Props {
  applicationSeq: number;
  /** application.status — PAID/IN_PROGRESS/COMPLETED 일 때만 카드 노출. */
  applicationStatus: string;
}

const POSTPAYMENT_STATUSES = new Set(['PAID', 'IN_PROGRESS', 'COMPLETED']);

const STATUS_BADGE: Record<KvaAdjustmentStatus, { label: string; variant: 'gray' | 'success' | 'warning' | 'error' | 'info' | 'primary' }> = {
  PENDING_ADMIN_REVIEW: { label: 'Pending review', variant: 'warning' },
  APPLIED: { label: 'Applied', variant: 'success' },
  RESOLVED_BY_ADMIN_OVERRIDE: { label: 'Resolved by override', variant: 'info' },
  REJECTED: { label: 'Rejected', variant: 'error' },
  CANCELLED: { label: 'Cancelled', variant: 'gray' },
};

const ROLE_BADGE: Record<KvaAdjustmentChangedByRole, { label: string; variant: 'primary' | 'gray' }> = {
  ADMIN: { label: 'Admin', variant: 'primary' },
  LEW: { label: 'LEW', variant: 'gray' },
};

const PAYMENT_BADGE: Record<KvaPaymentAdjustment, { label: string; variant: 'gray' | 'success' | 'warning' | 'error' | 'info' }> = {
  PENDING: { label: 'Settlement pending', variant: 'warning' },
  PAID_DIFFERENCE: { label: 'Paid difference', variant: 'success' },
  REFUNDED: { label: 'Refunded', variant: 'success' },
  WAIVED: { label: 'Waived', variant: 'gray' },
};

function formatDateTime(s?: string): string {
  if (!s) return '—';
  try {
    return new Date(s).toLocaleString();
  } catch {
    return s;
  }
}

function formatMoney(n: number | undefined | null): string {
  if (n == null) return '—';
  return `$${Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatDifference(n: number | undefined | null): string {
  if (n == null) return '—';
  if (n > 0) return `+${formatMoney(n)}`;
  if (n < 0) return `−${formatMoney(Math.abs(n))}`;
  return '$0.00';
}

export function AdminKvaAdjustmentSection({ applicationSeq, applicationStatus }: Props) {
  const { user } = useAuthStore();
  const toast = useToastStore();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SYSTEM_ADMIN';

  const [items, setItems] = useState<KvaAdjustmentHistoryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [settlementTarget, setSettlementTarget] = useState<KvaAdjustmentHistoryItem | null>(null);

  const visible = POSTPAYMENT_STATUSES.has(applicationStatus);

  const fetchItems = useCallback(async () => {
    if (!visible) {
      setLoading(false);
      return;
    }
    try {
      const data = await getKvaAdjustments(applicationSeq);
      setItems(data);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      // 권한 없거나 다른 에러는 toast 하나만, 카드는 빈 배열 상태로.
      toast.error(e.message ?? 'Failed to load kVA adjustment history');
    } finally {
      setLoading(false);
    }
  }, [applicationSeq, visible, toast]);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  // grouping: ADMIN row.lewRequestSeq 가 가리키는 LEW row 를 묶음.
  // 응답은 시간 내림차순이므로 ADMIN row 가 먼저 오고 LEW row 가 뒤에 오는 케이스가 자연스럽다.
  const groups = useMemo(() => {
    if (items.length === 0) return [];
    // adjustmentSeq → row 로 인덱싱.
    const byId = new Map<number, KvaAdjustmentHistoryItem>();
    for (const it of items) byId.set(it.adjustmentSeq, it);
    // lewRequestSeq 가 있는 ADMIN row 가 가리키는 LEW row 의 id 를 children 으로 모은다.
    const consumedAsChild = new Set<number>();
    for (const it of items) {
      if (it.changedByRole === 'ADMIN' && it.lewRequestSeq != null && byId.has(it.lewRequestSeq)) {
        consumedAsChild.add(it.lewRequestSeq);
      }
    }
    const result: Array<{ parent: KvaAdjustmentHistoryItem; child?: KvaAdjustmentHistoryItem }> = [];
    for (const it of items) {
      if (consumedAsChild.has(it.adjustmentSeq)) continue; // 부모(ADMIN) 측에서 함께 표시됨.
      const child = (it.changedByRole === 'ADMIN' && it.lewRequestSeq != null)
        ? byId.get(it.lewRequestSeq)
        : undefined;
      result.push({ parent: it, child });
    }
    return result;
  }, [items]);

  if (!visible) return null;

  const handleSettlementSuccess = (updated: KvaAdjustmentHistoryItem) => {
    // 즉시 로컬 갱신 — 깜빡임 방지. 추가로 fetch 도 호출하여 audit 완전성 보장.
    setItems((prev) =>
      prev.map((it) => (it.adjustmentSeq === updated.adjustmentSeq ? updated : it))
    );
    fetchItems();
  };

  return (
    <>
      <Card>
        <div className="flex items-start justify-between mb-4 gap-3">
          <div>
            <h3 className="text-sm font-semibold text-gray-800">kVA adjustment history</h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Post-payment kVA changes and manual settlement records.
            </p>
          </div>
        </div>

        {loading ? (
          <div className="py-8 flex items-center justify-center">
            <LoadingSpinner size="sm" label="Loading history..." />
          </div>
        ) : groups.length === 0 ? (
          <div className="py-6 text-center text-sm text-gray-500 border border-dashed border-gray-200 rounded-md">
            No adjustment history yet.
          </div>
        ) : (
          <ol className="space-y-4">
            {groups.map(({ parent, child }) => (
              <li
                key={parent.adjustmentSeq}
                className="border border-gray-200 rounded-md p-3 bg-white"
              >
                <AdjustmentRow
                  row={parent}
                  isAdmin={isAdmin}
                  onMarkSettlement={() => setSettlementTarget(parent)}
                />
                {child && (
                  <div className="mt-3 ml-4 pl-3 border-l-2 border-gray-200">
                    <p className="text-xs text-gray-500 mb-1">Originated from this LEW request:</p>
                    <AdjustmentRow row={child} isAdmin={isAdmin} onMarkSettlement={() => setSettlementTarget(child)} />
                  </div>
                )}
              </li>
            ))}
          </ol>
        )}
      </Card>

      {settlementTarget && (
        <KvaSettlementModal
          isOpen={!!settlementTarget}
          applicationSeq={applicationSeq}
          adjustment={settlementTarget}
          onClose={() => setSettlementTarget(null)}
          onSuccess={handleSettlementSuccess}
        />
      )}
    </>
  );
}

interface RowProps {
  row: KvaAdjustmentHistoryItem;
  isAdmin: boolean;
  onMarkSettlement: () => void;
}

function AdjustmentRow({ row, isAdmin, onMarkSettlement }: RowProps) {
  const statusBadge = STATUS_BADGE[row.status];
  const roleBadge = ROLE_BADGE[row.changedByRole];

  // settlement 마킹 가능 조건:
  // (1) ADMIN 권한, (2) status APPLIED/RESOLVED_BY_ADMIN_OVERRIDE,
  // (3) 아직 finalize 되지 않음 (PENDING/null).
  const canMarkSettlement =
    isAdmin
    && (row.status === 'APPLIED' || row.status === 'RESOLVED_BY_ADMIN_OVERRIDE')
    && (row.paymentAdjustment == null || row.paymentAdjustment === 'PENDING');

  const paymentBadge = row.paymentAdjustment ? PAYMENT_BADGE[row.paymentAdjustment] : null;

  // 변경 양상: ADMIN row 는 previous→new, LEW row 는 previous→proposed (제안).
  const fromKva = row.previousKva;
  const toKva = row.changedByRole === 'ADMIN' ? row.newKva : row.proposedKva;

  return (
    <div>
      {/* Header — badges + meta */}
      <div className="flex flex-wrap items-center gap-2 mb-1.5">
        <Badge variant={statusBadge.variant}>{statusBadge.label}</Badge>
        <Badge variant={roleBadge.variant}>{roleBadge.label}</Badge>
        {paymentBadge && <Badge variant={paymentBadge.variant}>{paymentBadge.label}</Badge>}
        <span className="text-xs text-gray-500 ml-auto">
          {formatDateTime(row.createdAt)}
        </span>
      </div>

      {/* kVA + amount summary */}
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm text-gray-800">
        <span className="font-medium">
          {fromKva != null ? `${fromKva} kVA` : '—'}
          <span className="mx-1.5 text-gray-400">→</span>
          {toKva != null ? `${toKva} kVA` : '—'}
        </span>
        {row.amountDifference != null && row.changedByRole === 'ADMIN' && (
          <span className="text-xs text-gray-600">
            quote {formatMoney(row.previousQuoteAmount)} → {formatMoney(row.newQuoteAmount)}
            <span className={`ml-1.5 font-medium ${row.amountDifference > 0 ? 'text-error-600' : row.amountDifference < 0 ? 'text-success-600' : 'text-gray-700'}`}>
              ({formatDifference(row.amountDifference)})
            </span>
          </span>
        )}
        {row.changedByUserName && (
          <span className="text-xs text-gray-500">by {row.changedByUserName}</span>
        )}
      </div>

      {/* Reason */}
      {row.reason && (
        <p className="mt-1.5 text-xs text-gray-700 bg-gray-50 border border-gray-200 rounded p-2 italic">
          "{row.reason}"
        </p>
      )}

      {/* Settlement details (이미 마킹된 row) */}
      {row.paymentAdjustment && row.paymentAdjustment !== 'PENDING' && (
        <div className="mt-2 text-xs text-gray-700 grid grid-cols-2 gap-x-4 gap-y-0.5">
          {row.settledAmount != null && (
            <div>
              <span className="text-gray-500">Settled: </span>
              {formatMoney(row.settledAmount)}
            </div>
          )}
          {row.receiptReferenceNumber && (
            <div>
              <span className="text-gray-500">Ref: </span>
              {row.receiptReferenceNumber}
            </div>
          )}
          {row.settledAt && (
            <div>
              <span className="text-gray-500">Settled at: </span>
              {formatDateTime(row.settledAt)}
            </div>
          )}
          {row.settlementMemo && (
            <div className="col-span-2">
              <span className="text-gray-500">Memo: </span>
              {row.settlementMemo}
            </div>
          )}
        </div>
      )}

      {canMarkSettlement && (
        <div className="mt-2.5">
          <Button size="sm" variant="outline" onClick={onMarkSettlement}>
            Mark settlement
          </Button>
        </div>
      )}
    </div>
  );
}

export default AdminKvaAdjustmentSection;
