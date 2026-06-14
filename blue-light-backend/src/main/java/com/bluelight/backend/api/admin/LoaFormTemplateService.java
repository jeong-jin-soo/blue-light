package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.LoaFormTemplateResponse;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.loaform.LoaFormTemplate;
import com.bluelight.backend.domain.loaform.LoaFormTemplateRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LoA 폼 템플릿 관리 서비스 (admin).
 *
 * <p>스펙: {@code loa-exchange-redesign-spec.md} §2.1, §3.1, §3.2 (PR2).</p>
 *
 * <h2>active 단일성</h2>
 * <p>MySQL 8.0 은 부분 유니크 인덱스를 지원하지 않아 "동시 active 1건" 을 DB 제약으로 걸 수 없다.
 * 따라서 {@link #activate(Long, Long)} 가 동일 트랜잭션 내에서 기존 active 를 전부 비활성화한 뒤
 * 대상만 활성화한다. PayNow QR 의 "기존 삭제 후 신규" 패턴과 동형
 * ({@code AdminPriceSettingsController#uploadPaymentQr}).</p>
 *
 * <h2>FileType 처리 (설계 결정)</h2>
 * <p>LoA 폼 PDF 전용 FileType 을 신설하지 않고 기존 {@link FileType#OWNER_AUTH_LETTER} 를 재사용한다.
 * 이유: (1) 폼 PDF 는 "Letter of Appointment" 문서 그 자체라 의미가 정확히 일치하고, (2) 신규 enum
 * 추가는 schema/DTO/프론트 매핑 파급을 만든다. 단 이 파일은 특정 신청에 묶이지 않으므로
 * {@link FileEntity} 생성 시 {@code application = null} 로 둔다 (SLD 전용 주문 파일이 application 없이
 * 저장되는 것과 동일 — {@code FileEntity.application} 은 nullable). 신청별 LoA 업로드(OWNER_AUTH_LETTER
 * with application)와는 application FK 유무로 구분된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoaFormTemplateService {

    private final LoaFormTemplateRepository templateRepository;
    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final UserRepository userRepository;

    private static final String STORE_SUBDIR = "loa-form-templates";

    // ── 조회 ──────────────────────────────

    /** 전체 버전 목록 (최신순, soft-deleted 제외) + 업로더 표시명 매핑. */
    public List<LoaFormTemplateResponse> list() {
        List<LoaFormTemplate> templates = templateRepository.findAllByOrderByUploadedAtDesc();
        Map<Long, String> nameByUserSeq = resolveUploaderNames(templates);
        return templates.stream()
                .map(t -> LoaFormTemplateResponse.from(t, nameByUserSeq.get(t.getUploadedBy())))
                .collect(Collectors.toList());
    }

    /** admin 검수용 파일 조회 (다운로드 컨트롤러가 사용). */
    public FileEntity getFileForTemplate(Long templateSeq) {
        LoaFormTemplate template = findOrThrow(templateSeq);
        return fileRepository.findById(template.getFileSeq())
                .orElseThrow(() -> new BusinessException(
                        "LoA form file not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));
    }

    public Resource loadResource(String storedPath) {
        return fileStorageService.loadAsResource(storedPath);
    }

    // ── 업로드 ──────────────────────────────

    /**
     * 신규 폼 업로드.
     *
     * @param file     폼 PDF (multipart)
     * @param label    운영용 표시 라벨
     * @param activate true 면 업로드 직후 이 버전을 active 로 지정(기존 active 비활성화)
     * @param userSeq  업로더 user_seq
     */
    @Transactional
    public LoaFormTemplateResponse upload(MultipartFile file, String label, boolean activate, Long userSeq) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("File is required", HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        }
        if (label == null || label.isBlank()) {
            throw new BusinessException("Label is required", HttpStatus.BAD_REQUEST, "LABEL_REQUIRED");
        }

        // PDF 만 허용 (운영용 서식 — PayNow QR 가 image/* 만 받는 것과 동형 가드).
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            throw new BusinessException("Only PDF files are allowed", HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE");
        }

        // 1) 파일 저장 (S3/Local + AES-256).
        String storedPath = fileStorageService.store(file, STORE_SUBDIR);

        // 2) FileEntity 생성 — application 없이(폼은 특정 신청에 묶이지 않음, 설계 메모 참조).
        FileEntity fileEntity = FileEntity.builder()
                .fileType(FileType.OWNER_AUTH_LETTER)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();
        fileEntity = fileRepository.save(fileEntity);

        // 3) 템플릿 레코드 생성.
        LoaFormTemplate template = LoaFormTemplate.builder()
                .label(label.trim())
                .fileSeq(fileEntity.getFileSeq())
                .isActive(false)
                .uploadedBy(userSeq)
                .build();
        template = templateRepository.save(template);

        // 4) activate 옵션 — 동일 트랜잭션 내에서 단일성 보장.
        if (activate) {
            applyActivation(template);
        }

        log.info("LoA form template uploaded: seq={}, label={}, activate={}, uploadedBy={}",
                template.getLoaFormTemplateSeq(), label, activate, userSeq);

        return LoaFormTemplateResponse.from(template, resolveSingleUploaderName(userSeq));
    }

    // ── 활성화 ──────────────────────────────

    /**
     * 해당 버전 활성화 + 기존 active 전부 비활성화 (동일 트랜잭션, active 단일성 보장).
     */
    @Transactional
    public LoaFormTemplateResponse activate(Long templateSeq, Long userSeq) {
        LoaFormTemplate target = findOrThrow(templateSeq);
        applyActivation(target);
        log.info("LoA form template activated: seq={}, by={}", templateSeq, userSeq);
        return LoaFormTemplateResponse.from(target, resolveSingleUploaderName(target.getUploadedBy()));
    }

    /** 기존 active 전부 false → 대상 true. 호출자는 반드시 @Transactional 경로여야 한다. */
    private void applyActivation(LoaFormTemplate target) {
        List<LoaFormTemplate> currentlyActive = templateRepository.findByIsActiveTrue();
        for (LoaFormTemplate active : currentlyActive) {
            if (!active.getLoaFormTemplateSeq().equals(target.getLoaFormTemplateSeq())) {
                active.deactivate();
            }
        }
        target.activate();
    }

    // ── 삭제 ──────────────────────────────

    /**
     * soft delete.
     *
     * <p>PR2 에서는 Application.loaFormTemplateSeq 컬럼이 아직 없으므로 "신청 참조 중이면 409
     * LOA_FORM_IN_USE" 가드는 생략한다. PR3 에서 추가 예정 (스펙 §3.1 / AC-8).</p>
     */
    @Transactional
    public void softDelete(Long templateSeq, Long userSeq) {
        LoaFormTemplate template = findOrThrow(templateSeq);
        // TODO(PR3): Application.loaFormTemplateSeq 추가 후, 참조 중이면 409 LOA_FORM_IN_USE 로 차단.
        templateRepository.delete(template); // @SQLDelete → soft delete
        log.info("LoA form template soft-deleted: seq={}, by={}", templateSeq, userSeq);
    }

    // ── active 폼 소비 (§3.2) ──────────────────────────────

    /**
     * 현재 글로벌 active 폼 1건 메타 반환.
     *
     * <p>PR2 는 신청별 스냅샷({@code loaFormTemplateSeq})이 아직 없으므로 글로벌 active 를 반환한다.
     * 신청별 고정(send-form 시점 스냅샷)은 PR3 에서 도입된다.</p>
     *
     * @throws BusinessException 404 {@code NO_ACTIVE_LOA_FORM} active 폼이 하나도 없을 때
     */
    public LoaFormTemplateResponse getActiveForm() {
        LoaFormTemplate active = findActiveOrThrow();
        return LoaFormTemplateResponse.from(active, resolveSingleUploaderName(active.getUploadedBy()));
    }

    /** active 폼의 FileEntity (소비 다운로드용). */
    public FileEntity getActiveFormFile() {
        LoaFormTemplate active = findActiveOrThrow();
        return fileRepository.findById(active.getFileSeq())
                .orElseThrow(() -> new BusinessException(
                        "Active LoA form file not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));
    }

    private LoaFormTemplate findActiveOrThrow() {
        return templateRepository.findFirstByIsActiveTrueOrderByUploadedAtDesc()
                .orElseThrow(() -> new BusinessException(
                        "No active LoA form is configured", HttpStatus.NOT_FOUND, "NO_ACTIVE_LOA_FORM"));
    }

    // ── 내부 헬퍼 ──────────────────────────────

    private LoaFormTemplate findOrThrow(Long templateSeq) {
        return templateRepository.findById(templateSeq)
                .orElseThrow(() -> new BusinessException(
                        "LoA form template not found", HttpStatus.NOT_FOUND, "LOA_FORM_TEMPLATE_NOT_FOUND"));
    }

    private Map<Long, String> resolveUploaderNames(List<LoaFormTemplate> templates) {
        List<Long> userSeqs = templates.stream()
                .map(LoaFormTemplate::getUploadedBy)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (userSeqs.isEmpty()) return Map.of();
        return userRepository.findAllById(userSeqs).stream()
                .collect(Collectors.toMap(User::getUserSeq, User::getFullName, (a, b) -> a));
    }

    private String resolveSingleUploaderName(Long userSeq) {
        if (userSeq == null) return null;
        return userRepository.findById(userSeq).map(User::getFullName).orElse(null);
    }
}
