package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import org.springframework.http.HttpStatus;

/**
 * Concierge 요청 접근 권한 검증 (★ Kaki Concierge v1.5, Phase 1 PR#4 Stage A).
 * <p>
 * <ul>
 *   <li>ADMIN / SYSTEM_ADMIN: 모든 요청 접근 가능</li>
 *   <li>CONCIERGE_MANAGER: 자신에게 assigned된 요청만 접근 가능</li>
 *   <li>그 외 역할: 403</li>
 * </ul>
 * 목록 조회는 {@link #resolveListFilterManagerSeq(User)}로 필터 seq를 결정하여
 * 매니저가 자신의 배정만 조회할 수 있도록 한다.
 */
public final class ConciergeOwnershipValidator {

    private ConciergeOwnershipValidator() {
        // utility class
    }

    /**
     * 상세/수정 접근 검증. ADMIN 우회 + 담당 Manager만 허용.
     *
     * @throws BusinessException 401(UNAUTHORIZED) 또는 403(FORBIDDEN/CONCIERGE_NOT_ASSIGNED)
     */
    public static void assertManagerCanAccess(ConciergeRequest request, User actor) {
        if (actor == null) {
            throw new BusinessException("Unauthenticated", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        UserRole role = actor.getRole();
        if (role == UserRole.ADMIN || role == UserRole.SYSTEM_ADMIN) {
            return;
        }
        if (role != UserRole.CONCIERGE_MANAGER) {
            throw new BusinessException("Forbidden", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        User assigned = request.getAssignedManager();
        if (assigned == null || !assigned.getUserSeq().equals(actor.getUserSeq())) {
            throw new BusinessException(
                "This concierge request is not assigned to you",
                HttpStatus.FORBIDDEN, "CONCIERGE_NOT_ASSIGNED");
        }
    }

    /**
     * 목록 조회 시 대상 매니저 seq를 결정한다.
     * <ul>
     *   <li>ADMIN / SYSTEM_ADMIN: null 반환 (전체 조회)</li>
     *   <li>CONCIERGE_MANAGER: 자신의 userSeq 반환</li>
     *   <li>그 외: 403</li>
     * </ul>
     */
    public static Long resolveListFilterManagerSeq(User actor) {
        if (actor == null) {
            throw new BusinessException("Unauthenticated", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        UserRole role = actor.getRole();
        if (role == UserRole.ADMIN || role == UserRole.SYSTEM_ADMIN) {
            return null;
        }
        if (role == UserRole.CONCIERGE_MANAGER) {
            return actor.getUserSeq();
        }
        throw new BusinessException("Forbidden", HttpStatus.FORBIDDEN, "FORBIDDEN");
    }
}
