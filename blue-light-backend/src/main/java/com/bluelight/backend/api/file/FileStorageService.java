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
     * Load a file as a Resource
     */
    Resource loadAsResource(String filePath);

    /**
     * Delete a file
     */
    void delete(String filePath);
}
