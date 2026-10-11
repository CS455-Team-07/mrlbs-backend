package com.mrlbs.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record ResourceRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 100) String type,
        @NotBlank @Size(max = 255) String location,
        @Positive Integer capacity,
        @DecimalMin(value = "0.01") BigDecimal minimumDurationHours,
        @DecimalMin(value = "0.00") BigDecimal maximumContentionMultiplier,
        @NotEmpty List<@NotBlank @Size(max = 1000) String> rules) {
}
