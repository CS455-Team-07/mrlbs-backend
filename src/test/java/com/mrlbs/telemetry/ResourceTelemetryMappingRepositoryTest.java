package com.mrlbs.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResourceTelemetryMappingRepositoryTest {

    @Autowired
    private ResourceTelemetryMappingRepository repository;

    @Test
    void testSaveAndFindByResourceId() {
        UUID resId = UUID.randomUUID();
        ResourceTelemetryMapping mapping = new ResourceTelemetryMapping(resId, "gpu-node-01");
        repository.save(mapping);

        Optional<ResourceTelemetryMapping> found = repository.findByResourceId(resId);
        assertTrue(found.isPresent());
        assertEquals("gpu-node-01", found.get().getExternalResourceId());
        assertEquals("DEMO_TELEMETRY_SERVICE", found.get().getTelemetrySource());
        assertNotNull(found.get().getCreatedAt());

        Optional<ResourceTelemetryMapping> byExtId = repository.findByExternalResourceId("gpu-node-01");
        assertTrue(byExtId.isPresent());
        assertEquals(resId, byExtId.get().getResourceId());

        assertTrue(repository.existsByResourceId(resId));
        assertFalse(repository.existsByResourceId(UUID.randomUUID()));
    }
}
