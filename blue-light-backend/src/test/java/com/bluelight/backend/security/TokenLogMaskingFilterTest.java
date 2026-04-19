package com.bluelight.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenLogMaskingFilter 단위 테스트 (★ Kaki Concierge v1.5, 보안 리뷰 H-2).
 * <p>
 * 정적 헬퍼 {@code maskTokenInPath}의 마스킹 규칙만 검증한다.
 * FilterChain 통합 시나리오는 MockMvc 기반 통합 테스트에서 별도 커버 예정.
 */
class TokenLogMaskingFilterTest {

    @Test
    void maskTokenInPath_account_setup_경로의_토큰을_앞4자_뒤4자로_마스킹() {
        // given: 16자 토큰
        String uri = "/api/public/account-setup/abcdef1234567890";

        // when
        String masked = TokenLogMaskingFilter.maskTokenInPath(uri);

        // then: abcd + **** + 7890
        assertThat(masked).isEqualTo("/api/public/account-setup/abcd****7890");
    }

    @Test
    void maskTokenInPath_8자_이하_토큰은_전체_마스킹() {
        // given: "short"는 5자로 8자 이하
        String uri = "/api/public/account-setup/short";

        // when
        String masked = TokenLogMaskingFilter.maskTokenInPath(uri);

        // then: 토큰 전체가 "****"로 치환
        assertThat(masked).isEqualTo("/api/public/account-setup/****");
    }

    @Test
    void maskTokenInPath_토큰_없는_경로는_원본_유지() {
        // given: 대상 경로가 아닌 일반 API
        String uri = "/api/applications/1";

        // when
        String masked = TokenLogMaskingFilter.maskTokenInPath(uri);

        // then: 변경되지 않음
        assertThat(masked).isEqualTo("/api/applications/1");
    }
}
