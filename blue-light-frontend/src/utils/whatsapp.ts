/**
 * WhatsApp click-to-chat 링크 유틸.
 * wa.me 는 국제형식에서 '+'·공백·하이픈을 제거한 숫자만 허용한다.
 * 번호 원본은 admin system_settings 에서 내려오며(설정 우선 원칙),
 * 관리자가 어떤 표기("+65 8796 7667" 등)로 저장해도 여기서 정규화한다.
 */

/** "+65 8796 7667" → "6587967667" */
export function normalizeWhatsAppNumber(raw: string): string {
  return (raw ?? '').replace(/\D/g, '');
}

/** wa.me 링크 생성. message 는 URL 인코딩되어 프리필된다. */
export function buildWhatsAppLink(rawNumber: string, message?: string): string {
  const number = normalizeWhatsAppNumber(rawNumber);
  const query = message ? `?text=${encodeURIComponent(message)}` : '';
  return `https://wa.me/${number}${query}`;
}
