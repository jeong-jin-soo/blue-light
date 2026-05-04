package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import org.springframework.http.HttpStatus;

/**
 * Concierge 요청 접근 권한 검증 (★ Kaki Concierge v1.5, Phase 1 PR#4 Stage A + PR-3 확장).
 * <p>
 * <ul>
 *   <li>ADMIN / SYSTEM_ADMIN: 모든 요청 접근 가능</li>
 *   <li>CONCIERGE_MANAGER: 자신에게 assigned된 요청만 접근 가능</li>
 *   <li>★ PR-3 (D7=B): 다중 역할로 LEW 권한을 보유한 사용자는 본인이
 *       {@code assignedLewSeq} 인 요청만 접근 가능 ({@link #assertAccessible})</li>
 *   <li>그 외 역할: 403</li>
 * </ul>
 * 목록 조회는 {@link #resolveListFilterManagerSeq(User)} 로 매니저 필터를, LEW 는
 * {@code assignedLewSeq} 별도 필터를 사용한다 (서비스 레이어 책임).
 */
public final class ConciergeOwnershipValidator {

    private ConciergeOwnershipValidator() {
        // utility class
    }

    /**
     * 상세/수정 접근 검증. ADMIN 우회 + 담당 Manager만 허용.
     * <p>
     * ★ PR-3 변경: 본 메서드는 매니저-only 가드로 의미가 좁혀졌고, LEW 도 허용해야 하는
     * 엔드포인트(GET 상세, createApplicationOnBehalf 등)는 {@link #assertAccessible} 을 사용한다.
     * 기존 호출자 호환을 위해 동작은 동일 — primary {@code role} 만 체크 (다중 역할은 본 메서드 범위 외).
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
     * ★ PR-3: ADMIN / 담당 매니저 / 배정 LEW 가 모두 접근 가능한 통합 가드.
     * <p>
     * - ADMIN/SYSTEM_ADMIN: 통과 (다중 역할 보유 시에도 동일)
     * - CONCIERGE_MANAGER: assignedManagerSeq == actor 일 때 통과
     * - LEW: assignedLewSeq == actor 일 때 통과 (D7=B — assigned 한정)
     * - 그 외 역할(또는 미배정 LEW): 403
     * <p>
     * 다중 역할은 {@link User#hasRole(UserRole)} 으로 판정 — primary {@code role} 또는
     * secondary {@code roles} 집합 둘 다 인식한다.
     *
     * @throws BusinessException 401/403
     */
    public static void assertAccessible(ConciergeRequest request, User actor) {
        if (actor == null) {
            throw new BusinessException("Unauthenticated", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (actor.hasRole(UserRole.ADMIN) || actor.hasRole(UserRole.SYSTEM_ADMIN)) {
            return;
        }
        // CONCIERGE_MANAGER 우선 체크 — 매니저로 배정되어 있으면 즉시 통과.
        if (actor.hasRole(UserRole.CONCIERGE_MANAGER)) {
            User assigned = request.getAssignedManager();
            if (assigned != null && assigned.getUserSeq().equals(actor.getUserSeq())) {
                return;
            }
        }
        // LEW 권한 보유 + 본인이 ConciergeRequest 의 배정 LEW 인 경우 통과.
        if (actor.hasRole(UserRole.LEW)) {
            Long lewSeq = request.getAssignedLewSeq();
            if (lewSeq != null && lewSeq.equals(actor.getUserSeq())) {
                return;
            }
            // LEW 인데 배정되지 않은 다른 요청에 접근 시도 — D7=B 명시 거부.
            throw new BusinessException(
                "This concierge request is not assigned to you",
                HttpStatus.FORBIDDEN, "CONCIERGE_LEW_NOT_ASSIGNED");
        }
        // 매니저 권한이 있었지만 본인 배정이 아닌 경우 — 매니저 표준 메시지로.
        if (actor.hasRole(UserRole.CONCIERGE_MANAGER)) {
            throw new BusinessException(
                "This concierge request is not assigned to you",
                HttpStatus.FORBIDDEN, "CONCIERGE_NOT_ASSIGNED");
        }
        throw new BusinessException("Forbidden", HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    /**
     * 목록 조회 시 대상 매니저 seq를 결정한다.
     * <ul>
     *   <li>ADMIN / SYSTEM_ADMIN: null 반환 (전체 조회)</li>
     *   <li>CONCIERGE_MANAGER: 자신의 userSeq 반환</li>
     *   <li>LEW (다중 역할 비-매니저): 매니저 필터로는 사용 불가 — 401/403 가 아니라 null 을 반환하지 않고
     *       서비스가 LEW 필터로 분기해야 한다. 본 메서드는 매니저 컨텍스트 한정이므로 LEW 단독은 거부.</li>
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
