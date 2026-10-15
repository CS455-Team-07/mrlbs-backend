package com.mrlbs.member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MemberLoginRequest(
        @NotBlank @Email @Size(max = 254)
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@iitk\\.ac\\.in$", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "email must use the @iitk.ac.in domain") String email,
        @Size(max = 120) String name
) {}
