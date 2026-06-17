package com.bluelight.backend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link User#changePaynow} 도메인 메서드 단위 테스트 (PR-PN1).
 */
@DisplayName("User.changePaynow - PR-PN1")
class UserChangePaynowTest {

    private User buildUser() {
        return User.builder()
                .email("lew@example.com")
                .password("hash")
                .firstName("Test")
                .lastName("Lew")
                .role(UserRole.LEW)
                .build();
    }

    @Test
    @DisplayName("정상 설정: type+value 세팅, value 는 trim")
    void set_ok() {
        User user = buildUser();
        user.changePaynow(PaynowType.MOBILE, "  97771983 ");
        assertThat(user.getPaynowType()).isEqualTo(PaynowType.MOBILE);
        assertThat(user.getPaynowValue()).isEqualTo("97771983");
    }

    @Test
    @DisplayName("type null → IllegalArgumentException")
    void type_null() {
        User user = buildUser();
        assertThatThrownBy(() -> user.changePaynow(null, "97771983"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("value blank → IllegalArgumentException")
    void value_blank() {
        User user = buildUser();
        assertThatThrownBy(() -> user.changePaynow(PaynowType.MOBILE, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user.changePaynow(PaynowType.MOBILE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("유형 전환: COMPANY_UEN → MOBILE 재설정 가능")
    void switch_type() {
        User user = buildUser();
        user.changePaynow(PaynowType.COMPANY_UEN, "201837490N");
        assertThat(user.getPaynowType()).isEqualTo(PaynowType.COMPANY_UEN);
        user.changePaynow(PaynowType.MOBILE, "97771983");
        assertThat(user.getPaynowType()).isEqualTo(PaynowType.MOBILE);
        assertThat(user.getPaynowValue()).isEqualTo("97771983");
    }
}
