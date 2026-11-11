package com.mrlbs.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.time.LocalDate;

public record AiPromptRequest(
        @NotBlank(message = "Prompt cannot be blank")
        @Size(max = 1000, message = "Prompt cannot exceed 1000 characters")
        String prompt,
        LocalDate contextDate,
        @Valid ExtractedIntent previousIntent
) {
    public AiPromptRequest(String prompt, String contextDate) {
        this(prompt, contextDate == null || contextDate.isBlank() ? null : LocalDate.parse(contextDate), null);
    }
}
