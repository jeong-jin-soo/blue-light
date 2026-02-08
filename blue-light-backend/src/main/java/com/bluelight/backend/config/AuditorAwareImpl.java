package com.bluelight.backend.config;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 현재 인증된 사용자의 ID를 반환하는 AuditorAware 구현체
 * - Spring Security의 SecurityContext에서 사용자 정보를 추출
 * - 인증되지 않은 경우 빈 Optional 반환
 */
@Component
public class AuditorAwareImpl implements AuditorAware<Long> {

    @Override
    public Optional<Long> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 인증 정보가 없거나 인증되지 않은 경우
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        // Anonymous 사용자인 경우
        if (authentication.getPrincipal().equals("anonymousUser")) {
            return Optional.empty();
        }

        // JwtAuthenticationFilter에서 Principal로 userSeq(Long)를 설정함
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long userSeq) {
            return Optional.of(userSeq);
        }

        return Optional.empty();
    }
}
