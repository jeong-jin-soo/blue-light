package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.loaform.LoaFormTemplate;
import com.bluelight.backend.domain.loaform.LoaFormTemplateRepository;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LoaFormTemplateService} 의 active 단일성 검증.
 *
 * <p>스펙: {@code loa-exchange-redesign-spec.md} AC-7 — 새 버전을 activate 하면 기존 active 가 false 가
 * 되고 동시 active 가 0 또는 2 로 남지 않아야 한다.</p>
 */
class LoaFormTemplateServiceTest {

    private LoaFormTemplateRepository templateRepository;
    private FileRepository fileRepository;
    private FileStorageService fileStorageService;
    private UserRepository userRepository;
    private LoaFormTemplateService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(LoaFormTemplateRepository.class);
        fileRepository = mock(FileRepository.class);
        fileStorageService = mock(FileStorageService.class);
        userRepository = mock(UserRepository.class);
        service = new LoaFormTemplateService(
                templateRepository, fileRepository, fileStorageService, userRepository);
    }

    @Test
    void activate_새버전_활성화시_기존_active_는_false_가_된다() {
        // GIVEN v1 이 active, v2 가 비활성
        LoaFormTemplate v1 = LoaFormTemplate.builder()
                .label("v1").fileSeq(10L).isActive(true).uploadedBy(1L).build();
        LoaFormTemplate v2 = LoaFormTemplate.builder()
                .label("v2").fileSeq(20L).isActive(false).uploadedBy(1L).build();
        setSeq(v1, 1L);
        setSeq(v2, 2L);

        when(templateRepository.findById(2L)).thenReturn(Optional.of(v2));
        // applyActivation 이 조회하는 "현재 active" 목록 = [v1]
        when(templateRepository.findByIsActiveTrue()).thenReturn(List.of(v1));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        // WHEN v2 활성화
        service.activate(2L, 99L);

        // THEN v1 비활성, v2 활성 — 동시 active 1건 유지
        assertThat(v1.isActive()).isFalse();
        assertThat(v2.isActive()).isTrue();
    }

    @Test
    void upload_with_activate_true_도_기존_active_를_비활성화한다() {
        // GIVEN v1 active
        LoaFormTemplate v1 = LoaFormTemplate.builder()
                .label("v1").fileSeq(10L).isActive(true).uploadedBy(1L).build();
        setSeq(v1, 1L);

        org.springframework.web.multipart.MultipartFile file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "loa.pdf", "application/pdf", new byte[]{1, 2, 3});

        when(fileStorageService.store(any(), any())).thenReturn("loa-form-templates/loa.pdf");
        when(fileRepository.save(any())).thenAnswer(inv -> {
            var fe = (com.bluelight.backend.domain.file.FileEntity) inv.getArgument(0);
            setFileSeq(fe, 30L);
            return fe;
        });
        when(templateRepository.save(any())).thenAnswer(inv -> {
            var t = (LoaFormTemplate) inv.getArgument(0);
            setSeq(t, 2L);
            return t;
        });
        // applyActivation 시점의 현재 active = [v1]
        when(templateRepository.findByIsActiveTrue()).thenReturn(List.of(v1));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        // WHEN activate=true 로 업로드
        var response = service.upload(file, "v2", true, 99L);

        // THEN 새 버전 active, 기존 v1 비활성
        assertThat(response.isActive()).isTrue();
        assertThat(v1.isActive()).isFalse();
    }

    // ── reflection 헬퍼 (엔티티 PK 는 IDENTITY 생성이라 테스트에서 직접 주입) ──

    private static void setSeq(LoaFormTemplate t, Long seq) {
        try {
            var f = LoaFormTemplate.class.getDeclaredField("loaFormTemplateSeq");
            f.setAccessible(true);
            f.set(t, seq);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setFileSeq(com.bluelight.backend.domain.file.FileEntity fe, Long seq) {
        try {
            var f = com.bluelight.backend.domain.file.FileEntity.class.getDeclaredField("fileSeq");
            f.setAccessible(true);
            f.set(fe, seq);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
