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

/**
 * 샘플 파일 관리 서비스
 * - 관리자가 카테고리별 참고 파일을 업로드/삭제 (카테고리당 여러 파일 가능)
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
     * 샘플 파일 업로드 (카테고리에 추가)
     */
    @Transactional
    public SampleFileResponse upload(String categoryKey, MultipartFile file) {
        String storedPath = fileStorageService.store(file, SAMPLE_SUB_DIR);

        int sortOrder = (int) sampleFileRepository.countByCategoryKey(categoryKey);

        SampleFile sampleFile = SampleFile.builder()
                .categoryKey(categoryKey)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .sortOrder(sortOrder)
                .build();
        sampleFileRepository.save(sampleFile);
        log.info("Sample file uploaded: category={}, filename={}, sortOrder={}",
                categoryKey, file.getOriginalFilename(), sortOrder);

        return SampleFileResponse.from(sampleFile);
    }

    /**
     * 샘플 파일 개별 삭제 (seq 기반)
     */
    @Transactional
    public void delete(Long sampleFileSeq) {
        SampleFile sampleFile = sampleFileRepository.findById(sampleFileSeq)
                .orElseThrow(() -> new BusinessException(
                        "Sample file not found: seq=" + sampleFileSeq,
                        HttpStatus.NOT_FOUND, "SAMPLE_NOT_FOUND"));

        fileStorageService.delete(sampleFile.getFileUrl());
        sampleFileRepository.delete(sampleFile);
        log.info("Sample file deleted: seq={}, category={}", sampleFileSeq, sampleFile.getCategoryKey());
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
     * 샘플 파일 다운로드 (seq 기반, Resource 반환)
     */
    public Resource download(Long sampleFileSeq) {
        SampleFile sampleFile = sampleFileRepository.findById(sampleFileSeq)
                .orElseThrow(() -> new BusinessException(
                        "Sample file not found: seq=" + sampleFileSeq,
                        HttpStatus.NOT_FOUND, "SAMPLE_NOT_FOUND"));

        return fileStorageService.loadAsResource(sampleFile.getFileUrl());
    }

    /**
     * seq로 샘플 파일 엔티티 조회 (다운로드 헤더용)
     */
    public SampleFile getEntity(Long sampleFileSeq) {
        return sampleFileRepository.findById(sampleFileSeq)
                .orElseThrow(() -> new BusinessException(
                        "Sample file not found: seq=" + sampleFileSeq,
                        HttpStatus.NOT_FOUND, "SAMPLE_NOT_FOUND"));
    }
}
