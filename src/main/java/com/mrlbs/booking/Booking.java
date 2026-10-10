package com.mrlbs.booking;

import com.mrlbs.resource.Resource;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Member identifier – placeholder until real Member entity exists
    @Column(nullable = false)
    private Long memberId;

    @ManyToOne
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    // Calculated credit cost; may be null for drafts before pricing
    @Column(precision = 10, scale = 2)
    private BigDecimal creditCost;

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Resource getResource() { return resource; }
    public void setResource(Resource resource) { this.resource = resource; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public BigDecimal getCreditCost() { return creditCost; }
    public void setCreditCost(BigDecimal creditCost) { this.creditCost = creditCost; }
}
