package com.bluelight.backend.api.loa;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.user.User;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * LOA (Letter of Appointment) PDF 생성 서비스
 * - EMA 원본 양식 PDF를 템플릿으로 사용하여 PdfStamper로 텍스트 오버레이
 */
@Slf4j
@Service
public class LoaGenerationService {

    private static final String TEMPLATE_NEW = "templates/loa-electrical-installation.pdf";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Value("${file.upload-dir}")
    private String uploadDir;

    /**
     * New Licence LOA PDF 생성
     * EMA 양식 템플릿 위에 신청자+LEW 데이터를 오버레이
     *
     * @return 생성된 PDF의 상대 경로 (uploadDir 기준)
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

        Path targetDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(subDirectory);
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new BusinessException("Failed to create directory",
                    HttpStatus.INTERNAL_SERVER_ERROR, "DIR_CREATE_ERROR");
        }

        Path targetPath = targetDir.resolve(filename);

        try {
            // 템플릿 PDF 로드
            ClassPathResource templateResource = new ClassPathResource(TEMPLATE_NEW);
            PdfReader reader = new PdfReader(templateResource.getInputStream());
            PdfStamper stamper = new PdfStamper(reader, new FileOutputStream(targetPath.toFile()));
            PdfContentByte over = stamper.getOverContent(1);

            // 폰트 설정
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            BaseFont bfBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);

            // ── 템플릿 빈칸에 데이터 기입 ──
            // 좌표: PDF 좌하단 (0,0), A4 = 595 x 842 pt
            // pymupdf로 밑줄(___) 텍스트 스팬의 정확한 Y 좌표 추출
            // 밑줄 하단(y_bu) + gap(1/3) 으로 밑줄 바로 위에 근접 배치 (겹침 없음)

            // LEW 이름: 밑줄 Y_td=140.8~157.1 → y_bu=691
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(331, 691);
            over.showText(lew.getName() != null ? lew.getName() : "");
            over.endText();

            // LEW 면허번호: 밑줄 Y_td=166.1~182.5 → y_bu=666
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(170, 666);
            over.showText(lew.getLewLicenceNo() != null ? lew.getLewLicenceNo() : "");
            over.endText();

            // 설치 주소 1줄: 밑줄 Y_td=242.1~255.7 → y_bu=591
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(57, 591);
            over.showText(application.getAddress() != null ? application.getAddress() : "");
            over.endText();

            // 설치 주소 2줄: 밑줄 Y_td=279.0~292.6 → y_bu=554
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(57, 554);
            over.showText("SINGAPORE " + (application.getPostalCode() != null ? application.getPostalCode() : ""));
            over.endText();

            // 회사명 (FOR): 밑줄 Y_td=315.6~332.0 → y_bu=516
            over.beginText();
            over.setFontAndSize(bfBold, 10);
            over.setTextMatrix(86, 516);
            over.showText(applicant.getCompanyName() != null ? applicant.getCompanyName().toUpperCase() : "");
            over.endText();

            // 신청자 이름+직함: 밑줄 Y_td=460.4~471.3 → y_bu=375
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(57, 375);
            String nameDesignation = (applicant.getName() != null ? applicant.getName() : "")
                    + (applicant.getDesignation() != null ? "    " + applicant.getDesignation() : "");
            over.showText(nameDesignation);
            over.endText();

            // 우편주소 (Correspondence Address) Line 1: 밑줄 Y_td=533.2~545.4 → y_bu=301
            String corrAddr = applicant.getCorrespondenceAddress() != null
                    ? applicant.getCorrespondenceAddress() : "";
            // 주소가 길면 2줄로 분할 (50자 기준)
            if (corrAddr.length() > 50) {
                over.beginText();
                over.setFontAndSize(bf, 9);
                over.setTextMatrix(57, 301);
                over.showText(corrAddr.substring(0, 50));
                over.endText();

                // 우편주소 Line 2: 밑줄 Y_td=557.3~569.6 → y_bu=277
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

            // UEN: 9개 박스 Y_td=601.3~622.3, X=364.2~549.1 (UEN은 변경 없음)
            // 박스 중앙 x 좌표: 374.5, 395.0, 415.6, 436.1, 456.7, 477.2, 497.8, 518.3, 538.9
            // 박스 내부 수직 중앙 baseline y_bu=228
            if (applicant.getUen() != null && !applicant.getUen().isBlank()) {
                String uen = applicant.getUen();
                float[] boxCenterX = {374.5f, 395.0f, 415.6f, 436.1f, 456.7f, 477.2f, 497.8f, 518.3f, 538.9f};
                float uenY = 228;

                if (uen.length() <= 9) {
                    // 9자 이하: 각 글자를 박스 중앙에 배치
                    for (int i = 0; i < uen.length(); i++) {
                        over.beginText();
                        over.setFontAndSize(bf, 11);
                        float charX = boxCenterX[i] - 3;
                        over.setTextMatrix(charX, uenY);
                        over.showText(String.valueOf(uen.charAt(i)));
                        over.endText();
                    }
                } else {
                    // 10자 이상: 전체 박스 영역(364.2~549.1)에 균등 배치
                    float totalWidth = 549.1f - 364.2f; // 184.9pt
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

            // Postal Code: 밑줄 Y_td=601.9~618.3 → y_bu=230
            if (applicant.getCorrespondencePostalCode() != null
                    && !applicant.getCorrespondencePostalCode().isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(129, 230);
                over.showText(applicant.getCorrespondencePostalCode());
                over.endText();
            }

            // Email: 밑줄 Y_td=626.0~642.4 → y_bu=206
            if (applicant.getEmail() != null && !applicant.getEmail().isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(94, 206);
                over.showText(applicant.getEmail());
                over.endText();
            }

            // Tel No: 밑줄 Y_td=650.2~666.5 → y_bu=182
            if (applicant.getPhone() != null && !applicant.getPhone().isBlank()) {
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(103, 182);
                over.showText(applicant.getPhone());
                over.endText();

                // SMS: 밑줄 Y_td=702.0~718.4 → y_bu=130
                over.beginText();
                over.setFontAndSize(bf, 10);
                over.setTextMatrix(289, 130);
                over.showText(applicant.getPhone());
                over.endText();
            }

            stamper.close();
            reader.close();

        } catch (Exception e) {
            log.error("Failed to generate LOA PDF: {}", e.getMessage(), e);
            throw new BusinessException("Failed to generate LOA PDF",
                    HttpStatus.INTERNAL_SERVER_ERROR, "LOA_GENERATION_ERROR");
        }

        String relativePath = subDirectory + "/" + filename;
        log.info("LOA PDF generated: {}", relativePath);
        return relativePath;
    }

    /**
     * Renewal LOA PDF 생성
     * Renewal은 관계기관이 발급하므로 시스템 생성은 선택적
     * New Licence와 동일한 템플릿 사용, 갱신 정보 추가 기입
     */
    public String generateRenewalLoa(Application application) {
        // Renewal도 동일한 New Licence 템플릿 기반으로 생성
        // 추가 필드: 기존 면허번호, 만료일, 갱신 참조번호
        String pdfPath = generateNewLicenceLoa(application);

        // 갱신 전용 정보 추가 오버레이
        Path absolutePath = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(pdfPath);
        try {
            PdfReader reader = new PdfReader(absolutePath.toString());

            // 임시 파일로 작성 후 교체
            String tempFilename = "LOA_RENEWAL_" + application.getApplicationSeq() + "_"
                    + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
            String subDirectory = "applications/" + application.getApplicationSeq();
            Path tempPath = Paths.get(uploadDir).toAbsolutePath().normalize()
                    .resolve(subDirectory).resolve(tempFilename);

            PdfStamper stamper = new PdfStamper(reader, new FileOutputStream(tempPath.toFile()));
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

            // 원본 삭제, 갱신 파일을 결과로 반환
            Files.deleteIfExists(absolutePath);

            String renewalRelativePath = subDirectory + "/" + tempFilename;
            log.info("Renewal LOA PDF generated: {}", renewalRelativePath);
            return renewalRelativePath;

        } catch (Exception e) {
            log.error("Failed to add renewal info to LOA PDF: {}", e.getMessage(), e);
            // 기본 LOA라도 반환
            return pdfPath;
        }
    }

