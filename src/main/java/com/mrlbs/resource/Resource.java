package com.mrlbs.resource;

import jakarta.persistence.*;

@Entity
@Table(name = "resources")
public class Resource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String type;

    private String location;

    private Integer capacity;

    @Column(nullable = false)
    private java.math.BigDecimal baseRate;
    // Admin‑configured time multiplier (null means 1.0)
    private java.math.BigDecimal mtimeMultiplier;

    @OneToOne(
        cascade = CascadeType.ALL,
        orphanRemoval = true
    )
    @JoinColumn(name = "booking_rule_id")
    private BookingRule bookingRule;

    public Resource() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public java.math.BigDecimal getBaseRate() {
        return baseRate;
    }

    public void setBaseRate(java.math.BigDecimal baseRate) {
        this.baseRate = baseRate;
    }

    public java.math.BigDecimal getMtimeMultiplier() {
        return mtimeMultiplier;
    }

    public void setMtimeMultiplier(java.math.BigDecimal mtimeMultiplier) {
        this.mtimeMultiplier = mtimeMultiplier;
    }


    public BookingRule getBookingRule() {
        return bookingRule;
    }

    public void setBookingRule(BookingRule bookingRule) {
        this.bookingRule = bookingRule;
    }
}