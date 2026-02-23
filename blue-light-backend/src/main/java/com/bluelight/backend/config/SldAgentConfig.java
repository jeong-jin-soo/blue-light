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

import java.time.Duration;
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

    @Value("${sld.agent.url:http://localhost:8100}")
    private String agentUrl;

    @Value("${sld.agent.service-key:dev-service-key}")
    private String serviceKey;

    @Value("${sld.agent.timeout-seconds:120}")
    private int timeoutSeconds;

    @Bean
    public WebClient sldAgentWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)  // 연결 타임아웃 10초
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))   // 응답 타임아웃
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
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
