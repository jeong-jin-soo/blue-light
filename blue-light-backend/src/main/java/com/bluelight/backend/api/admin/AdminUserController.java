package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminUserResponse;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin User Management API controller (ADMIN role only)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserRepository userRepository;

    /**
     * Get all users
     * GET /api/admin/users
     */
    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> getAllUsers() {
        log.info("Admin get all users");
        List<AdminUserResponse> users = userRepository.findAll()
                .stream()
                .map(AdminUserResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }
}
