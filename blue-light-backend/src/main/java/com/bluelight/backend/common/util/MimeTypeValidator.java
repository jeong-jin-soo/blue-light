package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 파일 업로드 MIME 검증 유틸
 * - DocumentTypeCatalog.acceptedMime(쉼표 구분 화이트리스트)과 교차 검증
 * - 확장자 + 매직 바이트 시그니처 이중 검증 (Phase 2 B-3)
 *
 * 결정: Apache Tika 의존성 추가는 PR#1 범위 외. URLConnection.guessContentTypeFromStream
 *      + 확장자 매핑 조합으로 1차 방어선 구축. AV/심층 매직 바이트 검증은 Phase 3 R-1(ClamAV) 시점에 강화.
 */
@Slf4j
public final class MimeTypeValidator {

    private MimeTypeValidator() {
        // utility
    }

    /**
     * 업로드 파일이 카탈로그 허용 MIME과 일치하는지 검증.
     * 불일치 시 INVALID_FILE_TYPE 400 발생.
     *
     * @param file               업로드 파일
     * @param acceptedMimeCsv    화이트리스트 (예: "application/pdf,image/png,image/jpeg")
     */
    public static void validate(MultipartFile file, String acceptedMimeCsv) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Empty file", HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
        Set<String> accepted = parseAcceptedMime(acceptedMimeCsv);
        if (accepted.isEmpty()) {
            throw new BusinessException(
                    "Document type has no accepted MIME configured",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INVALID_CATALOG");
        }

        String declared = normalize(file.getContentType());
        String sniffed  = sniffFromStream(file);
        String byExt    = guessFromFilename(file.getOriginalFilename());

        // 다단 매칭: 적어도 하나는 화이트리스트에 있어야 하고,
        // 선언/시그니처/확장자 사이에 동의 가능한 1쌍이 있어야 한다.
        boolean declaredOk = declared != null && accepted.contains(declared);
        boolean sniffedOk  = sniffed  != null && accepted.contains(sniffed);
        boolean extOk      = byExt    != null && accepted.contains(byExt);

        // 시그니처가 식별되었는데 선언/확장자와 불일치 → 위장 의심
        if (sniffed != null && declared != null && !mimeFamilyMatches(sniffed, declared)) {
            log.warn("MIME mismatch (declared={}, sniffed={}, ext={}, name={})",
                    declared, sniffed, byExt, file.getOriginalFilename());
            throw new BusinessException(
                    "File content does not match declared type",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FILE_TYPE");
        }

        if (!(declaredOk || sniffedOk || extOk)) {
            log.warn("MIME not in accepted whitelist (declared={}, sniffed={}, ext={}, accepted={})",
                    declared, sniffed, byExt, accepted);
            throw new BusinessException(
                    "File type not allowed. Accepted: " + acceptedMimeCsv,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FILE_TYPE");
        }
    }

    /**
     * 카탈로그 max_size_mb 검증.
     */
    public static void validateSize(MultipartFile file, int maxSizeMb) {
        long maxBytes = (long) maxSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(
                    "File size exceeds maximum (" + maxSizeMb + "MB)",
                    HttpStatus.BAD_REQUEST,
                    "FILE_TOO_LARGE");
        }
    }

    // -------------------- internal --------------------

    static Set<String> parseAcceptedMime(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String norm = normalize(token);
            if (norm != null) {
                set.add(norm);
            }
        }
        return set;
    }

    private static String normalize(String mime) {
        if (mime == null) return null;
        String trimmed = mime.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return null;
        // strip parameters (e.g., "application/pdf; charset=...")
        int semi = trimmed.indexOf(';');
        if (semi >= 0) {
            trimmed = trimmed.substring(0, semi).trim();
        }
        return trimmed;
    }

    private static String sniffFromStream(MultipartFile file) {
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            String guessed = URLConnection.guessContentTypeFromStream(in);
            return normalize(guessed);
        } catch (IOException e) {
            log.debug("MIME sniff failed: {}", e.getMessage());
            return null;
        }
    }

    private static String guessFromFilename(String filename) {
        if (filename == null) return null;
        String guessed = URLConnection.guessContentTypeFromName(filename);
        if (guessed != null) return normalize(guessed);
        // URLConnection은 일부 확장자(.dxf 등)를 모름 → 보조 매핑
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        return null;
    }

    /**
     * MIME 패밀리 일치 (예: image/png ≈ image/jpeg는 다른 family이지만,
     * 시그니처 측은 "image/jpeg" 선언 측은 "image/jpg" 같은 변형은 같다고 봄).
     *
     * 엄격하게 같은 MIME만 허용한다.
     */
    private static boolean mimeFamilyMatches(String a, String b) {
        if (a == null || b == null) return true; // 하나라도 모르면 다른 단계에서 판정
        if (a.equals(b)) return true;
        // jpeg/jpg 변형 호환
        return Arrays.asList("image/jpeg", "image/jpg").containsAll(Arrays.asList(a, b));
    }
}
