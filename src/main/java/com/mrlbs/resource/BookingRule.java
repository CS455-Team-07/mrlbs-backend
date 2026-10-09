package com.mrlbs.resource;

import jakarta.persistence.*;

@Entity
@Table(name = "booking_rules")
public class BookingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingPolicy bookingPolicy;

    @Column(nullable = false)
    private Integer minDurationMinutes;

    @Column(nullable = false)
    private Integer maxDurationMinutes;

    @Column(nullable = false)
    private Integer bufferTimeMinutes;

    @Column(nullable = false)
    private Integer maxBookingsPerMember;

    public BookingRule() {
    }

    public Long getId() {
        return id;
    }

    public BookingPolicy getBookingPolicy() {
        return bookingPolicy;
    }

    public void setBookingPolicy(BookingPolicy bookingPolicy) {
        this.bookingPolicy = bookingPolicy;
    }

    public Integer getMinDurationMinutes() {
        return minDurationMinutes;
    }

    public void setMinDurationMinutes(Integer minDurationMinutes) {
        this.minDurationMinutes = minDurationMinutes;
    }

    public Integer getMaxDurationMinutes() {
        return maxDurationMinutes;
    }

    public void setMaxDurationMinutes(Integer maxDurationMinutes) {
        this.maxDurationMinutes = maxDurationMinutes;
    }

    public Integer getBufferTimeMinutes() {
        return bufferTimeMinutes;
    }

    public void setBufferTimeMinutes(Integer bufferTimeMinutes) {
        this.bufferTimeMinutes = bufferTimeMinutes;
    }

    public Integer getMaxBookingsPerMember() {
        return maxBookingsPerMember;
    }

    public void setMaxBookingsPerMember(Integer maxBookingsPerMember) {
        this.maxBookingsPerMember = maxBookingsPerMember;
    }
}