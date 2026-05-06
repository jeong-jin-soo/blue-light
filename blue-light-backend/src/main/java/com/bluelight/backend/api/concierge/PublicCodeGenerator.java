package com.bluelight.backend.api.concierge;

import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;

/**
 * ConciergeRequest 공개 코드 생성기 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * 포맷: {@code C-YYYY-NNNN}
 * <ul>
 *   <li>YYYY: 생성 시점의 연도</li>
 *   <li>NNNN: 0000~9999 랜덤 SecureRandom (충돌 시 최대 5회 재시도)</li>
 * </ul>
 * 연간 1만 건 근접 시 별도 핸들링 필요 — IllegalStateException 발생 시 Admin 경고 대상.
 */
@Component
@RequiredArgsConstructor
public class PublicCodeGenerator {

    private static final int MAX_ATTEMPTS = 5;
    private static final int RANGE = 10_000;

    private final ConciergeRequestRepository repository;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        int year = LocalDate.now().getYear();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            int n = random.nextInt(RANGE);
            String code = String.format("C-%04d-%04d", year, n);
            if (!repository.existsByPublicCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException(
            "Failed to generate unique public code after " + MAX_ATTEMPTS + " attempts");
    }
}
