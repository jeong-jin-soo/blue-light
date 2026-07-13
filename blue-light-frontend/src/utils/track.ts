/**
 * 1st-party 텔레메트리 전송 (우리 백엔드 /public/events 로만).
 * 쿠키 미전송(credentials: 'omit'), fetch keepalive 로 fire-and-forget.
 * 실패는 조용히 무시 — 사용자 경험에 영향 없음.
 */
import { getAttribution, getSessionId } from './attribution';

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api';
const ENDPOINT = `${API_BASE}/public/events`;

type EventType = 'PAGE_VIEW' | 'WHATSAPP_CLICK';

function base(type: EventType): Record<string, unknown> {
  const a = getAttribution();
  return {
    type,
    path: typeof window !== 'undefined' ? window.location.pathname : undefined,
    utmSource: a.source,
    utmMedium: a.medium,
    utmCampaign: a.campaign,
    utmContent: a.content,
    referrerHost: a.referrerHost,
    sessionId: getSessionId(),
  };
}

function send(payload: Record<string, unknown>): void {
  try {
    fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      keepalive: true,
      credentials: 'omit',
      mode: 'cors',
    }).catch(() => { /* 무시 */ });
  } catch { /* 무시 */ }
}

/** 공개 페이지 방문 기록 */
export function trackPageView(): void {
  send(base('PAGE_VIEW'));
}

/** WhatsApp 문의 클릭 기록. service = 서비스 slug(있으면) */
export function trackWhatsAppClick(service?: string): void {
  send({ ...base('WHATSAPP_CLICK'), service: service || undefined });
}
