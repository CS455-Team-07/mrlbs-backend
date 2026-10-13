package com.mrlbs.booking;

import jakarta.validation.constraints.Size;

public record BookingDecisionRequest(@Size(max = 1000) String note) { }
