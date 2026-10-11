package com.mrlbs.resource;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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

    @Column(name = "minimum_duration_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal minimumDurationHours = new BigDecimal("1.00");

    @Column(name = "maximum_contention_multiplier", nullable = false, precision = 8, scale = 2)
    private BigDecimal maximumContentionMultiplier = new BigDecimal("3.00");

    @ElementCollection
    @CollectionTable(name = "resource_rules", joinColumns = @JoinColumn(name = "resource_id"))
    @Column(name = "rule_text", nullable = false, length = 1000)
    @OrderColumn(name = "rule_order")
    private List<String> rules = new ArrayList<>();

    protected LabResource() {
    }

    public LabResource(String name, String type, String location, Integer capacity, List<String> rules,
                       BigDecimal minimumDurationHours, BigDecimal maximumContentionMultiplier) {
        this.name = name;
        this.type = type;
        this.location = location;
        this.capacity = capacity;
        this.rules = new ArrayList<>(rules);
        this.minimumDurationHours = minimumDurationHours;
        this.maximumContentionMultiplier = maximumContentionMultiplier;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getLocation() {
        return location;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public BigDecimal getMinimumDurationHours() {
        return minimumDurationHours;
    }

    public BigDecimal getMaximumContentionMultiplier() {
        return maximumContentionMultiplier;
    }

    public List<String> getRules() {
        return List.copyOf(rules);
    }
}
