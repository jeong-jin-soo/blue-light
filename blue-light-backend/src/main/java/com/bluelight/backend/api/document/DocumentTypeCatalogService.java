package com.bluelight.backend.api.document;

import com.bluelight.backend.api.document.dto.DocumentTypeDto;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.document.DocumentTypeCatalog;
import com.bluelight.backend.domain.document.DocumentTypeCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Document Type Catalog 조회 서비스
 *
 * 캐싱 노트: 카탈로그 변경 빈도가 낮으므로 향후 @Cacheable("documentTypes") 적용 가능.
 * Phase 2 PR#1 범위에서는 캐싱 인프라(@EnableCaching) 도입 없이 단순 DB 조회.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentTypeCatalogService {

    private final DocumentTypeCatalogRepository catalogRepository;

    /**
     * 활성화된 카탈로그를 display_order 오름차순으로 반환
     */
    public List<DocumentTypeDto> listActive() {
        return catalogRepository.findAllByActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .map(DocumentTypeDto::from)
                .toList();
    }

    /**
     * 코드로 활성 카탈로그 조회. 미존재 시 400 UNKNOWN_DOCUMENT_TYPE.
     */
    public DocumentTypeCatalog requireActiveByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(
                    "documentTypeCode is required",
                    HttpStatus.BAD_REQUEST,
                    "UNKNOWN_DOCUMENT_TYPE");
        }
        return catalogRepository.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> new BusinessException(
                        "Unknown document type: " + code,
                        HttpStatus.BAD_REQUEST,
                        "UNKNOWN_DOCUMENT_TYPE"));
    }
}
