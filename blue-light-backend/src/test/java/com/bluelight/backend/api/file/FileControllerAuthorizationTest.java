package com.bluelight.backend.api.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 #2 회귀 방지 — admin 파일 업로드(uploadFileAsAdmin)의 cross-tenant 가드 검증.
 * <p>
 * 배정 LEW만 자기 신청에 업로드 가능해야 한다(미배정 LEW가 타 신청의 LICENSE_PDF/LOA_FINAL 등
 * 게이트 산출물을 위조하는 것을 차단). 경로변수가 {@code applicationId} 이므로 SpEL 은
 * {@code @appSec.isAssignedLew(#applicationId, authentication)} 를 사용한다.
 * 빈/SpEL 동작 자체는 {@link com.bluelight.backend.common.security.AppSecurityTest} 가 검증.
 */
@DisplayName("FileController @PreAuthorize cross-tenant 가드 검증")
class FileControllerAuthorizationTest {

    @Test
    @DisplayName("uploadFileAsAdmin — ADMIN/SYSTEM_ADMIN bypass + isAssignedLew(#applicationId) 가드")
    void uploadFileAsAdmin_hasAssignedLewGuard() {
        Method method = Arrays.stream(FileController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("uploadFileAsAdmin"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("uploadFileAsAdmin not found"));

        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("uploadFileAsAdmin 에 @PreAuthorize 필요")
                .isNotNull();

        String value = annotation.value().replaceAll("\\s+", "");
        assertThat(value)
                .as("ADMIN/SYSTEM_ADMIN bypass 포함")
                .contains("hasAnyRole('ADMIN','SYSTEM_ADMIN')");
        assertThat(value)
                .as("배정 LEW 가드(@appSec.isAssignedLew(#applicationId)) 포함")
                .contains("@appSec.isAssignedLew(#applicationId,authentication)");
        // role-only 가드(누구나 LEW면 통과)로 회귀하지 않았는지 — 단독 hasAnyRole('ADMIN','LEW') 금지
        assertThat(value)
                .as("role-only LEW 허용으로 회귀 금지")
                .doesNotContain("hasAnyRole('ADMIN','LEW')");
    }
}
