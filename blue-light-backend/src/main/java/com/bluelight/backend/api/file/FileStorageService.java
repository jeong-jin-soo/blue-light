package com.bluelight.backend.api.file;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * File storage abstraction.
 * MVP: LocalFileStorageService (disk).
 * Future: S3FileStorageService (AWS S3).
 */
public interface FileStorageService {

    /**
     * Store a file and return the stored path/key
     */
    String store(MultipartFile file, String subDirectory);

    /**
     * Store raw bytes and return the stored path/key
     * - AI 생성 파일 등 MultipartFile이 아닌 바이트 배열을 직접 저장할 때 사용
     */
    String storeBytes(byte[] data, String filename, String subDirectory);

    /**
     * Load a file as a Resource
     */
    Resource loadAsResource(String filePath);

    /**
     * Delete a file
     */
    void delete(String filePath);
}
