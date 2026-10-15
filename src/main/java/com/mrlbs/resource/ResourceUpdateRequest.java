package com.mrlbs.resource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class ResourceUpdateRequest {

    @Size(min = 1, max = 160)
    @Pattern(regexp = ".*\\S.*", message = "name must not be blank")
    private String name;

    @Size(min = 1, max = 100)
    @Pattern(regexp = ".*\\S.*", message = "type must not be blank")
    private String type;

    @Size(min = 1, max = 255)
    @Pattern(regexp = ".*\\S.*", message = "location must not be blank")
    private String location;

    @Positive
    private Integer capacity;

    @Valid
    private BookingRule bookingRule;

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

    public BookingRule getBookingRule() {
        return bookingRule;
    }

    public void setBookingRule(BookingRule bookingRule) {
        this.bookingRule = bookingRule;
    }
}
