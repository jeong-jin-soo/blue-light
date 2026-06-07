package com.bluelight.backend.api.admin.notification.template.dto;

import java.util.List;

/**
 * PR-T7 P1 — XLIFF/CSV 임포트 결과 리포트.
 *
 * <p>운영자가 결과를 즉시 확인하고 실패 행을 수정 후 재업로드 할 수 있게,
 * 행 단위 결과 + 사유를 함께 반환한다.</p>
 *
 * @param locale        target locale (예: "ko")
 * @param format        업로드 파일 포맷
 * @param totalRows     처리 시도한 (code, channel) pair 수
 * @param draftsCreated 새 draft 로 생성된 수 (status=PENDING)
 * @param skipped       기존 row 없음·중복 등 사유로 건너뛴 수
 * @param failed        파싱·검증 실패 수
 * @param items         행별 상세 결과
 */
public record ImportReportResponse(
        String locale,
        LocalizationFormat format,
        int totalRows,
        int draftsCreated,
        int skipped,
        int failed,
        List<Item> items
) {

    public enum ItemStatus { CREATED, SKIPPED, FAILED }

    public record Item(
            String templateCode,
            String channel,
            ItemStatus status,
            String reason,    // FAILED/SKIPPED 일 때만 메시지
            Long draftSeq     // CREATED 일 때만 채워짐
    ) {
        public static Item created(String code, String channel, Long draftSeq) {
            return new Item(code, channel, ItemStatus.CREATED, null, draftSeq);
        }
        public static Item skipped(String code, String channel, String reason) {
            return new Item(code, channel, ItemStatus.SKIPPED, reason, null);
        }
        public static Item failed(String code, String channel, String reason) {
            return new Item(code, channel, ItemStatus.FAILED, reason, null);
        }
    }
}
