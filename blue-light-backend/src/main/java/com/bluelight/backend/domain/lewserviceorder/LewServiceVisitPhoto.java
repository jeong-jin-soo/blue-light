package com.bluelight.backend.domain.lewserviceorder;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * LEW Service 방문 사진 Entity (LEW Service 방문형 리스키닝 PR 3).
 * <p>방문 시 여러 장의 사진을 업로드하기 위한 join table.
 * {@code files} 테이블을 참조하여 실제 파일 저장은 FileEntity 가 담당.
 */
@Entity
@Table(name = "lew_service_visit_photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE lew_service_visit_photos SET deleted_at = NOW() WHERE photo_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class LewServiceVisitPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "photo_seq")
    private Long photoSeq;

    /**
     * 주문 참조 (FK → lew_service_orders.lew_service_order_seq)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_seq", nullable = false)
    private LewServiceOrder order;

    /**
     * 파일 참조 (FK → files.file_seq)
     */
    @Column(name = "file_seq", nullable = false)
    private Long fileSeq;

    /**
     * 사진 설명 (선택)
     */
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    /**
     * 업로드 일시
     */
    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    /**
     * Soft delete
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public LewServiceVisitPhoto(LewServiceOrder order, Long fileSeq, String caption) {
        this.order = order;
        this.fileSeq = fileSeq;
        this.caption = caption;
        this.uploadedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
