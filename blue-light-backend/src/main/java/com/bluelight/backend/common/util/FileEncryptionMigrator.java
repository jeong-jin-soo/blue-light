package com.bluelight.backend.common.util;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 기존 평문 파일을 AES-256-GCM 암호화로 일괄 변환하는 마이그레이터
 *
 * 활성화: file.encryption-migrate=true (환경변수 FILE_ENCRYPTION_MIGRATE=true)
 * 조건: file.encryption-key가 설정되어 있어야 함 (FileEncryptionUtil.isEnabled())
 *
 * 동작:
 *   1. uploads/ 디렉토리 재귀 탐색
 *   2. 각 파일을 읽어 복호화 시도 → 실패하면 평문으로 간주
 *   3. 평문 파일을 암호화하여 덮어쓰기
 *   4. 서버 기동 시 1회 실행 (재실행 시 이미 암호화된 파일은 건너뜀)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.encryption-migrate", havingValue = "true")
public class FileEncryptionMigrator {

    private final FileEncryptionUtil fileEncryptionUtil;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @PostConstruct
    public void migrate() {
        if (!fileEncryptionUtil.isEnabled()) {
            log.warn("파일 마이그레이션 건너뜀: 암호화 키가 설정되지 않음");
            return;
        }

        Path rootPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.exists(rootPath)) {
            log.info("파일 마이그레이션 건너뜀: 업로드 디렉토리 없음 ({})", rootPath);
            return;
        }

        log.info("파일 암호화 마이그레이션 시작: {}", rootPath);

        AtomicInteger encrypted = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        try (Stream<Path> files = Files.walk(rootPath)) {
            files.filter(Files::isRegularFile).forEach(filePath -> {
                try {
                    byte[] data = Files.readAllBytes(filePath);

                    if (data.length == 0) {
                        skipped.incrementAndGet();
                        return;
                    }

                    // 이미 암호화되었는지 확인: 복호화 시도
                    if (isAlreadyEncrypted(data)) {
                        skipped.incrementAndGet();
                        return;
                    }

                    // 평문 → 암호화
                    byte[] encryptedData = fileEncryptionUtil.encrypt(data);
                    Files.write(filePath, encryptedData);
                    encrypted.incrementAndGet();

                    log.debug("파일 암호화 완료: {}", filePath);

                } catch (IOException e) {
                    failed.incrementAndGet();
                    log.error("파일 마이그레이션 실패: {}", filePath, e);
                }
            });
        } catch (IOException e) {
            log.error("파일 마이그레이션 디렉토리 탐색 실패", e);
            return;
        }

        log.info("파일 암호화 마이그레이션 완료: 암호화={}건, 건너뜀={}건, 실패={}건",
                encrypted.get(), skipped.get(), failed.get());
    }

    /**
     * 데이터가 이미 암호화되어 있는지 판별
     * 복호화 시도하여 성공하면 암호화된 것으로 판단
     */
    private boolean isAlreadyEncrypted(byte[] data) {
        if (!fileEncryptionUtil.isLikelyEncrypted(data)) {
            return false;
        }

        try {
            fileEncryptionUtil.decrypt(data);
            return true;  // 복호화 성공 → 이미 암호화된 파일
        } catch (Exception e) {
            return false;  // 복호화 실패 → 평문 파일
        }
    }
}
