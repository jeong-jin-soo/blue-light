package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 PR#1 — B-3 MIME 검증 단위 테스트
 */
class MimeTypeValidatorTest {

    // ----- accepted MIME 파싱 -----

    @Test
    void parseAcceptedMime_정상_csv를_set으로_변환하고_trim_및_소문자화() {
        Set<String> set = MimeTypeValidator.parseAcceptedMime("application/PDF, image/png ,image/jpeg");
        assertThat(set).containsExactlyInAnyOrder("application/pdf", "image/png", "image/jpeg");
    }

    @Test
    void parseAcceptedMime_빈_입력은_빈_set() {
        assertThat(MimeTypeValidator.parseAcceptedMime(null)).isEmpty();
        assertThat(MimeTypeValidator.parseAcceptedMime("")).isEmpty();
        assertThat(MimeTypeValidator.parseAcceptedMime("   ")).isEmpty();
    }

    // ----- 정상 케이스 -----

    @Test
    void validate_PDF_파일은_application_pdf_화이트리스트와_매칭() {
        // Minimal PDF magic bytes
        byte[] pdf = "%PDF-1.4\n%âãÏÓ\n".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdf);
        // 예외 없이 통과
        MimeTypeValidator.validate(file, "application/pdf,image/png");
    }

    @Test
    void validate_PNG_매직바이트_화이트리스트_매칭() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", png);
        MimeTypeValidator.validate(file, "image/png,image/jpeg");
    }

    // ----- 거부 케이스 -----

    @Test
    void validate_화이트리스트에_없는_MIME은_INVALID_FILE_TYPE_400() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", png);
        assertThatThrownBy(() -> MimeTypeValidator.validate(file, "application/pdf"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("INVALID_FILE_TYPE"));
    }

    @Test
    void validate_빈_파일은_EMPTY_FILE_400() {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> MimeTypeValidator.validate(file, "application/pdf"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("EMPTY_FILE"));
    }

    @Test
    void validate_선언_MIME과_시그니처_불일치는_INVALID_FILE_TYPE_400() {
        // 선언은 application/pdf인데 실제 내용은 PNG → MIME 위장
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf", png);
        assertThatThrownBy(() -> MimeTypeValidator.validate(file, "application/pdf,image/png"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("INVALID_FILE_TYPE"));
    }

    // ----- size -----

    @Test
    void validateSize_초과시_FILE_TOO_LARGE_400() {
        // 2MB 파일을 1MB 한도로 검증
        byte[] big = new byte[2 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", big);
        assertThatThrownBy(() -> MimeTypeValidator.validateSize(file, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("FILE_TOO_LARGE"));
    }

    @Test
    void validateSize_경계_정확히_max는_허용() {
        byte[] data = new byte[1024 * 1024]; // 정확히 1MB
        MockMultipartFile file = new MockMultipartFile("file", "ok.pdf", "application/pdf", data);
        MimeTypeValidator.validateSize(file, 1); // 예외 없음
    }
}
