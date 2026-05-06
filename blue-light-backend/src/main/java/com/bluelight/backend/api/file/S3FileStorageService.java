package com.bluelight.backend.api.file;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.FileEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.util.UUID;

/**
 * AWS S3 파일 저장 구현체.
 *
 * <p>{@code file.storage-type=s3} 일 때 활성화.</p>
 *
 * <h2>암호화 설계 — 이중 방어(defense in depth)</h2>
 * <ol>
 *   <li><b>클라이언트 측 AES-256-GCM</b>: {@link FileEncryptionUtil}이 로컬 구현체와
 *       동일하게 동작. {@code FILE_ENCRYPTION_KEY} 환경변수 하나로 로컬·S3 파일을
 *       같은 키 체계로 관리한다. 로컬 → S3 마이그레이션 시 재암호화 불필요.</li>
 *   <li><b>S3 서버 측 SSE-S3</b>: AWS 관리 키로 추가 암호화. 버킷 misconfig로
 *       공개 노출되는 사고에도 평문이 나가지 않도록 하는 최후 방어선.</li>
 * </ol>
 *
 * <h2>인증</h2>
 * IAM Role(EC2/ECS) 또는 AWS CLI 프로파일/환경변수 (DefaultCredentialsProvider).
 *
 * <h2>S3 키 구조</h2>
 * {@code {subDirectory}/{UUID}.{ext}} — 로컬 경로 패턴 동일. 마이그레이션 시
 * {@code file_url} 컬럼 값을 그대로 재사용 가능.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "file.storage-type", havingValue = "s3")
@RequiredArgsConstructor
public class S3FileStorageService implements FileStorageService {

    private final S3Client s3Client;
    private final FileEncryptionUtil fileEncryptionUtil;

    @Value("${file.s3.bucket}")
    private String bucket;

    @Override
    public String store(MultipartFile file, String subDirectory) {
        if (file.isEmpty()) {
            throw new BusinessException("Cannot store empty file", HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String extension = extractExtension(originalFilename);
            String storedFilename = UUID.randomUUID() + extension;
            String s3Key = subDirectory + "/" + storedFilename;

            byte[] payload = file.getBytes();
            boolean encrypted = fileEncryptionUtil.isEnabled();
            if (encrypted) {
                payload = fileEncryptionUtil.encrypt(payload);
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .serverSideEncryption(ServerSideEncryption.AES256)  // SSE-S3 (defense in depth)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(payload));

            log.info("File stored to S3 ({}): {} -> s3://{}/{}",
                    encrypted ? "encrypted" : "plaintext", originalFilename, bucket, s3Key);
            return s3Key;

        } catch (IOException e) {
            throw new BusinessException("Failed to store file", HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_ERROR");
        } catch (S3Exception e) {
            log.error("S3 upload failed: {}", e.getMessage(), e);
            throw new BusinessException("Failed to store file to S3", HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_ERROR");
        }
    }

    @Override
    public String storeBytes(byte[] data, String filename, String subDirectory) {
        if (data == null || data.length == 0) {
            throw new BusinessException("Cannot store empty data", HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }

        try {
            String extension = extractExtension(filename);
            String storedFilename = UUID.randomUUID() + extension;
            String s3Key = subDirectory + "/" + storedFilename;

            byte[] payload = data;
            boolean encrypted = fileEncryptionUtil.isEnabled();
            if (encrypted) {
                payload = fileEncryptionUtil.encrypt(payload);
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(payload));

            log.info("Bytes stored to S3 ({}): {} -> s3://{}/{}",
                    encrypted ? "encrypted" : "plaintext", filename, bucket, s3Key);
            return s3Key;

        } catch (S3Exception e) {
            log.error("S3 upload failed: {}", e.getMessage(), e);
            throw new BusinessException("Failed to store file to S3", HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_ERROR");
        }
    }

    @Override
    public Resource loadAsResource(String filePath) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(filePath)
                    .build();

            byte[] data = s3Client.getObjectAsBytes(getRequest).asByteArray();

            // 로컬 구현체와 동일하게, 키가 설정돼 있으면 저장시 암호화된 것으로 간주하고 복호화.
            if (fileEncryptionUtil.isEnabled()) {
                data = fileEncryptionUtil.decrypt(data);
            }

            return new ByteArrayResource(data);

        } catch (NoSuchKeyException e) {
            throw new BusinessException("File not found: " + filePath, HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        } catch (S3Exception e) {
            log.error("S3 download failed: key={}, error={}", filePath, e.getMessage(), e);
            throw new BusinessException("Failed to read file from S3", HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR");
        }
    }

    @Override
    public void delete(String filePath) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(filePath)
                    .build();

            s3Client.deleteObject(deleteRequest);
            log.info("File deleted from S3: s3://{}/{}", bucket, filePath);

        } catch (S3Exception e) {
            log.warn("Failed to delete file from S3: key={}, error={}", filePath, e.getMessage());
        }
    }

    private String extractExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."));
        }
        return "";
    }
}
