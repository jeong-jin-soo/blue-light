import { useState, type ReactNode } from 'react';
import { EmptyState } from '../ui/EmptyState';

export interface Column<T> {
  key: string;
  header: string;
  render?: (item: T) => ReactNode;
  sortable?: boolean;
  width?: string;
  align?: 'left' | 'center' | 'right';
  /** Additional CSS classes (e.g., 'hidden sm:table-cell' for responsive hiding) */
  className?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  loading?: boolean;
  emptyIcon?: ReactNode;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: ReactNode;
  onRowClick?: (item: T) => void;
  keyExtractor: (item: T) => string | number;
  className?: string;
  /** Optional mobile card renderer. When provided, cards are shown on mobile (<640px) instead of the table. */
  mobileCardRender?: (item: T) => ReactNode;
}

export function DataTable<T>({
  columns,
  data,
  loading = false,
  emptyIcon,
  emptyTitle = 'No data found',
  emptyDescription,
  emptyAction,
  onRowClick,
  keyExtractor,
  className = '',
  mobileCardRender,
}: DataTableProps<T>) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const handleSort = (key: string) => {
    if (sortKey === key) {
      setSortDir((prev) => (prev === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  };

  // Local sort
  const sortedData = sortKey
    ? [...data].sort((a, b) => {
        const aVal = (a as Record<string, unknown>)[sortKey];
        const bVal = (b as Record<string, unknown>)[sortKey];
        if (aVal == null || bVal == null) return 0;
        const cmp = String(aVal).localeCompare(String(bVal), undefined, { numeric: true });
        return sortDir === 'asc' ? cmp : -cmp;
      })
    : data;

  const alignClass = (align?: string) => {
    if (align === 'center') return 'text-center';
    if (align === 'right') return 'text-right';
    return 'text-left';
  };

  const sortableColumns = columns.filter((col) => col.sortable);

  return (
    <div className={`bg-surface rounded-xl shadow-card overflow-hidden ${className}`}>
      {/* Mobile card view */}
      {mobileCardRender && (
        <div className="sm:hidden">
          {/* Mobile sort dropdown */}
          {sortableColumns.length > 0 && !loading && data.length > 1 && (
            <div className="flex items-center gap-2 px-4 py-2.5 border-b border-gray-100 bg-surface-secondary">
              <label htmlFor="mobile-sort" className="text-xs text-gray-500 flex-shrink-0">Sort by</label>
              <select
                id="mobile-sort"
                value={sortKey ? `${sortKey}:${sortDir}` : ''}
                onChange={(e) => {
                  if (!e.target.value) { setSortKey(null); return; }
                  const [key, dir] = e.target.value.split(':');
                  setSortKey(key);
                  setSortDir(dir as 'asc' | 'desc');
                }}
                className="flex-1 text-xs border border-gray-200 rounded-md px-2 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-1 focus:ring-primary/20"
              >
                <option value="">Default</option>
                {sortableColumns.map((col) => (
                  <optgroup key={col.key} label={col.header}>
                    <option value={`${col.key}:asc`}>{col.header} ↑</option>
                    <option value={`${col.key}:desc`}>{col.header} ↓</option>
                  </optgroup>
                ))}
              </select>
            </div>
          )}
          {loading
            ? Array.from({ length: 3 }).map((_, i) => (
                <div key={`m-skeleton-${i}`} className="p-4 border-b border-gray-100">
                  <div className="space-y-2">
                    <div className="h-4 w-3/4 bg-gray-200 rounded animate-pulse" />
                    <div className="h-3 w-1/2 bg-gray-200 rounded animate-pulse" />
                    <div className="h-3 w-1/3 bg-gray-200 rounded animate-pulse" />
                  </div>
                </div>
              ))
            : sortedData.map((item) => (
                <div
                  key={keyExtractor(item)}
                  className={onRowClick ? 'cursor-pointer active:bg-gray-50' : ''}
                  role={onRowClick ? 'button' : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                  onClick={() => onRowClick?.(item)}
                  onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(item); } } : undefined}
                >
                  {mobileCardRender(item)}
                </div>
              ))}
        </div>
      )}
      {/* Desktop table view */}
      <div className={`overflow-x-auto ${mobileCardRender ? 'hidden sm:block' : ''}`}>
        <table className="w-full">
          <thead>
            <tr className="border-b border-gray-200 bg-surface-secondary">
              {columns.map((col) => (
                <th
                  key={col.key}
                  className={`px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider ${alignClass(col.align)} ${
                    col.sortable ? 'cursor-pointer select-none hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-primary/20' : ''
                  } ${col.className || ''}`}
                  style={col.width ? { width: col.width } : undefined}
                  onClick={col.sortable ? () => handleSort(col.key) : undefined}
                  aria-sort={
                    col.sortable && sortKey === col.key
                      ? sortDir === 'asc' ? 'ascending' : 'descending'
                      : col.sortable ? 'none' : undefined
                  }
                  tabIndex={col.sortable ? 0 : undefined}
                  onKeyDown={col.sortable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleSort(col.key); } } : undefined}
                >
                  <span className="inline-flex items-center gap-1">
                    {col.header}
                    {col.sortable && sortKey === col.key && (
                      <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                          d={sortDir === 'asc' ? 'M5 15l7-7 7 7' : 'M19 9l-7 7-7-7'}
                        />
                      </svg>
                    )}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading
              ? Array.from({ length: 5 }).map((_, i) => (
                  <tr key={`skeleton-${i}`}>
                    {columns.map((col) => (
                      <td key={col.key} className={`px-4 py-3 ${col.className || ''}`}>
                        <div className="h-4 bg-gray-200 rounded animate-pulse" />
                      </td>
                    ))}
                  </tr>
                ))
              : sortedData.map((item) => (
                  <tr
                    key={keyExtractor(item)}
                    className={`transition-colors ${
                      onRowClick
                        ? 'cursor-pointer hover:bg-gray-50 focus-within:ring-2 focus-within:ring-primary/20'
                        : ''
                    }`}
                    tabIndex={onRowClick ? 0 : undefined}
                    onClick={() => onRowClick?.(item)}
                    onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(item); } } : undefined}
                  >
                    {columns.map((col) => (
                      <td
                        key={col.key}
                        className={`px-4 py-3 text-sm text-gray-700 ${alignClass(col.align)} ${col.className || ''}`}
                      >
                        {col.render
                          ? col.render(item)
                          : String((item as Record<string, unknown>)[col.key] ?? '-')}
                      </td>
                    ))}
                  </tr>
                ))}
          </tbody>
        </table>
      </div>

      {/* Empty state */}
      {!loading && data.length === 0 && (
        <EmptyState
          icon={emptyIcon}
          title={emptyTitle}
          description={emptyDescription}
          action={emptyAction}
        />
      )}
    </div>
  );
}
