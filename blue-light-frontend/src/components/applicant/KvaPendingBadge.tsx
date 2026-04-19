interface KvaPendingBadgeProps {
  /** Badge만 표시할지(기본) 아니면 텍스트 포함 전체 pill인지. */
  label?: string;
  className?: string;
}

/**
 * KvaPendingBadge — kVA UNKNOWN 상태 표시용 amber pill (Phase 5 PR#2)
 *
 * Phase 3 PendingDocsBadge 규격과 동일 (warning tone + border).
 * 시계 SVG 아이콘으로 "확정 대기" 뉘앙스 전달.
 */
export function KvaPendingBadge({ label = 'kVA pending', className = '' }: KvaPendingBadgeProps) {
  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold text-warning-800 bg-warning-50 border border-warning-500/40 rounded-full ${className}`}
      title="kVA pending LEW review"
    >
      <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="9" />
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 7v5l3 2" />
      </svg>
      {label}
    </span>
  );
}
