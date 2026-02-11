package com.bluelight.backend.api.file;

import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * File management service
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FileService {

    private final FileRepository fileRepository;
    private final ApplicationRepository applicationRepository;
    private final FileStorageService fileStorageService;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".jpg", ".jpeg", ".png",
            ".dwg", ".dxf", ".dgn",           // CAD drawing formats (ELISE SLD)
            ".tif", ".tiff", ".gif",           // Image formats (ELISE SLD)
            ".zip"                             // Archive (ELISE SLD bundle)
    );

    /**
     * Upload a file for an application
     */
    @Transactional
    public FileResponse uploadFile(Long userSeq, Long applicationSeq, MultipartFile file, FileType fileType) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException("Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // Verify ownership (applicant) or allow admin
        if (!application.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        // Validate file extension
        validateFileExtension(file.getOriginalFilename());

        // Store file on disk
        String subDirectory = "applications/" + applicationSeq;
        String storedPath = fileStorageService.store(file, subDirectory);

        // Create DB record
        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(fileType)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();

        FileEntity saved = fileRepository.save(fileEntity);
        log.info("File uploaded: fileSeq={}, applicationSeq={}, type={}, name={}, size={}",
                saved.getFileSeq(), applicationSeq, fileType, file.getOriginalFilename(), file.getSize());

        return FileResponse.from(saved);
    }

    /**
     * Upload a file for an application (admin - no ownership check)
     */
    @Transactional
    public FileResponse uploadFileAsAdmin(Long applicationSeq, MultipartFile file, FileType fileType) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException("Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        validateFileExtension(file.getOriginalFilename());

        String subDirectory = "applications/" + applicationSeq;
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(fileType)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();

        FileEntity saved = fileRepository.save(fileEntity);
        log.info("File uploaded by admin: fileSeq={}, applicationSeq={}, type={}, size={}", saved.getFileSeq(), applicationSeq, fileType, file.getSize());

        return FileResponse.from(saved);
    }

    /**
     * Get all files for an application (with ownership check)
     */
    public List<FileResponse> getFilesByApplication(Long userSeq, String role, Long applicationSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException("Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // Admins can view any application's files; applicants can only view their own
        if (!"ROLE_ADMIN".equals(role) && !application.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        return fileRepository.findByApplicationApplicationSeq(applicationSeq)
                .stream()
                .map(FileResponse::from)
                .toList();
    }

    /**
     * Download a file (with ownership check)
     */
    public Resource downloadFile(Long userSeq, String role, Long fileSeq) {
        FileEntity fileEntity = fileRepository.findById(fileSeq)
                .orElseThrow(() -> new BusinessException("File not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));

        // Admins can download any file; applicants can only download their own
        if (!"ROLE_ADMIN".equals(role) && !fileEntity.getApplication().getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        return fileStorageService.loadAsResource(fileEntity.getFileUrl());
    }

    /**
     * Get file entity with ownership check (for content-disposition header)
     */
    public FileEntity getFileEntity(Long userSeq, String role, Long fileSeq) {
        FileEntity fileEntity = fileRepository.findById(fileSeq)
                .orElseThrow(() -> new BusinessException("File not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));

        // Admins can access any file; applicants can only access their own
        if (!"ROLE_ADMIN".equals(role) && !fileEntity.getApplication().getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        return fileEntity;
    }

    /**
     * Delete a file
     */
    @Transactional
    public void deleteFile(Long userSeq, String role, Long fileSeq) {
        FileEntity fileEntity = fileRepository.findById(fileSeq)
                .orElseThrow(() -> new BusinessException("File not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));

        // Admins can delete any file; applicants can only delete their own
        if (!"ROLE_ADMIN".equals(role) && !fileEntity.getApplication().getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        // Delete from disk
        fileStorageService.delete(fileEntity.getFileUrl());

        // Soft delete from DB
        fileRepository.delete(fileEntity);
        log.info("File deleted: fileSeq={}", fileSeq);
    }

    private void validateFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new BusinessException("Invalid file name", HttpStatus.BAD_REQUEST, "INVALID_FILENAME");
        }
        String extension = filename.substring(filename.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(
                    "File type not allowed. Accepted: PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FILE_TYPE"
            );
        }
    }
}
