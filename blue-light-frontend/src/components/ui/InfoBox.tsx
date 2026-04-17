import type { ReactNode } from 'react';

interface InfoBoxProps {
  title?: string;
  children: ReactNode;
  variant?: 'info' | 'muted';
  icon?: ReactNode;
  className?: string;
}

/**
 * InfoBox — 안내 메시지 블록 (Phase 1 §3A, 04-design-spec.md)
 * - variant="info": 파란 톤 (bg-info-50), role="note". 경고 아님.
 * - variant="muted": 배경 없는 가이드 힌트 (Signup 하단 등)
 *
 * 디자인 토큰:
 *   bg-info-50(#eff6ff), border-info-500/30, text-info-600(#2563eb)
 *   text-info-800는 토큰 미정의 → 인라인 hex(#1e40af) 사용 (design-spec 허용)
 *   text-info-700은 토큰 미정의 → 인라인 hex(#1d4ed8) 사용
 */
export function InfoBox({
  title,
  children,
  variant = 'info',
  icon,
  className = '',
}: InfoBoxProps) {
  if (variant === 'muted') {
    return (
      <p
        className={`flex items-start gap-2 text-xs text-gray-500 mt-6 ${className}`}
      >
        {children}
      </p>
    );
  }

  const defaultIcon = (
    <svg
      className="w-5 h-5 flex-shrink-0 text-info-600 mt-0.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="10" />
      <path strokeLinecap="round" d="M12 16v-4M12 8h.01" />
    </svg>
  );

  return (
    <div
      role="note"
      className={`bg-info-50 border border-info-500/30 rounded-lg p-4 flex items-start gap-3 max-w-prose ${className}`}
    >
      {icon ?? defaultIcon}
      <div>
        {title && (
          <h3 className="text-sm font-semibold text-[#1e40af]">{title}</h3>
        )}
        <div className="text-xs text-[#1d4ed8] mt-1 leading-relaxed">
          {children}
        </div>
      </div>
    </div>
  );
}
