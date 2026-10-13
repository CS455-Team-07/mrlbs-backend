package com.mrlbs.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/** Durable audit entry for a booking lifecycle transition. */
@Entity
@Table(name = "booking_status_events")
public class BookingStatusEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private BookingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private BookingStatus toStatus;

    @Column(name = "actor_email", nullable = false, length = 320)
    private String actorEmail;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(length = 1000)
    private String note;

    protected BookingStatusEvent() { }

    public BookingStatusEvent(UUID bookingId, BookingStatus fromStatus, BookingStatus toStatus,
                              String actorEmail, LocalDateTime occurredAt, String note) {
        this.bookingId = bookingId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorEmail = actorEmail;
        this.occurredAt = occurredAt;
        this.note = note;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public BookingStatus getFromStatus() { return fromStatus; }
    public BookingStatus getToStatus() { return toStatus; }
    public String getActorEmail() { return actorEmail; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public String getNote() { return note; }
}
