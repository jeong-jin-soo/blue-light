import axiosClient from './axiosClient';

/**
 * LoA 폼 템플릿 관리 API (admin) + active 폼 소비.
 *
 * 스펙: doc/Project Analysis/loa-exchange-redesign-spec.md §3.1, §3.2 (PR2).
 */

export interface LoaFormTemplateResponse {
  loaFormTemplateSeq: number;
  label: string;
  fileSeq: number;
  isActive: boolean;
  uploadedBy: number | null;
  uploadedByName: string | null;
  uploadedAt: string;
}

// ── admin CRUD (/api/admin/loa-form-templates) ──────────────────────────────

export const listLoaFormTemplates = async (): Promise<LoaFormTemplateResponse[]> => {
  const response = await axiosClient.get<LoaFormTemplateResponse[]>('/admin/loa-form-templates');
  return response.data;
};

export const uploadLoaFormTemplate = async (
  file: File,
  label: string,
  activate = false
): Promise<LoaFormTemplateResponse> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('label', label);
  formData.append('activate', String(activate));
  const response = await axiosClient.post<LoaFormTemplateResponse>(
    '/admin/loa-form-templates',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

export const activateLoaFormTemplate = async (
  seq: number
): Promise<LoaFormTemplateResponse> => {
  const response = await axiosClient.patch<LoaFormTemplateResponse>(
    `/admin/loa-form-templates/${seq}/activate`
  );
  return response.data;
};

export const deleteLoaFormTemplate = async (seq: number): Promise<void> => {
  await axiosClient.delete(`/admin/loa-form-templates/${seq}`);
};

/**
 * admin 검수용 다운로드 — 브라우저에 파일을 내려받는다.
 */
export const downloadLoaFormTemplate = async (
  seq: number,
  label: string
): Promise<void> => {
  const response = await axiosClient.get(`/admin/loa-form-templates/${seq}/download`, {
    responseType: 'blob',
  });
  triggerBlobDownload(response.data as Blob, `${sanitize(label)}.pdf`);
};

// ── active 폼 소비 (/api/applications/{id}/loa/active-form) ──────────────────

export const getActiveLoaForm = async (
  applicationId: number
): Promise<LoaFormTemplateResponse> => {
  const response = await axiosClient.get<LoaFormTemplateResponse>(
    `/applications/${applicationId}/loa/active-form`
  );
  return response.data;
};

export const downloadActiveLoaForm = async (
  applicationId: number,
  label = 'loa-form'
): Promise<void> => {
  const response = await axiosClient.get(
    `/applications/${applicationId}/loa/active-form/download`,
    { responseType: 'blob' }
  );
  triggerBlobDownload(response.data as Blob, `${sanitize(label)}.pdf`);
};

// ── 내부 헬퍼 ──────────────────────────────

function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
}

function sanitize(name: string): string {
  return name.replace(/[^\w.\-가-힣 ]+/g, '_').trim() || 'loa-form';
}
