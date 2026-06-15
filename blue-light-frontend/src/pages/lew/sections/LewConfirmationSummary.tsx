import type { ReactNode } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge, type BadgeVariant } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import type { AdminApplication, LoaStatus, SldRequest } from '../../../types';
import type { DocumentRequest } from '../../../types/document';

type SummaryTab = 'kva' | 'documents' | 'loa' | 'sld';

interface Props {
  application: AdminApplication;
  loaStatus: LoaStatus | null;
  documentRequests: DocumentRequest[];
  sldRequired: boolean;
  sldRequest: SldRequest | null;
  /** 결제 요청 전(PENDING_REVIEW/REVISION_REQUESTED)에만 true — 수정·재확정 링크 노출. */
  editable: boolean;
  onGoToTab: (tab: SummaryTab) => void;
  onDownloadFinalLoa: (fileSeq: number, filename: string) => void;
}

/**
 * LEW 확정 내역 요약 — kVA / LoA / Documents (/ SLD) 확정 상태를 한 카드에 모은다.
 *
 * <p>기존 sticky "Ready for payment?" 행은 결제 요청 전(inPhase1)에만 보여 결제 후엔 확정 내역을
 * 다시 볼 곳이 없었다. 이 패널은 <b>모든 단계(결제 요청 후 PENDING_PAYMENT/PAID 포함)에서 항상</b>
 * 노출되어 LEW 가 자신이 확정한 내역을 확인할 수 있게 한다. 결제 요청 전에는 각 항목에서 해당 탭으로
 * 이동해 수정·재확정할 수 있다.</p>
 */
export function LewConfirmationSummary({
  application,
  loaStatus,
  documentRequests,
  sldRequired,
  sldRequest,
  editable,
  onGoToTab,
  onDownloadFinalLoa,
}: Props) {
  // ── kVA ──
  const kvaConfirmed = application.kvaStatus === 'CONFIRMED';
  const kvaLewVerified = kvaConfirmed && application.kvaSource === 'LEW_VERIFIED';
  const kvaBadge: { text: string; variant: BadgeVariant } = kvaLewVerified
    ? { text: 'Confirmed by LEW', variant: 'success' }
    : kvaConfirmed
      ? { text: 'Applicant value', variant: 'info' }
      : { text: 'Pending', variant: 'warning' };

  // ── LoA ──
  const loaFinal = loaStatus?.loaStage === 'FINAL_UPLOADED';
  const loaApplicantUp = !!loaStatus?.applicantFileSeq;
  const loaBadge: { text: string; variant: BadgeVariant } = loaFinal
    ? { text: 'Final uploaded', variant: 'success' }
    : loaApplicantUp
      ? { text: 'Awaiting final', variant: 'info' }
      : { text: 'Pending', variant: 'warning' };

  // ── Documents ──
  const approvedDocs = documentRequests.filter((d) => d.status === 'APPROVED').length;
  const pendingDocs = documentRequests.filter(
    (d) => d.status === 'REQUESTED' || d.status === 'UPLOADED',
  ).length;
  const rejectedDocs = documentRequests.filter((d) => d.status === 'REJECTED').length;
  const docsBadge: { text: string; variant: BadgeVariant } =
    pendingDocs > 0 || rejectedDocs > 0
      ? { text: `${pendingDocs + rejectedDocs} outstanding`, variant: 'warning' }
      : { text: 'All resolved', variant: 'success' };

  // ── SLD ──
  const sldConfirmed = sldRequest?.status === 'CONFIRMED';
  const sldBadge: { text: string; variant: BadgeVariant } = sldConfirmed
    ? { text: 'Confirmed', variant: 'success' }
    : { text: sldRequest?.status ?? 'Missing', variant: 'warning' };

  return (
    <Card>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-800">Confirmation Summary</h2>
        {editable && (
          <span className="text-xs text-gray-500">Tap an item to change &amp; re-confirm</span>
        )}
      </div>

      <div className="divide-y divide-gray-100">
        <SummaryRow
          label="kVA"
          badge={kvaBadge}
          value={`${application.selectedKva ?? '?'} kVA`}
          action={editable ? { text: 'Change / re-confirm', onClick: () => onGoToTab('kva') } : undefined}
        />

        <SummaryRow
          label="Letter of Appointment"
          badge={loaBadge}
          value={
            loaFinal && loaStatus?.finalFileSeq ? (
              <button
                type="button"
                onClick={() => onDownloadFinalLoa(loaStatus.finalFileSeq!, `LoA_final_${application.applicationSeq}`)}
                className="text-primary-600 hover:text-primary-700 hover:underline"
              >
                Download final LoA
              </button>
            ) : loaFinal ? 'Final LoA uploaded' : loaApplicantUp ? 'Applicant signed copy received' : 'Not received'
          }
          action={editable ? { text: 'Open LOA', onClick: () => onGoToTab('loa') } : undefined}
        />

        <SummaryRow
          label="Documents"
          badge={docsBadge}
          value={
            documentRequests.length === 0
              ? 'No document requests'
              : `${approvedDocs} approved · ${pendingDocs} pending${rejectedDocs > 0 ? ` · ${rejectedDocs} rejected` : ''}`
          }
          action={editable ? { text: 'Open Documents', onClick: () => onGoToTab('documents') } : undefined}
        />

        {sldRequired && (
          <SummaryRow
            label="SLD"
            badge={sldBadge}
            value={sldConfirmed ? 'Single line diagram confirmed' : 'Not yet confirmed'}
            action={editable ? { text: 'Open SLD', onClick: () => onGoToTab('sld') } : undefined}
          />
        )}
      </div>
    </Card>
  );
}

function SummaryRow({
  label,
  badge,
  value,
  action,
}: {
  label: string;
  badge: { text: string; variant: BadgeVariant };
  value: ReactNode;
  action?: { text: string; onClick: () => void };
}) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 py-3 first:pt-0 last:pb-0">
      <span className="w-44 shrink-0 text-sm font-medium text-gray-700">{label}</span>
      <Badge variant={badge.variant}>{badge.text}</Badge>
      <span className="min-w-0 flex-1 truncate text-sm text-gray-600">{value}</span>
      {action && (
        <Button size="sm" variant="ghost" onClick={action.onClick}>
          {action.text} →
        </Button>
      )}
    </div>
  );
}
