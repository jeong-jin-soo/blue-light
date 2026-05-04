import { useState } from 'react';
import { Tabs, TabPanel } from '../../components/ui/Tabs';
import { ManualEmailComposeForm } from '../../components/admin/manualemail/ManualEmailComposeForm';
import { ManualEmailHistoryTable } from '../../components/admin/manualemail/ManualEmailHistoryTable';

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

  const handleSent = () => {
    setHistoryRefreshKey((k) => k + 1);
    setActive('history');
  };

  return (
    <div className="max-w-5xl mx-auto">
      <div className="mb-4">
        <h1 className="text-2xl font-semibold text-gray-900">Manual Email Dispatch</h1>
        <p className="text-sm text-gray-500 mt-1">
          Send ad-hoc operational notices to applicants, LEWs, or external addresses. All dispatches
          are permanently recorded in the audit log.
        </p>
      </div>

      <Tabs<TabKey>
        tabs={[
          { key: 'compose', label: 'Compose' },
          { key: 'history', label: 'History' },
        ]}
        activeKey={active}
        onChange={setActive}
        className="mb-4"
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
