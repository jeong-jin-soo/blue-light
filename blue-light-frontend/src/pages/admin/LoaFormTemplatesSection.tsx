import { useCallback, useEffect, useRef, useState } from 'react';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { useToastStore } from '../../stores/toastStore';
import {
  listLoaFormTemplates,
  uploadLoaFormTemplate,
  activateLoaFormTemplate,
  deleteLoaFormTemplate,
  downloadLoaFormTemplate,
  type LoaFormTemplateResponse,
} from '../../api/loaFormTemplateApi';
import { CheckCircle2, Download, FileText, Trash2, UploadCloud } from 'lucide-react';

/**
 * Settings > LoA Forms 섹션.
 *
 * 스펙: doc/Project Analysis/loa-exchange-redesign-spec.md §4.1 (PR2).
 * PayNow QR 업로드 UI 패턴을 미러한다: 버전 목록(라벨·업로드일·active 배지) + 업로드(파일+라벨)
 * + "현재 폼으로 지정"(activate) + 삭제 + 다운로드.
 */
export default function LoaFormTemplatesSection() {
  // 전체 스토어 구독 금지: toasts 배열 변경마다 재렌더 → load 재생성 → useEffect 재실행 → 에러 토스트 무한루프
  const toastError = useToastStore((s) => s.error);
  const toastSuccess = useToastStore((s) => s.success);

  const [templates, setTemplates] = useState<LoaFormTemplateResponse[]>([]);
  const [loading, setLoading] = useState(true);

  // 업로드 폼 상태
  const [label, setLabel] = useState('');
  const [activateOnUpload, setActivateOnUpload] = useState(true);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // 액션 진행 상태
  const [activatingSeq, setActivatingSeq] = useState<number | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<LoaFormTemplateResponse | null>(null);
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    listLoaFormTemplates()
      .then(setTemplates)
      .catch((err: { message?: string }) => toastError(err.message || 'Failed to load LoA forms'))
      .finally(() => setLoading(false));
  }, [toastError]);

  useEffect(() => {
    load();
  }, [load]);

  const activeTemplate = templates.find((t) => t.isActive) ?? null;
  const pastTemplates = templates.filter((t) => !t.isActive);

  // ── 핸들러 ──────────────────────────────

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] ?? null;
    if (file && file.type !== 'application/pdf') {
      toastError('Please select a PDF file');
      if (fileInputRef.current) fileInputRef.current.value = '';
      return;
    }
    if (file && file.size > 20 * 1024 * 1024) {
      toastError('File must be less than 20MB');
      if (fileInputRef.current) fileInputRef.current.value = '';
      return;
    }
    setSelectedFile(file);
  };

  const handleUpload = async () => {
    if (!selectedFile) {
      toastError('Please select a PDF file');
      return;
    }
    if (!label.trim()) {
      toastError('Please enter a label');
      return;
    }
    setUploading(true);
    try {
      await uploadLoaFormTemplate(selectedFile, label.trim(), activateOnUpload);
      toastSuccess('LoA form uploaded');
      setLabel('');
      setSelectedFile(null);
      setActivateOnUpload(true);
      if (fileInputRef.current) fileInputRef.current.value = '';
      load();
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to upload LoA form';
      toastError(message);
    } finally {
      setUploading(false);
    }
  };

  const handleActivate = async (seq: number) => {
    setActivatingSeq(seq);
    try {
      await activateLoaFormTemplate(seq);
      toastSuccess('Form set as current');
      load();
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to activate form';
      toastError(message);
    } finally {
      setActivatingSeq(null);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteLoaFormTemplate(deleteTarget.loaFormTemplateSeq);
      toastSuccess('Form removed');
      setDeleteTarget(null);
      load();
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to remove form';
      toastError(message);
    } finally {
      setDeleting(false);
    }
  };

  const handleDownload = async (t: LoaFormTemplateResponse) => {
    try {
      await downloadLoaFormTemplate(t.loaFormTemplateSeq, t.label);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to download form';
      toastError(message);
    }
  };

  const formatDate = (iso: string) => {
    try {
      return new Date(iso).toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      });
    } catch {
      return iso;
    }
  };

  // ── 렌더링 ──────────────────────────────

  return (
    <Card>
      <div className="mb-4">
        <h2 className="text-lg font-semibold text-gray-800 flex items-center gap-2">
          <FileText className="w-5 h-5 text-primary-600" />
          LoA Forms
        </h2>
        <p className="text-xs text-gray-500 mt-0.5">
          Manage the Letter of Appointment form that applicants download, sign offline, and upload.
          Only one version is active at a time. Past versions are kept for traceability.
        </p>
      </div>

      {/* 현재 active 폼 카드 */}
      <div className="mb-5">
        <h3 className="text-sm font-semibold text-gray-700 mb-2">Current form</h3>
        {loading ? (
          <div className="h-16 bg-gray-100 rounded-lg animate-pulse" />
        ) : activeTemplate ? (
          <div className="flex items-center justify-between gap-3 p-3 border border-success-200 bg-success-50/40 rounded-lg">
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className="font-medium text-gray-800 truncate">{activeTemplate.label}</span>
                <Badge variant="success" dot>
                  Active
                </Badge>
              </div>
              <p className="text-xs text-gray-500 mt-0.5">
                Uploaded {formatDate(activeTemplate.uploadedAt)}
                {activeTemplate.uploadedByName ? ` · ${activeTemplate.uploadedByName}` : ''}
              </p>
            </div>
            <Button variant="outline" size="sm" onClick={() => handleDownload(activeTemplate)}>
              <Download className="w-4 h-4 mr-1 inline-block" />
              Download
            </Button>
          </div>
        ) : (
          <div className="p-4 border-2 border-dashed border-gray-200 rounded-lg text-center">
            <p className="text-sm text-gray-500 font-medium">No active LoA form</p>
            <p className="text-xs text-gray-400 mt-0.5">
              Upload a form below and set it as the current version.
            </p>
          </div>
        )}
      </div>

      {/* 업로드 폼 */}
      <div className="mb-5 pt-4 border-t border-gray-100">
        <h3 className="text-sm font-semibold text-gray-700 mb-3">Upload new version</h3>
        <div className="grid gap-3 sm:grid-cols-2 max-w-2xl">
          <Input
            label="Label"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="e.g., EMA NEW LoA v2026.06"
          />
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">PDF file</label>
            <div
              className="border-2 border-dashed border-gray-300 rounded-lg px-3 py-2.5 text-center cursor-pointer hover:border-primary-400 hover:bg-primary-50/50 transition-colors"
              onClick={() => fileInputRef.current?.click()}
            >
              <div className="flex items-center justify-center gap-2 text-sm text-gray-600">
                <UploadCloud className="w-4 h-4" />
                <span className="truncate">
                  {selectedFile ? selectedFile.name : 'Click to select a PDF (max 20MB)'}
                </span>
              </div>
            </div>
            <input
              ref={fileInputRef}
              type="file"
              accept="application/pdf"
              className="hidden"
              onChange={handleFileSelect}
            />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-4 mt-3">
          <label className="inline-flex items-center gap-2 text-sm text-gray-600 cursor-pointer">
            <input
              type="checkbox"
              checked={activateOnUpload}
              onChange={(e) => setActivateOnUpload(e.target.checked)}
              className="rounded border-gray-300 text-primary focus:ring-primary/30"
            />
            Set as current form immediately
          </label>
          <Button size="sm" onClick={handleUpload} loading={uploading} disabled={!selectedFile || !label.trim()}>
            Upload
          </Button>
        </div>
      </div>

      {/* 과거 버전 목록 */}
      <div className="pt-4 border-t border-gray-100">
        <h3 className="text-sm font-semibold text-gray-700 mb-2">Past versions</h3>
        {loading ? (
          <div className="space-y-2">
            {[1, 2].map((i) => (
              <div key={i} className="h-12 bg-gray-100 rounded-lg animate-pulse" />
            ))}
          </div>
        ) : pastTemplates.length === 0 ? (
          <p className="text-xs text-gray-400 py-3">No past versions.</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {pastTemplates.map((t) => (
              <div key={t.loaFormTemplateSeq} className="flex items-center justify-between gap-3 py-2.5">
                <div className="min-w-0">
                  <span className="font-medium text-gray-800 truncate block">{t.label}</span>
                  <p className="text-xs text-gray-500 mt-0.5">
                    Uploaded {formatDate(t.uploadedAt)}
                    {t.uploadedByName ? ` · ${t.uploadedByName}` : ''}
                  </p>
                </div>
                <div className="flex items-center gap-1 flex-shrink-0">
                  <Button variant="ghost" size="sm" onClick={() => handleDownload(t)} title="Download">
                    <Download className="w-4 h-4" />
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleActivate(t.loaFormTemplateSeq)}
                    loading={activatingSeq === t.loaFormTemplateSeq}
                    title="Set as current form"
                  >
                    <CheckCircle2 className="w-4 h-4 mr-1 inline-block" />
                    Set current
                  </Button>
                  <button
                    type="button"
                    onClick={() => setDeleteTarget(t)}
                    className="p-1.5 text-gray-400 hover:text-error-500 hover:bg-error-50 rounded-md transition-colors"
                    title="Remove"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        loading={deleting}
        title="Remove LoA Form"
        message={`Remove "${deleteTarget?.label}"? This version will be soft-deleted and hidden from the list.`}
        confirmLabel="Remove"
        variant="danger"
      />
    </Card>
  );
}
