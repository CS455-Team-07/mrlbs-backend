package com.mrlbs.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiPromptRequest(
        @NotBlank(message = "Prompt cannot be blank")
        @Size(max = 1000, message = "Prompt cannot exceed 1000 characters")
        String prompt,
        String contextDate,
        ExtractedIntent previousIntent
) {
    public AiPromptRequest(String prompt, String contextDate) {
        this(prompt, contextDate, null);
    }
}
