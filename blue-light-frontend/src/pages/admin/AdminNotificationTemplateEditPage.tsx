import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import axios, { AxiosError } from 'axios';
import { useNotificationTemplateStore } from '../../stores/notificationTemplateStore';
import * as api from '../../api/notificationTemplateApi';
import type {
  CatalogEntry,
  CreateDraftRequest,
  LintErrorBody,
  LintIssue,
  NotificationCategory,
  NotificationSeverity,
  TemplateMetricsResponse,
  TemplatePreviewResponse,
} from '../../types/notificationTemplate';
import { useAuthStore } from '../../stores/authStore';

/**
 * 알림 템플릿 편집 화면 — PR-T6.
 *
 * 흐름:
 *  1. URL templateSeq 로 현재 template 로드
 *  2. NM 이 본문/메타 편집 → "Submit for approval" 시 draft 생성
 *  3. Preview 모달로 변수 sample 입력 → 렌더 결과·warnings 확인
 *  4. Test send (EMAIL) — 본인에게 발송
 *  5. Enable/Disable — D-6 사유 강제, H-S3 SECURITY 잠금
 */
export default function AdminNotificationTemplateEditPage() {
  const { id } = useParams<{ id: string }>();
  const templateSeq = id ? Number(id) : 0;
  const navigate = useNavigate();
  const {
    current,
    currentLoading,
    currentError,
    loadTemplate,
    catalog,
    loadCatalog,
    metrics,
    metricsLoading,
    loadMetrics,
  } = useNotificationTemplateStore();
  const role = useAuthStore((s) => s.user?.role);

  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [variablesJson, setVariablesJson] = useState('');
  const [providerTemplateName, setProviderTemplateName] = useState('');
  const [category, setCategory] = useState<NotificationCategory | ''>('');
  const [severity, setSeverity] = useState<NotificationSeverity | ''>('');
  const [recipientRoles, setRecipientRoles] = useState('');
  const [submissionNote, setSubmissionNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [lintErrors, setLintErrors] = useState<LintIssue[]>([]);
  const [genericError, setGenericError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewPayload, setPreviewPayload] = useState<Record<string, string>>({});
  const [previewResult, setPreviewResult] = useState<TemplatePreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [testSendStatus, setTestSendStatus] = useState<string | null>(null);

  useEffect(() => {
    loadCatalog();
    if (templateSeq) {
      loadTemplate(templateSeq);
      // PR-T7 P1 — 헤더 인라인 메트릭스 (지난 30일 발송 현황)
      loadMetrics(templateSeq, 30);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [templateSeq]);

  useEffect(() => {
    if (current) {
      setSubject(current.subject ?? '');
      setBody(current.bodyText);
      setVariablesJson(current.variablesJson ?? '');
      setProviderTemplateName(current.providerTemplateName ?? '');
      setCategory(current.category ?? '');
      setSeverity(current.severity ?? '');
      setRecipientRoles(current.recipientRoles ?? '');
    }
  }, [current]);

  if (currentLoading) return <div className="p-6">로딩 중...</div>;
  if (currentError) return <div className="p-6 text-red-600">{currentError}</div>;
  if (!current) return <div className="p-6 text-gray-500">템플릿을 찾을 수 없습니다.</div>;

  const catalogEntry: CatalogEntry | undefined = catalog.find(
    (c) => c.templateCode === current.templateCode
  );

  const allowedVariables: string[] = catalogEntry
    ? safeParseStringArray(catalogEntry.allowedVariablesJson)
    : safeParseStringArray(variablesJson);

  const handleSubmitDraft = async () => {
    setSaving(true);
    setLintErrors([]);
    setGenericError(null);
    setSuccessMessage(null);
    try {
      const request: CreateDraftRequest = {
        templateSeq: current.templateSeq,
        templateCode: current.templateCode,
        channel: current.channel,
        locale: current.locale,
        subject: subject || null,
        body,
        variablesJson: variablesJson || null,
        providerTemplateName: providerTemplateName || null,
        category: (category || null) as NotificationCategory | null,
        severity: (severity || null) as NotificationSeverity | null,
        recipientRoles: recipientRoles || null,
        submissionNote: submissionNote || null,
      };
      const draft = await api.createDraft(request);
      setSuccessMessage(
        `Draft #${draft.draftSeq} 생성됨. SYSTEM_ADMIN 승인 후 publish 됩니다.`
      );
    } catch (e) {
      handleApiError(e, setLintErrors, setGenericError);
    } finally {
      setSaving(false);
    }
  };

  const handleEnableDisable = async (enable: boolean) => {
    setGenericError(null);
    setSuccessMessage(null);
    const reasonRequired =
      !enable &&
      (current.category === 'SECURITY' ||
        current.category === 'PAYMENT' ||
        current.category === 'MARKETING');
    let reason: string | null = null;
    if (reasonRequired) {
      const minLength = current.category === 'SECURITY' ? 50 : 1;
      reason = window.prompt(
        `${current.category} 카테고리 disable — 사유를 입력하세요 (${minLength}자 이상):`
      );
      if (!reason || reason.length < minLength) {
        setGenericError(`사유가 ${minLength}자 이상이어야 합니다.`);
        return;
      }
    }
    try {
      if (enable) {
        await api.enableTemplate(current.templateSeq, reason);
      } else {
        await api.disableTemplate(current.templateSeq, reason ?? '');
      }
      setSuccessMessage(enable ? '활성화 완료' : '비활성화 완료');
      await loadTemplate(current.templateSeq);
    } catch (e) {
      handleApiError(e, setLintErrors, setGenericError);
    }
  };

  const handlePreview = async () => {
    setPreviewLoading(true);
    try {
      const result = await api.previewTemplate(current.templateSeq, previewPayload);
      setPreviewResult(result);
    } catch (e) {
      const msg = e instanceof Error ? e.message : '미리보기 실패';
      setPreviewResult(null);
      setGenericError(msg);
    } finally {
      setPreviewLoading(false);
    }
  };

  const handleTestSend = async () => {
    setTestSendStatus(null);
    try {
      const result = await api.testSendTemplate(current.templateSeq, previewPayload);
      setTestSendStatus(
        `테스트 발송 완료. outbox #${result.outboxSeq} · 오늘 ${result.dailyQuotaUsed}/${result.dailyQuotaMax} 사용.`
      );
    } catch (e) {
      const err = e as AxiosError<{ code?: string; message?: string }>;
      if (err.response?.status === 429) {
        setTestSendStatus(`한도 초과: ${err.response.data.message ?? ''}`);
      } else if (err.response?.status === 400) {
        setTestSendStatus(`불가: ${err.response.data.message ?? ''}`);
      } else {
        setTestSendStatus(`발송 실패: ${err.message}`);
      }
    }
  };

  return (
    <div className="p-6 max-w-7xl mx-auto">
      <div className="mb-4">
        <button onClick={() => navigate(-1)} className="text-sm text-gray-600 hover:underline">
          ← 목록으로
        </button>
      </div>

      <header className="mb-6">
        <div className="flex items-center gap-3 mb-2">
          <h1 className="text-2xl font-bold font-mono">{current.templateCode}</h1>
          {current.severity === 'CRITICAL' && (
            <span className="text-red-600 text-sm font-medium">★ Critical</span>
          )}
          {!current.enabled && (
            <span className="px-2 py-0.5 text-xs bg-gray-200 rounded">비활성</span>
          )}
        </div>
        <div className="text-sm text-gray-500">
          {current.channel} · {current.locale} · version {current.version}
          {catalogEntry && (
            <>
              {' · '}
              <span className="text-gray-700">{catalogEntry.description}</span>
            </>
          )}
        </div>
        {/* PR-T7 P1 — 지난 30일 발송 메트릭스 인라인 배지 */}
        <MetricsBadge metrics={metrics} loading={metricsLoading} />
      </header>

      {/* 알림 메시지 영역 */}
      {successMessage && (
        <div className="bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm px-4 py-3 rounded mb-4">
          {successMessage}
        </div>
      )}
      {genericError && (
        <div className="bg-red-50 border border-red-200 text-red-800 text-sm px-4 py-3 rounded mb-4">
          {genericError}
        </div>
      )}
      {lintErrors.length > 0 && (
        <div className="bg-red-50 border border-red-200 px-4 py-3 rounded mb-4">
          <div className="font-medium text-red-900 mb-2">Lint 차단 — {lintErrors.length} 개 오류</div>
          <ul className="space-y-1 text-sm">
            {lintErrors.map((e, idx) => (
              <li key={idx} className="text-red-700">
                <span className="font-mono text-xs bg-red-100 px-1 mr-2">{e.ruleCode}</span>
                {e.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 좌측: 편집 폼 */}
        <section className="bg-white border border-gray-200 rounded p-4 space-y-4">
          <h2 className="font-semibold text-gray-900">편집</h2>

          {current.channel === 'EMAIL' && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Subject</label>
              <input
                type="text"
                value={subject}
                onChange={(e) => setSubject(e.target.value)}
                className="w-full px-3 py-2 text-sm border border-gray-300 rounded font-mono"
              />
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Body</label>
            <textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={14}
              className="w-full px-3 py-2 text-sm border border-gray-300 rounded font-mono"
            />
            {current.channel === 'SMS' && (
              <div className="text-xs text-gray-500 mt-1">
                현재 {body.length} 자 · 160자 이내 권장
              </div>
            )}
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">Category</label>
              <select
                value={category}
                onChange={(e) => setCategory(e.target.value as NotificationCategory | '')}
                className="w-full px-2 py-1 text-sm border border-gray-300 rounded"
              >
                <option value="">(미설정)</option>
                {[
                  'SECURITY',
                  'STATUS',
                  'PAYMENT',
                  'REMINDER',
                  'VISIT',
                  'REASSURANCE',
                  'EXPIRY',
                  'MARKETING',
                  'FEEDBACK',
                  'OPS',
                ].map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">Severity</label>
              <select
                value={severity}
                onChange={(e) => setSeverity(e.target.value as NotificationSeverity | '')}
                className="w-full px-2 py-1 text-sm border border-gray-300 rounded"
              >
                <option value="">(미설정)</option>
                <option value="CRITICAL">CRITICAL</option>
                <option value="IMPORTANT">IMPORTANT</option>
                <option value="INFORMATIONAL">INFORMATIONAL</option>
                <option value="MARKETING">MARKETING</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Recipient Roles (comma-separated)
            </label>
            <input
              type="text"
              value={recipientRoles}
              onChange={(e) => setRecipientRoles(e.target.value)}
              placeholder="APPLICANT,LEW"
              className="w-full px-2 py-1 text-sm border border-gray-300 rounded font-mono"
            />
          </div>

          {current.channel === 'WHATSAPP' && (
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">
                Provider Template Name (Meta 등록명)
              </label>
              <input
                type="text"
                value={providerTemplateName}
                onChange={(e) => setProviderTemplateName(e.target.value)}
                className="w-full px-2 py-1 text-sm border border-gray-300 rounded font-mono"
              />
            </div>
          )}

          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Variables JSON 배열
            </label>
            <input
              type="text"
              value={variablesJson}
              onChange={(e) => setVariablesJson(e.target.value)}
              placeholder='["applicantName","amount"]'
              className="w-full px-2 py-1 text-sm border border-gray-300 rounded font-mono"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">
              Submission Note (선택)
            </label>
            <input
              type="text"
              value={submissionNote}
              onChange={(e) => setSubmissionNote(e.target.value)}
              placeholder="법무 요청 반영 / 오탈자 수정 등"
              className="w-full px-2 py-1 text-sm border border-gray-300 rounded"
            />
          </div>

          <div className="flex gap-2 pt-2 border-t">
            <button
              onClick={handleSubmitDraft}
              disabled={saving || body.length === 0}
              className="px-4 py-2 text-sm bg-teal-600 text-white rounded hover:bg-teal-700 disabled:opacity-50"
            >
              {saving ? '제출 중...' : 'Draft 제출 (SA 승인 대기)'}
            </button>
            <button
              onClick={() => setPreviewOpen(true)}
              className="px-4 py-2 text-sm bg-white border border-gray-300 rounded hover:bg-gray-50"
            >
              미리보기
            </button>
          </div>
        </section>

        {/* 우측: 메타 + 활성토글 + history */}
        <section className="space-y-4">
          <div className="bg-white border border-gray-200 rounded p-4">
            <h2 className="font-semibold text-gray-900 mb-3">메타</h2>
            <dl className="text-sm space-y-2">
              <div className="flex justify-between">
                <dt className="text-gray-600">상태</dt>
                <dd>
                  {current.enabled ? (
                    <span className="text-emerald-700">● 활성</span>
                  ) : (
                    <span className="text-gray-500">○ 비활성</span>
                  )}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-600">Version (ETag)</dt>
                <dd className="font-mono">{current.version}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-600">최종 수정</dt>
                <dd className="text-xs">{new Date(current.updatedAt).toLocaleString('ko-KR')}</dd>
              </div>
            </dl>
          </div>

          {/* 활성 토글 */}
          <div className="bg-white border border-gray-200 rounded p-4">
            <h2 className="font-semibold text-gray-900 mb-3">활성 제어</h2>
            {current.enabled ? (
              <button
                onClick={() => handleEnableDisable(false)}
                disabled={
                  (current.category === 'SECURITY' || current.category === 'PAYMENT') &&
                  role !== 'SYSTEM_ADMIN'
                }
                className="w-full px-4 py-2 text-sm bg-red-50 text-red-700 border border-red-200 rounded hover:bg-red-100 disabled:opacity-50"
              >
                비활성화
              </button>
            ) : (
              <button
                onClick={() => handleEnableDisable(true)}
                className="w-full px-4 py-2 text-sm bg-emerald-50 text-emerald-700 border border-emerald-200 rounded hover:bg-emerald-100"
              >
                활성화
              </button>
            )}
            {(current.category === 'SECURITY' || current.category === 'PAYMENT') &&
              role !== 'SYSTEM_ADMIN' && (
                <p className="text-xs text-gray-500 mt-2">
                  {current.category} 카테고리는 SYSTEM_ADMIN 만 비활성 가능 (H-S3).
                </p>
              )}
          </div>

          {/* 카탈로그 메타 (있을 때만) */}
          {catalogEntry && (
            <div className="bg-white border border-gray-200 rounded p-4">
              <h2 className="font-semibold text-gray-900 mb-3">카탈로그 메타</h2>
              <div className="text-sm space-y-1">
                <div>
                  <span className="text-gray-600">허용 변수:</span>{' '}
                  <span className="font-mono text-xs">{allowedVariables.join(', ') || '-'}</span>
                </div>
                {catalogEntry.requiredTokensJson && (
                  <div>
                    <span className="text-gray-600">강제 토큰:</span>{' '}
                    <span className="font-mono text-xs">
                      {safeParseStringArray(catalogEntry.requiredTokensJson).join(', ')}
                    </span>
                  </div>
                )}
              </div>
            </div>
          )}
        </section>
      </div>

      {/* Preview Modal */}
      {previewOpen && (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl w-full max-w-3xl max-h-[90vh] overflow-y-auto">
            <header className="px-6 py-4 border-b flex items-center justify-between">
              <h3 className="font-semibold">Preview · {current.templateCode}</h3>
              <button
                onClick={() => {
                  setPreviewOpen(false);
                  setPreviewResult(null);
                  setTestSendStatus(null);
                }}
                className="text-gray-500 hover:text-gray-700"
              >
                ✕
              </button>
            </header>
            <div className="p-6 space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Sample 변수 값
                </label>
                <div className="space-y-2">
                  {allowedVariables.length === 0 && (
                    <div className="text-xs text-gray-500">
                      카탈로그·variables_json 가 비어있어 변수 슬롯 자동 검출 불가.
                    </div>
                  )}
                  {allowedVariables.map((key) => (
                    <div key={key} className="flex gap-2 items-center">
                      <span className="font-mono text-xs w-48 text-gray-700">{key}</span>
                      <input
                        type="text"
                        value={previewPayload[key] ?? ''}
                        onChange={(e) =>
                          setPreviewPayload((prev) => ({ ...prev, [key]: e.target.value }))
                        }
                        className="flex-1 px-2 py-1 text-sm border border-gray-300 rounded font-mono"
                      />
                    </div>
                  ))}
                </div>
              </div>

              <div className="flex gap-2 pt-3 border-t">
                <button
                  onClick={handlePreview}
                  disabled={previewLoading}
                  className="px-4 py-2 text-sm bg-teal-600 text-white rounded hover:bg-teal-700 disabled:opacity-50"
                >
                  {previewLoading ? '렌더 중...' : '미리보기 실행'}
                </button>
                {current.channel === 'EMAIL' && (
                  <button
                    onClick={handleTestSend}
                    className="px-4 py-2 text-sm bg-white border border-gray-300 rounded hover:bg-gray-50"
                  >
                    내게 테스트 발송
                  </button>
                )}
              </div>

              {testSendStatus && (
                <div className="text-sm text-gray-700 bg-gray-50 px-3 py-2 rounded">
                  {testSendStatus}
                </div>
              )}

              {previewResult && (
                <div className="border-t pt-4 space-y-3">
                  {previewResult.missingKeys.length > 0 && (
                    <div className="bg-amber-50 border border-amber-200 px-3 py-2 rounded text-sm text-amber-800">
                      ⚠ 누락 변수: {previewResult.missingKeys.join(', ')}
                    </div>
                  )}
                  {previewResult.warnings.length > 0 && (
                    <div className="bg-amber-50 border border-amber-200 px-3 py-2 rounded text-sm">
                      <div className="font-medium text-amber-900 mb-1">Warnings</div>
                      <ul className="space-y-0.5 text-amber-800">
                        {previewResult.warnings.map((w, idx) => (
                          <li key={idx}>
                            <span className="font-mono text-xs">{w.ruleCode}</span>: {w.message}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                  {previewResult.subject && (
                    <div>
                      <div className="text-xs font-medium text-gray-700 mb-1">Subject</div>
                      <div className="bg-gray-50 px-3 py-2 rounded font-mono text-sm">
                        {previewResult.subject}
                      </div>
                    </div>
                  )}
                  <div>
                    <div className="text-xs font-medium text-gray-700 mb-1">
                      Body ({previewResult.charCount} 자
                      {previewResult.smsSegments != null
                        ? ` · ${previewResult.smsSegments} segment`
                        : ''}
                      )
                    </div>
                    <pre className="bg-gray-50 px-3 py-2 rounded text-sm whitespace-pre-wrap font-mono max-h-96 overflow-auto">
                      {previewResult.body}
                    </pre>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────
function safeParseStringArray(json: string | null | undefined): string[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : [];
  } catch {
    return [];
  }
}

function handleApiError(
  e: unknown,
  setLintErrors: (errs: LintIssue[]) => void,
  setGenericError: (msg: string) => void
) {
  if (axios.isAxiosError(e)) {
    const body = e.response?.data as Partial<LintErrorBody> | { code?: string; message?: string };
    if (e.response?.status === 400 && body && 'lint' in body && body.lint?.errors) {
      setLintErrors(body.lint.errors);
      return;
    }
    const message =
      (body as { message?: string })?.message ?? e.message ?? '요청 실패';
    setGenericError(message);
  } else {
    setGenericError(e instanceof Error ? e.message : '요청 실패');
  }
}

/**
 * PR-T7 P1 — 지난 30일 발송 메트릭스 인라인 배지.
 *
 * 운영 발송만 집계 (admin test-send 제외).
 * 표시 형태: "지난 30일 1,204회 발송 · 실패 0.3% · 누락변수 5건"
 * 발송 0 건이면 회색 안내. 실패율 ≥1% 또는 누락변수 ≥1 이면 강조색.
 */
function MetricsBadge({
  metrics,
  loading,
}: {
  metrics: TemplateMetricsResponse | null;
  loading: boolean;
}) {
  if (loading) {
    return (
      <div className="mt-2 text-xs text-gray-400">지난 30일 통계 로딩 중…</div>
    );
  }
  if (!metrics) return null;

  const operationalTotal = metrics.totalSent + metrics.totalFailed;
  if (operationalTotal === 0 && metrics.totalSkipped === 0) {
    return (
      <div className="mt-2 text-xs text-gray-400">
        지난 {metrics.days}일 동안 운영 발송 이력 없음
      </div>
    );
  }

  const failurePct = (metrics.failureRate * 100).toFixed(2);
  const failureAlert = metrics.failureRate >= 0.01;  // 1% 초과 강조
  const warningAlert = metrics.renderWarnings > 0;

  return (
    <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
      <span className="text-gray-600">
        지난 <strong className="font-semibold">{metrics.days}일</strong>
      </span>
      <span className="text-gray-700">
        <strong className="font-semibold">{metrics.totalSent.toLocaleString()}</strong> 회 발송
      </span>
      <span
        className={
          failureAlert
            ? 'px-1.5 py-0.5 rounded bg-red-50 text-red-700 border border-red-200'
            : 'text-gray-600'
        }
      >
        실패 {failurePct}% ({metrics.totalFailed.toLocaleString()}건)
      </span>
      {metrics.totalSkipped > 0 && (
        <span className="text-gray-500">
          가드 컷 {metrics.totalSkipped.toLocaleString()}건
        </span>
      )}
      {warningAlert && (
        <span className="px-1.5 py-0.5 rounded bg-amber-50 text-amber-700 border border-amber-200">
          ⚠ 변수 누락 {metrics.renderWarnings.toLocaleString()}건
        </span>
      )}
    </div>
  );
}
