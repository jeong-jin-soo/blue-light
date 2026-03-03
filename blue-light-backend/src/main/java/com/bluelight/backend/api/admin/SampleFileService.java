package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SampleFileResponse;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.SampleFile;
import com.bluelight.backend.domain.file.SampleFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/**
 * 샘플 파일 관리 서비스
 * - 관리자가 카테고리별 참고 파일을 업로드/삭제
 * - 신청자가 조회/다운로드
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SampleFileService {

    private final SampleFileRepository sampleFileRepository;
    private final FileStorageService fileStorageService;

    private static final String SAMPLE_SUB_DIR = "samples";

    /**
     * 샘플 파일 업로드 (upsert: 기존 있으면 교체)
     */
    @Transactional
    public SampleFileResponse upload(String categoryKey, MultipartFile file) {
        String storedPath = fileStorageService.store(file, SAMPLE_SUB_DIR);

        Optional<SampleFile> existing = sampleFileRepository.findByCategoryKey(categoryKey);
        SampleFile sampleFile;

        if (existing.isPresent()) {
            sampleFile = existing.get();
            // 기존 디스크 파일 삭제
            fileStorageService.delete(sampleFile.getFileUrl());
            // DB 레코드 업데이트
            sampleFile.updateFile(storedPath, file.getOriginalFilename(), file.getSize());
            log.info("Sample file replaced: category={}, filename={}", categoryKey, file.getOriginalFilename());
        } else {
            sampleFile = SampleFile.builder()
                    .categoryKey(categoryKey)
                    .fileUrl(storedPath)
                    .originalFilename(file.getOriginalFilename())
                    .fileSize(file.getSize())
                    .build();
            sampleFileRepository.save(sampleFile);
            log.info("Sample file uploaded: category={}, filename={}", categoryKey, file.getOriginalFilename());
        }

        return SampleFileResponse.from(sampleFile);
    }

    /**
     * 샘플 파일 삭제
     */
    @Transactional
    public void delete(String categoryKey) {
        SampleFile sampleFile = sampleFileRepository.findByCategoryKey(categoryKey)
                .orElseThrow(() -> new BusinessException(
                        "Sample file not found for category: " + categoryKey,
                        HttpStatus.NOT_FOUND, "SAMPLE_NOT_FOUND"));

        fileStorageService.delete(sampleFile.getFileUrl());
        sampleFileRepository.delete(sampleFile);
        log.info("Sample file deleted: category={}", categoryKey);
    }

    /**
     * 전체 샘플 파일 목록 조회
     */
    public List<SampleFileResponse> getAll() {
        return sampleFileRepository.findAll().stream()
                .map(SampleFileResponse::from)
                .toList();
    }

    /**
     * 샘플 파일 다운로드 (Resource 반환)
     */
    public Resource download(String categoryKey) {
        SampleFile sampleFile = sampleFileRepository.findByCategoryKey(categoryKey)
                .orElseThrow(() -> new BusinessException(
                        "Sample file not found for category: " + categoryKey,
                        HttpStatus.NOT_FOUND, "SAMPLE_NOT_FOUND"));

        return fileStorageService.loadAsResource(sampleFile.getFileUrl());
    }

    /**
     * 카테고리 키로 샘플 파일 엔티티 조회 (다운로드 헤더용)
     */
    public SampleFile getEntity(String categoryKey) {
        return sampleFileRepository.findByCategoryKey(categoryKey)
                .orElseThrow(() -> new BusinessException(
                        "Sample file not found for category: " + categoryKey,
                        HttpStatus.NOT_FOUND, "SAMPLE_NOT_FOUND"));
    }
}
