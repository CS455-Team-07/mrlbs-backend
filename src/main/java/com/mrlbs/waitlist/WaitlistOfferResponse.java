package com.mrlbs.waitlist;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WaitlistOfferResponse(
        UUID id,
        UUID waitlistEntryId,
        UUID resourceId,
        String resourceName,
        String resourceType,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal creditCost,
        String status,
        LocalDateTime offeredAt,
        LocalDateTime expiresAt,
        LocalDateTime respondedAt,
        UUID resultingBookingId
) {
    public static WaitlistOfferResponse from(WaitlistOffer offer) {
        if (offer == null) return null;
        WaitlistEntry entry = offer.getWaitlistEntry();
        UUID resId = null;
        String resName = "Lab Resource";
        String resType = "Resource";
        LocalDateTime start = null;
        LocalDateTime end = null;
        BigDecimal cost = BigDecimal.ZERO;

        if (entry != null) {
            start = entry.getStartTime();
            end = entry.getEndTime();
            cost = entry.getCreditCost() != null ? entry.getCreditCost() : BigDecimal.ZERO;
            if (entry.getResource() != null) {
                try {
                    resId = entry.getResource().getId();
                    resName = entry.getResource().getName();
                    resType = entry.getResource().getType();
                } catch (Exception ignored) {
                }
            }
        }

        return new WaitlistOfferResponse(
                offer.getId(),
                entry != null ? entry.getId() : null,
                resId,
                resName,
                resType,
                start,
                end,
                cost,
                offer.getStatus(),
                offer.getOfferedAt(),
                offer.getExpiresAt(),
                offer.getRespondedAt(),
                offer.getResultingBookingId()
        );
    }
}
