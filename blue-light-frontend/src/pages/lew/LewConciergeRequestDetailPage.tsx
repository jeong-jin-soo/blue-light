/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — LEW 컨시어지 상세 페이지.
 *
 * <p>스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §14 PR-4 D, AC-D1.</p>
 *
 * <p>경로: {@code /lew/concierge-requests/:id}. 본인 배정된 컨시어지 요청 상세 + 신청서 대행 작성 CTA.</p>
 *
 * <h3>권한</h3>
 * <ul>
 *   <li>백엔드 {@code ConciergeOwnershipValidator.assertAccessible} 가 LEW 호출 시
 *       {@code cr.assignedLewSeq == actor.userSeq} 검증 — 다른 LEW 의 row 는 403.</li>
 *   <li>"Create Application on Behalf" 버튼은 LEW_ASSIGNED / CONTACTING / QUOTE_SENT 에서 노출.</li>
 *   <li>매니저 전용 액션(상태 전이, 노트, 견적 등)은 비노출.</li>
 * </ul>
 */

import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConciergeStatusBadge } from '../../components/concierge/ConciergeStatusBadge';
import { PageHeader } from '../../components/ui/PageHeader';
import ConciergeCreateApplicationModal from '../concierge-manager/sections/ConciergeCreateApplicationModal';
import conciergeManagerApi, {
  type ConciergeRequestDetail,
} from '../../api/conciergeManagerApi';

function errMsg(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'message' in err) {
    return String((err as { message: unknown }).message);
  }
  return fallback;
}

