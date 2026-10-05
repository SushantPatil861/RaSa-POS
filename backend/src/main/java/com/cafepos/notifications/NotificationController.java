package com.cafepos.notifications;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Any signed-in user; what they see is filtered by permission inside the service. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record CountDto(long count) {}

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<NotificationService.NotificationDto> mine(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return notifications.mine(unreadOnly, limit);
    }

    @GetMapping("/unread-count")
    public CountDto unreadCount() {
        return new CountDto(notifications.unreadCount());
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable long id) {
        notifications.markRead(id);
    }

    @PostMapping("/read-all")
    public void markAllRead() {
        notifications.markAllRead();
    }
}
