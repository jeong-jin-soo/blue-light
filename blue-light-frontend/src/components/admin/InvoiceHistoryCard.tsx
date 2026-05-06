/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — 영수증 이력 카드.
 *
 * <p>스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §10 AC-R1.</p>
 *
 * <p>Application 상세 / Concierge 요청 상세에 공통으로 사용. 현재 시점에 백엔드는 Application 단위
 * 단일 Invoice 만 노출하고 있다 (GET /api/admin/applications/{id}/invoice 또는
 * /api/applications/{id}/invoice 신청자 본인). 향후 multi-invoice 가 도입되면 본 카드의
 * receipts 배열을 그대로 확장한다.</p>
 *
 * <p>Manual Payment 직후 invoice 가 비동기 발행이라 즉시 표시되지 않을 수 있다 (AFTER_COMMIT).
 * 부모 컴포넌트가 manual-payment 응답 후 reload 시 본 카드가 재로드되어 새 row 가 등장한다.</p>
 */

import { useEffect, useState } from 'react';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import invoiceApi from '../../api/invoiceApi';
import type { Invoice } from '../../types';

type Mode = 'admin' | 'applicant';

interface Props {
  /** Application 결제 시 신청 seq. Concierge 결제 시 null — 컨시어지는 별도 invoice 조회 API
   *  가 아직 없으므로 receipts prop 으로 직접 주입한다. */
  applicationSeq?: number | null;
  /** 외부에서 영수증 목록을 직접 주입할 때 사용 (Concierge 흐름). */
  receipts?: Invoice[];
  /** 어떤 권한 컨텍스트에서 호출되는지 — admin 모드는 admin 엔드포인트 사용. */
  mode: Mode;
  /** Reload 핸들러 (Manual Payment 후 재조회 트리거 — 부모가 제공) */
  refreshKey?: number | string;
}

export function InvoiceHistoryCard({ applicationSeq, receipts, mode, refreshKey }: Props) {
  const [items, setItems] = useState<Invoice[]>(receipts ?? []);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // 외부 주입 모드 — 그대로 표시.
    if (receipts) {
      setItems(receipts);
      return;
    }
    if (applicationSeq == null) {
      setItems([]);
      return;
    }
    let cancelled = false;
    const fetchInvoice = async () => {
      setLoading(true);
      setError(null);
      try {
        const inv = mode === 'admin'
          ? await invoiceApi.getInvoiceAsAdmin(applicationSeq)
          : await invoiceApi.getMyInvoice(applicationSeq);
        if (!cancelled) {
          setItems(inv ? [inv] : []);
        }
      } catch (err) {
        if (cancelled) return;
        // 404 등 invoice 미발행 케이스는 정상 — 단순 빈 상태로 처리.
        const status = (err as { status?: number; statusCode?: number })?.status
          ?? (err as { statusCode?: number })?.statusCode;
        if (status === 404) {
          setItems([]);
        } else {
          const msg = err && typeof err === 'object' && 'message' in err
            ? String((err as { message?: unknown }).message)
            : 'Failed to load receipts';
          setError(msg);
          setItems([]);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchInvoice();
    return () => {
      cancelled = true;
    };
  }, [applicationSeq, mode, receipts, refreshKey]);

  const downloadHref = (invoice: Invoice) => invoiceApi.buildInvoicePdfDownloadUrl(invoice.pdfFileSeq);

  return (
    <Card id="receipts">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-gray-800">Receipts</h3>
        {loading && <span className="text-xs text-gray-400">Loading...</span>}
      </div>

      {error && (
        <div role="alert" className="text-xs text-error-600 mb-2">{error}</div>
      )}

      {!loading && !error && items.length === 0 && (
        <p className="text-xs text-gray-500">
          No receipts issued yet. A receipt PDF is auto-generated when a payment is recorded with
          "Issue receipt" on.
        </p>
      )}

      {items.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 text-xs text-gray-500">
                <th className="text-left font-medium py-2 pr-2">Invoice #</th>
                <th className="text-right font-medium py-2 pr-2">Amount</th>
                <th className="text-left font-medium py-2 pr-2">Issued at</th>
                <th className="text-right font-medium py-2">PDF</th>
              </tr>
            </thead>
            <tbody>
              {items.map((inv) => (
                <tr key={inv.invoiceSeq} className="border-b border-gray-100 last:border-b-0">
                  <td className="py-2 pr-2 font-mono text-xs text-gray-700">
                    {inv.invoiceNumber}
                  </td>
                  <td className="py-2 pr-2 text-right text-gray-800">
                    {inv.currency} {Number(inv.totalAmount).toFixed(2)}
                  </td>
                  <td className="py-2 pr-2 text-gray-600 text-xs">
                    {new Date(inv.issuedAt).toLocaleString()}
                  </td>
                  <td className="py-2 text-right">
                    <a
                      href={downloadHref(inv)}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-block"
                    >
                      <Button variant="outline" size="sm">
                        Download
                      </Button>
                    </a>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
