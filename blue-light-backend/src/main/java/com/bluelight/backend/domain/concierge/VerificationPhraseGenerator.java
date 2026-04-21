package com.bluelight.backend.domain.concierge;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 컨시어지 신청 건별 피싱 방지용 4단어 검증 문구 생성기.
 * <p>
 * 생성된 문구는 (1) 매니저가 통화 중 구두로 안내하고, (2) 견적 이메일 본문에도 동일하게 포함된다.
 * 신청자는 "이메일 문구 == 통화 문구"인 경우에만 이메일을 신뢰한다.
 * <p>
 * 공격자가 신청자 이메일과 요청 정보를 스니핑해 사칭 메일을 발송해도 verification phrase 는
 * 서버와 매니저만 아는 값이므로 재현 불가 — 단순한 MITM 방어에 효과적.
 * <p>
 * 보안 원칙:
 * - SecureRandom 사용 (추측 불가)
 * - 단어 풀은 혼동이 적고 발음·기억이 쉬운 어휘로 구성
 * - 4단어 조합 공간 ≈ 64^4 ≈ 1,677만 (통화 세션 범위에서 충분)
 */
@Component
public class VerificationPhraseGenerator {

    // 64개의 짧고 흔한 영어 단어 — 전화로 받아적기 쉬워야 함
    private static final String[] WORDS = {
        "amber", "anchor", "apple", "autumn", "basil", "berry", "breeze", "bronze",
        "candle", "canyon", "cedar", "clover", "coral", "cotton", "crane", "crystal",
        "delta", "diamond", "ember", "falcon", "forest", "garnet", "glacier", "granite",
        "harbor", "hazel", "indigo", "island", "ivory", "jasper", "juniper", "lantern",
        "lilac", "maple", "marble", "meadow", "mellow", "metro", "nectar", "noble",
        "opal", "orchid", "otter", "pebble", "pepper", "pioneer", "quartz", "raven",
        "river", "rustic", "saffron", "silver", "sparrow", "spruce", "sunset", "tango",
        "topaz", "tulip", "velvet", "violet", "walnut", "willow", "zephyr", "zenith"
    };

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append('-');
            sb.append(WORDS[random.nextInt(WORDS.length)]);
        }
        return sb.toString();
    }
}