    /**
     * 기존 LOA PDF에 서명 이미지 임베드
     *
     * @param existingPdfPath     기존 LOA PDF 상대 경로
     * @param signatureImagePath  서명 PNG 상대 경로
     * @param application         신청 엔티티
     * @return 서명 임베드된 새 PDF의 상대 경로
     */
    public String embedSignatureIntoPdf(String existingPdfPath, String signatureImagePath,
                                        Application application) {
        Path pdfAbsolutePath = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(existingPdfPath);
        Path signatureAbsPath = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(signatureImagePath);

        String subDirectory = "applications/" + application.getApplicationSeq();
        String signedFilename = "LOA_SIGNED_" + application.getApplicationSeq() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
        Path signedPdfPath = Paths.get(uploadDir).toAbsolutePath().normalize()
                .resolve(subDirectory).resolve(signedFilename);

        try {
            PdfReader reader = new PdfReader(pdfAbsolutePath.toString());
            PdfStamper stamper = new PdfStamper(reader, new FileOutputStream(signedPdfPath.toFile()));

            // 서명 이미지 삽입
            Image signatureImage = Image.getInstance(signatureAbsPath.toString());
            signatureImage.scaleToFit(120, 50);

            // 서명란 위치: 밑줄 Y_td=458.4~472.0, X=304.8~545.1
            // 서명 이미지: 밑줄 바로 위 y_bu=375
            float sigX = 309;
            float sigY = 375;
            signatureImage.setAbsolutePosition(sigX, sigY);

            PdfContentByte over = stamper.getOverContent(1);
            over.addImage(signatureImage);

            // 서명 날짜: Date 밑줄 바로 위 → y_bu=375
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            over.beginText();
            over.setFontAndSize(bf, 10);
            over.setTextMatrix(448, 375);
            over.showText(LocalDate.now().format(DATE_FORMAT));
            over.endText();

            stamper.close();
            reader.close();

        } catch (Exception e) {
            log.error("Failed to embed signature into PDF: {}", e.getMessage(), e);
            throw new BusinessException("Failed to sign LOA PDF",
                    HttpStatus.INTERNAL_SERVER_ERROR, "LOA_SIGNING_ERROR");
        }

        String relativePath = subDirectory + "/" + signedFilename;
        log.info("Signed LOA PDF generated: {}", relativePath);
        return relativePath;
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
