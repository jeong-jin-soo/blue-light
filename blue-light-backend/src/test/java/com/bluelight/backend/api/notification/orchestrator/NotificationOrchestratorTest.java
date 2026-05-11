package com.bluelight.backend.api.notification.orchestrator;

import com.bluelight.backend.api.notification.outbox.NotificationOutboxDispatcher;
import com.bluelight.backend.api.notification.outbox.NotificationOutboxWriter;
import com.bluelight.backend.api.notification.outbox.NotificationOutboxWriter.EnqueueRequest;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationOrchestrator 단위 테스트 (PR-0B).
 *
 * <p>이벤트 수신부터 outbox 적재 + dispatcher 호출까지 흐름 검증.
 * 의존성(UserRepository, PreferenceResolver, TemplateRegistry, OutboxWriter, OutboxDispatcher)은 모두 mock.</p>
 */
@DisplayName("NotificationOrchestrator - PR-0B")
class NotificationOrchestratorTest {

    private UserRepository userRepository;
    private NotificationPreferenceResolver preferenceResolver;
    private NotificationTemplateRegistry templateRegistry;
    private NotificationOutboxWriter outboxWriter;
    private NotificationOutboxDispatcher outboxDispatcher;
    private NotificationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        preferenceResolver = mock(NotificationPreferenceResolver.class);
        templateRegistry = mock(NotificationTemplateRegistry.class);
        outboxWriter = mock(NotificationOutboxWriter.class);
        outboxDispatcher = mock(NotificationOutboxDispatcher.class);
        orchestrator = new NotificationOrchestrator(
                userRepository, preferenceResolver, templateRegistry, outboxWriter, outboxDispatcher);
    }

    @Test
    @DisplayName("recipient 미존재 - 어떤 작업도 수행하지 않음")
    void recipientNotFound_noop() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        orchestrator.onDispatch(event(99L));

        verify(preferenceResolver, never()).resolveEnabledChannels(any(), any());
        verify(outboxWriter, never()).enqueue(any());
    }

    @Test
    @DisplayName("활성 채널 없음 - enqueue 호출하지 않음")
    void noEnabledChannels_skipsEnqueue() {
        User user = userWithSeq(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(preferenceResolver.resolveEnabledChannels(user, "PAYMENT_REQUEST")).thenReturn(Set.of());

        orchestrator.onDispatch(event(1L));

        verify(outboxWriter, never()).enqueue(any());
        verify(outboxDispatcher, never()).dispatchAsync(anyLong());
    }

    @Test
    @DisplayName("정상 흐름 - 활성 채널마다 enqueue + dispatchAsync 호출")
    void normalFlow_enqueuesAndDispatches() {
        User user = userWithSeq(1L);
        ReflectionTestUtils.setField(user, "preferredLanguage", "en");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(preferenceResolver.resolveEnabledChannels(user, "PAYMENT_REQUEST"))
                .thenReturn(EnumSet.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(mock(NotificationTemplate.class)));
        when(outboxWriter.enqueue(any())).thenAnswer(inv -> {
            NotificationOutbox row = NotificationOutbox.builder()
                    .idempotencyKey("K").userSeq(1L).channel(NotificationChannel.EMAIL)
                    .eventType("PAYMENT_REQUEST").templateCode("T").locale("en")
                    .payloadJson("{}").build();
            ReflectionTestUtils.setField(row, "outboxSeq", 100L);
            return Optional.of(row);
        });

        orchestrator.onDispatch(event(1L));

        ArgumentCaptor<EnqueueRequest> reqCaptor = ArgumentCaptor.forClass(EnqueueRequest.class);
        verify(outboxWriter, times(2)).enqueue(reqCaptor.capture());
        verify(outboxDispatcher, times(2)).dispatchAsync(100L);

        // 채널 인자가 IN_APP, EMAIL 두 채널 모두 포함
        assertThat(reqCaptor.getAllValues())
                .extracting(EnqueueRequest::channel)
                .containsExactlyInAnyOrder(NotificationChannel.IN_APP, NotificationChannel.EMAIL);
        // locale 은 user.preferredLanguage("en") 사용
        assertThat(reqCaptor.getAllValues()).extracting(EnqueueRequest::locale).containsOnly("en");
    }

    @Test
    @DisplayName("템플릿 없는 채널 - 해당 채널만 skip, 다른 채널은 발송")
    void missingTemplate_skipsThatChannelOnly() {
        User user = userWithSeq(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(preferenceResolver.resolveEnabledChannels(user, "PAYMENT_REQUEST"))
                .thenReturn(EnumSet.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL));
        // IN_APP 템플릿만 있고 EMAIL 은 없음
        when(templateRegistry.findActive("T", NotificationChannel.IN_APP, "en"))
                .thenReturn(Optional.of(mock(NotificationTemplate.class)));
        when(templateRegistry.findActive("T", NotificationChannel.EMAIL, "en"))
                .thenReturn(Optional.empty());
        when(outboxWriter.enqueue(any())).thenAnswer(inv -> {
            NotificationOutbox row = NotificationOutbox.builder()
                    .idempotencyKey("K").userSeq(1L).channel(NotificationChannel.IN_APP)
                    .eventType("PAYMENT_REQUEST").templateCode("T").locale("en")
                    .payloadJson("{}").build();
            ReflectionTestUtils.setField(row, "outboxSeq", 200L);
            return Optional.of(row);
        });

        orchestrator.onDispatch(event(1L));

        verify(outboxWriter, times(1)).enqueue(any()); // IN_APP 한 번만
        verify(outboxDispatcher, times(1)).dispatchAsync(200L);
    }

    @Test
    @DisplayName("enqueue 결과 outboxSeq=null - dispatcher 호출하지 않음 (멱등 가드)")
    void duplicateEnqueue_skipsDispatch() {
        User user = userWithSeq(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(preferenceResolver.resolveEnabledChannels(user, "PAYMENT_REQUEST"))
                .thenReturn(EnumSet.of(NotificationChannel.IN_APP));
        when(templateRegistry.findActive(any(), any(), any()))
                .thenReturn(Optional.of(mock(NotificationTemplate.class)));
        // outboxSeq null (저장 안 된 mock row — 중복 가드 상황 시뮬레이션)
        when(outboxWriter.enqueue(any())).thenReturn(Optional.of(
                NotificationOutbox.builder()
                        .idempotencyKey("K").userSeq(1L).channel(NotificationChannel.IN_APP)
                        .eventType("PAYMENT_REQUEST").templateCode("T").locale("en")
                        .payloadJson("{}").build()));

        orchestrator.onDispatch(event(1L));

        verify(outboxDispatcher, never()).dispatchAsync(anyLong());
    }

    private static NotificationDispatchEvent event(Long userSeq) {
        return new NotificationDispatchEvent(
                "PAYMENT_REQUEST",
                userSeq,
                "APPLICATION",
                42L,
                "T",
                Map.of("amount", "185.00"));
    }

    private static User userWithSeq(Long seq) {
        User user = User.builder()
                .email("u@test.sg")
                .password("x")
                .firstName("U")
                .lastName("L")
                .build();
        ReflectionTestUtils.setField(user, "userSeq", seq);
        return user;
    }
}
