import type { DocumentType } from '../../types/document';

/**
 * "application/pdf,image/png,image/jpeg" → "PDF · PNG · JPG"
 */
export function prettyMime(acceptedMime: string): string {
  const map: Record<string, string> = {
    'application/pdf': 'PDF',
    'image/png': 'PNG',
    'image/jpeg': 'JPG',
    'image/jpg': 'JPG',
    'image/gif': 'GIF',
    'image/webp': 'WEBP',
  };
  const seen = new Set<string>();
  const tokens: string[] = [];
  for (const raw of acceptedMime.split(',')) {
    const mime = raw.trim();
    const pretty = map[mime] ?? mime.split('/')[1]?.toUpperCase() ?? mime;
    if (!seen.has(pretty)) {
      seen.add(pretty);
      tokens.push(pretty);
    }
  }
  return tokens.join(' · ');
}

/**
 * 파일 크기를 읽기 쉬운 단위로 포맷 (정수 MB면 정수, 소수면 1자리)
 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  const mb = bytes / (1024 * 1024);
  return mb >= 10 ? `${mb.toFixed(0)} MB` : `${mb.toFixed(1)} MB`;
}

/**
 * 파일의 MIME이 허용 리스트에 있는지 검사 (빈 타입은 확장자 기반 fallback).
 */
export function isMimeAccepted(file: File, acceptedMime: string): boolean {
  const allowed = acceptedMime.split(',').map((s) => s.trim()).filter(Boolean);
  if (allowed.length === 0) return true;
  if (file.type && allowed.includes(file.type)) return true;
  // 확장자 기반 fallback (브라우저가 type을 비워서 보내는 경우)
  const name = file.name.toLowerCase();
  const extMap: Record<string, string[]> = {
    'application/pdf': ['.pdf'],
    'image/png': ['.png'],
    'image/jpeg': ['.jpg', '.jpeg'],
    'image/gif': ['.gif'],
    'image/webp': ['.webp'],
  };
  for (const mime of allowed) {
    for (const ext of extMap[mime] ?? []) {
      if (name.endsWith(ext)) return true;
    }
  }
  return false;
}

/**
 * DocumentType 에 따라 <Select> 옵션 라벨을 단일 라인으로 포맷
 * "{iconEmoji} {labelKo} · {prettyMime} · 최대 {N}MB"
 */
export function formatTypeOptionLabel(dt: DocumentType): string {
  const icon = dt.iconEmoji ? `${dt.iconEmoji} ` : '';
  return `${icon}${dt.labelKo} · ${prettyMime(dt.acceptedMime)} · 최대 ${dt.maxSizeMb}MB`;
}
