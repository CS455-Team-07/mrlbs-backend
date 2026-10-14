package com.mrlbs.waitlist;

import com.mrlbs.booking.BookingStatus;
import com.mrlbs.resource.LabResource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "waitlist_entries")
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    private LabResource resource;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "credit_cost", precision = 12, scale = 0)
    private java.math.BigDecimal creditCost;

    @Column(name = "contention_multiplier", precision = 8, scale = 2)
    private java.math.BigDecimal contentionMultiplier;

    protected WaitlistEntry() {
    }

    public WaitlistEntry(UUID memberId, LabResource resource, LocalDateTime startTime,
                         LocalDateTime endTime, BookingStatus status, UUID bookingId) {
        this(memberId, resource, startTime, endTime, status, bookingId, null, null);
    }

    public WaitlistEntry(UUID memberId, LabResource resource, LocalDateTime startTime,
                         LocalDateTime endTime, BookingStatus status, UUID bookingId,
                         java.math.BigDecimal creditCost, java.math.BigDecimal contentionMultiplier) {
        this.memberId = memberId;
        this.resource = resource;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.bookingId = bookingId;
        this.creditCost = creditCost;
        this.contentionMultiplier = contentionMultiplier;
        this.joinedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.joinedAt == null) {
            this.joinedAt = LocalDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public void setMemberId(UUID memberId) {
        this.memberId = memberId;
    }

    public LabResource getResource() {
        return resource;
    }

    public void setResource(LabResource resource) {
        this.resource = resource;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public LocalDateTime getLeftAt() {
        return leftAt;
    }

    public void setLeftAt(LocalDateTime leftAt) {
        this.leftAt = leftAt;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public void setBookingId(UUID bookingId) {
        this.bookingId = bookingId;
    }

    public java.math.BigDecimal getCreditCost() {
        return creditCost;
    }

    public void setCreditCost(java.math.BigDecimal creditCost) {
        this.creditCost = creditCost;
    }

    public java.math.BigDecimal getContentionMultiplier() {
        return contentionMultiplier;
    }

    public void setContentionMultiplier(java.math.BigDecimal contentionMultiplier) {
        this.contentionMultiplier = contentionMultiplier;
    }
}
