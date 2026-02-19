package com.bluelight.backend.api.file;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.FileEncryptionUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Local disk file storage implementation with at-rest encryption
 *
 * 암호화 설정 시 (FILE_ENCRYPTION_KEY 환경변수):
 *   - store(): 파일 데이터 → AES-256-GCM 암호화 → 디스크 저장
 *   - loadAsResource(): 디스크 → 복호화 → ByteArrayResource 반환
 * 암호화 미설정 시:
 *   - 기존과 동일하게 평문 저장/로드
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {

    private final FileEncryptionUtil fileEncryptionUtil;

    @Value("${file.upload-dir}")
    private String uploadDir;

    private Path rootLocation;

    @PostConstruct
    public void init() {
        this.rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootLocation);
            log.info("File upload directory initialized: {}", this.rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory: " + this.rootLocation, e);
        }
    }

    @Override
    public String store(MultipartFile file, String subDirectory) {
        if (file.isEmpty()) {
            throw new BusinessException("Cannot store empty file", HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }

        try {
            // Create subdirectory (e.g., "applications/1")
            Path targetDir = this.rootLocation.resolve(subDirectory).normalize();
            Files.createDirectories(targetDir);

            // Generate unique filename to prevent collision
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String storedFilename = UUID.randomUUID() + extension;
            Path targetPath = targetDir.resolve(storedFilename);

            // 암호화 저장 또는 평문 저장
            if (fileEncryptionUtil.isEnabled()) {
                byte[] plainData = file.getBytes();
                byte[] encryptedData = fileEncryptionUtil.encrypt(plainData);
                Files.write(targetPath, encryptedData);
                log.info("File stored (encrypted): {} -> {}", originalFilename, subDirectory + "/" + storedFilename);
            } else {
                Files.write(targetPath, file.getBytes());
                log.info("File stored: {} -> {}", originalFilename, subDirectory + "/" + storedFilename);
            }

            // Return relative path from root
            return subDirectory + "/" + storedFilename;

        } catch (IOException e) {
            throw new BusinessException("Failed to store file", HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_ERROR");
        }
    }

    @Override
    public Resource loadAsResource(String filePath) {
        try {
            Path file = this.rootLocation.resolve(filePath).normalize();

            if (!Files.exists(file) || !Files.isReadable(file)) {
                throw new BusinessException("File not found: " + filePath, HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
            }

            // 암호화 활성화 시 복호화 후 반환
            if (fileEncryptionUtil.isEnabled()) {
                byte[] encryptedData = Files.readAllBytes(file);
                byte[] decryptedData = fileEncryptionUtil.decrypt(encryptedData);
                return new ByteArrayResource(decryptedData);
            }

            // 암호화 비활성화 시 기존 방식
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new BusinessException("File not found: " + filePath, HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
            }
        } catch (MalformedURLException e) {
            throw new BusinessException("File not found: " + filePath, HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        } catch (IOException e) {
            throw new BusinessException("Failed to read file: " + filePath, HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR");
        }
    }

    @Override
    public void delete(String filePath) {
        try {
            Path file = this.rootLocation.resolve(filePath).normalize();
            Files.deleteIfExists(file);
            log.info("File deleted: {}", filePath);
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", filePath, e);
        }
    }
}
