import { useCallback, useEffect, useState } from 'react';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { PageHeader } from '../../components/ui/PageHeader';
import { useToastStore } from '../../stores/toastStore';
import { getAnalyticsOverview, type AnalyticsOverview, type KeyCount } from '../../api/analyticsApi';

const RANGES = [
  { days: 7, label: '7d' },
  { days: 30, label: '30d' },
  { days: 90, label: '90d' },
];

/** KPI 타일 */
function Kpi({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card className="flex flex-col gap-1">
      <span className="text-xs font-medium uppercase tracking-wide text-gray-400">{label}</span>
      <span className="text-2xl font-bold text-gray-900 tabular-nums">{value}</span>
      {hint && <span className="text-xs text-gray-400">{hint}</span>}
    </Card>
  );
}

/** 가로 막대 분포 리스트 */
function BarList({ title, rows, accent }: { title: string; rows: KeyCount[]; accent: string }) {
  const max = Math.max(1, ...rows.map((r) => r.count));
  return (
    <Card>
      <h3 className="text-sm font-semibold text-gray-800 mb-4">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-sm text-gray-400">No data yet.</p>
      ) : (
        <div className="flex flex-col gap-2.5">
          {rows.slice(0, 8).map((r) => (
            <div key={r.key} className="flex items-center gap-3">
              <span className="w-28 shrink-0 truncate text-sm text-gray-600" title={r.key}>{r.key}</span>
              <div className="relative h-5 flex-1 rounded bg-gray-100">
                <div
                  className="absolute inset-y-0 left-0 rounded"
                  style={{ width: `${(r.count / max) * 100}%`, background: accent }}
                />
              </div>
              <span className="w-10 shrink-0 text-right text-sm font-medium text-gray-700 tabular-nums">{r.count}</span>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

/** 일자별 방문자/방문/클릭 막대 */
function DailyChart({ data }: { data: AnalyticsOverview['daily'] }) {
  const max = Math.max(1, ...data.map((d) => Math.max(d.visitors, d.visits)));
  return (
    <Card>
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-800">Daily visitors &amp; enquiries</h3>
        <div className="flex items-center gap-4 text-xs text-gray-500">
          <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-sm bg-[#26406E]" />Visitors</span>
          <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-sm bg-[#7E9BCE]" />Pageviews</span>
          <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-sm bg-[#25D366]" />Enquiries</span>
        </div>
      </div>
      {data.length === 0 ? (
        <p className="text-sm text-gray-400">No visits recorded yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <div className="flex items-end gap-1.5" style={{ height: 140, minWidth: data.length * 20 }}>
            {data.map((d) => (
              <div key={d.date} className="flex flex-1 flex-col items-center gap-0.5" style={{ minWidth: 14 }}
                title={`${d.date} · ${d.visitors} visitors · ${d.visits} pageviews · ${d.clicks} enquiries`}>
                <div className="flex w-full items-end justify-center gap-[2px]" style={{ height: 120 }}>
                  <div className="w-1.5 rounded-t bg-[#26406E]" style={{ height: `${(d.visitors / max) * 100}%` }} />
                  <div className="w-1.5 rounded-t bg-[#7E9BCE]" style={{ height: `${(d.visits / max) * 100}%` }} />
                  <div className="w-1.5 rounded-t bg-[#25D366]" style={{ height: `${(d.clicks / max) * 100}%` }} />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </Card>
  );
}

/** 일자별 상세 표 (최신일 우선) */
function DailyTable({ data }: { data: AnalyticsOverview['daily'] }) {
  return (
    <Card>
      <h3 className="mb-4 text-sm font-semibold text-gray-800">Daily breakdown</h3>
      {data.length === 0 ? (
        <p className="text-sm text-gray-400">No data yet.</p>
      ) : (
        <div className="max-h-80 overflow-y-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-white">
              <tr className="border-b border-gray-200 text-left text-xs font-medium uppercase tracking-wide text-gray-400">
                <th className="py-2 pr-4">Date</th>
                <th className="py-2 pr-4 text-right">Visitors</th>
                <th className="py-2 pr-4 text-right">Pageviews</th>
                <th className="py-2 text-right">Enquiries</th>
              </tr>
            </thead>
            <tbody>
              {[...data].reverse().map((d) => (
                <tr key={d.date} className="border-b border-gray-100 last:border-0">
                  <td className="py-2 pr-4 text-gray-600">{d.date}</td>
                  <td className="py-2 pr-4 text-right font-medium text-gray-900 tabular-nums">{d.visitors}</td>
                  <td className="py-2 pr-4 text-right text-gray-600 tabular-nums">{d.visits}</td>
                  <td className="py-2 text-right text-gray-600 tabular-nums">{d.clicks}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/**
 * admin 유입/문의 분석 (1st-party, 제3자 트래커 없음).
 * 방문·WhatsApp 문의클릭을 UTM 출처/캠페인/서비스별로 집계해 마케팅 판단을 돕는다.
 */
export default function AdminAnalyticsPage() {
  const [days, setDays] = useState(30);
  const [data, setData] = useState<AnalyticsOverview | null>(null);
  const [loading, setLoading] = useState(true);
  // 전체 스토어 구독 금지: toasts 배열 변경마다 재렌더 → load 재생성 → useEffect 재실행 → 에러 토스트 무한루프
  const toastError = useToastStore((s) => s.error);

  const load = useCallback(async (d: number) => {
    setLoading(true);
    try {
      setData(await getAnalyticsOverview(d));
    } catch {
      toastError('Failed to load analytics');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => { load(days); }, [days, load]);

  const rate = data && data.totalVisits > 0
    ? `${((data.whatsappClicks / data.totalVisits) * 100).toFixed(1)}%`
    : '—';

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Traffic & Enquiries"
        subtitle="Where visitors and WhatsApp enquiries come from — first-party only, no third-party trackers."
        actions={
          <div className="flex items-center gap-1 rounded-lg border border-gray-200 p-1">
            {RANGES.map((r) => (
              <Button
                key={r.days}
                size="sm"
                variant={days === r.days ? 'primary' : 'ghost'}
                onClick={() => setDays(r.days)}
              >
                {r.label}
              </Button>
            ))}
          </div>
        }
      />

      {loading && !data ? (
        <Card><p className="text-sm text-gray-400">Loading…</p></Card>
      ) : data ? (
        <>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <Kpi label="Visits" value={data.totalVisits.toLocaleString()} hint={`last ${data.days} days`} />
            <Kpi label="Unique visitors" value={data.uniqueVisitors.toLocaleString()} />
            <Kpi label="WhatsApp enquiries" value={data.whatsappClicks.toLocaleString()} />
            <Kpi label="Enquiry rate" value={rate} hint="enquiries ÷ visits" />
          </div>

          <DailyChart data={data.daily} />

          <DailyTable data={data.daily} />

          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <BarList title="Enquiries by source" rows={data.clicksBySource} accent="#26406E" />
            <BarList title="Enquiries by campaign" rows={data.clicksByCampaign} accent="#25D366" />
            <BarList title="Enquiries by service" rows={data.clicksByService} accent="#8A5CF6" />
            <BarList title="Visits by source" rows={data.visitsBySource} accent="#E0894A" />
          </div>

          <p className="text-xs text-gray-400">
            Source/campaign come from UTM tags on the links we place (ads, posts, email, QR).
            Untagged / direct traffic shows as <code>(direct)</code>.
          </p>
        </>
      ) : (
        <Card className="flex flex-col items-start gap-3">
          <p className="text-sm text-gray-500">Failed to load analytics data.</p>
          <Button size="sm" onClick={() => load(days)}>Retry</Button>
        </Card>
      )}
    </div>
  );
}
