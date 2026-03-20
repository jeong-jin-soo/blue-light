package com.bluelight.backend.api.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 알림 API 컨트롤러
 * - 인증된 사용자 본인의 알림만 접근 가능
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 내 알림 목록 (paginated)
     * GET /api/notifications?page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getMyNotifications(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userSeq = (Long) authentication.getPrincipal();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50));
        return ResponseEntity.ok(notificationService.getMyNotifications(userSeq, pageable));
    }

    /**
     * 읽지 않은 알림 건수
     * GET /api/notifications/unread-count
     */
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> getUnreadCount(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        long count = notificationService.getUnreadCount(userSeq);
        return ResponseEntity.ok(new UnreadCountResponse(count));
    }

    /**
     * 단건 읽음 처리
     * PATCH /api/notifications/{notificationSeq}/read
     */
    @PatchMapping("/{notificationSeq}/read")
    public ResponseEntity<Void> markAsRead(
            Authentication authentication,
            @PathVariable Long notificationSeq) {
        Long userSeq = (Long) authentication.getPrincipal();
        notificationService.markAsRead(userSeq, notificationSeq);
        return ResponseEntity.ok().build();
    }

    /**
     * 전체 읽음 처리
     * PATCH /api/notifications/read-all
     */
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        notificationService.markAllAsRead(userSeq);
        return ResponseEntity.ok().build();
    }
}
