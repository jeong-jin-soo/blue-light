import axiosClient from './axiosClient';

export interface PublicContactInfo {
  whatsappBusinessNumber: string;
}

/**
 * 공개 연락처 조회 (인증 불필요)
 * 번호 정본은 system_settings.whatsapp_business_number
 */
export const getContactInfo = async (): Promise<PublicContactInfo> => {
  const response = await axiosClient.get<PublicContactInfo>('/public/contact-info');
  return response.data;
};

export const contactApi = {
  getContactInfo,
};
