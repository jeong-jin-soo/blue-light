import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useNotificationTemplateStore } from '../../stores/notificationTemplateStore';
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
