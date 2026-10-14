package com.mrlbs.ai.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "booking_ai_reviews")
public class BookingAiReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", length = 32)
    private AiReviewRecommendation recommendation;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "supporting_evidence", columnDefinition = "TEXT")
    private String supportingEvidence;

    @Column(name = "data_limitations", columnDefinition = "TEXT")
    private String dataLimitations;

    @Column(name = "is_demonstration_data", nullable = false)
    private boolean isDemonstrationData = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AiReviewStatus status = AiReviewStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public BookingAiReview() {}

    public BookingAiReview(UUID bookingId, AiReviewStatus status) {
        this.bookingId = bookingId;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public static BookingAiReview fallbackHumanOnly(UUID bookingId, String reason) {
        BookingAiReview review = new BookingAiReview(bookingId, AiReviewStatus.FALLBACK_HUMAN_ONLY);
        review.setRecommendation(AiReviewRecommendation.NEEDS_HUMAN_REVIEW);
        review.setConfidenceScore(1.0);
        review.setRationale("Manual review required: " + reason);
        review.setErrorMessage(reason);
        review.setCompletedAt(LocalDateTime.now());
        return review;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public AiReviewRecommendation getRecommendation() { return recommendation; }
    public void setRecommendation(AiReviewRecommendation recommendation) { this.recommendation = recommendation; }
    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public String getSupportingEvidence() { return supportingEvidence; }
    public void setSupportingEvidence(String supportingEvidence) { this.supportingEvidence = supportingEvidence; }
    public String getDataLimitations() { return dataLimitations; }
    public void setDataLimitations(String dataLimitations) { this.dataLimitations = dataLimitations; }
    public boolean isDemonstrationData() { return isDemonstrationData; }
    public void setDemonstrationData(boolean demonstrationData) { isDemonstrationData = demonstrationData; }
    public AiReviewStatus getStatus() { return status; }
    public void setStatus(AiReviewStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
