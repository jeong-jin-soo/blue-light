package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.RoleMetadataResponse;
import com.bluelight.backend.api.admin.dto.UpdateRoleMetadataRequest;
import com.bluelight.backend.domain.rolemetadata.RoleMetadata;
import com.bluelight.backend.domain.rolemetadata.RoleMetadataRepository;
import com.bluelight.backend.domain.user.UserRole;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRoleMetadataService {

    private final RoleMetadataRepository roleMetadataRepository;

    public List<RoleMetadataResponse> getAll() {
        return roleMetadataRepository.findAllByOrderBySortOrderAscRoleCodeAsc().stream()
                .map(RoleMetadataResponse::from)
                .toList();
    }

    @Transactional
    public RoleMetadataResponse update(String roleCode, UpdateRoleMetadataRequest request) {
        UserRole code;
        try {
            code = UserRole.valueOf(roleCode);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Unknown role code: " + roleCode);
        }

        RoleMetadata entity = roleMetadataRepository.findById(code)
                .orElseThrow(() -> new EntityNotFoundException("Role metadata not found: " + roleCode));

        entity.update(
                request.getDisplayLabel(),
                request.getAssignable(),
                request.getFilterable(),
                request.getSortOrder()
        );
        return RoleMetadataResponse.from(entity);
    }
}
