package com.mrlbs.waitlist;

import com.mrlbs.booking.Booking;
import com.mrlbs.notification.NotificationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {
    private final WaitlistService waitlistService;
    private final WaitlistOfferService waitlistOfferService;

    public WaitlistController(WaitlistService waitlistService, WaitlistOfferService waitlistOfferService) {
        this.waitlistService = waitlistService;
        this.waitlistOfferService = waitlistOfferService;
    }

    @GetMapping
    @PreAuthorize("hasRole('MEMBER')")
    public List<WaitlistResponse> getMyWaitlist(
            @AuthenticationPrincipal OidcUser user) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to view waitlist");
        }
        return waitlistService.getMemberWaitlist(email);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MEMBER')")
    public WaitlistResponse leaveWaitlist(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID id) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to leave waitlist");
        }
        return waitlistService.leaveWaitlist(email, id);
    }

    @GetMapping("/offers")
    @PreAuthorize("hasRole('MEMBER')")
    public List<WaitlistOfferResponse> getActiveOffers(
            @AuthenticationPrincipal OidcUser user) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to view offers");
        }
        return waitlistOfferService.getActiveOffersForMember(email);
    }

    @PostMapping("/offers/{offerId}/accept")
    @PreAuthorize("hasRole('MEMBER')")
    public Booking acceptOffer(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID offerId) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to accept offer");
        }
        return waitlistOfferService.acceptOffer(email, offerId);
    }

    @PostMapping("/offers/{offerId}/decline")
    @PreAuthorize("hasRole('MEMBER')")
    public WaitlistOfferResponse declineOffer(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID offerId) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to decline offer");
        }
        WaitlistOffer declined = waitlistOfferService.declineOffer(email, offerId);
        return WaitlistOfferResponse.from(declined);
    }

    @GetMapping("/notifications")
    @PreAuthorize("hasRole('MEMBER')")
    public List<NotificationResponse> getNotifications(
            @AuthenticationPrincipal OidcUser user) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to view notifications");
        }
        return waitlistOfferService.getNotificationsForMember(email).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @PostMapping("/notifications/{id}/read")
    @PreAuthorize("hasRole('MEMBER')")
    public void markNotificationRead(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable UUID id) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to update notification");
        }
        waitlistOfferService.markNotificationRead(email, id);
    }
}
