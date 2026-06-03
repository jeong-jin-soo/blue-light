import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useNotificationTemplateStore } from '../../stores/notificationTemplateStore';
import * as api from '../../api/notificationTemplateApi';
import type { TemplateDraftStatus } from '../../types/notificationTemplate';

/**
 * Draft 리뷰 큐 — SYSTEM_ADMIN 전용 (D-1 2-step publish 워크플로).
 *
 * NM 이 submit 한 draft 들을 SA 가 한 화면에서 본문 확인 → approve / reject.
 */
export default function AdminNotificationTemplateDraftReviewPage() {
  const { draftQueue, draftQueueLoading, draftQueueError, draftStatusFilter, loadDraftQueue } =
    useNotificationTemplateStore();
  const [actionStatus, setActionStatus] = useState<string | null>(null);
  // EMAIL draft 본문(HTML)은 기본 렌더(iframe)로 보여 태그 노출 방지. 소스 보기 토글된 draftSeq 집합.
  const [sourceView, setSourceView] = useState<Set<number>>(new Set());
  const toggleSource = (seq: number) =>
    setSourceView((prev) => {
      const next = new Set(prev);
      next.has(seq) ? next.delete(seq) : next.add(seq);
      return next;
    });

  useEffect(() => {
    loadDraftQueue('PENDING');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const switchStatus = (status: TemplateDraftStatus) => {
    void loadDraftQueue(status);
  };

  const handleApprove = async (draftSeq: number, category: string | null) => {
    const requiresReason =
      category === 'SECURITY' || category === 'PAYMENT' || category === 'MARKETING';
    let note: string | null = null;
    if (requiresReason) {
      note = window.prompt(`Publish ${category} category — enter a reason:`);
      if (!note || !note.trim()) {
        setActionStatus('Reason input cancelled — approve aborted');
        return;
      }
    }
    try {
      await api.approveDraft(draftSeq, note);
      setActionStatus(`Draft #${draftSeq} approved`);
      void loadDraftQueue(draftStatusFilter);
    } catch (e) {
      setActionStatus(`Approve failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  const handleReject = async (draftSeq: number) => {
    const note = window.prompt('Enter a reject reason (required):');
    if (!note || !note.trim()) {
      setActionStatus('Reason input cancelled — reject aborted');
      return;
    }
    try {
      await api.rejectDraft(draftSeq, note);
      setActionStatus(`Draft #${draftSeq} rejected`);
      void loadDraftQueue(draftStatusFilter);
    } catch (e) {
      setActionStatus(`Reject failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Draft Queue</h1>
          <p className="text-sm text-gray-500 mt-1">
            ADMIN/SYSTEM_ADMIN reviews and publishes submitted copy changes.
          </p>
        </div>
        <Link
          to="/admin/notification-templates"
          className="text-sm text-gray-600 hover:underline"
        >
          ← Notification Templates
        </Link>
      </header>

      <div className="flex gap-2 mb-4">
        {(['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'] as TemplateDraftStatus[]).map((s) => (
          <button
            key={s}
            onClick={() => switchStatus(s)}
            className={
              'px-3 py-1 text-sm rounded ' +
              (draftStatusFilter === s
                ? 'bg-teal-600 text-white'
                : 'bg-white border border-gray-300 text-gray-700 hover:bg-gray-50')
            }
          >
            {s}
          </button>
        ))}
      </div>

      {actionStatus && (
        <div className="bg-blue-50 border border-blue-200 text-blue-800 text-sm px-4 py-2 rounded mb-4">
          {actionStatus}
        </div>
      )}

      {draftQueueError && (
        <div className="bg-red-50 border border-red-200 text-red-800 text-sm px-4 py-3 rounded mb-4">
          {draftQueueError}
        </div>
      )}

      {draftQueueLoading && <div className="text-gray-500">Loading...</div>}

      {!draftQueueLoading && draftQueue.length === 0 && (
        <div className="bg-gray-50 border border-gray-200 text-gray-500 text-sm px-4 py-8 rounded text-center">
          No drafts in {draftStatusFilter} status.
        </div>
      )}

      <div className="space-y-3">
        {draftQueue.map((d) => (
          <div key={d.draftSeq} className="bg-white border border-gray-200 rounded p-4">
            <div className="flex items-start justify-between mb-3">
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <span className="font-mono text-sm font-medium">{d.templateCode}</span>
                  <span className="text-xs text-gray-500">
                    {d.channel} · {d.locale}
                  </span>
                  {d.category && (
                    <span className="px-2 py-0.5 text-xs bg-gray-100 rounded">{d.category}</span>
                  )}
                  {d.severity === 'CRITICAL' && (
                    <span className="text-red-600 text-xs">★ Critical</span>
                  )}
                </div>
                <div className="text-xs text-gray-500">
                  submittedBy #{d.submittedBy} · {new Date(d.submittedAt).toLocaleString('en-SG')}
                </div>
              </div>
              {draftStatusFilter === 'PENDING' && (
                <div className="flex gap-2">
                  <button
                    onClick={() => handleApprove(d.draftSeq, d.category)}
                    className="px-3 py-1 text-xs bg-emerald-600 text-white rounded hover:bg-emerald-700"
                  >
                    Approve
                  </button>
                  <button
                    onClick={() => handleReject(d.draftSeq)}
                    className="px-3 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700"
                  >
                    Reject
                  </button>
                </div>
              )}
            </div>
            {d.submissionNote && (
              <div className="text-sm bg-amber-50 px-3 py-2 rounded mb-3 text-amber-900">
                <span className="font-medium">Submission reason: </span>
                {d.submissionNote}
              </div>
            )}
            {d.subject && (
              <div className="mb-2">
                <div className="text-xs font-medium text-gray-700">Subject</div>
                <div className="bg-gray-50 px-3 py-1 rounded text-sm font-mono">{d.subject}</div>
              </div>
            )}
            <div>
              <div className="flex items-center justify-between">
                <div className="text-xs font-medium text-gray-700">Body</div>
                {d.channel === 'EMAIL' && (
                  <button
                    type="button"
                    onClick={() => toggleSource(d.draftSeq)}
                    className="text-xs text-teal-600 hover:underline cursor-pointer"
                  >
                    {sourceView.has(d.draftSeq) ? 'View rendered' : 'View HTML source'}
                  </button>
                )}
              </div>
              {d.channel === 'EMAIL' && !sourceView.has(d.draftSeq) ? (
                <iframe
                  title={`draft-${d.draftSeq}-preview`}
                  sandbox=""
                  srcDoc={d.bodyText}
                  className="w-full h-64 bg-white border border-gray-200 rounded"
                />
              ) : (
                <pre className="bg-gray-50 px-3 py-2 rounded text-xs whitespace-pre-wrap font-mono max-h-40 overflow-auto">
                  {d.bodyText}
                </pre>
              )}
            </div>
            {d.reviewNote && (
              <div className="mt-2 text-xs text-gray-600">
                <span className="font-medium">Review note: </span>
                {d.reviewNote}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
