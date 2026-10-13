package com.mrlbs.ai;

import java.util.List;

public record AiRecommendationResponse(
        boolean success,
        String message,
        String followUpQuestion,
        String engagementPrompt,
        List<String> suggestedRefinements,
        ExtractedIntent extractedIntent,
        ResourceRecommendation topRecommendation,
        List<ResourceRecommendation> alternatives,
        boolean configurationError
) {
    public static AiRecommendationResponse error(String message) {
        return new AiRecommendationResponse(
                false,
                message,
                null,
                null,
                List.of(),
                null,
                null,
                List.of(),
                false
        );
    }

    public static AiRecommendationResponse configError(String message) {
        return new AiRecommendationResponse(
                false,
                message,
                null,
                null,
                List.of(),
                null,
                null,
                List.of(),
                true
        );
    }

    public static AiRecommendationResponse followUp(String followUpQuestion, ExtractedIntent intent, List<String> suggestions) {
        return new AiRecommendationResponse(
                true,
                followUpQuestion,
                followUpQuestion,
                null,
                suggestions != null ? suggestions : List.of(),
                intent,
                null,
                List.of(),
                false
        );
    }

    public static AiRecommendationResponse results(
            String message,
            String engagementPrompt,
            List<String> suggestedRefinements,
            ExtractedIntent intent,
            ResourceRecommendation topRecommendation,
            List<ResourceRecommendation> alternatives) {
        return new AiRecommendationResponse(
                true,
                message,
                null,
                engagementPrompt,
                suggestedRefinements != null ? suggestedRefinements : List.of(),
                intent,
                topRecommendation,
                alternatives != null ? alternatives : List.of(),
                false
        );
    }
}
