package com.bluelight.backend.domain.rolemetadata;

import com.bluelight.backend.domain.common.BaseEntity;
import com.bluelight.backend.domain.user.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 역할별 표시 라벨과 노출/할당 가능 여부를 저장한다.
 * - roleCode 는 {@link UserRole} 과 1:1 매칭되는 PK
 * - enum 에 있는 값은 앱 부팅 시 {@code DatabaseMigrationRunner} 가 upsert 하며,
 *   enum 에 없는 row 는 정리된다.
 */
@Entity
@Table(name = "role_metadata")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleMetadata extends BaseEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", length = 32)
    private UserRole roleCode;

    @Column(name = "display_label", nullable = false, length = 100)
    private String displayLabel;

    /** Admin 이 Users 페이지에서 이 역할을 **지정 가능한가** */
    @Column(name = "assignable", nullable = false)
    private Boolean assignable;

    /** Users 페이지 필터 드롭다운에 노출할 것인가 */
    @Column(name = "filterable", nullable = false)
    private Boolean filterable;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Builder
    public RoleMetadata(UserRole roleCode, String displayLabel, Boolean assignable, Boolean filterable, Integer sortOrder) {
        this.roleCode = roleCode;
        this.displayLabel = displayLabel;
        this.assignable = assignable != null ? assignable : Boolean.TRUE;
        this.filterable = filterable != null ? filterable : Boolean.TRUE;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
    }

    public void update(String displayLabel, Boolean assignable, Boolean filterable, Integer sortOrder) {
        if (displayLabel != null) {
            this.displayLabel = displayLabel;
        }
        if (assignable != null) {
            this.assignable = assignable;
        }
        if (filterable != null) {
            this.filterable = filterable;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }
}
