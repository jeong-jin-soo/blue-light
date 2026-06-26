package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.LicenseParseResponse;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLConnection;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 라이선스 PDF 파싱 서비스 — 업로드된 LICENSE_PDF 를 AI 서비스(blue-light-ai)로 보내
 * 라이선스 번호·발급일·만료일을 추출한다. SLD AI 호출과 동일한 WebClient(X-Service-Key) 사용.
 *
 * <p>추출값은 프론트에서 LEW 가 검토·수정할 프리필일 뿐, 발급(완료)은 별도 폼 제출로 이뤄진다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseParseService {

    private final WebClient sldAgentWebClient;
    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;

    /**
     * 신청의 최신 LICENSE_PDF 를 AI 서비스로 파싱한다.
     *
     * @throws BusinessException LICENSE_PDF 없음(LICENSE_PDF_MISSING) / 파싱 실패(LICENSE_PARSE_FAILED)
     */
    public LicenseParseResponse parseLatestLicense(Long applicationSeq) {
        FileEntity license = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.LICENSE_PDF)
                .stream()
                .max(Comparator.comparing(FileEntity::getFileSeq))
                .orElseThrow(() -> new BusinessException(
                        "Upload the licence PDF before parsing",
                        HttpStatus.BAD_REQUEST, "LICENSE_PDF_MISSING"));

        byte[] bytes;
        try {
            bytes = fileStorageService.loadAsResource(license.getFileUrl())
                    .getInputStream().readAllBytes();
        } catch (Exception e) {
            log.warn("Licence parse: file read failed applicationSeq={}, err={}", applicationSeq, e.getMessage());
            throw new BusinessException("Failed to read the licence file",
                    HttpStatus.INTERNAL_SERVER_ERROR, "LICENSE_FILE_READ_FAILED");
        }

        String filename = license.getOriginalFilename() != null ? license.getOriginalFilename() : "license.pdf";
        String mimeType = URLConnection.guessContentTypeFromName(filename);
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "application/pdf";
        }

        Map<String, Object> body = Map.of(
                "attached_file", Map.of(
                        "filename", filename,
                        "content_base64", Base64.getEncoder().encodeToString(bytes),
                        "mime_type", mimeType));

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = sldAgentWebClient.post()
                    .uri("/api/license/parse")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(60));

            if (result == null) {
                throw new BusinessException("Empty response from licence parser",
                        HttpStatus.BAD_GATEWAY, "LICENSE_PARSE_FAILED");
            }
            return LicenseParseResponse.builder()
                    .licenseNumber(asString(result.get("license_number")))
                    .issueDate(asString(result.get("issue_date")))
                    .expiryDate(asString(result.get("expiry_date")))
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Licence parse: AI call failed applicationSeq={}, err={}", applicationSeq, e.getMessage());
            throw new BusinessException("Failed to parse the licence document",
                    HttpStatus.BAD_GATEWAY, "LICENSE_PARSE_FAILED");
        }
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
