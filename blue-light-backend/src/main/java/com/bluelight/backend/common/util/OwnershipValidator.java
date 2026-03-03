package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
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
     * 소유권 검증 (단순 비교)
     * - 리소스 소유자와 요청자가 다르면 FORBIDDEN 예외
     *
     * @param ownerSeq  리소스 소유자 userSeq
     * @param requestorSeq 요청자 userSeq
     */
    public static void validateOwner(Long ownerSeq, Long requestorSeq) {
        if (!ownerSeq.equals(requestorSeq)) {
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
     * 관리자 역할 여부 확인
     */
    private static boolean isAdmin(String role) {
        return "ROLE_ADMIN".equals(role) || "ROLE_SYSTEM_ADMIN".equals(role);
    }
}
