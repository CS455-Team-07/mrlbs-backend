package com.mrlbs.ai.review;

import java.time.LocalDateTime;

public record AiReviewSummaryResponse(
        AiReviewRecommendation recommendation,
        Double confidenceScore,
        String rationale,
        String supportingEvidence,
        String dataLimitations,
        boolean isDemonstrationData,
        AiReviewStatus status,
        String modelName,
        LocalDateTime completedAt
) {
    public static AiReviewSummaryResponse from(BookingAiReview review) {
        if (review == null) {
            return null;
        }
        return new AiReviewSummaryResponse(
                review.getRecommendation(),
                review.getConfidenceScore(),
                review.getRationale(),
                review.getSupportingEvidence(),
                review.getDataLimitations(),
                review.isDemonstrationData(),
                review.getStatus(),
                review.getModelName(),
                review.getCompletedAt()
        );
    }
}