export default function LewConciergeRequestDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ConciergeRequestDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [createAppOpen, setCreateAppOpen] = useState(false);

  const reload = useCallback(async () => {
    if (!id) return;
    try {
      setLoading(true);
      setError(null);
      const data = await conciergeManagerApi.getDetail(Number(id));
      setDetail(data);
    } catch (err) {
      setError(errMsg(err, 'Failed to load request'));
      setDetail(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    reload();
  }, [reload]);

  if (!id) {
    return (
      <div className="max-w-5xl mx-auto">
        <p className="text-sm text-error-700">Invalid request id.</p>
      </div>
    );
  }

  if (loading && !detail) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading concierge request..." />
      </div>
    );
  }

  if (error && !detail) {
    return (
      <div className="max-w-5xl mx-auto">
        <div role="alert" className="p-3 rounded-md bg-error-50 border border-error-200 text-sm text-error-700">
          {error}
        </div>
        <div className="mt-3">
          <Button variant="outline" size="sm" onClick={() => navigate('/lew/concierge-requests')}>
            Back to list
          </Button>
        </div>
      </div>
    );
  }

  if (!detail) return null;

  // 신청서 대행 작성 CTA 노출 조건 — LEW_ASSIGNED 우선, CONTACTING/QUOTE_SENT 도 백엔드가 허용.
  const canCreateApplication = !detail.applicationSeq
    && (detail.status === 'LEW_ASSIGNED'
      || detail.status === 'CONTACTING'
      || detail.status === 'QUOTE_SENT');

  return (
    <div className="max-w-5xl mx-auto">
      {/* Breadcrumb */}
      <nav aria-label="Breadcrumb" className="mb-3 text-sm">
        <ol className="flex items-center gap-1.5 text-gray-500">
          <li>
            <Link to="/lew/concierge-requests" className="hover:text-gray-800">
              My Concierge Requests
            </Link>
          </li>
          <li aria-hidden="true">/</li>
          <li className="text-gray-800 font-mono">{detail.publicCode}</li>
        </ol>
      </nav>

      {/* 상태 헤더 */}
      <PageHeader
        title={detail.submitterName}
        subtitle={
          <span className="space-x-3">
            <span className="font-mono text-gray-500">{detail.publicCode}</span>
            <span className="break-all">{detail.submitterEmail}</span>
            <span>{detail.submitterPhone}</span>
          </span>
        }
        actions={
          <div className="flex flex-col items-end gap-1.5">
            <ConciergeStatusBadge status={detail.status} />
            {detail.slaBreached && <Badge variant="error">SLA Breach</Badge>}
          </div>
        }
      />
      <div className="mb-4" />

      {/* Memo */}
      {detail.memo && (
        <Card padding="md" className="mb-4 bg-gray-50">
          <div className="text-xs font-medium text-gray-500 mb-1">Memo from applicant</div>
          <p className="text-sm text-gray-800 whitespace-pre-wrap break-words">
            {detail.memo}
          </p>
        </Card>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* 좌측: 신청자 정보 + LEW 배정 정보 */}
        <div className="lg:col-span-1 space-y-4">
          <Card padding="md">
            <h2 className="text-sm font-semibold text-gray-800 mb-2">Applicant contact</h2>
            <dl className="text-sm space-y-1.5">
              <div>
                <dt className="text-xs text-gray-500">Name</dt>
                <dd className="text-gray-800">{detail.submitterName}</dd>
              </div>
              <div>
                <dt className="text-xs text-gray-500">Email</dt>
                <dd className="text-gray-800 break-all">{detail.submitterEmail}</dd>
              </div>
              <div>
                <dt className="text-xs text-gray-500">Phone</dt>
                <dd className="text-gray-800">{detail.submitterPhone}</dd>
              </div>
              {detail.callScheduledAt && (
                <div>
                  <dt className="text-xs text-gray-500">Call scheduled</dt>
                  <dd className="text-gray-800">{new Date(detail.callScheduledAt).toLocaleString()}</dd>
                </div>
              )}
            </dl>
          </Card>

          {detail.lewAssignedAt && (
            <Card padding="md">
              <h2 className="text-sm font-semibold text-gray-800 mb-2">Assignment</h2>
              <p className="text-sm text-gray-700">
                Assigned to you on {new Date(detail.lewAssignedAt).toLocaleString()}
              </p>
              {detail.assignedManagerName && (
                <p className="text-xs text-gray-500 mt-1">
                  Concierge manager: {detail.assignedManagerName}
                </p>
              )}
            </Card>
          )}

          {detail.applicationSeq && (
            <Card padding="md">
              <h2 className="text-sm font-semibold text-gray-800 mb-2">Linked application</h2>
              <p className="text-sm text-gray-700 mb-2">
                Application #{detail.applicationSeq}
              </p>
              <Link to={`/lew/applications/${detail.applicationSeq}`}>
                <Button variant="outline" size="sm" fullWidth>
                  Open application
                </Button>
              </Link>
            </Card>
          )}
        </div>

        {/* 우측: 액션 */}
        <div className="lg:col-span-2 space-y-4">
          <Card padding="md">
            <h2 className="text-sm font-semibold text-gray-800 mb-3">Actions</h2>
            {canCreateApplication ? (
              <div>
                <p className="text-sm text-gray-600 mb-3">
                  After speaking with the applicant and confirming the installation details,
                  create the licence application on their behalf.
                </p>
                <Button
                  variant="primary"
                  onClick={() => setCreateAppOpen(true)}
                  fullWidth
                >
                  Create application on behalf
                </Button>
              </div>
            ) : detail.applicationSeq ? (
              <p className="text-sm text-gray-600">
                Application has been created. Continue from the Application page.
              </p>
            ) : (
              <p className="text-sm text-gray-600">
                No actions available in the current status ({detail.status}).
              </p>
            )}
          </Card>
        </div>
      </div>

      {/* Create Application Modal — 매니저 워크스페이스의 모달을 재사용 (백엔드 권한 가드는 동일). */}
      <ConciergeCreateApplicationModal
        conciergeRequestSeq={detail.conciergeRequestSeq}
        submitterName={detail.submitterName}
        isOpen={createAppOpen}
        onClose={() => setCreateAppOpen(false)}
        onCreated={() => {
          setCreateAppOpen(false);
          void reload();
        }}
      />
    </div>
  );
}
