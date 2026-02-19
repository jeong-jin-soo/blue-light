import { useState, useEffect, useCallback } from 'react';
import { getAuditLogs, type AuditLogFilter } from '../../api/auditLogApi';
import type { AuditLog, AuditCategory } from '../../types';
import { Pagination } from '../../components/data/Pagination';

const PAGE_SIZE = 20;

const CATEGORY_OPTIONS: { value: AuditCategory | ''; label: string }[] = [
  { value: '', label: 'All Categories' },
  { value: 'AUTH', label: 'Authentication' },
  { value: 'APPLICATION', label: 'Application' },
  { value: 'ADMIN', label: 'Admin' },
  { value: 'SYSTEM', label: 'System' },
];

const CATEGORY_COLORS: Record<string, string> = {
  AUTH: 'bg-blue-100 text-blue-800',
  APPLICATION: 'bg-green-100 text-green-800',
  ADMIN: 'bg-purple-100 text-purple-800',
  SYSTEM: 'bg-orange-100 text-orange-800',
};

const ACTION_LABELS: Record<string, string> = {
  LOGIN_SUCCESS: 'Login Success',
  LOGIN_FAILURE: 'Login Failure',
  SIGNUP: 'Signup',
  PASSWORD_RESET_REQUEST: 'Password Reset Request',
  PASSWORD_RESET_COMPLETE: 'Password Reset Complete',
  EMAIL_VERIFIED: 'Email Verified',
  APPLICATION_CREATED: 'Application Created',
  APPLICATION_UPDATED: 'Application Updated',
  APPLICATION_STATUS_CHANGE: 'Status Change',
  APPLICATION_REVISION_REQUESTED: 'Revision Requested',
  APPLICATION_APPROVED: 'Approved',
  APPLICATION_COMPLETED: 'Completed',
  APPLICATION_RESUBMITTED: 'Resubmitted',
  FILE_UPLOADED: 'File Uploaded',
  FILE_DELETED: 'File Deleted',
  LEW_APPROVED: 'LEW Approved',
  LEW_REJECTED: 'LEW Rejected',
  USER_ROLE_CHANGED: 'Role Changed',
  PAYMENT_CONFIRMED: 'Payment Confirmed',
  LEW_ASSIGNED: 'LEW Assigned',
  LEW_UNASSIGNED: 'LEW Unassigned',
  SYSTEM_PROMPT_UPDATED: 'Prompt Updated',
  SYSTEM_PROMPT_RESET: 'Prompt Reset',
  GEMINI_KEY_UPDATED: 'API Key Updated',
  GEMINI_KEY_CLEARED: 'API Key Cleared',
  EMAIL_VERIFICATION_TOGGLED: 'Email Verification Toggled',
  PRICE_UPDATED: 'Price Updated',
  SETTINGS_UPDATED: 'Settings Updated',
};

const ROLE_BADGES: Record<string, string> = {
  ADMIN: 'bg-purple-100 text-purple-700',
  SYSTEM_ADMIN: 'bg-red-100 text-red-700',
  LEW: 'bg-blue-100 text-blue-700',
  APPLICANT: 'bg-gray-100 text-gray-700',
};

