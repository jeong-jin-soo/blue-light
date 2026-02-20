package com.bluelight.backend.domain.application;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD(Single Line Diagram) 작성 요청 Entity
 * - 신청자가 LEW에게 SLD 작성을 요청할 때 생성
 */
@Entity
@Table(name = "sld_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SldRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sld_request_seq")
    private Long sldRequestSeq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SldRequestStatus status = SldRequestStatus.REQUESTED;

    @Column(name = "applicant_note", columnDefinition = "TEXT")
    private String applicantNote;

    @Column(name = "lew_note", columnDefinition = "TEXT")
    private String lewNote;

    @Column(name = "uploaded_file_seq")
    private Long uploadedFileSeq;

    @Column(name = "sketch_file_seq")
    private Long sketchFileSeq;

    @Builder
    public SldRequest(Application application, String applicantNote) {
        this.application = application;
        this.applicantNote = applicantNote;
        this.status = SldRequestStatus.REQUESTED;
    }

    /**
     * AI SLD 생성 시작
     */
    public void startAiGeneration() {
        this.status = SldRequestStatus.AI_GENERATING;
    }

    /**
     * LEW가 SLD 파일 업로드 완료
     */
    public void markUploaded(Long fileSeq, String lewNote) {
        this.status = SldRequestStatus.UPLOADED;
        this.uploadedFileSeq = fileSeq;
        this.lewNote = lewNote;
    }

    /**
     * SLD 확인 완료
     */
    public void confirm() {
        this.status = SldRequestStatus.CONFIRMED;
    }

    /**
     * 신청자가 메모와 스케치 파일 정보를 업데이트
     */
    public void updateApplicantDetails(String applicantNote, Long sketchFileSeq) {
        this.applicantNote = applicantNote;
        this.sketchFileSeq = sketchFileSeq;
    }
}
