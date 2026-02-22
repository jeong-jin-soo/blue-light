/**
 * crypto.randomUUID() polyfill
 * - crypto.randomUUID()는 Secure Context(HTTPS)에서만 사용 가능
 * - HTTP 환경에서도 동작하도록 폴백 제공
 */
export function generateUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // fallback: crypto.getRandomValues 기반 UUID v4
  return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c) =>
    (+c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))).toString(16)
  );
}
