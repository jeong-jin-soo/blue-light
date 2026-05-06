package com.bluelight.backend.api.document;

import com.bluelight.backend.api.document.dto.DocumentTypeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Document Type Catalog API
 *
 * GET /api/document-types — 인증된 모든 사용자 조회 가능
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/document-types")
public class DocumentTypeCatalogController {

    private final DocumentTypeCatalogService catalogService;

    @GetMapping
    public ResponseEntity<List<DocumentTypeDto>> list() {
        return ResponseEntity.ok(catalogService.listActive());
    }
}
