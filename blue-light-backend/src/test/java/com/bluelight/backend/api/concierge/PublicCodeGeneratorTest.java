package com.bluelight.backend.api.concierge;

import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PublicCodeGenerator 단위 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 */
@DisplayName("PublicCodeGenerator - PR#2 Stage B")
class PublicCodeGeneratorTest {

    private ConciergeRequestRepository repository;
    private PublicCodeGenerator generator;

    @BeforeEach
    void setUp() {
        repository = mock(ConciergeRequestRepository.class);
        generator = new PublicCodeGenerator(repository);
    }

    @Test
    @DisplayName("첫 시도 성공 시 C-YYYY-NNNN 포맷 반환")
    void generate_firstTry_success() {
        when(repository.existsByPublicCode(anyString())).thenReturn(false);

        String code = generator.generate();

        int year = LocalDate.now().getYear();
        assertThat(code).matches("^C-" + year + "-\\d{4}$");
        verify(repository, times(1)).existsByPublicCode(anyString());
    }

    @Test
    @DisplayName("충돌 후 성공 (1~4회 충돌)")
    void generate_afterCollisions_success() {
        when(repository.existsByPublicCode(anyString()))
            .thenReturn(true, true, true, false);

        String code = generator.generate();

        int year = LocalDate.now().getYear();
        assertThat(code).matches("^C-" + year + "-\\d{4}$");
        verify(repository, times(4)).existsByPublicCode(anyString());
    }

    @Test
    @DisplayName("5회 전부 충돌 시 IllegalStateException")
    void generate_allAttemptsCollide_throws() {
        when(repository.existsByPublicCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> generator.generate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to generate unique public code");
        verify(repository, times(5)).existsByPublicCode(anyString());
    }
}
