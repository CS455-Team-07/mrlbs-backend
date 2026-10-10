package com.mrlbs.booking.service;

import com.mrlbs.resource.Resource;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ContentionProvider} that returns a multiplier of 1.
 * This can be replaced with a bean that calculates contention based on a waitlist.
 */
@Service
public class DefaultContentionProvider implements ContentionProvider {
    @Override
    public BigDecimal getMcontentionMultiplier(Resource resource) {
        return BigDecimal.ONE;
    }
}
