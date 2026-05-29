import { useEffect, useState, useRef } from 'react';
import { Link } from 'react-router-dom';
import { useNotificationTemplateStore } from '../../stores/notificationTemplateStore';
import * as api from '../../api/notificationTemplateApi';
import type { LocalizationFormat, ImportReportResponse } from '../../api/notificationTemplateApi';
import type {
  NotificationCategory,
  NotificationChannel,
} from '../../types/notificationTemplate';

/**
 * 알림 템플릿 목록 화면 — PR-T6.
 *
 * 스펙: doc/Project Analysis/notification-template-manager-spec.md §7.2
 * (List 매트릭스 도트 뷰의 단순화 버전)
 */
export default function AdminNotificationTemplateListPage() {
  const {
    templates,
    templatesPage,
    templatesLoading,
    templatesError,
    filters,
    setFilters,
    loadTemplates,
  } = useNotificationTemplateStore();

  useEffect(() => {
    loadTemplates();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters.page, filters.size, filters.channel, filters.locale, filters.enabled, filters.category]);

  const onCodeSearch = (value: string) => {
    setFilters({ code: value });
    // debounce 없이 즉시 적용 — list 호출 빈도가 낮으므로 OK
    setTimeout(() => loadTemplates(), 0);
  };

  return (
    <div className="p-6">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">알림 템플릿</h1>
          <p className="text-sm text-gray-500 mt-1">
            카피 편집·미리보기·테스트 발송 (NOTIFICATION_MANAGER / SYSTEM_ADMIN)
          </p>
        </div>
        <div className="flex gap-2">
          <LocalizationToolbar />
          <Link
            to="/admin/notification-templates/drafts"
            className="px-4 py-2 text-sm bg-white border border-gray-300 rounded hover:bg-gray-50"
          >
            Draft 리뷰 큐
          </Link>
        </div>
      </header>

      {/* 필터 바 */}
      <div className="bg-white border border-gray-200 rounded p-4 mb-4 grid grid-cols-2 lg:grid-cols-6 gap-3">
        <div className="col-span-2">
          <label className="block text-xs font-medium text-gray-700 mb-1">코드 검색</label>
          <input
            type="text"
            value={filters.code ?? ''}
            onChange={(e) => onCodeSearch(e.target.value)}
            placeholder="A-17, payment, ..."
            className="w-full px-3 py-2 text-sm border border-gray-300 rounded focus:outline-none focus:border-teal-500"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-700 mb-1">채널</label>
          <select
            value={filters.channel ?? ''}
            onChange={(e) =>
              setFilters({ channel: (e.target.value || undefined) as NotificationChannel | undefined })
            }
            className="w-full px-3 py-2 text-sm border border-gray-300 rounded"
          >
            <option value="">전체</option>
            <option value="EMAIL">EMAIL</option>
            <option value="IN_APP">IN_APP</option>
            <option value="SMS">SMS</option>
            <option value="WHATSAPP">WHATSAPP</option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-700 mb-1">Locale</label>
          <select
            value={filters.locale ?? ''}
            onChange={(e) => setFilters({ locale: e.target.value || undefined })}
            className="w-full px-3 py-2 text-sm border border-gray-300 rounded"
          >
            <option value="">전체</option>
            <option value="en">en</option>
            <option value="ko">ko</option>
            <option value="zh-Hans">zh-Hans</option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-700 mb-1">카테고리</label>
          <select
            value={filters.category ?? ''}
            onChange={(e) =>
              setFilters({ category: (e.target.value || undefined) as NotificationCategory | undefined })
            }
            className="w-full px-3 py-2 text-sm border border-gray-300 rounded"
          >
            <option value="">전체</option>
            <option value="SECURITY">SECURITY</option>
            <option value="STATUS">STATUS</option>
            <option value="PAYMENT">PAYMENT</option>
            <option value="REMINDER">REMINDER</option>
            <option value="VISIT">VISIT</option>
            <option value="EXPIRY">EXPIRY</option>
            <option value="MARKETING">MARKETING</option>
            <option value="FEEDBACK">FEEDBACK</option>
            <option value="OPS">OPS</option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-700 mb-1">상태</label>
          <select
            value={filters.enabled === undefined ? '' : String(filters.enabled)}
            onChange={(e) =>
              setFilters({
                enabled: e.target.value === '' ? undefined : e.target.value === 'true',
              })
            }
            className="w-full px-3 py-2 text-sm border border-gray-300 rounded"
          >
            <option value="">전체</option>
            <option value="true">활성</option>
            <option value="false">비활성</option>
          </select>
        </div>
      </div>

      {/* 결과 */}
      {templatesError && (
        <div className="bg-red-50 border border-red-200 text-red-800 text-sm px-4 py-3 rounded mb-4">
          {templatesError}
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-gray-700">
            <tr>
              <th className="text-left px-4 py-2 font-medium">상태</th>
              <th className="text-left px-4 py-2 font-medium">코드</th>
              <th className="text-left px-4 py-2 font-medium">채널 · Locale</th>
              <th className="text-left px-4 py-2 font-medium">카테고리</th>
              <th className="text-left px-4 py-2 font-medium">중요도</th>
              <th className="text-left px-4 py-2 font-medium">수신자</th>
              <th className="text-left px-4 py-2 font-medium">Subject</th>
              <th className="text-left px-4 py-2 font-medium">수정일</th>
            </tr>
          </thead>
          <tbody>
            {templatesLoading && (
              <tr>
                <td colSpan={8} className="text-center py-8 text-gray-500">
                  로딩 중...
                </td>
              </tr>
            )}
            {!templatesLoading && templates.length === 0 && (
              <tr>
                <td colSpan={8} className="text-center py-8 text-gray-500">
                  결과가 없습니다.
                </td>
              </tr>
            )}
            {templates.map((t) => (
              <tr key={t.templateSeq} className="border-t hover:bg-gray-50">
                <td className="px-4 py-2">
                  <span
                    className={
                      'inline-flex items-center justify-center w-2 h-2 rounded-full ' +
                      (t.enabled ? 'bg-emerald-500' : 'bg-gray-300')
                    }
                    title={t.enabled ? '활성' : '비활성'}
                  />
                </td>
                <td className="px-4 py-2 font-mono text-xs">
                  <Link
                    to={`/admin/notification-templates/${t.templateSeq}`}
                    className="text-teal-700 hover:underline"
                  >
                    {t.templateCode}
                  </Link>
                </td>
                <td className="px-4 py-2 text-gray-700">
                  {t.channel} · {t.locale}
                </td>
                <td className="px-4 py-2">
                  {t.category && (
                    <span className="inline-block px-2 py-0.5 text-xs bg-gray-100 rounded">
                      {t.category}
                    </span>
                  )}
                </td>
                <td className="px-4 py-2">
                  {t.severity === 'CRITICAL' && (
                    <span className="text-red-600 font-medium">★ Critical</span>
                  )}
                  {t.severity === 'IMPORTANT' && <span className="text-amber-700">● Important</span>}
                  {t.severity === 'INFORMATIONAL' && <span className="text-gray-500">○ Info</span>}
                  {t.severity === 'MARKETING' && <span className="text-purple-700">M</span>}
                </td>
                <td className="px-4 py-2 text-gray-700">{t.recipientRoles ?? '-'}</td>
                <td className="px-4 py-2 text-gray-700 max-w-md truncate">
                  {t.subject ?? <span className="text-gray-400">(없음)</span>}
                </td>
                <td className="px-4 py-2 text-gray-500 text-xs">
                  {new Date(t.updatedAt).toLocaleString('ko-KR')}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* 페이지네이션 */}
      {templatesPage && templatesPage.totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between text-sm">
          <div className="text-gray-600">
            총 {templatesPage.totalElements}건 · {templatesPage.number + 1} / {templatesPage.totalPages}{' '}
            페이지
          </div>
          <div className="flex gap-2">
            <button
              disabled={templatesPage.first}
              onClick={() => setFilters({ page: Math.max(0, filters.page - 1) })}
              className="px-3 py-1 border border-gray-300 rounded disabled:opacity-50"
            >
              이전
            </button>
            <button
              disabled={templatesPage.last}
              onClick={() => setFilters({ page: filters.page + 1 })}
              className="px-3 py-1 border border-gray-300 rounded disabled:opacity-50"
            >
              다음
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * PR-T7 P1 — 외주 번역 라운드용 export/import 툴바.
 *
 * Export: 지정 locale (보통 en) 활성 템플릿을 XLIFF/CSV 로 다운로드 → LSP 전달.
 * Import: 번역된 파일 업로드 → target locale 의 draft 일괄 생성 (PENDING) → SA approve.
 */
function LocalizationToolbar() {
  const [open, setOpen] = useState<'none' | 'export' | 'import'>('none');
  const [exportLocale, setExportLocale] = useState('en');
  const [exportFormat, setExportFormat] = useState<LocalizationFormat>('xliff');
  const [importLocale, setImportLocale] = useState('ko');
  const [importFormat, setImportFormat] = useState<LocalizationFormat>('xliff');
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [importReport, setImportReport] = useState<ImportReportResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleExport = async () => {
    try {
      setError(null);
      const { blob, filename } = await api.exportTemplates(exportLocale, exportFormat);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      setOpen('none');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Export 실패');
    }
  };

  const handleImport = async () => {
    if (!importFile) {
      setError('파일을 선택해주세요');
      return;
    }
    try {
      setImporting(true);
      setError(null);
      const report = await api.importTemplates(importLocale, importFormat, importFile);
      setImportReport(report);
      setImportFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
    } catch (e) {
      // 백엔드가 LocalizationException → 400 LOCALIZATION_FAILED 로 변환
      const apiError = e as { response?: { data?: { message?: string } } };
      setError(apiError.response?.data?.message ?? (e instanceof Error ? e.message : 'Import 실패'));
    } finally {
      setImporting(false);
    }
  };

  return (
    <>
      <button
        onClick={() => setOpen('export')}
        className="px-3 py-2 text-sm bg-white border border-gray-300 rounded hover:bg-gray-50"
      >
        Export
      </button>
      <button
        onClick={() => setOpen('import')}
        className="px-3 py-2 text-sm bg-white border border-gray-300 rounded hover:bg-gray-50"
      >
        Import
      </button>

      {open === 'export' && (
        <div className="fixed inset-0 bg-black bg-opacity-30 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-96 shadow-xl">
            <h3 className="text-lg font-semibold mb-4">번역용 파일 Export</h3>
            <p className="text-xs text-gray-500 mb-4">
              지정 locale 의 활성 템플릿을 외주 LSP 전달용 파일로 다운로드.
            </p>
            <label className="block text-sm font-medium mb-1">소스 locale</label>
            <input
              value={exportLocale}
              onChange={(e) => setExportLocale(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded mb-3"
              placeholder="en"
            />
            <label className="block text-sm font-medium mb-1">포맷</label>
            <select
              value={exportFormat}
              onChange={(e) => setExportFormat(e.target.value as LocalizationFormat)}
              className="w-full px-3 py-2 border border-gray-300 rounded mb-4"
            >
              <option value="xliff">XLIFF 1.2 (LSP 표준)</option>
              <option value="csv">CSV (스프레드시트)</option>
            </select>
            {error && <p className="text-red-600 text-sm mb-3">{error}</p>}
            <div className="flex gap-2 justify-end">
              <button
                onClick={() => { setOpen('none'); setError(null); }}
                className="px-4 py-2 text-sm border border-gray-300 rounded"
              >
                취소
              </button>
              <button
                onClick={handleExport}
                className="px-4 py-2 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"
              >
                다운로드
              </button>
            </div>
          </div>
        </div>
      )}

      {open === 'import' && (
        <div className="fixed inset-0 bg-black bg-opacity-30 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-[32rem] max-h-[80vh] overflow-auto shadow-xl">
            <h3 className="text-lg font-semibold mb-2">번역된 파일 Import</h3>
            <p className="text-xs text-gray-500 mb-4">
              업로드한 파일의 각 행마다 PENDING draft 가 생성됩니다. SYSTEM_ADMIN 이
              Draft 리뷰 큐에서 일괄 approve 해야 publish 됩니다.
            </p>

            {!importReport && (
              <>
                <label className="block text-sm font-medium mb-1">타겟 locale</label>
                <input
                  value={importLocale}
                  onChange={(e) => setImportLocale(e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded mb-3"
                  placeholder="ko / zh-Hans"
                />
                <label className="block text-sm font-medium mb-1">포맷</label>
                <select
                  value={importFormat}
                  onChange={(e) => setImportFormat(e.target.value as LocalizationFormat)}
                  className="w-full px-3 py-2 border border-gray-300 rounded mb-3"
                >
                  <option value="xliff">XLIFF 1.2</option>
                  <option value="csv">CSV</option>
                </select>
                <label className="block text-sm font-medium mb-1">파일</label>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".xliff,.xml,.csv"
                  onChange={(e) => setImportFile(e.target.files?.[0] ?? null)}
                  className="w-full mb-4 text-sm"
                />
                {error && <p className="text-red-600 text-sm mb-3">{error}</p>}
                <div className="flex gap-2 justify-end">
                  <button
                    onClick={() => { setOpen('none'); setError(null); setImportFile(null); }}
                    className="px-4 py-2 text-sm border border-gray-300 rounded"
                  >
                    취소
                  </button>
                  <button
                    onClick={handleImport}
                    disabled={importing || !importFile}
                    className="px-4 py-2 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
                  >
                    {importing ? '처리 중…' : '업로드'}
                  </button>
                </div>
              </>
            )}

            {importReport && (
              <ImportReportView
                report={importReport}
                onClose={() => { setImportReport(null); setOpen('none'); }}
                onRerun={() => setImportReport(null)}
              />
            )}
          </div>
        </div>
      )}
    </>
  );
}

function ImportReportView({
  report,
  onClose,
  onRerun,
}: {
  report: ImportReportResponse;
  onClose: () => void;
  onRerun: () => void;
}) {
  return (
    <div>
      <div className="grid grid-cols-4 gap-2 mb-4 text-center">
        <div className="bg-gray-50 rounded p-2">
          <div className="text-xs text-gray-500">총 처리</div>
          <div className="text-lg font-semibold">{report.totalRows}</div>
        </div>
        <div className="bg-emerald-50 rounded p-2">
          <div className="text-xs text-emerald-700">생성</div>
          <div className="text-lg font-semibold text-emerald-700">{report.draftsCreated}</div>
        </div>
        <div className="bg-gray-50 rounded p-2">
          <div className="text-xs text-gray-500">Skip</div>
          <div className="text-lg font-semibold">{report.skipped}</div>
        </div>
        <div className="bg-red-50 rounded p-2">
          <div className="text-xs text-red-700">실패</div>
          <div className="text-lg font-semibold text-red-700">{report.failed}</div>
        </div>
      </div>

      {report.items.length > 0 && (
        <div className="border border-gray-200 rounded max-h-60 overflow-auto mb-4">
          <table className="w-full text-xs">
            <thead className="bg-gray-50 sticky top-0">
              <tr>
                <th className="px-2 py-1 text-left">Code</th>
                <th className="px-2 py-1 text-left">Channel</th>
                <th className="px-2 py-1 text-left">Status</th>
                <th className="px-2 py-1 text-left">Reason</th>
              </tr>
            </thead>
            <tbody>
              {report.items.map((item, i) => (
                <tr key={i} className="border-t border-gray-100">
                  <td className="px-2 py-1 font-mono">{item.templateCode}</td>
                  <td className="px-2 py-1">{item.channel}</td>
                  <td className="px-2 py-1">
                    {item.status === 'CREATED' && (
                      <span className="text-emerald-700">✓ Draft #{item.draftSeq}</span>
                    )}
                    {item.status === 'SKIPPED' && (
                      <span className="text-gray-500">Skip</span>
                    )}
                    {item.status === 'FAILED' && (
                      <span className="text-red-600">실패</span>
                    )}
                  </td>
                  <td className="px-2 py-1 text-gray-600">{item.reason ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="flex gap-2 justify-end">
        <button onClick={onRerun} className="px-4 py-2 text-sm border border-gray-300 rounded">
          다른 파일 업로드
        </button>
        <button
          onClick={onClose}
          className="px-4 py-2 text-sm bg-blue-600 text-white rounded hover:bg-blue-700"
        >
          닫기
        </button>
      </div>
    </div>
  );
}
