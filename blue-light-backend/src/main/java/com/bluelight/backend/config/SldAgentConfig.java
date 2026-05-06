package com.bluelight.backend.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * SLD AI Agent (Python FastAPI) 연동 설정
 * - WebClient 빈 + 서비스 설정값
 * - 타임아웃: connect 10s, read/write = timeoutSeconds (기본 120s)
 */
@Configuration
@Getter
@Slf4j
public class SldAgentConfig {

    @Value("${sld.agent.url:http://127.0.0.1:8100}")
    private String agentUrl;

    @Value("${sld.agent.service-key:dev-service-key}")
    private String serviceKey;

    @Value("${sld.agent.timeout-seconds:120}")
    private int timeoutSeconds;

    /**
     * WebClient ReadTimeout. SseEmitter timeout(`sld.agent.sse-timeout-ms`) 및
     * AI service heartbeat 간격과 정합되어야 한다. 한쪽만 짧으면 정상 SSE도 끊긴다.
     */
    @Value("${sld.agent.read-timeout-seconds:600}")
    private int readTimeoutSeconds;

    @Bean
    public WebClient sldAgentWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)  // 연결 타임아웃 10초
                // responseTimeout 제거 — SSE 스트리밍에서는 전체 응답 시간 제한 불필요
                // 상위 SseEmitter timeout과 동일한 값을 사용해 끊김 일관성 유지.
                // Nginx 사용 시 proxy_read_timeout도 동일 값으로 설정할 것.
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(agentUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Service-Key", serviceKey)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))  // 2MB (SVG 응답 대응)
                .build();
    }
}
