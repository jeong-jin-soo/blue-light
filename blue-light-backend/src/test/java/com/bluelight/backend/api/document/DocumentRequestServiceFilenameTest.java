package com.bluelight.backend.api.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sanitizeFilename 동작 검증 (B-3 §2.2-3 — HTTP response splitting 방지)
 */
class DocumentRequestServiceFilenameTest {

    @Test
    void 제어문자_및_CRLF는_언더스코어로_치환() {
        assertThat(DocumentRequestService.sanitizeFilename("a\rb\nc.pdf"))
                .isEqualTo("a_b_c.pdf");
    }

    @Test
    void 슬래시_역슬래시는_제거되고_basename만_남김() {
        assertThat(DocumentRequestService.sanitizeFilename("../../etc/passwd"))
                .isEqualTo("passwd");
        assertThat(DocumentRequestService.sanitizeFilename("C:\\Windows\\evil.exe"))
                .isEqualTo("evil.exe");
    }

    @Test
    void null과_빈_문자열_안전_처리() {
        assertThat(DocumentRequestService.sanitizeFilename(null)).isNull();
        assertThat(DocumentRequestService.sanitizeFilename("   ")).isNull();
    }

    @Test
    void 정상_파일명은_그대로_보존() {
        assertThat(DocumentRequestService.sanitizeFilename("breaker_photo.jpg"))
                .isEqualTo("breaker_photo.jpg");
    }
}