function formatDateTime(dateStr: string): string {
  const d = new Date(dateStr);
  return d.toLocaleString('en-SG', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

function formatJson(str: string): string {
  try {
    return JSON.stringify(JSON.parse(str), null, 2);
  } catch {
    return str;
  }
}

/** ─── Detail Modal ─── */
function AuditDetailModal({ log, onClose }: { log: AuditLog; onClose: () => void }) {
  // ESC 키로 닫기
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [onClose]);

  const rows: { label: string; value: React.ReactNode }[] = [
    { label: 'Log ID', value: <span className="font-mono">#{log.auditLogSeq}</span> },
    { label: 'Time', value: <span className="font-mono">{formatDateTime(log.createdAt)}</span> },
    {
      label: 'User',
      value: (
        <div className="flex items-center gap-2">
          <span>{log.userEmail || '-'}</span>
          {log.userRole && (
            <span className={`text-[11px] font-medium px-1.5 py-0.5 rounded ${ROLE_BADGES[log.userRole] || 'bg-gray-100 text-gray-600'}`}>
              {log.userRole}
            </span>
          )}
          {log.userSeq && <span className="text-gray-400 font-mono text-xs">(seq: {log.userSeq})</span>}
        </div>
      ),
    },
    {
      label: 'Category',
      value: (
        <span className={`text-xs font-medium px-2 py-1 rounded-full ${CATEGORY_COLORS[log.actionCategory] || 'bg-gray-100 text-gray-700'}`}>
          {log.actionCategory}
        </span>
      ),
    },
    { label: 'Action', value: ACTION_LABELS[log.action] || log.action },
    {
      label: 'Entity',
      value: log.entityType
        ? <span className="font-mono">{log.entityType}{log.entityId ? ` #${log.entityId}` : ''}</span>
        : <span className="text-gray-400">-</span>,
    },
    { label: 'Description', value: log.description || <span className="text-gray-400">-</span> },
    {
      label: 'Request',
      value: log.requestMethod
        ? <span className="font-mono">{log.requestMethod} {log.requestUri}</span>
        : <span className="text-gray-400">-</span>,
    },
    {
      label: 'HTTP Status',
      value: log.httpStatus ? (
        <span className={`text-xs font-medium px-2 py-1 rounded ${
          log.httpStatus < 300 ? 'bg-green-100 text-green-700'
          : log.httpStatus < 400 ? 'bg-yellow-100 text-yellow-700'
          : 'bg-red-100 text-red-700'
        }`}>
          {log.httpStatus}
        </span>
      ) : <span className="text-gray-400">-</span>,
    },
    { label: 'IP Address', value: <span className="font-mono">{log.ipAddress || '-'}</span> },
  ];

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-xl shadow-2xl w-full max-w-2xl mx-4 max-h-[90vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
          <h2 className="text-lg font-semibold text-gray-900">Audit Log Detail</h2>
          <button
            onClick={onClose}
            className="p-1.5 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors cursor-pointer"
            aria-label="Close"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Body */}
        <div className="overflow-y-auto px-6 py-4 space-y-4">
          {/* Info Rows */}
          <table className="w-full text-sm">
            <tbody>
              {rows.map(({ label, value }) => (
                <tr key={label} className="border-b border-gray-100 last:border-0">
                  <td className="py-2.5 pr-4 font-medium text-gray-500 whitespace-nowrap align-top w-28">{label}</td>
                  <td className="py-2.5 text-gray-800 break-all">{value}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Before / After Values */}
          {(log.beforeValue || log.afterValue) && (
            <div className="space-y-3 pt-2">
              <div className="text-sm font-medium text-gray-700 border-b border-gray-200 pb-1">Change Details</div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {log.beforeValue && (
                  <div>
                    <div className="text-xs font-medium text-red-600 mb-1 flex items-center gap-1">
                      <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" /></svg>
                      Before
                    </div>
                    <pre className="bg-red-50 border border-red-200 rounded-lg p-3 text-xs overflow-x-auto whitespace-pre-wrap break-all max-h-60 overflow-y-auto">
                      {formatJson(log.beforeValue)}
                    </pre>
                  </div>
                )}
                {log.afterValue && (
                  <div>
                    <div className="text-xs font-medium text-green-600 mb-1 flex items-center gap-1">
                      <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
                      After
                    </div>
                    <pre className="bg-green-50 border border-green-200 rounded-lg p-3 text-xs overflow-x-auto whitespace-pre-wrap break-all max-h-60 overflow-y-auto">
                      {formatJson(log.afterValue)}
                    </pre>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-3 border-t border-gray-200 flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-md text-sm font-medium bg-gray-100 text-gray-700 hover:bg-gray-200 transition-colors cursor-pointer"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

/** ─── Main Page ─── */
export default function AuditLogPage() {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedLog, setSelectedLog] = useState<AuditLog | null>(null);

  // Filters
  const [category, setCategory] = useState<AuditCategory | ''>('');
  const [searchTerm, setSearchTerm] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');

  const fetchLogs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const filter: AuditLogFilter = {
        page,
        size: PAGE_SIZE,
      };
      if (category) filter.category = category;
      if (searchTerm.trim()) filter.search = searchTerm.trim();
      if (startDate) filter.startDate = new Date(startDate).toISOString();
      if (endDate) {
        const end = new Date(endDate);
        end.setHours(23, 59, 59, 999);
        filter.endDate = end.toISOString();
      }
      const data = await getAuditLogs(filter);
      setLogs(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch {
      setError('Failed to load audit logs');
    } finally {
      setLoading(false);
    }
  }, [page, category, searchTerm, startDate, endDate]);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  const handleSearch = () => {
    setPage(0);
    fetchLogs();
  };

  const handleReset = () => {
    setCategory('');
    setSearchTerm('');
    setStartDate('');
    setEndDate('');
    setPage(0);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Audit Logs</h1>
        <p className="mt-1 text-sm text-gray-500">
          System activity logs — {totalElements.toLocaleString()} total records
        </p>
      </div>

      {/* Filters */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
          <select
            value={category}
            onChange={(e) => { setCategory(e.target.value as AuditCategory | ''); setPage(0); }}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
          >
            {CATEGORY_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>

          <input
            type="date"
            value={startDate}
            onChange={(e) => { setStartDate(e.target.value); setPage(0); }}
            placeholder="Start Date"
            className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
          />

          <input
            type="date"
            value={endDate}
            onChange={(e) => { setEndDate(e.target.value); setPage(0); }}
            placeholder="End Date"
            className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
          />

          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleSearch(); }}
            placeholder="Search email, description, ID..."
            className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
          />

          <div className="flex gap-2">
            <button
              onClick={handleSearch}
              className="flex-1 bg-primary text-white px-4 py-2 rounded-md text-sm font-medium hover:bg-primary/90 transition-colors cursor-pointer"
            >
              Search
            </button>
            <button
              onClick={handleReset}
              className="px-4 py-2 rounded-md text-sm font-medium border border-gray-300 text-gray-600 hover:bg-gray-50 transition-colors cursor-pointer"
            >
              Reset
            </button>
          </div>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg p-3 text-sm">
          {error}
        </div>
      )}

      {/* Table */}
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-20">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        ) : logs.length === 0 ? (
          <div className="text-center py-20 text-gray-400">
            <p className="text-lg">No audit logs found</p>
            <p className="text-sm mt-1">Try adjusting your filters</p>
          </div>
        ) : (
          <>
            {/* Desktop Table */}
            <div className="hidden lg:block overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Time</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">User</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Category</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Action</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Description</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Request</th>
                    <th className="px-4 py-3 text-center font-medium text-gray-500">Status</th>
                    <th className="px-4 py-3 text-center font-medium text-gray-500">Detail</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {logs.map((log) => (
                    <tr key={log.auditLogSeq} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 text-xs text-gray-500 whitespace-nowrap font-mono">
                        {formatDateTime(log.createdAt)}
                      </td>
                      <td className="px-4 py-3">
                        <div className="text-xs text-gray-700">{log.userEmail || '-'}</div>
                        {log.userRole && (
                          <span className={`inline-block mt-0.5 text-[10px] font-medium px-1.5 py-0.5 rounded ${ROLE_BADGES[log.userRole] || 'bg-gray-100 text-gray-600'}`}>
                            {log.userRole}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <span className={`text-xs font-medium px-2 py-1 rounded-full ${CATEGORY_COLORS[log.actionCategory] || 'bg-gray-100 text-gray-700'}`}>
                          {log.actionCategory}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-700">
                        {ACTION_LABELS[log.action] || log.action}
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-600 max-w-[200px] truncate" title={log.description || ''}>
                        {log.description || '-'}
                      </td>
                      <td className="px-4 py-3 text-xs text-gray-500 font-mono whitespace-nowrap">
                        {log.requestMethod && log.requestUri
                          ? `${log.requestMethod} ${log.requestUri}`
                          : '-'}
                      </td>
                      <td className="px-4 py-3 text-center">
                        {log.httpStatus ? (
                          <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${
                            log.httpStatus < 300 ? 'bg-green-100 text-green-700'
                            : log.httpStatus < 400 ? 'bg-yellow-100 text-yellow-700'
                            : 'bg-red-100 text-red-700'
                          }`}>
                            {log.httpStatus}
                          </span>
                        ) : '-'}
                      </td>
                      <td className="px-4 py-3 text-center">
                        <button
                          onClick={() => setSelectedLog(log)}
                          className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-primary bg-primary/5 border border-primary/20 rounded-md hover:bg-primary/10 transition-colors cursor-pointer"
                        >
                          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                          </svg>
                          Detail
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Mobile Cards */}
            <div className="lg:hidden divide-y divide-gray-100">
              {logs.map((log) => (
                <div key={log.auditLogSeq} className="p-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className={`text-xs font-medium px-2 py-1 rounded-full ${CATEGORY_COLORS[log.actionCategory] || 'bg-gray-100 text-gray-700'}`}>
                      {log.actionCategory}
                    </span>
                    <span className="text-xs text-gray-400 font-mono">
                      {formatDateTime(log.createdAt)}
                    </span>
                  </div>
                  <div className="text-sm font-medium text-gray-800">
                    {ACTION_LABELS[log.action] || log.action}
                  </div>
                  <div className="text-xs text-gray-500">
                    {log.userEmail || 'Anonymous'} {log.userRole && <span className={`ml-1 px-1 py-0.5 rounded ${ROLE_BADGES[log.userRole] || ''}`}>{log.userRole}</span>}
                  </div>
                  {log.description && (
                    <div className="text-xs text-gray-600">{log.description}</div>
                  )}
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3 text-xs text-gray-400">
                      {log.requestMethod && <span className="font-mono">{log.requestMethod} {log.requestUri}</span>}
                      {log.httpStatus && (
                        <span className={`font-medium px-1 py-0.5 rounded ${
                          log.httpStatus < 300 ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                        }`}>{log.httpStatus}</span>
                      )}
                    </div>
                    <button
                      onClick={() => setSelectedLog(log)}
                      className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-primary bg-primary/5 border border-primary/20 rounded-md hover:bg-primary/10 transition-colors cursor-pointer"
                    >
                      <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                      </svg>
                      Detail
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </div>

      {/* Pagination */}
      <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />

      {/* Detail Modal */}
      {selectedLog && (
        <AuditDetailModal log={selectedLog} onClose={() => setSelectedLog(null)} />
      )}
    </div>
  );
}
