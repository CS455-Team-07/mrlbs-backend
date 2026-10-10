package com.mrlbs.booking.service;

import com.mrlbs.resource.Resource;
import com.mrlbs.waitlist.WaitlistRepository;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Provides the contention multiplier (Mcontention) based on the number of members waiting for a resource.
 * If the wait‑list is empty the multiplier is 1.0. For each 5 waiting members an additional 0.2 is added.
 */
@Service
@Primary
public class WaitlistContentionProvider implements ContentionProvider {

    private final WaitlistRepository waitlistRepository;

    @Autowired
    public WaitlistContentionProvider(WaitlistRepository waitlistRepository) {
        this.waitlistRepository = waitlistRepository;
    }

    @Override
    public BigDecimal getMcontentionMultiplier(Resource resource) {
        long waiting = waitlistRepository.countByResource_Id(resource.getId());
        if (waiting == 0) {
            return BigDecimal.ONE;
        }
        // Example: every 5 waiting members adds 0.2 to the multiplier
        BigDecimal step = new BigDecimal("0.2");
        BigDecimal extra = step.multiply(new BigDecimal(waiting / 5));
        return BigDecimal.ONE.add(extra);
    }
}
