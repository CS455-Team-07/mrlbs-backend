package com.mrlbs.booking;

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
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_email", nullable = false, length = 320)
    private String ownerEmail;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    private LabResource resource;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BookingStatus status;

    @Column(name = "credit_cost", nullable = false, precision = 12, scale = 0)
    private BigDecimal creditCost;

    protected Booking() {
    }

    public Booking(String ownerEmail, LabResource resource, LocalDateTime startTime,
                   LocalDateTime endTime, BookingStatus status, BigDecimal creditCost) {
        this.ownerEmail = ownerEmail;
        this.resource = resource;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.creditCost = creditCost;
    }

    public UUID getId() { return id; }
    public String getOwnerEmail() { return ownerEmail; }
    public LabResource getResource() { return resource; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public BookingStatus getStatus() { return status; }
    public BigDecimal getCreditCost() { return creditCost; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public void setStatus(BookingStatus status) { this.status = status; }
}
