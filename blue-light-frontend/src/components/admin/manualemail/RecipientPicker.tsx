import { useEffect, useRef, useState } from 'react';
import { Input } from '../../ui/Input';
import { getUsers } from '../../../api/adminUserApi';
import type { User, UserRole } from '../../../types';

/**
 * 시스템 사용자(APPLICANT/LEW) 검색 autocomplete.
 *
 * <p>스펙: doc/Project Analysis/admin-manual-email-spec.md §7.2.1.</p>
 *
 * <ul>
 *   <li>이메일/이름 검색 — {@code GET /api/admin/users?role=APPLICANT&search=...}.</li>
 *   <li>300ms 디바운스 후 호출, 최대 8건 표시.</li>
 *   <li>이미 추가된 user_seq 는 결과에서 제외.</li>
 *   <li>키보드: ↑/↓ 이동, Enter 선택, Esc 취소.</li>
 * </ul>
 */

interface SystemUserPickerProps {
  /** 단일 role 필터 ('APPLICANT' / 'LEW') 또는 null (MULTI 시 양쪽 모두 검색). */
  roleFilter: UserRole | null;
  /** 이미 선택된 user_seq — 검색 결과에서 제외. */
  excludeUserSeqs: number[];
  onSelect: (user: User) => void;
  placeholder?: string;
  disabled?: boolean;
}

const SEARCH_DEBOUNCE_MS = 300;
const MAX_RESULTS = 8;

export function SystemUserPicker({
  roleFilter,
  excludeUserSeqs,
  onSelect,
  placeholder = 'Search by email or name…',
  disabled = false,
}: SystemUserPickerProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<User[]>([]);
  const [activeIdx, setActiveIdx] = useState(-1);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // 디바운스 검색
  useEffect(() => {
    if (!query.trim() || query.trim().length < 2) {
      setResults([]);
      setOpen(false);
      return;
    }
    const timer = setTimeout(async () => {
      setLoading(true);
      try {
        if (roleFilter) {
          // 단일 role 검색
          const page = await getUsers(0, 20, roleFilter, query.trim());
          const filtered = page.content.filter((u) => !excludeUserSeqs.includes(u.userSeq)).slice(0, MAX_RESULTS);
          setResults(filtered);
        } else {
          // MULTI: APPLICANT 와 LEW 양쪽 호출 후 합쳐서 반환
          const [appPage, lewPage] = await Promise.all([
            getUsers(0, 10, 'APPLICANT', query.trim()),
            getUsers(0, 10, 'LEW', query.trim()),
          ]);
          const merged = [...appPage.content, ...lewPage.content]
            .filter((u) => !excludeUserSeqs.includes(u.userSeq))
            .slice(0, MAX_RESULTS);
          setResults(merged);
        }
        setOpen(true);
        setActiveIdx(-1);
      } catch {
        setResults([]);
      } finally {
        setLoading(false);
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query, roleFilter, excludeUserSeqs]);

  // 외부 클릭 시 닫기
  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, []);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!open || results.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIdx((i) => Math.min(i + 1, results.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIdx((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const idx = activeIdx >= 0 ? activeIdx : 0;
      const target = results[idx];
      if (target) {
        onSelect(target);
        setQuery('');
        setResults([]);
        setOpen(false);
      }
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const fullName = (u: User) => [u.firstName, u.lastName].filter(Boolean).join(' ');

  return (
    <div ref={containerRef} className="relative">
      <Input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => results.length > 0 && setOpen(true)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        disabled={disabled}
        autoComplete="off"
      />
      {open && (
        <div className="absolute z-20 mt-1 w-full bg-white border border-gray-200 rounded-md shadow-lg max-h-72 overflow-auto">
          {loading && <div className="px-3 py-2 text-sm text-gray-500">Searching…</div>}
          {!loading && results.length === 0 && (
            <div className="px-3 py-2 text-sm text-gray-500">
              {query.trim().length < 2 ? 'Type at least 2 characters' : 'No users found'}
            </div>
          )}
          {!loading &&
            results.map((u, i) => (
              <button
                key={u.userSeq}
                type="button"
                onClick={() => {
                  onSelect(u);
                  setQuery('');
                  setResults([]);
                  setOpen(false);
                }}
                onMouseEnter={() => setActiveIdx(i)}
                className={`w-full text-left px-3 py-2 text-sm transition-colors ${
                  i === activeIdx ? 'bg-blue-50' : 'hover:bg-gray-50'
                }`}
              >
                <div className="font-medium text-gray-800 truncate">{u.email}</div>
                <div className="text-xs text-gray-500 truncate">
                  {fullName(u) || '(no name)'}
                  <span className="ml-2 inline-block px-1.5 py-0.5 bg-gray-100 text-gray-700 rounded text-[10px]">
                    {u.role}
                  </span>
                  {u.role === 'LEW' && u.lewGrade && (
                    <span className="ml-1 text-gray-400">{u.lewGrade.replace('GRADE_', 'Grade ')}</span>
                  )}
                </div>
              </button>
            ))}
        </div>
      )}
    </div>
  );
}
