import type { ReactNode } from 'react';

/**
 * 페이지 헤더 — 좌측 레드 슬래시 바(로고 모티프, §9-4) + 타이틀 + 부제 + 우측 액션.
 * 모든 작업 화면의 표준 헤더. 타이틀 위계: text-2xl font-bold text-gray-900.
 */
export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-3 min-w-0">
        <span className="block w-1 h-9 rounded-full bg-accent shrink-0" aria-hidden />
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-gray-900 truncate">{title}</h1>
          {subtitle && <p className="text-sm text-gray-500">{subtitle}</p>}
        </div>
      </div>
      {actions && <div className="flex items-center gap-2 sm:shrink-0">{actions}</div>}
    </div>
  );
}
