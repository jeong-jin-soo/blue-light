package com.bluelight.backend.api.file;

import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
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
            ".zip",                            // Archive (ELISE SLD bundle)
            ".xlsx", ".xls", ".csv"            // Circuit schedule (Excel, CSV)
    );

    /**
     * Upload a file for an application
     */
    @Transactional
    public FileResponse uploadFile(Long userSeq, Long applicationSeq, MultipartFile file, FileType fileType) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException("Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // Verify ownership (applicant)
        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

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

        // Admin: 전체 접근 / LEW: 자신에게 할당된 신청서만 / Applicant: 본인 소유만
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                application.getUser().getUserSeq(), userSeq, role, getAssignedLewSeq(application));

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

        // Admin: 전체 접근 / LEW: 자신에게 할당된 신청서만 / Applicant: 본인 소유만
        Application application = fileEntity.getApplication();
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                application.getUser().getUserSeq(), userSeq, role, getAssignedLewSeq(application));

        return fileStorageService.loadAsResource(fileEntity.getFileUrl());
    }

    /**
     * Get file entity with ownership check (for content-disposition header)
     */
    public FileEntity getFileEntity(Long userSeq, String role, Long fileSeq) {
        FileEntity fileEntity = fileRepository.findById(fileSeq)
                .orElseThrow(() -> new BusinessException("File not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));

        // Admin: 전체 접근 / LEW: 자신에게 할당된 신청서만 / Applicant: 본인 소유만
        Application app = fileEntity.getApplication();
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                app.getUser().getUserSeq(), userSeq, role, getAssignedLewSeq(app));

        return fileEntity;
    }

    /**
     * Delete a file
     */
    @Transactional
    public void deleteFile(Long userSeq, String role, Long fileSeq) {
        FileEntity fileEntity = fileRepository.findById(fileSeq)
                .orElseThrow(() -> new BusinessException("File not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));

        // Admin: 전체 접근 / LEW: 자신에게 할당된 신청서만 / Applicant: 본인 소유만
        Application delApp = fileEntity.getApplication();
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                delApp.getUser().getUserSeq(), userSeq, role, getAssignedLewSeq(delApp));

        // Delete from disk
        fileStorageService.delete(fileEntity.getFileUrl());

        // Soft delete from DB
        fileRepository.delete(fileEntity);
        log.info("File deleted: fileSeq={}", fileSeq);
    }

    /**
     * 신청서에 할당된 LEW의 userSeq 반환 (없으면 null)
     */
    private Long getAssignedLewSeq(Application application) {
        return application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq()
                : null;
    }

    private void validateFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new BusinessException("Invalid file name", HttpStatus.BAD_REQUEST, "INVALID_FILENAME");
        }
        String extension = filename.substring(filename.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(
                    "File type not allowed. Accepted: PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP, XLSX, XLS, CSV",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FILE_TYPE"
            );
        }
    }
}
