/**
 * WhatsApp click-to-chat 링크 유틸.
 * wa.me 는 국제형식에서 '+'·공백·하이픈을 제거한 숫자만 허용한다.
 * 번호 원본은 admin system_settings 에서 내려오며(설정 우선 원칙),
 * 관리자가 어떤 표기("+65 8796 7667" 등)로 저장해도 여기서 정규화한다.
 */

import { attributionTag } from './attribution';

/** "+65 8796 7667" → "6587967667" */
export function normalizeWhatsAppNumber(raw: string): string {
  return (raw ?? '').replace(/\D/g, '');
}

/**
 * wa.me 링크 생성. message 는 URL 인코딩되어 프리필된다.
 * 광고 유입(UTM)이 있으면 담당자가 출처를 건별로 알 수 있도록
 * 프리필 메시지 끝에 "(ref: source / medium / campaign)" 태그를 붙인다.
 * 자연 유입이면 태그 없이 그대로.
 */
export function buildWhatsAppLink(rawNumber: string, message?: string): string {
  const number = normalizeWhatsAppNumber(rawNumber);
  let msg = message ?? '';
  const tag = attributionTag();
  if (tag) msg = `${msg ? `${msg}\n\n` : ''}(ref: ${tag})`;
  const query = msg ? `?text=${encodeURIComponent(msg)}` : '';
  return `https://wa.me/${number}${query}`;
}
