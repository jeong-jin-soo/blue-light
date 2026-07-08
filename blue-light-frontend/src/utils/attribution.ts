/**
 * 1st-party 유입 어트리뷰션 (쿠키 없음, sessionStorage 만 사용).
 *
 * 광고 링크의 UTM 파라미터를 "첫 방문 시점(first-touch)"에 캡처해 세션 동안 유지한다.
 * WhatsApp 프리필 태그 + 방문/클릭 텔레메트리에 사용된다. 제3자 트래커 미사용.
 * gclid/fbclid 만 있는 경우(광고 자동태그) 최소한의 source/medium 을 추정한다.
 */

const ATTR_KEY = 'lk_attr';
const SID_KEY = 'lk_sid';

export interface Attribution {
  source?: string;
  medium?: string;
  campaign?: string;
  content?: string;
  referrerHost?: string;
}

function safeGet(k: string): string | null {
  try { return window.sessionStorage.getItem(k); } catch { return null; }
}
function safeSet(k: string, v: string): void {
  try { window.sessionStorage.setItem(k, v); } catch { /* private mode 등 무시 */ }
}

/** 앱 부팅 시 1회 호출(렌더 전). 세션ID 발급 + first-touch 어트리뷰션 캡처. */
export function initAttribution(): void {
  if (typeof window === 'undefined') return;

  if (!safeGet(SID_KEY)) {
    const rnd =
      (typeof crypto !== 'undefined' && 'randomUUID' in crypto)
        ? crypto.randomUUID().replace(/-/g, '').slice(0, 32)
        : Math.random().toString(36).slice(2) + Date.now().toString(36);
    safeSet(SID_KEY, rnd);
  }

  // first-touch 유지 — 이미 "의미있는" 어트리뷰션이 캡처됐으면 덮어쓰지 않음.
  // (빈 {} 는 미캡처로 간주 → 이후 UTM 이 붙은 방문에서 캡처 가능)
  const existing = getAttribution();
  if (Object.keys(existing).length > 0) return;

  const p = new URLSearchParams(window.location.search);
  const source = p.get('utm_source') || '';
  const medium = p.get('utm_medium') || '';
  const campaign = p.get('utm_campaign') || '';
  const content = p.get('utm_content') || '';

  const attr: Attribution = {};
  if (source || medium || campaign || content) {
    if (source) attr.source = source;
    if (medium) attr.medium = medium;
    if (campaign) attr.campaign = campaign;
    if (content) attr.content = content;
  } else if (p.get('gclid')) {
    attr.source = 'google';
    attr.medium = 'paid';
  } else if (p.get('fbclid')) {
    attr.source = 'facebook';
    attr.medium = 'paid';
  }

  try {
    if (document.referrer) {
      const host = new URL(document.referrer).host;
      if (host && host !== window.location.host) attr.referrerHost = host;
    }
  } catch { /* invalid referrer 무시 */ }

  // 캡처된 게 있을 때만 저장(빈 {} 저장 금지 — 이후 방문에서 재캡처 허용)
  if (Object.keys(attr).length > 0) {
    safeSet(ATTR_KEY, JSON.stringify(attr));
  }
}

export function getAttribution(): Attribution {
  const raw = safeGet(ATTR_KEY);
  if (!raw) return {};
  try { return JSON.parse(raw) as Attribution; } catch { return {}; }
}

export function getSessionId(): string {
  return safeGet(SID_KEY) || '';
}

/** WhatsApp 프리필용 사람이 읽는 태그. 예: "facebook / paid / jul-sld-promo" (없으면 빈 문자열) */
export function attributionTag(): string {
  const a = getAttribution();
  const parts = [a.source, a.medium, a.campaign].filter(Boolean);
  return parts.length ? parts.join(' / ') : '';
}
