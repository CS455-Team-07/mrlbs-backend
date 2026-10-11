package com.mrlbs.resource;

public class ResourceUpdateRequest {

    private String name;
    private String type;
    private String location;
    private Integer capacity;
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
