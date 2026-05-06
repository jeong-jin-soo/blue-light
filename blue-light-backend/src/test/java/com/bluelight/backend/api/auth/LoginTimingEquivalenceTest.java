package com.bluelight.backend.api.auth;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 로그인 응답 시간 동등성 통합 테스트 (★ Kaki Concierge v1.5 §4.4 H-1).
 * <p>
 * 미존재 이메일 vs 존재 이메일 간 응답 시간 중앙값 편차가 30% 이내인지 검증.
 * CI 환경 노이즈를 감안해 절대 임계값(p95 &lt; 200ms) 대신 상대 비율로 측정.
 * <p>
 * <b>실행</b>: {@code ./gradlew timingTest} (기본 {@code test}에서는 제외).
 * <b>전제</b>: 실제 DB(MySQL) + Spring 풀 컨텍스트 기동 필요.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("timing")
@DisplayName("Login Timing Equivalence - PR#2 Stage C (timing)")
class LoginTimingEquivalenceTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final int WARMUP = 20;
    private static final int SAMPLES = 100;
    private static final double DEVIATION_THRESHOLD = 0.30; // 30%

    private Long timingUserSeq;

    @BeforeEach
    void setup() {
        User u = User.builder()
            .email("timing-test@example.com")
            .password(passwordEncoder.encode("RealPass!123"))
            .firstName("T").lastName("T")
            .status(UserStatus.PENDING_ACTIVATION)
            .build();
        User saved = userRepository.save(u);
        timingUserSeq = saved.getUserSeq();
    }

    @AfterEach
    void tearDown() {
        if (timingUserSeq != null) {
            userRepository.deleteById(timingUserSeq);
        }
    }

    @Test
    @DisplayName("로그인 응답 시간 편차가 미존재/존재 이메일 간 30% 이내여야 한다 (§4.4)")
    void loginTimingEquivalence() throws Exception {
        // Warmup — JIT/커넥션 풀 안정화
        for (int i = 0; i < WARMUP; i++) {
            loginSilent("timing-test@example.com", "wrong");
            loginSilent("unknown-warmup-" + i + "@example.com", "wrong");
        }

        // Measure
        long[] existingNs = new long[SAMPLES];
        long[] unknownNs = new long[SAMPLES];

        for (int i = 0; i < SAMPLES; i++) {
            final int idx = i;
            existingNs[idx] = timed(() -> loginSilent("timing-test@example.com", "wrong"));
            unknownNs[idx] = timed(() -> loginSilent("unknown-" + idx + "-zz@example.com", "wrong"));
        }

        double medExisting = median(existingNs);
        double medUnknown = median(unknownNs);
        double deviation = Math.abs(medExisting - medUnknown) / Math.max(medExisting, medUnknown);

        assertThat(deviation)
            .as("median existing=%.1fms, unknown=%.1fms, deviation=%.2f%%",
                medExisting / 1_000_000.0, medUnknown / 1_000_000.0, deviation * 100)
            .isLessThan(DEVIATION_THRESHOLD);
    }

    private long timed(Runnable r) {
        long t0 = System.nanoTime();
        try {
            r.run();
        } catch (Throwable ignored) {
            // 응답 시간만 측정 — 401/429 등 실패 응답도 정상 측정 대상
        }
        return System.nanoTime() - t0;
    }

    private void loginSilent(String email, String password) {
        try {
            mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}",
                    email.replace("\"", "\\\""), password.replace("\"", "\\\""))));
        } catch (Exception ignored) {
            // 측정 중 간헐 예외는 무시
        }
    }

    private double median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
