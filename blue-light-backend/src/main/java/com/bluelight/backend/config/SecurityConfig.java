package com.bluelight.backend.config;

import com.bluelight.backend.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 설정
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${cors.allowed-origins}")
    private String allowedOriginsRaw;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 비활성화 (JWT 사용으로 불필요)
                .csrf(AbstractHttpConfigurer::disable)

                // CORS 설정
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Security Response Headers
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(cto -> {})            // X-Content-Type-Options: nosniff
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions ->
                                permissions.policy("camera=(), microphone=(), geolocation=(), payment=()"))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(buildCspPolicy()))
                )

                // 세션 비활성화 (Stateless)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // URL별 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // SSE 비동기 완료 디스패치 허용
                        // SseEmitter.complete() 호출 시 Tomcat이 async dispatch를 발생시키는데,
                        // 이 디스패치는 원래 요청 URL로 Security 필터 체인을 재통과함.
                        // 이때 SecurityContext가 없어 AuthorizationDeniedException 발생 방지.
                        // 원본 요청은 이미 인증/인가를 통과했으므로 ASYNC dispatch는 안전하게 허용.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 인증 없이 접근 가능한 경로
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/prices/**").permitAll()
                        // Swagger, Health Check 등
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Error 페이지 (SSE 비동기 완료 시 SecurityContext 없이 디스패치됨)
                        .requestMatchers("/error").permitAll()
                        // ★ Kaki Concierge v1.5 Phase 1 PR#6 — LOA 경로 A (Manager 대리 업로드)
                        // URL은 /api/admin/**이지만 CONCIERGE_MANAGER가 대리 서명 업로드를 수행해야 하므로
                        // 더 구체적인 매처를 /api/admin/** 앞에 먼저 배치.
                        // AC-15b: LEW는 이 경로에서 제외되며, 메서드 @PreAuthorize로 이중 방어.
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/applications/*/loa/upload-signature")
                        .hasAnyRole("CONCIERGE_MANAGER", "ADMIN", "SYSTEM_ADMIN")
                        // Admin/LEW/SystemAdmin 경로 (URL-level defense-in-depth)
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "LEW", "SYSTEM_ADMIN")
                        // SLD Manager 경로
                        .requestMatchers("/api/sld-manager/**").hasAnyRole("SLD_MANAGER", "ADMIN", "SYSTEM_ADMIN")
                        // Concierge Manager 경로 (★ Kaki Concierge v1.5 Phase 1 PR#4)
                        .requestMatchers("/api/concierge-manager/**").hasAnyRole("CONCIERGE_MANAGER", "ADMIN", "SYSTEM_ADMIN")
                        // 그 외 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )

                // JWT 필터 추가
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS 설정
     * - 환경변수 CORS_ALLOWED_ORIGINS로 허용 Origin 관리
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 환경변수에서 허용 Origin 목록 로드
        List<String> origins = Arrays.asList(allowedOriginsRaw.split(","));
        configuration.setAllowedOrigins(origins);

        // 허용할 HTTP 메서드
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // 허용할 헤더 (필요한 것만 명시)
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Requested-With", "Authorization"));

        // 인증 정보 포함 허용
        configuration.setAllowCredentials(true);

        // 캐시 시간 (초)
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }

    /**
     * 비밀번호 암호화 인코더
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Content-Security-Policy 구성
     * - API 서버 자체 리소스 + CORS 허용 Origin 기반
     * - Gemini API (generativelanguage.googleapis.com) 연결 허용
     */
    private String buildCspPolicy() {
        // CORS 허용 Origin을 공백 구분 문자열로 변환
        String originsSources = String.join(" ",
                Arrays.stream(allowedOriginsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new));

        return String.join("; ",
                "default-src 'self'",
                "script-src 'self'",
                "style-src 'self' 'unsafe-inline'",                    // Tailwind 인라인 스타일 허용
                "img-src 'self' data: blob: " + originsSources,        // data:, blob: QR 미리보기
                "font-src 'self'",
                "connect-src 'self' " + originsSources + " https://generativelanguage.googleapis.com",  // API + Gemini
                "frame-ancestors 'none'",                              // X-Frame-Options 보강
                "base-uri 'self'",
                "form-action 'self'"
        );
    }
}
