package com.bluelight.backend.api.file;

import com.bluelight.backend.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local disk file storage implementation
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

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

            // Store file
            Path targetPath = targetDir.resolve(storedFilename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            // Return relative path from root
            String relativePath = subDirectory + "/" + storedFilename;
            log.info("File stored: {} -> {}", originalFilename, relativePath);
            return relativePath;

        } catch (IOException e) {
            throw new BusinessException("Failed to store file", HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_ERROR");
        }
    }

    @Override
    public Resource loadAsResource(String filePath) {
        try {
            Path file = this.rootLocation.resolve(filePath).normalize();
            Resource resource = new UrlResource(file.toUri());

            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new BusinessException("File not found: " + filePath, HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
            }
        } catch (MalformedURLException e) {
            throw new BusinessException("File not found: " + filePath, HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
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
