package com.bluelight.backend.common.security;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * SpEL 친화적 접근제어 컴포넌트 — {@code @appSec} 네임스페이스로 노출.
 *
 * <p>LEW Review Form P1.B (lew-review-form-spec.md §7). {@code @PreAuthorize}에서
 * {@code @appSec.isAssignedLew(#id, authentication)} 형태로 호출하여,
 * 현재 인증 사용자가 해당 Application의 배정 LEW인지 확인한다.</p>
 *
 * <p>{@code ConciergeOwnershipValidator}의 정적 util과 달리, Spring 빈으로 등록된 이유는
 * SpEL에서 주입 가능한 접근 경로가 필요하기 때문이다. Repository 주입 역시 생성자 주입.
 * </p>
 *
 * <p>모든 메서드는 null-safe: 인증 미존재 / Application 미존재 / 미배정은 전부 false 반환.
 * 실제 404/403 분기 메시지는 상위 서비스에서 담당.</p>
 */
@Slf4j
@Component("appSec")
@RequiredArgsConstructor
public class AppSecurity {

    private final ApplicationRepository applicationRepository;

    /**
     * 현재 인증 사용자가 주어진 Application에 배정된 LEW인지 확인.
     *
     * @param applicationId 대상 Application의 {@code applicationSeq}
     * @param auth          Spring Security Authentication — principal은 {@code Long userSeq}
     * @return 배정 LEW와 일치하면 {@code true}, 그 외 모든 경우({@code null}, 미배정, 조회 실패) {@code false}
     */
    public boolean isAssignedLew(Long applicationId, Authentication auth) {
        if (applicationId == null || auth == null || !auth.isAuthenticated()) {
            return false;
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof Long userSeq)) {
            return false;
        }
        Optional<Application> opt = applicationRepository.findById(applicationId);
        if (opt.isEmpty()) {
            return false;
        }
        User assigned = opt.get().getAssignedLew();
        if (assigned == null) {
            return false;
        }
        return userSeq.equals(assigned.getUserSeq());
    }
}
