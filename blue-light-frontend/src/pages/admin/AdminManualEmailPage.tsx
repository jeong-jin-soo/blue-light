import { useCallback, useEffect, useState } from 'react';
import { Tabs, TabPanel } from '../../components/ui/Tabs';
import { PageHeader } from '../../components/ui/PageHeader';
import { ManualEmailComposeForm } from '../../components/admin/manualemail/ManualEmailComposeForm';
import { ManualEmailHistoryTable } from '../../components/admin/manualemail/ManualEmailHistoryTable';
import { getManualEmailQuota } from '../../api/adminManualEmailApi';
import type { ManualEmailQuotaSnapshot } from '../../types/manualEmail';

/**
 * ADMIN 수동 이메일 발송 페이지 (PR-3).
 *
 * <p>스펙: doc/Project Analysis/admin-manual-email-spec.md §7.2.</p>
 *
 * <p>탭 컨테이너 — Compose / History. Compose 에서 Send 성공 시 History 탭으로 자동 전환되며 새로고침
 * 카운터를 통해 가장 최근 발송이 즉시 보이도록 한다.</p>
 *
 * <p>권한: ADMIN / SYSTEM_ADMIN — 라우트 가드는 {@code router/index.tsx} 에서 처리.</p>
 */

type TabKey = 'compose' | 'history';

export default function AdminManualEmailPage() {
  const [active, setActive] = useState<TabKey>('compose');
  const [historyRefreshKey, setHistoryRefreshKey] = useState(0);
  const [quota, setQuota] = useState<ManualEmailQuotaSnapshot | null>(null);

  // PR-4: 잔여 발송 한도 로드 — 페이지 마운트 + 발송 후 갱신.
  // useEffect 의 set-state-in-effect lint rule 회피를 위해 fetchQuota 는 직접 setState 하지 않고
  // Promise 를 반환하며, 호출부에서 .then 으로 setState 한다. (마운트 effect 는 IIFE.)
  const fetchQuota = useCallback(async (): Promise<ManualEmailQuotaSnapshot | null> => {
    try {
      return await getManualEmailQuota();
    } catch {
      // quota API 실패는 발송 자체와 무관 — 한도 표시만 비표시.
      return null;
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const snap = await fetchQuota();
      if (!cancelled) setQuota(snap);
    })();
    return () => {
      cancelled = true;
    };
  }, [fetchQuota]);

  const handleSent = () => {
    setHistoryRefreshKey((k) => k + 1);
    setActive('history');
    void fetchQuota().then((snap) => setQuota(snap));
  };

  return (
    <div className="max-w-5xl mx-auto">
      <PageHeader
        title="Manual Email Dispatch"
        subtitle="Send ad-hoc operational notices to applicants, LEWs, or external addresses. All dispatches are permanently recorded in the audit log."
        actions={
          /* PR-4: 잔여 발송 한도 표시 (D5=B) — Today: X / Y sent. system_settings 에서 cap 로드. */
          quota ? (
            <div
              className={`text-xs font-medium px-3 py-1.5 rounded-full border ${
                quota.remaining === 0
                  ? 'bg-error-50 border-error-200 text-error-700'
                  : quota.remaining <= 10
                    ? 'bg-warning-50 border-warning-200 text-warning-700'
                    : 'bg-gray-50 border-gray-200 text-gray-700'
              }`}
              title={`Daily cap resets at 00:00 SGT. Used ${quota.usedToday} of ${quota.dailyCap}.`}
            >
              Today: {quota.usedToday} / {quota.dailyCap} sent · {quota.remaining} left
            </div>
          ) : undefined
        }
      />

      <Tabs<TabKey>
        tabs={[
          { key: 'compose', label: 'Compose' },
          { key: 'history', label: 'History' },
        ]}
        activeKey={active}
        onChange={setActive}
        className="mt-6 mb-4"
      />

      <TabPanel active={active === 'compose'}>
        <ManualEmailComposeForm onSent={handleSent} />
      </TabPanel>
      <TabPanel active={active === 'history'}>
        <ManualEmailHistoryTable refreshKey={historyRefreshKey} />
      </TabPanel>
    </div>
  );
}
