import { useWhatsAppNumber } from '../../hooks/useWhatsAppNumber';
import { buildWhatsAppLink } from '../../utils/whatsapp';
import { WHATSAPP_GENERIC_MESSAGE } from '../../constants/publicServices';
import WhatsAppIcon from './WhatsAppIcon';

/**
 * 사이트 전역 플로팅 WhatsApp 버튼 (미팅 문서 "SITE-WIDE CTA").
 * 공개 페이지(랜딩·서비스 상세) 양쪽에 sticky 로 노출된다.
 */
export default function FloatingWhatsAppButton() {
  const number = useWhatsAppNumber();
  return (
    <a
      href={buildWhatsAppLink(number, WHATSAPP_GENERIC_MESSAGE)}
      target="_blank"
      rel="noopener noreferrer"
      aria-label="Chat with us on WhatsApp"
      className="fixed bottom-5 right-5 z-50 inline-flex items-center gap-2 rounded-full bg-[#25D366] px-4 py-3 text-sm font-semibold text-white shadow-lg hover:bg-[#1da851] hover:shadow-xl transition-all"
    >
      <WhatsAppIcon className="w-6 h-6" />
      <span className="hidden sm:inline">Chat with Us</span>
    </a>
  );
}
