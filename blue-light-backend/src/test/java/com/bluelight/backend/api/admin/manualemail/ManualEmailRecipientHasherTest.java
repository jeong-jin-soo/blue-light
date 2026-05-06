package com.bluelight.backend.api.admin.manualemail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-2 — {@link ManualEmailRecipientHasher} 단위 테스트.
 *
 * <p>스펙: AC-A9 멱등성 (D3=B 확장). 정렬 + 정규화 후 동일 입력은 동일 해시여야 한다.</p>
 */
@DisplayName("ManualEmailRecipientHasher — PR-2")
class ManualEmailRecipientHasherTest {

    @Test
    @DisplayName("동일 입력 → 동일 해시 (deterministic)")
    void deterministic() {
        String h1 = ManualEmailRecipientHasher.hashOf(
                List.of("a@x.com", "b@y.com"), "Subject", "Body");
        String h2 = ManualEmailRecipientHasher.hashOf(
                List.of("a@x.com", "b@y.com"), "Subject", "Body");
        assertThat(h1).isEqualTo(h2);
        // SHA-256 hex 길이 64.
        assertThat(h1).hasSize(64);
    }

    @Test
    @DisplayName("수신자 순서 차이 → 동일 해시 (정렬 정규화)")
    void sortNormalized() {
        String h1 = ManualEmailRecipientHasher.hashOf(
                List.of("a@x.com", "b@y.com", "c@z.com"), "S", "B");
        String h2 = ManualEmailRecipientHasher.hashOf(
                List.of("c@z.com", "a@x.com", "b@y.com"), "S", "B");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("대소문자/공백 차이 → 동일 해시 (LOWER+TRIM 정규화)")
    void caseTrimNormalized() {
        String h1 = ManualEmailRecipientHasher.hashOf(
                List.of("Alice@Example.COM", "  bob@example.com  "), "S", "B");
        String h2 = ManualEmailRecipientHasher.hashOf(
                List.of("alice@example.com", "bob@example.com"), "S", "B");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("subject 가 다르면 다른 해시")
    void differentSubject() {
        String h1 = ManualEmailRecipientHasher.hashOf(List.of("a@x.com"), "S1", "B");
        String h2 = ManualEmailRecipientHasher.hashOf(List.of("a@x.com"), "S2", "B");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("body 가 다르면 다른 해시")
    void differentBody() {
        String h1 = ManualEmailRecipientHasher.hashOf(List.of("a@x.com"), "S", "B1");
        String h2 = ManualEmailRecipientHasher.hashOf(List.of("a@x.com"), "S", "B2");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("수신자가 다르면 다른 해시")
    void differentRecipients() {
        String h1 = ManualEmailRecipientHasher.hashOf(List.of("a@x.com"), "S", "B");
        String h2 = ManualEmailRecipientHasher.hashOf(List.of("b@x.com"), "S", "B");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("중복 수신자 제거 — 동일 해시 (a,a == a)")
    void deduplicated() {
        String h1 = ManualEmailRecipientHasher.hashOf(
                List.of("a@x.com", "a@x.com"), "S", "B");
        String h2 = ManualEmailRecipientHasher.hashOf(
                List.of("a@x.com"), "S", "B");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("null/빈 리스트도 안전 — 해시 산출")
    void nullSafe() {
        String h1 = ManualEmailRecipientHasher.hashOf(null, "S", "B");
        String h2 = ManualEmailRecipientHasher.hashOf(List.of(), "S", "B");
        // null/empty 양쪽 모두 "[]" 로 정규화 → 동일.
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
    }
}
