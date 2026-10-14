package com.mrlbs.notification;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID memberId,
        String title,
        String message,
        UUID offerId,
        boolean isRead,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(InAppNotification n) {
        if (n == null) return null;
        return new NotificationResponse(
                n.getId(),
                n.getMemberId(),
                n.getTitle(),
                n.getMessage(),
                n.getOfferId(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}
