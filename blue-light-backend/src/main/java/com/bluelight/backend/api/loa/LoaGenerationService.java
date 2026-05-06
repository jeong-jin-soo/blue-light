package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicantType;
import com.bluelight.backend.domain.user.User;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * LOA (Letter of Appointment) PDF 생성 서비스
 * - EMA 원본 양식 PDF를 템플릿으로 사용하여 PdfStamper로 텍스트 오버레이
 * - FileStorageService를 통해 저장 (Local/S3 무관)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoaGenerationService {

    private static final String TEMPLATE_NEW = "templates/loa-electrical-installation.pdf";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FileStorageService fileStorageService;

    /**
     * New Licence LOA PDF 생성
     * EMA 양식 템플릿 위에 신청자+LEW 데이터를 오버레이
     *
     * @return 생성된 PDF의 저장 경로 (FileStorageService 기준)
     */
    public String generateNewLicenceLoa(Application application) {
        User lew = application.getAssignedLew();

        if (lew == null) {
            throw new BusinessException("LEW must be assigned before generating LOA",
                    HttpStatus.BAD_REQUEST, "LEW_NOT_ASSIGNED");
        }
        validateApplicantProfile(application);

        String subDirectory = "applications/" + application.getApplicationSeq();
        String filename = "LOA_" + application.getApplicationSeq() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + ".pdf";

        try {
            // 템플릿 PDF 로드 → 인메모리 ByteArrayOutputStream으로 출력
            ClassPathResource templateResource = new ClassPathResource(TEMPLATE_NEW);
            PdfReader reader = new PdfReader(templateResource.getInputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, baos);
            PdfContentByte over = stamper.getOverContent(1);

            // 폰트 설정
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);

            // ── 템플릿 빈칸에 데이터 기입 ──
            // 좌표: PDF 좌하단 (0,0), A4 = 595 x 842 pt

            // LEW 이름: y_bu=691
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(331, 691);
            over.showText(lew.getFullName());
            over.endText();

            // LEW 면허번호: y_bu=666
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(170, 666);
            over.showText(lew.getLewLicenceNo() != null ? lew.getLewLicenceNo() : "");
            over.endText();

            // 설치 주소 1줄: y_bu=591
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(57, 591);
            over.showText(application.getAddress() != null ? application.getAddress() : "");
            over.endText();

            // 설치 주소 2줄: y_bu=554
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(57, 554);
            over.showText("SINGAPORE " + (application.getPostalCode() != null ? application.getPostalCode() : ""));
            over.endText();

            // 회사명 (FOR): y_bu=516 — Application snapshot 우선
            String companyNameForLoa = resolveCompanyName(application);
            over.beginText();
            over.setFontAndSize(bfBold, 10);
            over.setTextMatrix(86, 516);
            over.showText(companyNameForLoa != null ? companyNameForLoa.toUpperCase() : "");
            over.endText();

            // 신청자 이름+직함: y_bu=375 — Application snapshot 우선
            String applicantNameForLoa = resolveApplicantName(application);
            String designationForLoa = resolveDesignation(application);
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(57, 375);
            String nameDesignation = applicantNameForLoa
                    + (designationForLoa != null && !designationForLoa.isBlank()
                            ? "    " + designationForLoa : "");
            over.showText(nameDesignation);
            over.endText();

            // 우편주소 (Correspondence Address) — 5-part > User legacy > Installation fallback
            String corrAddr = resolveCorrespondenceAddress(application);
            if (corrAddr.length() > 50) {
                over.beginText();
                over.setFontAndSize(bf, 9);
                over.setTextMatrix(57, 301);
                over.showText(corrAddr.substring(0, 50));
                over.endText();

                over.beginText();
                over.setFontAndSize(bf, 9);
                over.setTextMatrix(57, 277);
                over.showText(corrAddr.substring(50));
                over.endText();
            } else {
                over.beginText();
                over.setFontAndSize(bf, 9);
                over.setTextMatrix(57, 301);
                over.showText(corrAddr);
                over.endText();
            }

            // UEN: 9개 박스 — Application snapshot 우선
            String uenForLoa = resolveUen(application);
            if (uenForLoa != null && !uenForLoa.isBlank()) {
                String uen = uenForLoa;
                float[] boxCenterX = {374.5f, 395.0f, 415.6f, 436.1f, 456.7f, 477.2f, 497.8f, 518.3f, 538.9f};
                float uenY = 228;

                if (uen.length() <= 9) {
                    for (int i = 0; i < uen.length(); i++) {
                        over.beginText();
                        over.setFontAndSize(bf, 11);
                        float charX = boxCenterX[i] - 3;
                        over.setTextMatrix(charX, uenY);
                        over.showText(String.valueOf(uen.charAt(i)));
                        over.endText();
                    }
                } else {
                    float totalWidth = 549.1f - 364.2f;
                    float cellWidth = totalWidth / uen.length();
                    for (int i = 0; i < uen.length(); i++) {
                        over.beginText();
                        over.setFontAndSize(bf, 10);
                        float charX = 364.2f + (i * cellWidth) + (cellWidth / 2) - 3;
                        over.setTextMatrix(charX, uenY);
                        over.showText(String.valueOf(uen.charAt(i)));
                        over.endText();
                    }
                }
            }

            // Postal Code: y_bu=230 — Application 5-part > User legacy
            String corrPostalForLoa = resolveCorrespondencePostalCode(application);
            if (corrPostalForLoa != null && !corrPostalForLoa.isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(129, 230);
                over.showText(corrPostalForLoa);
                over.endText();
            }

            // Email: y_bu=206 — Application snapshot 우선
            String emailForLoa = resolveEmail(application);
            if (emailForLoa != null && !emailForLoa.isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(94, 206);
                over.showText(emailForLoa);
                over.endText();
            }

            // Tel No: y_bu=182 — Application snapshot 우선
            String phoneForLoa = resolvePhone(application);
            if (phoneForLoa != null && !phoneForLoa.isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(103, 182);
                over.showText(phoneForLoa);
                over.endText();

                // SMS: y_bu=130
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(289, 130);
                over.showText(phoneForLoa);
                over.endText();
            }

            stamper.close();
            reader.close();

            // FileStorageService를 통해 저장
            byte[] pdfBytes = baos.toByteArray();
            String storedPath = fileStorageService.storeBytes(pdfBytes, filename, subDirectory);

            log.info("LOA PDF generated: {}", storedPath);
            return storedPath;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate LOA PDF: {}", e.getMessage(), e);
            throw new BusinessException("Failed to generate LOA PDF",
                    HttpStatus.INTERNAL_SERVER_ERROR, "LOA_GENERATION_ERROR");
        }
    }

    /**
     * Renewal LOA PDF 생성
     * New Licence와 동일한 템플릿 사용, 갱신 정보 추가 기입
     */
    public String generateRenewalLoa(Application application) {
        // 기본 LOA 생성 (FileStorageService에 저장됨)
        String basePdfPath = generateNewLicenceLoa(application);

        try {
            // 저장된 기본 LOA를 다시 로드
            Resource baseResource = fileStorageService.loadAsResource(basePdfPath);
            byte[] basePdfBytes = baseResource.getInputStream().readAllBytes();

            PdfReader reader = new PdfReader(basePdfBytes);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, baos);
            PdfContentByte over = stamper.getOverContent(1);

            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);

            // "(Renewal)" 라벨 추가
            over.beginText();
            over.setFontAndSize(bfBold, 10);
            over.setTextMatrix(270, 690);
            over.showText("(Renewal)");
            over.endText();

            // 기존 면허번호
            if (application.getExistingLicenceNo() != null) {
                over.beginText();
                over.setFontAndSize(bf, 9);
                over.setTextMatrix(72, 505);
                over.showText("(LICENCE NO: " + application.getExistingLicenceNo() + ")");
                over.endText();
            }

            // 기존 만료일
            if (application.getExistingExpiryDate() != null) {
                over.beginText();
                over.setFontAndSize(bf, 9);
                over.setTextMatrix(250, 505);
                over.showText("EXPIRY DATE: " + application.getExistingExpiryDate().format(DATE_FORMAT));
                over.endText();
            }

            // 갱신 참조번호
            if (application.getRenewalReferenceNo() != null) {
                over.beginText();
                over.setFontAndSize(bfBold, 9);
                over.setTextMatrix(400, 745);
                over.showText("*Renewal Ref: " + application.getRenewalReferenceNo());
                over.endText();
            }

            stamper.close();
            reader.close();

            // 원본 삭제
            fileStorageService.delete(basePdfPath);

            // 갱신 LOA 저장
            String subDirectory = "applications/" + application.getApplicationSeq();
            String renewalFilename = "LOA_RENEWAL_" + application.getApplicationSeq() + "_"
                    + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
            byte[] renewalPdfBytes = baos.toByteArray();
            String renewalPath = fileStorageService.storeBytes(renewalPdfBytes, renewalFilename, subDirectory);

            log.info("Renewal LOA PDF generated: {}", renewalPath);
            return renewalPath;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to add renewal info to LOA PDF: {}", e.getMessage(), e);
            // 기본 LOA라도 반환
            return basePdfPath;
        }
    }

    /**
     * 기존 LOA PDF에 서명 이미지 임베드
     *
     * @param existingPdfPath     기존 LOA PDF 저장 경로
     * @param signatureImagePath  서명 PNG 저장 경로
     * @param application         신청 엔티티
     * @return 서명 임베드된 새 PDF의 저장 경로
     */
    public String embedSignatureIntoPdf(String existingPdfPath, String signatureImagePath,
                                        Application application) {
        try {
            // FileStorageService에서 기존 PDF와 서명 이미지 로드
            Resource pdfResource = fileStorageService.loadAsResource(existingPdfPath);
            byte[] pdfBytes = pdfResource.getInputStream().readAllBytes();

            Resource signatureResource = fileStorageService.loadAsResource(signatureImagePath);
            byte[] signatureBytes = signatureResource.getInputStream().readAllBytes();

            PdfReader reader = new PdfReader(pdfBytes);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, baos);

            // 서명 이미지 삽입
            Image signatureImage = Image.getInstance(signatureBytes);
            signatureImage.scaleToFit(120, 50);

            float sigX = 309;
            float sigY = 375;
            signatureImage.setAbsolutePosition(sigX, sigY);

            PdfContentByte over = stamper.getOverContent(1);
            over.addImage(signatureImage);

            // 서명 날짜
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(448, 375);
            over.showText(LocalDate.now().format(DATE_FORMAT));
            over.endText();

            stamper.close();
            reader.close();

            // 서명된 PDF를 FileStorageService로 저장
            String subDirectory = "applications/" + application.getApplicationSeq();
            String signedFilename = "LOA_SIGNED_" + application.getApplicationSeq() + "_"
                    + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
            byte[] signedPdfBytes = baos.toByteArray();
            String signedPath = fileStorageService.storeBytes(signedPdfBytes, signedFilename, subDirectory);

            log.info("Signed LOA PDF generated: {}", signedPath);
            return signedPath;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to embed signature into PDF: {}", e.getMessage(), e);
            throw new BusinessException("Failed to sign LOA PDF",
                    HttpStatus.INTERNAL_SERVER_ERROR, "LOA_SIGNING_ERROR");
        }
    }

    /**
     * 신청자 프로필 필수 필드 검증 (Layer B 정본).
     * <p>
     * C.2 JIT 재요청 감사 §8 P0 반영:
     * <ul>
     *   <li>CORPORATE: Company Name / Designation 누락 시 차단.</li>
     *   <li>INDIVIDUAL: 회사명/직함은 EMA 양식 규칙상 자동 대체되므로 검증 생략.</li>
     *   <li>Correspondence Address: 렌더 단계에서 Application 5-part → User legacy →
     *       Installation 주소 순으로 항상 fallback이 보장되므로 검증 대상에서 제외.</li>
     * </ul>
     * 스냅샷(Layer B)이 우선이고, 비어 있을 때만 User(Layer A)로 fallback.
     */
    public void validateApplicantProfile(Application application) {
        ApplicantType applicantType = application.getApplicantType();
        StringBuilder missing = new StringBuilder();

        if (applicantType == ApplicantType.CORPORATE) {
            String companyName = firstNonBlank(
                    application.getLoaCompanyNameSnapshot(),
                    application.getUser() != null ? application.getUser().getCompanyName() : null);
            if (companyName == null || companyName.isBlank()) {
                missing.append("Company Name, ");
            }

            String designation = firstNonBlank(
                    application.getLoaDesignationSnapshot(),
                    application.getUser() != null ? application.getUser().getDesignation() : null);
            if (designation == null || designation.isBlank()) {
                missing.append("Designation, ");
            }
        }

        if (!missing.isEmpty()) {
            String fields = missing.substring(0, missing.length() - 2);
            throw new BusinessException(
                    "Applicant profile is incomplete. Missing: " + fields,
                    HttpStatus.BAD_REQUEST, "INCOMPLETE_PROFILE");
        }
    }

    // ── Layer B (Application snapshot) 우선, User fallback 헬퍼 ─────────────

    /** 신청자 성명: 스냅샷 → User.fullName. */
    private String resolveApplicantName(Application app) {
        String snap = app.getLoaApplicantNameSnapshot();
        if (snap != null && !snap.isBlank()) return snap;
        return app.getUser() != null && app.getUser().getFullName() != null
                ? app.getUser().getFullName() : "";
    }

    /**
     * 회사명: INDIVIDUAL 은 본인 성명으로 대체 (EMA 양식 규칙).
     * CORPORATE: 스냅샷 → User.companyName.
     */
    private String resolveCompanyName(Application app) {
        if (app.getApplicantType() == ApplicantType.INDIVIDUAL) {
            return resolveApplicantName(app);
        }
        String snap = app.getLoaCompanyNameSnapshot();
        if (snap != null && !snap.isBlank()) return snap;
        return app.getUser() != null && app.getUser().getCompanyName() != null
                ? app.getUser().getCompanyName() : "";
    }

    /** UEN: INDIVIDUAL 은 공란. CORPORATE: 스냅샷 → User.uen. */
    private String resolveUen(Application app) {
        if (app.getApplicantType() == ApplicantType.INDIVIDUAL) return "";
        String snap = app.getLoaUenSnapshot();
        if (snap != null && !snap.isBlank()) return snap;
        return app.getUser() != null && app.getUser().getUen() != null
                ? app.getUser().getUen() : "";
    }

    /**
     * 직함: 스냅샷 → INDIVIDUAL 은 "Owner" 기본값, CORPORATE 는 User.designation.
     */
    private String resolveDesignation(Application app) {
        String snap = app.getLoaDesignationSnapshot();
        if (snap != null && !snap.isBlank()) return snap;
        if (app.getApplicantType() == ApplicantType.INDIVIDUAL) return "Owner";
        return app.getUser() != null && app.getUser().getDesignation() != null
                ? app.getUser().getDesignation() : "";
    }

    /** Phone: 스냅샷 → User.phone. */
    private String resolvePhone(Application app) {
        String snap = app.getLoaPhoneSnapshot();
        if (snap != null && !snap.isBlank()) return snap;
        return app.getUser() != null && app.getUser().getPhone() != null
                ? app.getUser().getPhone() : "";
    }

    /** Email: 스냅샷 → User.email. */
    private String resolveEmail(Application app) {
        String snap = app.getLoaEmailSnapshot();
        if (snap != null && !snap.isBlank()) return snap;
        return app.getUser() != null && app.getUser().getEmail() != null
                ? app.getUser().getEmail() : "";
    }

    /**
     * Correspondence 주소(단일 문자열): Application 5-part → User legacy →
     * Installation address 재사용 (EMA 양식 상 동일 주소 허용).
     */
    private String resolveCorrespondenceAddress(Application app) {
        String block = app.getCorrespondenceAddressBlock();
        String unit = app.getCorrespondenceAddressUnit();
        String street = app.getCorrespondenceAddressStreet();
        String building = app.getCorrespondenceAddressBuilding();
        String postal = app.getCorrespondenceAddressPostalCode();
        if (anyNotBlank(block, unit, street, building, postal)) {
            return joinParts(block, unit, street, building, postal);
        }
        String legacy = app.getUser() != null ? app.getUser().getCorrespondenceAddress() : null;
        if (legacy != null && !legacy.isBlank()) return legacy;
        log.info("LOA correspondence address: falling back to installation address (applicationSeq={})",
                app.getApplicationSeq());
        return app.getAddress() != null ? app.getAddress() : "";
    }

    /**
     * Correspondence postal code: Application 5-part → User legacy.
     * Installation 주소 fallback 시엔 Application.postalCode 로도 대체하지 않는다
     * (PDF 별도 필드이므로 공란 허용).
     */
    private String resolveCorrespondencePostalCode(Application app) {
        String postal = app.getCorrespondenceAddressPostalCode();
        if (postal != null && !postal.isBlank()) return postal;
        if (app.getUser() != null && app.getUser().getCorrespondencePostalCode() != null
                && !app.getUser().getCorrespondencePostalCode().isBlank()) {
            return app.getUser().getCorrespondencePostalCode();
        }
        // Installation 주소로 폴백된 경우 설치 postalCode 재사용 (Correspondence resolver 와 일관)
        String block = app.getCorrespondenceAddressBlock();
        String unit = app.getCorrespondenceAddressUnit();
        String street = app.getCorrespondenceAddressStreet();
        String building = app.getCorrespondenceAddressBuilding();
        String legacy = app.getUser() != null ? app.getUser().getCorrespondenceAddress() : null;
        boolean noneResolved = !anyNotBlank(block, unit, street, building)
                && (legacy == null || legacy.isBlank());
        if (noneResolved && app.getPostalCode() != null && !app.getPostalCode().isBlank()) {
            return app.getPostalCode();
        }
        return "";
    }

    private String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    private boolean anyNotBlank(String... s) {
        for (String v : s) {
            if (v != null && !v.isBlank()) return true;
        }
        return false;
    }

    private String joinParts(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
