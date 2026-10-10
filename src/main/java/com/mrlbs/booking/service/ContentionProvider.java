package com.mrlbs.booking.service;

import com.mrlbs.resource.Resource;
import java.math.BigDecimal;

/**
 * Provides the contention multiplier (Mcontention) for a booking.
 * The default implementation returns {@code BigDecimal.ONE} indicating no contention.
 */
public interface ContentionProvider {
    /**
     * @param resource the resource being booked
     * @return multiplier as BigDecimal (e.g., 1.2 if many users are waiting)
     */
    BigDecimal getMcontentionMultiplier(Resource resource);
}
