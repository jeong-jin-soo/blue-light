package com.bluelight.backend.api.admin.manualemail;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ADMIN 수동 이메일 발송 멱등성 해시 helper.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §6 AC-A9 + §8.3 (D3=B 멱등성).</p>
 *
 * <h3>왜 필요한가</h3>
 * <p>PR-1 은 단일 수신자 기준으로 (sender + recipientEmail + subject + bodyText) 정확 일치를
 * repository 쿼리로 비교했지만, PR-2 의 MULTI 발송에서는 수신자가 N 명일 수 있어 컬럼 단일 비교가
 * 불가능하다. 정렬된 수신자 리스트 + subject + bodyText 의 SHA-256 해시를 row 컬럼으로 미리
 * 저장해 두면 단일 컬럼 동등성 비교만으로 멱등 판정이 가능하다 (인덱스 활용 가능).</p>
 *
 * <h3>해시 입력 정규화</h3>
 * <ol>
 *   <li>모든 수신자 이메일을 소문자 + trim 으로 정규화 (대소문자/공백 차이로 해시가 갈리지 않도록).</li>
 *   <li>정규화된 이메일을 알파벳 정렬 (수신자 순서 차이로 해시가 갈리지 않도록).</li>
 *   <li>"," 로 join 한 수신자 문자열 + "" + subject + "" + bodyText 구성.</li>
 *   <li>UTF-8 바이트로 SHA-256 → hex 64자.</li>
 * </ol>
 *
 * <p>Unit separator (US, U+001F) 를 구분자로 사용해 본문에 ","/구분자가 있어도 해시 충돌이 발생하지
 * 않도록 한다 — 일반 문자가 아니라 ASCII 제어문자.</p>
 */
@Slf4j
public final class ManualEmailRecipientHasher {

    /** 해시 입력 필드 구분자 — ASCII 제어문자 Unit Separator. 본문/제목에 등장 가능성 사실상 0. */
    private static final String SEPARATOR = "";

    private ManualEmailRecipientHasher() {}

    /**
     * 수신자 이메일 리스트 + subject + bodyText 의 멱등성 해시를 계산.
     *
     * @param recipientEmails 수신자 이메일 (단일/다수 무관). null/빈 리스트 → "[]" 로 처리.
     * @param subject         메일 제목 (null → "")
     * @param bodyText        메일 본문 (null → "")
     * @return SHA-256 hex (소문자 64자)
     */
    public static String hashOf(List<String> recipientEmails, String subject, String bodyText) {
        String recipients = normalizeRecipients(recipientEmails);
        String input = recipients
                + SEPARATOR
                + Objects.toString(subject, "")
                + SEPARATOR
                + Objects.toString(bodyText, "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 — 발생 불가. 안전망으로 변환 없는 fallback.
            log.error("SHA-256 unavailable — should never happen", e);
            return "noalg-" + Math.abs(input.hashCode());
        }
    }

    private static String normalizeRecipients(List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) return "[]";
        return recipients.stream()
                .filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .sorted()
                .distinct()
                .collect(Collectors.joining(","));
    }
}
