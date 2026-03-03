import axiosClient from './axiosClient';
import type { SampleFileInfo } from '../types';

/**
 * 전체 샘플 파일 목록 조회
 */
export const getSampleFiles = async (): Promise<SampleFileInfo[]> => {
  const response = await axiosClient.get<SampleFileInfo[]>('/sample-files');
  return response.data;
};

/**
 * 샘플 파일 업로드/교체 (관리자 전용)
 */
export const uploadSampleFile = async (
  categoryKey: string,
  file: File,
): Promise<SampleFileInfo> => {
  const formData = new FormData();
  formData.append('file', file);

  const response = await axiosClient.post<SampleFileInfo>(
    `/admin/sample-files/${categoryKey}`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  return response.data;
};

/**
 * 샘플 파일 삭제 (관리자 전용)
 */
export const deleteSampleFile = async (categoryKey: string): Promise<void> => {
  await axiosClient.delete(`/admin/sample-files/${categoryKey}`);
};

/**
 * 샘플 파일 다운로드 (blob)
 */
export const downloadSampleFile = async (categoryKey: string, filename: string): Promise<void> => {
  const response = await axiosClient.get(`/sample-files/${categoryKey}/download`, {
    responseType: 'blob',
  });

  const url = window.URL.createObjectURL(new Blob([response.data]));
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', filename);
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
};

/**
 * 샘플 파일 미리보기 URL (blob URL 반환 — 호출 측에서 revokeObjectURL 필요)
 */
export const getSampleFilePreviewUrl = async (categoryKey: string): Promise<string> => {
  const response = await axiosClient.get(`/sample-files/${categoryKey}/download`, {
    responseType: 'blob',
  });
  const contentType = response.headers['content-type'] || 'application/octet-stream';
  return window.URL.createObjectURL(new Blob([response.data], { type: contentType }));
};

const sampleFileApi = {
  getSampleFiles,
  uploadSampleFile,
  deleteSampleFile,
  downloadSampleFile,
  getSampleFilePreviewUrl,
};

export default sampleFileApi;
