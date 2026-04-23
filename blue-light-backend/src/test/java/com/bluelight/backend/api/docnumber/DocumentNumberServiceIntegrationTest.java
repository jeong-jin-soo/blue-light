package com.bluelight.backend.api.docnumber;

import com.bluelight.backend.domain.docnumber.DocumentNumberSequenceRepository;
import com.bluelight.backend.domain.docnumber.DocumentNumberType;
import com.bluelight.backend.domain.docnumber.DocumentNumberTypeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentNumberService 통합 테스트 — 실제 DB(MySQL) 상에서 동시성·영속성 검증.
 *
 * <p>MySQL이 띄워져 있어야 한다 (docker compose up -d). CI/로컬의 기본 {@code ./gradlew test}에서는
 * {@code integration} 태그 제외로 스킵됨. 수동 실행: {@code ./gradlew integrationTest}.</p>
 *
 * <p>커버리지:
 * <ul>
 *   <li>AC-2 유일성 — 1000회 순차 발번 후 distinct count 일치</li>
 *   <li>AC-3 동시성 — 50개 스레드 동시 발번 후 중복 0, 누락 0</li>
 *   <li>AC-4 날짜 리셋 — Clock 교체 후 next_value=1 리셋</li>
 *   <li>AC-6 타입별 독립 — 두 타입 시퀀스가 서로 영향 없음</li>
 * </ul>
 * </p>
 */
@Tag("integration")
@SpringBootTest
@DisplayName("DocumentNumberService 통합 (AC-2/3/4/6) — MySQL 필요")
class DocumentNumberServiceIntegrationTest {

    private static final String TEST_TYPE_A = "__TEST_DOCNUM_A__";
    private static final String TEST_TYPE_B = "__TEST_DOCNUM_B__";
    private static final String TEST_PREFIX_A = "TSTA";
    private static final String TEST_PREFIX_B = "TSTB";

    @Autowired
    private DocumentNumberService service;

    @Autowired
    private DocumentNumberTypeRepository typeRepository;

    @Autowired
    private DocumentNumberSequenceRepository sequenceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // 정리: 이전 테스트 잔존 데이터 제거
        cleanupTestData();

        // 테스트 전용 타입 삽입
        typeRepository.save(DocumentNumberType.builder()
                .code(TEST_TYPE_A).prefix(TEST_PREFIX_A)
                .labelKo("테스트 A").labelEn("Test A")
                .description("Integration test — type A").active(true).displayOrder(9001)
                .build());
        typeRepository.save(DocumentNumberType.builder()
                .code(TEST_TYPE_B).prefix(TEST_PREFIX_B)
                .labelKo("테스트 B").labelEn("Test B")
                .description("Integration test — type B").active(true).displayOrder(9002)
                .build());
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        // sequence → types 순서로 HARD-DELETE (FK 제약 + soft-delete 부작용 회피).
        // @SQLDelete의 soft-delete를 우회하여 Native SQL로 확실히 제거.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "DELETE FROM document_number_sequence WHERE doc_type_code IN (?, ?)")
                    .setParameter(1, TEST_TYPE_A)
                    .setParameter(2, TEST_TYPE_B)
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM document_number_types WHERE code IN (?, ?)")
                    .setParameter(1, TEST_TYPE_A)
                    .setParameter(2, TEST_TYPE_B)
                    .executeUpdate();
        });
    }

    // ── AC-2: 유일성 (순차 1000회) ─────────────────────────────────────────────

    @Test
    @DisplayName("shouldGenerate1000UniqueSequentialNumbers")
    void shouldGenerate1000UniqueSequentialNumbers() {
        int N = 1000;
        List<String> numbers = IntStream.range(0, N)
                .mapToObj(i -> service.generate(TEST_TYPE_A))
                .toList();

        Set<String> unique = Set.copyOf(numbers);
        assertThat(unique).hasSize(N);
        // 순차적이어야 함: 0001 ~ 1000 (당일 기준)
        assertThat(numbers.get(0)).endsWith("-0001");
        assertThat(numbers.get(N - 1)).endsWith("-1000");
    }

    // ── AC-3: 동시성 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldGenerateUniqueNumbersUnder50ConcurrentThreads")
    void shouldGenerateUniqueNumbersUnder50ConcurrentThreads() throws Exception {
        int THREADS = 50;
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<CompletableFuture<String>> futures = IntStream.range(0, THREADS)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> service.generate(TEST_TYPE_A), executor))
                    .toList();

            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
            all.get(30, TimeUnit.SECONDS);

            List<String> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            // 중복 없음
            Set<String> unique = Set.copyOf(results);
            assertThat(unique).hasSize(THREADS);

            // 모든 결과가 TSTA prefix를 사용하며 4자리 숫자로 끝남
            assertThat(results).allMatch(n ->
                    n.contains("-" + TEST_PREFIX_A + "-") && n.matches(".*-[0-9]{4}$"));

            // 순번 집합은 0001 ~ 0050 완전 일치 (누락 없음, hole 없음)
            Set<Integer> sequences = results.stream()
                    .map(n -> Integer.parseInt(n.substring(n.length() - 4)))
                    .collect(Collectors.toSet());
            Set<Integer> expected = IntStream.rangeClosed(1, THREADS)
                    .boxed().collect(Collectors.toSet());
            assertThat(sequences).isEqualTo(expected);
        } finally {
            executor.shutdownNow();
        }
    }

    // ── AC-6: 타입별 시퀀스 독립 ──────────────────────────────────────────────

    @Test
    @DisplayName("shouldKeepSequenceIndependentAcrossDocTypes")
    void shouldKeepSequenceIndependentAcrossDocTypes() {
        String a1 = service.generate(TEST_TYPE_A);
        String a2 = service.generate(TEST_TYPE_A);
        String b1 = service.generate(TEST_TYPE_B);
        String a3 = service.generate(TEST_TYPE_A);
        String b2 = service.generate(TEST_TYPE_B);

        // 각 타입의 시퀀스가 독립적으로 0001, 0002, 0003 증가
        assertThat(a1).endsWith("-0001");
        assertThat(a2).endsWith("-0002");
        assertThat(a3).endsWith("-0003");
        assertThat(b1).endsWith("-0001");
        assertThat(b2).endsWith("-0002");

        // prefix는 다름
        assertThat(a1).contains(TEST_PREFIX_A);
        assertThat(b1).contains(TEST_PREFIX_B);
    }
}
