import axiosClient from './axiosClient';
import type { FileInfo, LoaStatus } from '../types';

/**
 * LOA PDF 생성 (Admin/LEW 액션)
 */
export const generateLoa = async (applicationId: number): Promise<FileInfo> => {
  const response = await axiosClient.post<FileInfo>(
    `/admin/applications/${applicationId}/loa/generate`
  );
  return response.data;
};

/**
 * LOA 전자서명 (Applicant 액션)
 * @param signatureBlob - 서명 캔버스에서 추출한 PNG Blob
 */
export const signLoa = async (applicationId: number, signatureBlob: Blob): Promise<FileInfo> => {
  const formData = new FormData();
  formData.append('signature', signatureBlob, 'signature.png');

  const response = await axiosClient.post<FileInfo>(
    `/applications/${applicationId}/loa/sign`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

/**
 * LOA 상태 조회
 */
export const getLoaStatus = async (applicationId: number): Promise<LoaStatus> => {
  const response = await axiosClient.get<LoaStatus>(
    `/applications/${applicationId}/loa/status`
  );
  return response.data;
};

export const loaApi = { generateLoa, signLoa, getLoaStatus };
export default loaApi;
