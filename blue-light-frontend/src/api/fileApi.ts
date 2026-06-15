import axiosClient from './axiosClient';
import type { FileInfo, FileType } from '../types';

/**
 * Upload a file for an application
 */
export const uploadFile = async (
  applicationId: number,
  file: File,
  fileType: FileType = 'DRAWING_SLD'
): Promise<FileInfo> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('fileType', fileType);

  const response = await axiosClient.post<FileInfo>(
    `/applications/${applicationId}/files`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

/**
 * Get all files for an application
 */
export const getFilesByApplication = async (applicationId: number): Promise<FileInfo[]> => {
  const response = await axiosClient.get<FileInfo[]>(`/applications/${applicationId}/files`);
  return response.data;
};

/**
 * Download a file (returns blob URL)
 */
export const downloadFile = async (fileId: number, filename: string): Promise<void> => {
  const response = await axiosClient.get(`/files/${fileId}/download`, {
    responseType: 'blob',
  });

  const contentType = response.headers['content-type'] || 'application/octet-stream';

  // 호출자가 넘긴 filename에 확장자가 없으면 서버의 원본 파일명(Content-Disposition)에서
  // 확장자를 가져와 붙인다. (확장자 없는 download 속성 + 타입 없는 Blob → 브라우저가 .txt로 저장)
  let downloadName = filename;
  if (!/\.[a-z0-9]+$/i.test(downloadName)) {
    const serverName = parseContentDispositionFilename(response.headers['content-disposition']);
    const ext = serverName?.match(/\.[a-z0-9]+$/i)?.[0];
    if (ext) downloadName += ext;
  }

  // Blob에 서버의 content-type을 실어 브라우저가 올바른 형식으로 인식하게 한다.
  const url = window.URL.createObjectURL(new Blob([response.data], { type: contentType }));
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', downloadName);
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
};

/**
 * Content-Disposition 헤더에서 파일명을 추출한다. filename*=UTF-8''... 우선.
 */
const parseContentDispositionFilename = (header?: string): string | undefined => {
  if (!header) return undefined;
  const star = header.match(/filename\*=(?:UTF-8'')?([^;]+)/i);
  if (star?.[1]) {
    try {
      return decodeURIComponent(star[1].replace(/^"|"$/g, ''));
    } catch {
      return star[1].replace(/^"|"$/g, '');
    }
  }
  const plain = header.match(/filename="?([^";]+)"?/i);
  return plain?.[1];
};

/**
 * Delete a file
 */
export const deleteFile = async (fileId: number): Promise<void> => {
  await axiosClient.delete(`/files/${fileId}`);
};

/**
 * Get a preview blob URL for a file (for image thumbnails).
 * Caller must revoke the URL via URL.revokeObjectURL() when done.
 */
export const getFilePreviewUrl = async (fileId: number): Promise<string> => {
  const response = await axiosClient.get(`/files/${fileId}/download`, {
    responseType: 'blob',
  });
  const contentType = response.headers['content-type'] || 'application/octet-stream';
  return window.URL.createObjectURL(new Blob([response.data], { type: contentType }));
};

export const fileApi = { uploadFile, getFilesByApplication, downloadFile, deleteFile, getFilePreviewUrl };
export default fileApi;
