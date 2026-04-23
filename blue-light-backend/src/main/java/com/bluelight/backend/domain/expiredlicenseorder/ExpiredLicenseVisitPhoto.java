package com.bluelight.backend.domain.expiredlicenseorder;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * Expired License 방문 사진 Entity (LEW Service 와 동일한 구조).
 */
@Entity
@Table(name = "expired_license_visit_photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE expired_license_visit_photos SET deleted_at = NOW() WHERE photo_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class ExpiredLicenseVisitPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "photo_seq")
    private Long photoSeq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_seq", nullable = false)
    private ExpiredLicenseOrder order;

    @Column(name = "file_seq", nullable = false)
    private Long fileSeq;

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public ExpiredLicenseVisitPhoto(ExpiredLicenseOrder order, Long fileSeq, String caption) {
        this.order = order;
        this.fileSeq = fileSeq;
        this.caption = caption;
        this.uploadedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
