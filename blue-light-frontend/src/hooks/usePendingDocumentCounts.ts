import { useEffect, useState } from 'react';
import documentApi from '../api/documentApi';

/**
 * Phase 3 PR#3 — 신청 목록/대시보드의 "awaiting N" 배지용 훅 (AC-AU3)
 *
 * 각 applicationSeq에 대해 DocumentRequest 목록을 병렬 조회하고,
 * REQUESTED 또는 REJECTED 상태의 요청 건수를 집계한다.
 *
 * 설계 노트:
 * - 목록 API에 집계값이 없어 per-row 조회가 필요하지만, 목록은 보통 ≤ 20건이며
 *   요청 자체도 캐시 친화적이라 overhead 미미.
 * - 실패 시 조용히 0으로 폴백 (배지 미노출).
 * - applicationSeqs가 빈 배열이면 즉시 빈 map 반환.
 */
export function usePendingDocumentCounts(
  applicationSeqs: number[],
  enabled = true,
): Record<number, number> {
  const [counts, setCounts] = useState<Record<number, number>>({});

  // 안정적인 key (정렬된 seqs)
  const key = applicationSeqs.slice().sort((a, b) => a - b).join(',');

  useEffect(() => {
    if (!enabled || applicationSeqs.length === 0) {
      setCounts({});
      return;
    }

    let cancelled = false;
    (async () => {
      try {
        const results = await Promise.all(
          applicationSeqs.map(async (seq) => {
            try {
              const reqs = await documentApi.getDocumentRequests(seq);
              const pending = reqs.filter(
                (r) => r.status === 'REQUESTED' || r.status === 'REJECTED',
              ).length;
              return [seq, pending] as const;
            } catch {
              return [seq, 0] as const;
            }
          }),
        );
        if (cancelled) return;
        const next: Record<number, number> = {};
        for (const [seq, count] of results) next[seq] = count;
        setCounts(next);
      } catch {
        if (!cancelled) setCounts({});
      }
    })();
    return () => {
      cancelled = true;
    };
    // key 의존성으로 안정화
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled]);

  return counts;
}
