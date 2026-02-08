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

  // Create download link
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
 * Delete a file
 */
export const deleteFile = async (fileId: number): Promise<void> => {
  await axiosClient.delete(`/files/${fileId}`);
};

export const fileApi = { uploadFile, getFilesByApplication, downloadFile, deleteFile };
export default fileApi;
