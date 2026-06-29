package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.user.User;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.ObjectNotFoundException;
import org.springframework.http.HttpStatus;

/**
 * 리소스 소유권 검증 유틸리티
 * - 신청서, 파일 등의 소유권을 확인하고 권한 없으면 예외 발생
 */
public final class OwnershipValidator {

    private OwnershipValidator() {
        // Utility class — 인스턴스 생성 방지
    }

    /**
     * 프록시 사용자의 userSeq 를 안전하게 반환한다. 참조 사용자가 소프트삭제(@SQLRestriction)되거나
     * 물리삭제돼 프록시 초기화가 실패하면 null 을 돌려준다(소유권 비교에서 null=불일치로 처리됨).
     * <p>삭제된 사용자를 참조하는 신청/주문의 권한검증·DTO 변환이 500 으로 깨지는 것을 막는다.</p>
     */
    public static Long userSeqOrNull(User user) {
        if (user == null) {
            return null;
        }
        try {
            return user.getUserSeq(); // 프록시면 초기화 유발 — 행 없으면 throw
        } catch (EntityNotFoundException | ObjectNotFoundException e) {
            return null;
        }
    }

    /**
     * 소유권 검증 (단순 비교)
     * - 리소스 소유자와 요청자가 다르면(또는 소유자가 null=삭제) FORBIDDEN 예외
     *
     * @param ownerSeq  리소스 소유자 userSeq (삭제된 소유자면 null)
     * @param requestorSeq 요청자 userSeq
     */
    public static void validateOwner(Long ownerSeq, Long requestorSeq) {
        if (ownerSeq == null || !ownerSeq.equals(requestorSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }

    /**
     * 소유권 검증 (관리자 우회 가능)
     * - ADMIN / SYSTEM_ADMIN 역할이면 통과
     * - 그 외에는 소유자 확인
     *
     * @param ownerSeq  리소스 소유자 userSeq
     * @param requestorSeq 요청자 userSeq
     * @param role      요청자 역할 (ROLE_ADMIN / ROLE_SYSTEM_ADMIN이면 우회)
     */
    public static void validateOwnerOrAdmin(Long ownerSeq, Long requestorSeq, String role) {
        if (isAdmin(role)) {
            return;
        }
        validateOwner(ownerSeq, requestorSeq);
    }

    /**
     * 소유권 검증 (관리자 또는 담당 LEW 우회 가능)
     * - ADMIN / SYSTEM_ADMIN → 무조건 통과
     * - LEW → 해당 신청서에 할당된 LEW(assignedLewSeq)인 경우만 통과
     * - 그 외 → 리소스 소유자 확인
     *
     * @param ownerSeq       리소스 소유자 userSeq
     * @param requestorSeq   요청자 userSeq
     * @param role           요청자 역할
     * @param assignedLewSeq 신청서에 할당된 LEW의 userSeq (nullable)
     */
    public static void validateOwnerOrAdminOrAssignedLew(
            Long ownerSeq, Long requestorSeq, String role, Long assignedLewSeq) {
        if (isAdmin(role)) {
            return;
        }
        if ("ROLE_LEW".equals(role)) {
            if (assignedLewSeq != null && assignedLewSeq.equals(requestorSeq)) {
                return; // 해당 신청서에 할당된 LEW만 접근 가능
            }
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        validateOwner(ownerSeq, requestorSeq);
    }

    /**
     * 관리자 역할 여부 확인 (ADMIN / SYSTEM_ADMIN)
     */
    public static boolean isAdmin(String role) {
        return "ROLE_ADMIN".equals(role) || "ROLE_SYSTEM_ADMIN".equals(role);
    }
}
