package com.mrlbs.waitlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "waitlist_offers")
public class WaitlistOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waitlist_entry_id", nullable = false)
    private WaitlistEntry waitlistEntry;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "offered_at", nullable = false)
    private LocalDateTime offeredAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "resulting_booking_id")
    private UUID resultingBookingId;

    protected WaitlistOffer() {
    }

    public WaitlistOffer(WaitlistEntry waitlistEntry, String status, LocalDateTime expiresAt) {
        this.waitlistEntry = waitlistEntry;
        this.status = status;
        this.offeredAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        if (this.offeredAt == null) {
            this.offeredAt = LocalDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public WaitlistEntry getWaitlistEntry() {
        return waitlistEntry;
    }

    public void setWaitlistEntry(WaitlistEntry waitlistEntry) {
        this.waitlistEntry = waitlistEntry;
    }

    public String getStatus() {
        if ("PENDING".equalsIgnoreCase(status) && expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return "EXPIRED";
        }
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getOfferedAt() {
        return offeredAt;
    }

    public void setOfferedAt(LocalDateTime offeredAt) {
        this.offeredAt = offeredAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(LocalDateTime respondedAt) {
        this.respondedAt = respondedAt;
    }

    public UUID getResultingBookingId() {
        return resultingBookingId;
    }

    public void setResultingBookingId(UUID resultingBookingId) {
        this.resultingBookingId = resultingBookingId;
    }
}
