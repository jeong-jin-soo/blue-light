package com.bluelight.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 알림 인프라용 ObjectMapper 폴백 빈 (PR-0B).
 *
 * <p>Spring Boot 4 의 starter-webmvc/webflux 구조는 starter-web 과 달리 Jackson
 * AutoConfiguration 을 자동 노출하지 않는 경우가 있다. ObjectMapper 빈이 컨텍스트에 없으면
 * {@link com.bluelight.backend.api.notification.outbox.NotificationOutboxWriter} 가 주입을
 * 받지 못해 컨텍스트 로드가 실패하므로 명시 fallback 을 둔다.</p>
 *
 * <p>{@code @ConditionalOnMissingBean} 으로 자동 설정이 ObjectMapper 를 제공한다면 본 빈은 등록
 * 되지 않아 중복 충돌을 피한다.</p>
 */
@Configuration
public class NotificationJacksonConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper notificationObjectMapper() {
        return new ObjectMapper();
    }
}
