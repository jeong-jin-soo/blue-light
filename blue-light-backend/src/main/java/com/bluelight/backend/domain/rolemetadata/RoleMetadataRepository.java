package com.bluelight.backend.domain.rolemetadata;

import com.bluelight.backend.domain.user.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoleMetadataRepository extends JpaRepository<RoleMetadata, UserRole> {
    List<RoleMetadata> findAllByOrderBySortOrderAscRoleCodeAsc();
}
