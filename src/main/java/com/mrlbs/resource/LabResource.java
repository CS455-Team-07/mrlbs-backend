package com.mrlbs.resource;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "resources")
public class LabResource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "resource_type", nullable = false, length = 100)
    private String type;

    @Column(nullable = false, length = 255)
    private String location;

    private Integer capacity;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "base_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseRate = BigDecimal.ZERO;

    @Column(name = "minimum_duration_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal minimumDurationHours = new BigDecimal("1.00");

    @Column(name = "maximum_contention_multiplier", nullable = false, precision = 8, scale = 2)
    private BigDecimal maximumContentionMultiplier = new BigDecimal("3.00");

    @org.hibernate.annotations.BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "resource_rules", joinColumns = @JoinColumn(name = "resource_id"))
    @Column(name = "rule_text", nullable = false, length = 1000)
    @OrderColumn(name = "rule_order")
    private List<String> rules = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "booking_rule_id")
    private BookingRule bookingRule;

    protected LabResource() {
    }

    public LabResource(String name, String type, String location, Integer capacity, List<String> rules,
                       BigDecimal minimumDurationHours, BigDecimal maximumContentionMultiplier,
                       BigDecimal baseRate) {
        this.name = name;
        this.type = type;
        this.location = location;
        this.capacity = capacity;
        this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
        this.minimumDurationHours = minimumDurationHours != null ? minimumDurationHours : new BigDecimal("1.00");
        this.maximumContentionMultiplier = maximumContentionMultiplier != null ? maximumContentionMultiplier : new BigDecimal("3.00");
        this.baseRate = baseRate != null ? baseRate : BigDecimal.ZERO;
        this.active = true;
        this.bookingRule = createDefaultBookingRule();
    }

    private BookingRule createDefaultBookingRule() {
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(BookingPolicy.FCFS);
        int minMinutes = this.minimumDurationHours != null ? (int) (this.minimumDurationHours.doubleValue() * 60) : 60;
        rule.setMinDurationMinutes(Math.max(minMinutes, 30));
        rule.setMaxDurationMinutes(Math.max(minMinutes * 4, 180));
        rule.setBufferTimeMinutes(15);
        rule.setMaxBookingsPerMember(3);
        return rule;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BigDecimal getMinimumDurationHours() {
        return minimumDurationHours;
    }

    public void setMinimumDurationHours(BigDecimal minimumDurationHours) {
        this.minimumDurationHours = minimumDurationHours;
    }

    public BigDecimal getBaseRate() {
        return baseRate;
    }

    public void setBaseRate(BigDecimal baseRate) {
        this.baseRate = baseRate;
    }

    public BigDecimal getMaximumContentionMultiplier() {
        return maximumContentionMultiplier;
    }

    public void setMaximumContentionMultiplier(BigDecimal maximumContentionMultiplier) {
        this.maximumContentionMultiplier = maximumContentionMultiplier;
    }

    public List<String> getRules() {
        return List.copyOf(rules);
    }

    public void setRules(List<String> rules) {
        this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
    }

    public BookingRule getBookingRule() {
        if (bookingRule == null) {
            bookingRule = createDefaultBookingRule();
        }
        return bookingRule;
    }

    public void setBookingRule(BookingRule bookingRule) {
        this.bookingRule = bookingRule;
    }
}
