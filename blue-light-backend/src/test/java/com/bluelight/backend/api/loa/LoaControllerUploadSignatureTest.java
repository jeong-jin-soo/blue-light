package com.bluelight.backend.api.loa;

import com.bluelight.backend.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LoaController.uploadLoaSignature 웹 레이어 테스트 (★ Phase 1 PR#6 Stage A).
 */
@DisplayName("LoaController.uploadLoaSignature - PR#6 Stage A")
class LoaControllerUploadSignatureTest {

    private static final long MANAGER_SEQ = 10L;
    private LoaService loaService;
    private MockMvc mockMvc;

    private HandlerExceptionResolver resolver() {
        return new ExceptionHandlerExceptionResolver() {
            @Override
            public org.springframework.web.servlet.ModelAndView resolveException(
                HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception ex) {
                if (ex instanceof BusinessException be) {
                    response.setStatus(be.getStatus().value());
                    return new org.springframework.web.servlet.ModelAndView();
                }
                return null;
            }
        };
    }

    private Authentication managerAuth() {
        return new UsernamePasswordAuthenticationToken(
            MANAGER_SEQ, null,
            List.of(new SimpleGrantedAuthority("ROLE_CONCIERGE_MANAGER")));
    }

    /**
     * multipart 빌더는 {@code MockMultipartHttpServletRequestBuilder}이므로
     * 공통 부모 타입({@code MockHttpServletRequestBuilder})으로 변환되지 않도록 구체 타입 유지.
     */
    private MockMultipartHttpServletRequestBuilder auth(MockMultipartHttpServletRequestBuilder builder) {
        builder.principal(managerAuth());
        return builder;
    }

    @BeforeEach
    void setUp() {
        loaService = mock(LoaService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new LoaController(loaService))
            .setHandlerExceptionResolvers(resolver())
            .build();
    }

    private MockMultipartFile samplePng() {
        return new MockMultipartFile(
            "signature", "sig.png", "image/png",
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }

    // 전자서명 기능 비활성화 (보안 이슈 — 2026-06-13).
    // 엔드포인트는 acknowledgeReceipt 값/서비스 로직에 도달하기 전에 SIGNATURE_DISABLED(403)로 차단된다.
    // 서비스 로직(uploadSignatureByManager) 자체의 검증은 LoaServiceUploadSignatureTest 가 계속 보장한다.

    @Test
    @DisplayName("POST upload-signature - 서명 비활성화 → 403, 서비스 미호출")
    void upload_signatureDisabled_403() throws Exception {
        mockMvc.perform(auth(
                MockMvcRequestBuilders.multipart("/api/admin/applications/42/loa/upload-signature")
                    .file(samplePng())
                    .param("acknowledgeReceipt", "true")
                    .param("memo", "email receipt")))
            .andExpect(status().isForbidden());

        verify(loaService, never()).uploadSignatureByManager(
            anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("POST upload-signature - acknowledgeReceipt=false 여도 동일하게 403 차단")
    void upload_signatureDisabled_evenWithoutAck_403() throws Exception {
        mockMvc.perform(auth(
                MockMvcRequestBuilders.multipart("/api/admin/applications/42/loa/upload-signature")
                    .file(samplePng())
                    .param("acknowledgeReceipt", "false")))
            .andExpect(status().isForbidden());

        verify(loaService, never()).uploadSignatureByManager(
            anyLong(), anyLong(), any(), any(), any());
    }
}
