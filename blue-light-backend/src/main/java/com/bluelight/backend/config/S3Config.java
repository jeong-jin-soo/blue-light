package com.bluelight.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * AWS S3 클라이언트 설정
 *
 * file.storage-type=s3 일 때만 활성화.
 * 인증 순서 (DefaultCredentialsProvider):
 *   1. 환경변수 (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 *   2. Java 시스템 프로퍼티
 *   3. AWS CLI 프로파일 (~/.aws/credentials)
 *   4. EC2/ECS IAM Role (운영 환경)
 *
 * 로컬 테스트: AWS CLI 프로파일 또는 환경변수 설정
 * 운영 환경: EC2/ECS에 IAM Role 부여 (키 불필요)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "file.storage-type", havingValue = "s3")
public class S3Config {

    @Value("${file.s3.region:ap-southeast-1}")
    private String region;

    @Value("${file.s3.endpoint:}")
    private String endpoint;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());

        // 커스텀 엔드포인트 (LocalStack 등 로컬 테스트용)
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true);  // LocalStack 호환
            log.info("S3 커스텀 엔드포인트 설정: {}", endpoint);
        }

        log.info("S3 클라이언트 초기화: region={}", region);
        return builder.build();
    }
}
