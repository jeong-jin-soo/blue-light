package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.concierge.dto.CancelRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestDetail;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestSummary;
import com.bluelight.backend.api.concierge.dto.NoteAddRequest;
import com.bluelight.backend.api.concierge.dto.NoteResponse;
import com.bluelight.backend.api.concierge.dto.StatusTransitionRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.concierge.NoteChannel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ConciergeManagerController 웹 레이어 테스트 (★ Phase 1 PR#4 Stage A).
 * Standalone MockMvc (Spring 컨텍스트/DB 의존 없음).
 * Authentication은 SecurityContextHolder에 미리 주입하여 컨트롤러의 {@code (Long) auth.getPrincipal()}
 * 패턴이 userSeq=42L를 반환하도록 한다.
 */
@DisplayName("ConciergeManagerController - PR#4 Stage A")
class ConciergeManagerControllerTest {

    private ConciergeManagerService managerService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final long ACTOR_SEQ = 42L;

    private HandlerExceptionResolver globalResolver() {
        return new ExceptionHandlerExceptionResolver() {
            @Override
            public org.springframework.web.servlet.ModelAndView resolveException(
                HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception ex) {
                if (ex instanceof BusinessException be) {
                    response.setStatus(be.getStatus().value());
                    return new org.springframework.web.servlet.ModelAndView();
                }
                if (ex instanceof org.springframework.web.bind.MethodArgumentNotValidException) {
                    response.setStatus(HttpStatus.BAD_REQUEST.value());
                    return new org.springframework.web.servlet.ModelAndView();
                }
                return null;
            }
        };
    }

    /** principal=ACTOR_SEQ인 Authentication 헬퍼 */
    private Authentication actorAuth() {
        return new UsernamePasswordAuthenticationToken(
            ACTOR_SEQ, null,
            List.of(new SimpleGrantedAuthority("ROLE_CONCIERGE_MANAGER")));
    }

    /**
     * 표준 MockMvcRequestBuilders의 .principal()로 Authentication을 주입.
     * Spring MVC의 ServletRequestMethodArgumentResolver가 {@code Authentication}
     * 파라미터 타입을 request.getUserPrincipal()에서 resolve할 때
     * UsernamePasswordAuthenticationToken이 Authentication 구현이므로 그대로 매칭됨.
     */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.principal(actorAuth());
    }

    @BeforeEach
    void setUp() {
        managerService = mock(ConciergeManagerService.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();

        mockMvc = MockMvcBuilders
            .standaloneSetup(new ConciergeManagerController(managerService))
            .setHandlerExceptionResolvers(globalResolver())
            .build();

        // SecurityContextHolder 세팅 (기본 safety net)
        SecurityContextHolder.getContext().setAuthentication(actorAuth());
    }

    private ConciergeRequestSummary sampleSummary() {
        return ConciergeRequestSummary.builder()
            .conciergeRequestSeq(100L)
            .publicCode("C-2026-0100")
            .submitterName("Tan")
            .submitterEmail("tan@example.com")
            .submitterPhone("+6591234567")
            .status("ASSIGNED")
            .slaBreached(false)
            .assignedManagerSeq(ACTOR_SEQ)
            .assignedManagerName("Concierge Manager")
            .applicantUserStatus("PENDING_ACTIVATION")
            .createdAt(LocalDateTime.now())
            .build();
    }

    private ConciergeRequestDetail sampleDetail() {
        return ConciergeRequestDetail.builder()
            .conciergeRequestSeq(100L)
            .publicCode("C-2026-0100")
            .submitterName("Tan")
            .submitterEmail("tan@example.com")
            .submitterPhone("+6591234567")
            .status("ASSIGNED")
            .slaBreached(false)
            .assignedManagerSeq(ACTOR_SEQ)
            .assignedManagerName("Concierge Manager")
            .createdAt(LocalDateTime.now())
            .memo("memo")
            .marketingOptIn(false)
            .notes(List.of())
            .build();
    }

    // ============================================================
    // GET /api/concierge-manager/requests
    // ============================================================

    @Test
    @DisplayName("GET list - 200 + 페이지 응답")
    void list_200() throws Exception {
        // Spring Data PageImpl(1-arg)은 pageable=Unpaged를 쓰는데 Jackson 직렬화 시
        // Unpaged.getOffset()이 UnsupportedOperation을 던지므로 3-arg 생성자 사용
        List<ConciergeRequestSummary> content = List.of(sampleSummary());
        Page<ConciergeRequestSummary> page = new PageImpl<>(content, PageRequest.of(0, 20), content.size());
        when(managerService.listForActor(eq(ACTOR_SEQ), any(), any(), eq(0), eq(20)))
            .thenReturn(page);

        mockMvc.perform(auth(get("/api/concierge-manager/requests")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].publicCode").value("C-2026-0100"))
            .andExpect(jsonPath("$.content[0].status").value("ASSIGNED"));
    }

    @Test
    @DisplayName("GET list - status/q 쿼리 파라미터 전달")
    void list_withFilters() throws Exception {
        Page<ConciergeRequestSummary> empty =
            new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(managerService.listForActor(eq(ACTOR_SEQ), eq("CONTACTING"), eq("tan"), eq(0), eq(20)))
            .thenReturn(empty);

        mockMvc.perform(auth(get("/api/concierge-manager/requests"))
                .param("status", "CONTACTING")
                .param("q", "tan"))
            .andExpect(status().isOk());
    }

    // ============================================================
    // GET /api/concierge-manager/requests/{id}
    // ============================================================

    @Test
    @DisplayName("GET detail - 200")
    void detail_200() throws Exception {
        when(managerService.getDetail(100L, ACTOR_SEQ)).thenReturn(sampleDetail());

        mockMvc.perform(auth(get("/api/concierge-manager/requests/100")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conciergeRequestSeq").value(100))
            .andExpect(jsonPath("$.publicCode").value("C-2026-0100"));
    }

    @Test
    @DisplayName("GET detail - NOT_FOUND이면 404")
    void detail_404() throws Exception {
        when(managerService.getDetail(eq(999L), anyLong()))
            .thenThrow(new BusinessException("Not found",
                HttpStatus.NOT_FOUND, "NOT_FOUND"));

        mockMvc.perform(auth(get("/api/concierge-manager/requests/999")))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET detail - 타 매니저 접근 시 403 CONCIERGE_NOT_ASSIGNED")
    void detail_forbidden() throws Exception {
        when(managerService.getDetail(eq(100L), anyLong()))
            .thenThrow(new BusinessException("not assigned",
                HttpStatus.FORBIDDEN, "CONCIERGE_NOT_ASSIGNED"));

        mockMvc.perform(auth(get("/api/concierge-manager/requests/100")))
            .andExpect(status().isForbidden());
    }

    // ============================================================
    // PATCH /api/concierge-manager/requests/{id}/status
    // ============================================================

    @Test
    @DisplayName("PATCH status - 200 + 상세 응답")
    void transitionStatus_200() throws Exception {
        when(managerService.transitionStatus(eq(100L), any(), eq(ACTOR_SEQ), any()))
            .thenReturn(sampleDetail());

        StatusTransitionRequest body = new StatusTransitionRequest();
        body.setNextStatus("ASSIGNED");

        mockMvc.perform(auth(patch("/api/concierge-manager/requests/100/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    @Test
    @DisplayName("PATCH status - nextStatus 누락 → 400 Validation")
    void transitionStatus_missingField_400() throws Exception {
        mockMvc.perform(auth(patch("/api/concierge-manager/requests/100/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    // ============================================================
    // POST /api/concierge-manager/requests/{id}/notes
    // ============================================================

    @Test
    @DisplayName("POST notes - 201 + 노트 응답")
    void addNote_201() throws Exception {
        NoteResponse response = NoteResponse.builder()
            .conciergeNoteSeq(999L)
            .authorUserSeq(ACTOR_SEQ)
            .authorName("Concierge Manager")
            .channel("PHONE")
            .content("Called at 10:30")
            .createdAt(LocalDateTime.now())
            .build();
        when(managerService.addNote(eq(100L), any(), eq(ACTOR_SEQ), any()))
            .thenReturn(response);

        NoteAddRequest body = new NoteAddRequest();
        body.setChannel(NoteChannel.PHONE);
        body.setContent("Called at 10:30");

        mockMvc.perform(auth(post("/api/concierge-manager/requests/100/notes"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.conciergeNoteSeq").value(999))
            .andExpect(jsonPath("$.channel").value("PHONE"));
    }

    @Test
    @DisplayName("POST notes - content 누락 → 400")
    void addNote_invalidBody_400() throws Exception {
        mockMvc.perform(auth(post("/api/concierge-manager/requests/100/notes"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"PHONE\"}"))
            .andExpect(status().isBadRequest());
    }

    // ============================================================
    // POST /api/concierge-manager/requests/{id}/resend-setup-email
    // ============================================================

    @Test
    @DisplayName("POST resend-setup-email - 202 Accepted")
    void resendSetupEmail_202() throws Exception {
        mockMvc.perform(auth(post("/api/concierge-manager/requests/100/resend-setup-email")))
            .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST resend-setup-email - Applicant가 PENDING_ACTIVATION 아니면 409 NOT_PENDING")
    void resendSetupEmail_notPending_409() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException("not pending",
                HttpStatus.CONFLICT, "NOT_PENDING"))
            .when(managerService).resendSetupEmail(eq(100L), eq(ACTOR_SEQ), any());

        mockMvc.perform(auth(post("/api/concierge-manager/requests/100/resend-setup-email")))
            .andExpect(status().isConflict());
    }

    // ============================================================
    // PATCH /api/concierge-manager/requests/{id}/cancel
    // ============================================================

    @Test
    @DisplayName("PATCH cancel - 200 + 취소 상세")
    void cancel_200() throws Exception {
        ConciergeRequestDetail cancelled = ConciergeRequestDetail.builder()
            .conciergeRequestSeq(100L)
            .publicCode("C-2026-0100")
            .submitterName("Tan").submitterEmail("tan@example.com").submitterPhone("+6591234567")
            .status("CANCELLED")
            .createdAt(LocalDateTime.now())
            .cancelledAt(LocalDateTime.now())
            .cancellationReason("applicant request")
            .notes(List.of())
            .build();
        when(managerService.cancel(eq(100L), any(), eq(ACTOR_SEQ), any()))
            .thenReturn(cancelled);

        CancelRequest body = new CancelRequest();
        body.setReason("applicant request");

        mockMvc.perform(auth(patch("/api/concierge-manager/requests/100/cancel"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationReason").value("applicant request"));
    }

    @Test
    @DisplayName("PATCH cancel - reason 누락 → 400")
    void cancel_missingReason_400() throws Exception {
        mockMvc.perform(auth(patch("/api/concierge-manager/requests/100/cancel"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }
}
