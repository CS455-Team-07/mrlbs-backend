package com.mrlbs.waitlist;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {

    private final WaitlistRepository waitlistRepository;

    public WaitlistController(WaitlistRepository waitlistRepository) {
        this.waitlistRepository = waitlistRepository;
    }

    /**
     * Returns all waitlist entries for the given resource.
     *
     * @param resourceId the ID of the resource whose waitlist is requested
     * @return list of {@link WaitlistEntry} objects for that resource
     */
    @GetMapping
    public ResponseEntity<List<WaitlistEntry>> getWaitlistForResource(
            @RequestParam Long resourceId) {
        List<WaitlistEntry> entries = waitlistRepository.findAllByResource_Id(resourceId);
        return ResponseEntity.ok(entries);
    }
}
