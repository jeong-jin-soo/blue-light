package com.bluelight.backend.domain.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 모든 Entity의 공통 필드를 정의하는 추상 클래스
 * - JPA Auditing을 통한 생성/수정 시간 자동 관리
 * - Soft Delete를 위한 deletedAt 필드 포함
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    /**
     * 생성 일시 (자동 설정)
     */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 수정 일시 (자동 설정)
     */
    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 생성자 ID (AuditorAware를 통해 자동 설정)
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    /**
     * 수정자 ID (AuditorAware를 통해 자동 설정)
     */
    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    /**
     * 삭제 일시 (Soft Delete)
     * - null이면 삭제되지 않은 상태
     * - 값이 있으면 해당 시점에 삭제된 것으로 간주
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Soft Delete 수행
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Soft Delete 복구
     */
    public void restore() {
        this.deletedAt = null;
    }

    /**
     * 삭제 여부 확인
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
