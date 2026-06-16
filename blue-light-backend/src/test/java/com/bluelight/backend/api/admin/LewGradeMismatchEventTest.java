package com.bluelight.backend.api.admin;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.user.LewGrade;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link LewGradeMismatchEvent#detect} 단위 테스트 (#5).
 */
@DisplayName("LewGradeMismatchEvent.detect")
class LewGradeMismatchEventTest {

    private Application appWith(User lew, Integer kva) {
        Application app = mock(Application.class);
        when(app.getAssignedLew()).thenReturn(lew);
        when(app.getSelectedKva()).thenReturn(kva);
        lenient().when(app.getApplicationSeq()).thenReturn(42L);
        return app;
    }

    private User lewGrade(LewGrade grade) {
        User lew = mock(User.class);
        lenient().when(lew.getLewGrade()).thenReturn(grade);
        lenient().when(lew.getUserSeq()).thenReturn(7L);
        lenient().when(lew.canHandleKva(anyInt()))
                .thenAnswer(inv -> grade != null && grade.canHandle(inv.getArgument(0)));
        return lew;
    }

    @Test
    @DisplayName("등급 초과면 이벤트 생성")
    void mismatch_present() {
        Optional<LewGradeMismatchEvent> e =
                LewGradeMismatchEvent.detect(appWith(lewGrade(LewGrade.GRADE_7), 100));

        assertThat(e).isPresent();
        assertThat(e.get().getNewKva()).isEqualTo(100);
        assertThat(e.get().getLewGradeName()).isEqualTo("GRADE_7");
        assertThat(e.get().getLewMaxKva()).isEqualTo(45);
        assertThat(e.get().getAssignedLewSeq()).isEqualTo(7L);
    }

    @Test
    @DisplayName("등급으로 처리 가능하면 empty")
    void withinGrade_empty() {
        assertThat(LewGradeMismatchEvent.detect(appWith(lewGrade(LewGrade.GRADE_8), 100))).isEmpty();
    }

    @Test
    @DisplayName("배정 LEW 없으면 empty")
    void noLew_empty() {
        assertThat(LewGradeMismatchEvent.detect(appWith(null, 100))).isEmpty();
    }

    @Test
    @DisplayName("kVA null 이면 empty")
    void nullKva_empty() {
        assertThat(LewGradeMismatchEvent.detect(appWith(lewGrade(LewGrade.GRADE_7), null))).isEmpty();
    }
}
