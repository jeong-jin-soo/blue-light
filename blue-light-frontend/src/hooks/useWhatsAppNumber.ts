import { useEffect, useState } from 'react';
import { contactApi } from '../api/contactApi';

// 설정 우선 원칙 예외: 공개 contact-info API 장애·미설정 시에도 사이트의 유일한
// 문의 채널(WhatsApp 버튼)이 죽지 않도록 하는 최후 폴백.
// 정본은 system_settings.whatsapp_business_number — doc/Project Analysis/whatsapp-landing-revamp.md 참조.
const FALLBACK_WHATSAPP_NUMBER = '+65 8796 7667';

// 페이지 간 이동 시 재요청하지 않도록 모듈 레벨 캐시
let cachedNumber: string | null = null;

/**
 * WhatsApp Business 상담 번호 (admin 설정값).
 * API 응답 전에는 폴백 번호를 반환하므로 버튼은 항상 동작한다.
 */
export function useWhatsAppNumber(): string {
  const [number, setNumber] = useState<string>(cachedNumber ?? FALLBACK_WHATSAPP_NUMBER);

  useEffect(() => {
    if (cachedNumber !== null) return;
    let active = true;
    contactApi
      .getContactInfo()
      .then((info) => {
        cachedNumber = info.whatsappBusinessNumber?.trim() || FALLBACK_WHATSAPP_NUMBER;
        if (active) setNumber(cachedNumber);
      })
      .catch(() => {
        if (active) setNumber(FALLBACK_WHATSAPP_NUMBER);
      });
    return () => {
      active = false;
    };
  }, []);

  return number;
}
