package com.mrlbs.resource;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.persistence.*;

@Entity
@Table(name = "booking_rules")
public class BookingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NotNull
    private BookingPolicy bookingPolicy;

    @Column(nullable = false)
    @Positive
    private Integer minDurationMinutes;

    @Column(nullable = false)
    @Positive
    private Integer maxDurationMinutes;

    @Column(nullable = false)
    @PositiveOrZero
    private Integer bufferTimeMinutes;

    @Column(nullable = false)
    @Positive
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

    @JsonIgnore
    @AssertTrue(message = "minDurationMinutes must not exceed maxDurationMinutes")
    public boolean isDurationRangeValid() {
        return minDurationMinutes == null || maxDurationMinutes == null
                || minDurationMinutes <= maxDurationMinutes;
    }
}
