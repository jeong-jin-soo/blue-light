package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
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
        User applicant = application.getUser();
        User lew = application.getAssignedLew();

        if (lew == null) {
            throw new BusinessException("LEW must be assigned before generating LOA",
                    HttpStatus.BAD_REQUEST, "LEW_NOT_ASSIGNED");
        }
        validateApplicantProfile(applicant);

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
            over.showText(lew.getName() != null ? lew.getName() : "");
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

            // 회사명 (FOR): y_bu=516
            over.beginText();
            over.setFontAndSize(bfBold, 10);
            over.setTextMatrix(86, 516);
            over.showText(applicant.getCompanyName() != null ? applicant.getCompanyName().toUpperCase() : "");
            over.endText();

            // 신청자 이름+직함: y_bu=375
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(57, 375);
            String nameDesignation = (applicant.getName() != null ? applicant.getName() : "")
                    + (applicant.getDesignation() != null ? "    " + applicant.getDesignation() : "");
            over.showText(nameDesignation);
            over.endText();

            // 우편주소 (Correspondence Address)
            String corrAddr = applicant.getCorrespondenceAddress() != null
                    ? applicant.getCorrespondenceAddress() : "";
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

            // UEN: 9개 박스
            if (applicant.getUen() != null && !applicant.getUen().isBlank()) {
                String uen = applicant.getUen();
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

            // Postal Code: y_bu=230
            if (applicant.getCorrespondencePostalCode() != null
                    && !applicant.getCorrespondencePostalCode().isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(129, 230);
                over.showText(applicant.getCorrespondencePostalCode());
                over.endText();
            }

            // Email: y_bu=206
            if (applicant.getEmail() != null && !applicant.getEmail().isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(94, 206);
                over.showText(applicant.getEmail());
                over.endText();
            }

            // Tel No: y_bu=182
            if (applicant.getPhone() != null && !applicant.getPhone().isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(103, 182);
                over.showText(applicant.getPhone());
                over.endText();

                // SMS: y_bu=130
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(289, 130);
                over.showText(applicant.getPhone());
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
     * 신청자 프로필 필수 필드 검증
     */
    public void validateApplicantProfile(User applicant) {
        StringBuilder missing = new StringBuilder();

        if (applicant.getCompanyName() == null || applicant.getCompanyName().isBlank()) {
            missing.append("Company Name, ");
        }
        if (applicant.getDesignation() == null || applicant.getDesignation().isBlank()) {
            missing.append("Designation, ");
        }
        if (applicant.getCorrespondenceAddress() == null || applicant.getCorrespondenceAddress().isBlank()) {
            missing.append("Correspondence Address, ");
        }

        if (!missing.isEmpty()) {
            String fields = missing.substring(0, missing.length() - 2);
            throw new BusinessException(
                    "Applicant profile is incomplete. Missing: " + fields,
                    HttpStatus.BAD_REQUEST, "INCOMPLETE_PROFILE");
        }
    }
}
