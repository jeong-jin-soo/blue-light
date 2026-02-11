package com.bluelight.backend.domain.user;

/**
 * LEW 등급 (Licensed Electrical Worker Grade)
 * - GRADE_7: 45 kVA 이하
 * - GRADE_8: 500 kVA 이하 (설계 제한: 150 kVA)
 * - GRADE_9: 1kV ~ 400kV (사실상 무제한)
 */
public enum LewGrade {
    GRADE_7(45),
    GRADE_8(500),
    GRADE_9(9999);

    private final int maxKva;

    LewGrade(int maxKva) {
        this.maxKva = maxKva;
    }

    public int getMaxKva() {
        return maxKva;
    }

    /**
     * 해당 등급이 주어진 kVA를 처리할 수 있는지 확인
     */
    public boolean canHandle(int kva) {
        return kva <= maxKva;
    }
}
