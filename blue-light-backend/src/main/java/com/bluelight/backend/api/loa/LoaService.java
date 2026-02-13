package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * LOA 비즈니스 로직 오케스트레이션 서비스
 * - PDF 생성, 서명, 상태 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoaService {

    private final ApplicationRepository applicationRepository;
    private final FileRepository fileRepository;
    private final LoaGenerationService loaGenerationService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    /**
     * LOA PDF 생성 (Admin/LEW 액션)
     * - 기존 미서명 LOA가 있으면 삭제 후 재생성
     */
    @Transactional
    public FileResponse generateLoa(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);

        // RENEWAL 타입은 LOA 자동 생성 불가 — 신청자가 관계기관에서 받아 업로드
        if (application.getApplicationType() == ApplicationType.RENEWAL) {
            throw new BusinessException(
                    "LOA cannot be auto-generated for renewal applications. Please upload the LOA document.",
                    HttpStatus.BAD_REQUEST, "LOA_RENEWAL_UPLOAD_REQUIRED");
        }

        // 이미 서명된 LOA가 있으면 재생성 불가
        if (application.getLoaSignatureUrl() != null) {
            throw new BusinessException("LOA has already been signed. Cannot regenerate.",
                    HttpStatus.BAD_REQUEST, "LOA_ALREADY_SIGNED");
        }

        // 기존 미서명 LOA 삭제 (재생성 케이스)
        List<FileEntity> existingLoas = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);
        existingLoas.forEach(f -> fileRepository.delete(f));

        // 타입에 따라 PDF 생성
        String pdfRelativePath;
        if (application.getApplicationType() == ApplicationType.RENEWAL) {
            pdfRelativePath = loaGenerationService.generateRenewalLoa(application);
        } else {
            pdfRelativePath = loaGenerationService.generateNewLicenceLoa(application);
        }

        // 파일 크기 확인
        Path absolutePath = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(pdfRelativePath);
        long fileSize;
        try {
            fileSize = Files.size(absolutePath);
        } catch (IOException e) {
            fileSize = 0;
        }

        // FileEntity 레코드 생성
        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(FileType.OWNER_AUTH_LETTER)
                .fileUrl(pdfRelativePath)
                .originalFilename("LOA_" + applicationSeq + ".pdf")
                .fileSize(fileSize)
                .build();

        FileEntity saved = fileRepository.save(fileEntity);
        log.info("LOA generated: applicationSeq={}, fileSeq={}", applicationSeq, saved.getFileSeq());

        return FileResponse.from(saved);
    }

    /**
     * LOA 전자서명 (Applicant 액션)
     * - 서명 이미지 저장 → PDF에 임베드 → FileEntity 업데이트
     */
    @Transactional
    public FileResponse signLoa(Long userSeq, Long applicationSeq, MultipartFile signatureImage) {
        Application application = findApplicationOrThrow(applicationSeq);

        // 소유권 검증
        if (!application.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        // 이미 서명된 경우
        if (application.getLoaSignatureUrl() != null) {
            throw new BusinessException("LOA has already been signed",
                    HttpStatus.BAD_REQUEST, "LOA_ALREADY_SIGNED");
        }

        // LOA PDF 존재 확인
        List<FileEntity> loaFiles = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);

        if (loaFiles.isEmpty()) {
            throw new BusinessException("LOA has not been generated yet",
                    HttpStatus.BAD_REQUEST, "LOA_NOT_FOUND");
        }

        FileEntity loaFile = loaFiles.get(loaFiles.size() - 1); // 최신 LOA

        // 서명 이미지 저장
        String subDirectory = "applications/" + applicationSeq;
        String sigFilename = "signature_loa_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        Path sigDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(subDirectory);

        try {
            Files.createDirectories(sigDir);
            Files.copy(signatureImage.getInputStream(), sigDir.resolve(sigFilename),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("Failed to save signature image",
                    HttpStatus.INTERNAL_SERVER_ERROR, "SIGNATURE_SAVE_ERROR");
        }

        String signatureRelativePath = subDirectory + "/" + sigFilename;

        // PDF에 서명 임베드
        String signedPdfPath = loaGenerationService.embedSignatureIntoPdf(
                loaFile.getFileUrl(), signatureRelativePath, application);

        // FileEntity 업데이트 (서명된 PDF로 교체)
        Path signedAbsPath = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(signedPdfPath);
        long fileSize;
        try {
            fileSize = Files.size(signedAbsPath);
        } catch (IOException e) {
            fileSize = 0;
        }
        loaFile.updateFileUrl(signedPdfPath, "LOA_SIGNED_" + applicationSeq + ".pdf", fileSize);

        // Application에 서명 정보 등록
        application.registerLoaSignature(signatureRelativePath);

        log.info("LOA signed: applicationSeq={}, signatureUrl={}", applicationSeq, signatureRelativePath);

        return FileResponse.from(loaFile);
    }

    /**
     * LOA 상태 조회
     * - Owner, ADMIN, LEW 모두 접근 가능
     */
    public LoaStatusResponse getLoaStatus(Long userSeq, String role, Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);

        // 접근 권한 검증: owner 또는 ADMIN/LEW
        boolean isAdminOrLew = "ROLE_ADMIN".equals(role) || "ROLE_LEW".equals(role);
        boolean isOwner = application.getUser().getUserSeq().equals(userSeq);

        if (!isAdminOrLew && !isOwner) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        List<FileEntity> loaFiles = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);

        boolean loaGenerated = !loaFiles.isEmpty();
        boolean loaSigned = application.getLoaSignatureUrl() != null;
        Long loaFileSeq = loaGenerated ? loaFiles.get(loaFiles.size() - 1).getFileSeq() : null;

        return LoaStatusResponse.builder()
                .applicationSeq(applicationSeq)
                .loaGenerated(loaGenerated)
                .loaSigned(loaSigned)
                .loaSignedAt(application.getLoaSignedAt())
                .loaFileSeq(loaFileSeq)
                .applicationType(application.getApplicationType().name())
                .build();
    }

    private Application findApplicationOrThrow(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }
}
