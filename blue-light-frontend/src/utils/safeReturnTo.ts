/** Parse and validate a `returnTo` path. Returns null when the value is missing or unsafe. */
export function parseReturnTo(raw: string | null): string | null {
  if (!raw) return null;
  if (!raw.startsWith('/')) return null;
  if (raw.startsWith('//') || raw.startsWith('/\\')) return null;
  // 보안 sanitize — null byte, 탭, CR/LF, 기타 제어 문자(0x00~0x1f) 차단 (의도된 control-regex 사용).
  // eslint-disable-next-line no-control-regex
  if (/[\\\t\r\n\x00-\x1f]/.test(raw)) return null;
  // Reject encoded path separators that browsers may normalize post-navigation
  const lower = raw.toLowerCase();
  if (lower.startsWith('/%2f') || lower.startsWith('/%5c')) return null;
  return raw;
}
