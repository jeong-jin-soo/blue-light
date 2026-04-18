import { useMemo } from 'react';
import type { DocumentRequest } from '../../types/document';

interface DocumentRequestBannerProps {
  /** 전체 요청 목록 — 내부에서 REQUESTED/REJECTED만 집계 */
  requests: DocumentRequest[];
  /** "View" 클릭 시 호출 (섹션 앵커로 스크롤) */
  onView?: () => void;
  /** 스크롤 대상 앵커 id (onView 미제공 시 사용) */
  anchorId?: string;
}

/**
 * Phase 3 PR#3 — 신청자 상단 경고 배너 (AC-AU1)
 *
 * 노출 조건: REQUESTED 또는 REJECTED 상태의 요청이 1건 이상 있을 때만 렌더.
 * 모든 요청이 APPROVED/CANCELLED가 되면 자동 숨김.
 *
 * 색: warning (Phase 1/2 InfoBox의 info 톤보다 한 단계 강함 — UX §3-1).
 */
export function DocumentRequestBanner({
  requests,
  onView,
  anchorId = 'doc-requests',
}: DocumentRequestBannerProps) {
  const { total, rejected } = useMemo(() => {
    let total = 0;
    let rejected = 0;
    for (const r of requests) {
      if (r.status === 'REQUESTED' || r.status === 'REJECTED') {
        total += 1;
        if (r.status === 'REJECTED') rejected += 1;
      }
    }
    return { total, rejected };
  }, [requests]);

  if (total === 0) return null;

  const handleView = () => {
    if (onView) {
      onView();
      return;
    }
    const el = document.getElementById(anchorId);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      // 포커스 이동 (키보드 사용자 접근성)
      const focusable = el.querySelector<HTMLElement>('h2, h3, h4, [tabindex]');
      if (focusable) focusable.focus({ preventScroll: true });
    }
  };

  return (
    <div
      role="alert"
      aria-live="polite"
      className="bg-warning-50 border border-warning-500/40 rounded-lg p-4"
    >
      <div className="flex items-start gap-3">
        <span className="text-lg flex-shrink-0" aria-hidden>
          🔔
        </span>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-warning-800">
            LEW가 서류 {total}건을 요청했습니다 · Your LEW requested {total} document
            {total > 1 ? 's' : ''}
          </p>
          {rejected > 0 && (
            <p className="text-xs text-warning-700 mt-1">
              {rejected}건 반려 · 재업로드가 필요합니다 · {rejected} rejected · please re-upload
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={handleView}
          className="flex-shrink-0 inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-warning-800 bg-white border border-warning-500/40 rounded-md hover:bg-warning-50 focus:outline-none focus:ring-2 focus:ring-warning-500/30 transition-colors"
        >
          보기 · View
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24" aria-hidden>
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 14l-7 7m0 0l-7-7m7 7V3" />
          </svg>
        </button>
      </div>
    </div>
  );
}
