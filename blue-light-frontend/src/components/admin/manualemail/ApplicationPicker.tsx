import { useEffect, useRef, useState } from 'react';
import { Input } from '../../ui/Input';
import { getApplications } from '../../../api/adminApplicationApi';
import type { AdminApplication } from '../../../types';

/**
 * 신청서(Application) 검색 autocomplete — Compose 폼의 "Related application" 옵션 필드.
 *
 * <p>스펙: doc/Project Analysis/admin-manual-email-spec.md §7.2.1.</p>
 *
 * <p>{@code GET /api/admin/applications?search=...} 를 그대로 사용 — 신청번호 / 이름 / 이메일 / 주소
 * 모두 백엔드 search 가드에서 처리한다.</p>
 */

interface Props {
  selected: AdminApplication | null;
  onSelect: (app: AdminApplication | null) => void;
  disabled?: boolean;
}

const SEARCH_DEBOUNCE_MS = 300;
const MAX_RESULTS = 8;

export function ApplicationPicker({ selected, onSelect, disabled = false }: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<AdminApplication[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!query.trim() || query.trim().length < 2) {
      setResults([]);
      setOpen(false);
      return;
    }
    const timer = setTimeout(async () => {
      setLoading(true);
      try {
        const page = await getApplications(0, MAX_RESULTS, undefined, query.trim());
        setResults(page.content);
        setOpen(true);
      } catch {
        setResults([]);
      } finally {
        setLoading(false);
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, []);

  if (selected) {
    return (
      <div className="flex items-center justify-between gap-3 px-3 py-2 bg-blue-50 border border-blue-200 rounded-md">
        <div className="text-sm text-blue-900 min-w-0 flex-1">
          <span className="font-medium">#{selected.applicationSeq}</span>
          <span className="ml-2 text-blue-700">— {selected.userEmail}</span>
          {selected.address && (
            <span className="ml-2 text-blue-600 truncate">— {selected.address}</span>
          )}
        </div>
        <button
          type="button"
          disabled={disabled}
          onClick={() => onSelect(null)}
          className="text-sm text-blue-700 hover:text-blue-900 disabled:opacity-50"
        >
          Clear
        </button>
      </div>
    );
  }

  return (
    <div ref={containerRef} className="relative">
      <Input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => results.length > 0 && setOpen(true)}
        placeholder="Search by application #, applicant name, email, or address…"
        disabled={disabled}
        autoComplete="off"
      />
      {open && (
        <div className="absolute z-20 mt-1 w-full bg-white border border-gray-200 rounded-md shadow-lg max-h-72 overflow-auto">
          {loading && <div className="px-3 py-2 text-sm text-gray-500">Searching…</div>}
          {!loading && results.length === 0 && (
            <div className="px-3 py-2 text-sm text-gray-500">No applications found</div>
          )}
          {!loading &&
            results.map((a) => (
              <button
                key={a.applicationSeq}
                type="button"
                onClick={() => {
                  onSelect(a);
                  setQuery('');
                  setResults([]);
                  setOpen(false);
                }}
                className="w-full text-left px-3 py-2 text-sm hover:bg-gray-50"
              >
                <div className="font-medium text-gray-800">#{a.applicationSeq} — {a.userEmail}</div>
                <div className="text-xs text-gray-500 truncate">
                  {a.userFirstName} {a.userLastName} · {a.address}
                </div>
              </button>
            ))}
        </div>
      )}
    </div>
  );
}
