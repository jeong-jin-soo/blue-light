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
 * 샘플 파일 업로드 (카테고리에 추가)
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
 * 샘플 파일 개별 삭제 (seq 기반)
 */
export const deleteSampleFile = async (sampleFileSeq: number): Promise<void> => {
  await axiosClient.delete(`/admin/sample-files/${sampleFileSeq}`);
};

/**
 * 샘플 파일 다운로드 (seq 기반)
 */
export const downloadSampleFile = async (sampleFileSeq: number, filename: string): Promise<void> => {
  const response = await axiosClient.get(`/sample-files/${sampleFileSeq}/download`, {
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
 * 샘플 파일 미리보기 URL (seq 기반, blob URL 반환 — 호출 측에서 revokeObjectURL 필요)
 */
export const getSampleFilePreviewUrl = async (sampleFileSeq: number): Promise<string> => {
  const response = await axiosClient.get(`/sample-files/${sampleFileSeq}/download`, {
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
