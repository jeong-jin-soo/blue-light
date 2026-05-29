package com.bluelight.backend.api.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드 부채 P0 단일화 검증 — Admin 계열 컨트롤러의 LEW cross-tenant 가드는
 * 메서드 레벨 {@code @PreAuthorize} 의 SpEL 빈 호출
 * {@code @appSec.isAssignedLew(#id, authentication)} 으로 단일화되었는지 리플렉션으로 확인.
 *
 * <p>이전 PR-T8 / L-3 의 서비스 단 가드는 제거됐고 단일 소스는 컨트롤러 어노테이션이다.
 * 어노테이션 누락 / SpEL 오타는 컴파일 시 잡히지 않으므로, 본 테스트가 정적 검증 역할을 한다.
 * 빈/SpEL 동작 자체는 {@link com.bluelight.backend.common.security.AppSecurityTest} 가 검증.</p>
 */
@DisplayName("Admin 컨트롤러 SpEL @PreAuthorize 단일화 검증")
class AdminSpELAuthorizationTest {

    private static final String EXPECTED_SPEL_FRAGMENT = "@appSec.isAssignedLew(#id, authentication)";
    private static final String EXPECTED_ADMIN_BYPASS = "hasAnyRole('ADMIN','SYSTEM_ADMIN')";

    static Stream<Arguments> guardedMethods() {
        return Stream.of(
                // AdminApplicationController — 6 methods (2 read + 4 mutate)
                Arguments.of(AdminApplicationController.class, "getApplication"),
                Arguments.of(AdminApplicationController.class, "updateStatus"),
                Arguments.of(AdminApplicationController.class, "completeApplication"),
                Arguments.of(AdminApplicationController.class, "requestRevision"),
                Arguments.of(AdminApplicationController.class, "approveForPayment"),
                Arguments.of(AdminApplicationController.class, "getPayments"),
                // AdminSldController — 4 methods
                Arguments.of(AdminSldController.class, "getAdminSldRequest"),
                Arguments.of(AdminSldController.class, "uploadSld"),
                Arguments.of(AdminSldController.class, "confirmSld"),
                Arguments.of(AdminSldController.class, "unconfirmSld"),
                // SldChatController — 6 methods
                Arguments.of(SldChatController.class, "chatStream"),
                Arguments.of(SldChatController.class, "getChatHistory"),
                Arguments.of(SldChatController.class, "resetChat"),
                Arguments.of(SldChatController.class, "acceptSld"),
                Arguments.of(SldChatController.class, "getSvgPreview"),
                Arguments.of(SldChatController.class, "downloadGeneratedFile")
        );
    }

    @ParameterizedTest(name = "{0}.{1}() 메서드에 @PreAuthorize SpEL 가드 부착")
    @MethodSource("guardedMethods")
    void shouldHaveSpELGuardAnnotation(Class<?> controller, String methodName) {
        Method method = findMethod(controller, methodName);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation)
                .as("%s.%s 에 @PreAuthorize 가 부착되어야 함", controller.getSimpleName(), methodName)
                .isNotNull();

        String value = annotation.value().replaceAll("\\s+", "");
        String expectedAdminBypass = EXPECTED_ADMIN_BYPASS.replaceAll("\\s+", "");
        String expectedSpEL = EXPECTED_SPEL_FRAGMENT.replaceAll("\\s+", "");

        assertThat(value)
                .as("%s.%s 의 SpEL 은 ADMIN/SYSTEM_ADMIN bypass 를 포함해야 함",
                        controller.getSimpleName(), methodName)
                .contains(expectedAdminBypass);
        assertThat(value)
                .as("%s.%s 의 SpEL 은 @appSec.isAssignedLew(#id, authentication) 을 포함해야 함",
                        controller.getSimpleName(), methodName)
                .contains(expectedSpEL);
    }

    private Method findMethod(Class<?> controller, String name) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        controller.getSimpleName() + "." + name + " not found"));
    }
}
