package com.bluelight.backend.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Spring Security {@link Authentication}에서 userSeq / role 을 추출하는 정적 헬퍼.
 *
 * <p>코드베이스 전반에 흩어져 있던 다음 두 패턴을 단일화한다.</p>
 * <pre>
 *   Long userSeq = (Long) authentication.getPrincipal();
 *   String role  = authentication.getAuthorities().iterator().next().getAuthority();
 * </pre>
 *
 * <p>{@code AppSecurity} 빈이 SpEL 경로 ({@code @PreAuthorize("@appSec.isAssignedLew(...)")})
 * 를 담당하는 반면, 본 유틸은 컨트롤러/서비스가 명시적으로 사용자 식별이 필요할 때(예: 데이터
 * 스코핑, 감사 로그) 쓰는 정적 추출기다. SpEL 친화 형태가 필요하면 {@link AppSecurity} 사용.</p>
 *
 * <p>null/타입 안전: principal 이 {@code Long}이 아니거나 authorities 가 비어있으면
 * {@link IllegalStateException} 을 던진다. 인증된 사용자에게서만 호출되므로 정상 흐름에서는
 * 발생하지 않으며, 발생 시 인증 필터 구성이 잘못된 것이다.</p>
 */
public final class AuthPrincipal {

    private AuthPrincipal() {}

    /**
     * 현재 인증 사용자의 {@code userSeq} 추출.
     *
     * @param auth Spring Security Authentication (null 비허용)
     * @return userSeq (Long)
     * @throws IllegalStateException principal 이 {@code Long} 타입이 아닌 경우
     */
    public static Long userSeq(Authentication auth) {
        if (auth == null) {
            throw new IllegalStateException("Authentication is null");
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof Long seq)) {
            throw new IllegalStateException(
                    "Expected Long principal but got " +
                            (principal == null ? "null" : principal.getClass().getName()));
        }
        return seq;
    }

    /**
     * 현재 인증 사용자의 첫 번째 role 추출 ({@code "ROLE_ADMIN"} 형식).
     *
     * <p>본 시스템은 사용자 1인당 단일 역할 모델이라 first authority 가 항상 role 이다.
     * 다중 역할 모델로 변경 시 본 메서드 시그니처도 함께 검토할 것.</p>
     *
     * @param auth Spring Security Authentication (null 비허용)
     * @return role 문자열 (예: "ROLE_LEW")
     * @throws IllegalStateException authorities 가 비어있는 경우
     */
    public static String role(Authentication auth) {
        if (auth == null) {
            throw new IllegalStateException("Authentication is null");
        }
        return auth.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElseThrow(() -> new IllegalStateException("No authorities on principal"));
    }
}
