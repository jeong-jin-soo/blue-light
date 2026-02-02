package com.bluelight.backend.domain.price;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;

/**
 * 용량별 단가표 Entity
 */
@Entity
@Table(name = "master_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE master_prices SET deleted_at = NOW() WHERE master_price_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class MasterPrice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "master_price_seq")
    private Long masterPriceSeq;

    /**
     * 표시 이름 (예: "100-200 kVA")
     */
    @Column(name = "description", length = 50)
    private String description;

    /**
     * 최소 용량 (kVA)
     */
    @Column(name = "kva_min", nullable = false)
    private Integer kvaMin;

    /**
     * 최대 용량 (kVA)
     */
    @Column(name = "kva_max", nullable = false)
    private Integer kvaMax;

    /**
     * 책정 가격 (SGD)
     */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /**
     * 사용 여부
     */
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Builder
    public MasterPrice(String description, Integer kvaMin, Integer kvaMax, BigDecimal price, Boolean isActive) {
        this.description = description;
        this.kvaMin = kvaMin;
        this.kvaMax = kvaMax;
        this.price = price;
        this.isActive = isActive != null ? isActive : true;
    }

    /**
     * 가격 수정
     */
    public void updatePrice(BigDecimal price) {
        this.price = price;
    }

    /**
     * 활성화/비활성화
     */
    public void setActive(boolean isActive) {
        this.isActive = isActive;
    }

    /**
     * 용량 범위 수정
     */
    public void updateKvaRange(Integer kvaMin, Integer kvaMax, String description) {
        this.kvaMin = kvaMin;
        this.kvaMax = kvaMax;
        this.description = description;
    }
}
