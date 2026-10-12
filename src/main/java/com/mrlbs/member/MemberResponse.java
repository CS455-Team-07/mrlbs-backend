package com.mrlbs.member;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record MemberResponse(
        UUID id,
        String email,
        String name,
        String role,
        BigDecimal credits,
        LocalDateTime createdAt
) {}
