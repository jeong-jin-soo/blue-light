package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.FileType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @DisplayName("POST upload-signature - acknowledgeReceipt=true + 정상 파일 → 201")
    void upload_acknowledged_201() throws Exception {
        when(loaService.uploadSignatureByManager(eq(MANAGER_SEQ), eq(42L), any(), anyString(), any()))
            .thenReturn(FileResponse.builder()
                .fileSeq(777L)
                .fileType(FileType.OWNER_AUTH_LETTER)
                .originalFilename("LOA_SIGNED_42.pdf")
                .build());

        mockMvc.perform(auth(
                MockMvcRequestBuilders.multipart("/api/admin/applications/42/loa/upload-signature")
                    .file(samplePng())
                    .param("acknowledgeReceipt", "true")
                    .param("memo", "email receipt")))
            .andExpect(status().isCreated());

        verify(loaService).uploadSignatureByManager(
            eq(MANAGER_SEQ), eq(42L), any(), eq("email receipt"), any());
    }

    @Test
    @DisplayName("POST upload-signature - acknowledgeReceipt=false → 400 ACKNOWLEDGEMENT_REQUIRED")
    void upload_notAcknowledged_400() throws Exception {
        mockMvc.perform(auth(
                MockMvcRequestBuilders.multipart("/api/admin/applications/42/loa/upload-signature")
                    .file(samplePng())
                    .param("acknowledgeReceipt", "false")))
            .andExpect(status().isBadRequest());

        verify(loaService, never()).uploadSignatureByManager(
            anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    @DisplayName("POST upload-signature - Service가 CONCIERGE_NOT_ASSIGNED 던지면 403")
    void upload_notAssigned_403() throws Exception {
        when(loaService.uploadSignatureByManager(anyLong(), anyLong(), any(), any(), any()))
            .thenThrow(new BusinessException("not assigned",
                HttpStatus.FORBIDDEN, "CONCIERGE_NOT_ASSIGNED"));

        mockMvc.perform(auth(
                MockMvcRequestBuilders.multipart("/api/admin/applications/42/loa/upload-signature")
                    .file(samplePng())
                    .param("acknowledgeReceipt", "true")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST upload-signature - Service가 LOA_ALREADY_SIGNED 던지면 400")
    void upload_alreadySigned_400() throws Exception {
        when(loaService.uploadSignatureByManager(anyLong(), anyLong(), any(), any(), any()))
            .thenThrow(new BusinessException("already signed",
                HttpStatus.BAD_REQUEST, "LOA_ALREADY_SIGNED"));

        mockMvc.perform(auth(
                MockMvcRequestBuilders.multipart("/api/admin/applications/42/loa/upload-signature")
                    .file(samplePng())
                    .param("acknowledgeReceipt", "true")))
            .andExpect(status().isBadRequest());
    }
}
