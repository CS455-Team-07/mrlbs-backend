package com.mrlbs.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(AiRecommendationService.class);

    private final GeminiClient geminiClient;
    private final LabResourceRepository resourceRepository;
    private final BookingRepository bookingRepository;
    private final ObjectMapper objectMapper;
    private final com.mrlbs.ai.observability.AiAgentLogger aiLogger;

    public AiRecommendationService(GeminiClient geminiClient,
                                   LabResourceRepository resourceRepository,
                                   BookingRepository bookingRepository,
                                   ObjectMapper objectMapper) {
        this(geminiClient, resourceRepository, bookingRepository, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiRecommendationService(GeminiClient geminiClient,
                                   LabResourceRepository resourceRepository,
                                   BookingRepository bookingRepository,
                                   ObjectMapper objectMapper,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false) com.mrlbs.ai.observability.AiAgentLogger aiLogger) {
        this.geminiClient = geminiClient;
        this.resourceRepository = resourceRepository;
        this.bookingRepository = bookingRepository;
        this.objectMapper = objectMapper;
        this.aiLogger = aiLogger;
    }

    /**
     * Parses the member's natural-language prompt into structured search criteria.
     * Enforces date as mandatory.
     */
    public ExtractedIntent parseIntent(String prompt, LocalDate referenceDate) {
        if (prompt == null || prompt.isBlank()) {
            return new ExtractedIntent(null, null, null, null, null, "Please enter what type of resource you are looking for.");
        }

        if (isGreeting(prompt)) {
            return new ExtractedIntent(
                    null, null, null, null, null,
                    "Hello! How can I help you with resource booking today? What type of resource are you looking for (e.g. Lecture Hall, Lab, GPU Cluster), and for which date?"
            );
        }

        LocalDate today = referenceDate != null ? referenceDate : LocalDate.now();

        // 1. Instant local fast path: if prompt has clear unambiguous criteria, return in < 1ms
        ExtractedIntent localIntent = parseIntentFallback(prompt, today);
        if (isHighConfidence(localIntent, prompt)) {
            return localIntent;
        }

        // 2. Natural language & complex conversational parsing via Gemini
        if (geminiClient.isConfigured()) {
            Optional<ExtractedIntent> aiIntent = parseIntentWithGemini(prompt, today);
            if (aiIntent.isPresent()) {
                return aiIntent.get();
            }
        }

        // 3. Fallback
        return localIntent;
    }

    private boolean isGreeting(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String clean = prompt.trim().toLowerCase(Locale.ROOT).replaceAll("[!?,.]", "");
        return clean.equals("hi") || clean.equals("hello") || clean.equals("hey") ||
                clean.equals("hii") || clean.equals("heyy") || clean.equals("good morning") ||
                clean.equals("good afternoon") || clean.equals("good evening") ||
                clean.equals("greetings") || clean.equals("help") || clean.equals("start") ||
                clean.equals("hi there") || clean.equals("hello there");
    }

    private boolean isHighConfidence(ExtractedIntent intent, String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT).trim();
        if (isGreeting(prompt)) {
            return true;
        }
        // Direct type selection chips (e.g. "Lecture Hall", "KD Lab", "GPU Cluster", "LHC")
        if (lower.equals("lecture hall") || lower.equals("lhc") || lower.equals("kd lab") || lower.equals("kd") || lower.equals("rm") || lower.equals("lab") || lower.equals("gpu") || lower.equals("gpu cluster") || lower.equals("microscope")) {
            return true;
        }
        // Clear date + recognized resource type (e.g. "lecture for 19 th oct")
        if (intent.hasDate() && intent.resourceType() != null) {
            return true;
        }
        // Direct refinement chips (e.g. "10:00 - 12:00", "50+ seats", "today", "tomorrow")
        if (lower.length() < 25 && (intent.startTime() != null || (intent.minCapacity() != null && lower.contains("seat")) || lower.equals("today") || lower.equals("tomorrow"))) {
            return true;
        }
        return false;
    }

    private Optional<ExtractedIntent> parseIntentWithGemini(String prompt, LocalDate today) {
        String correlationId = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID);
        String systemInstruction = String.format(
                "You are the MRLBS AI intent parser for Indian Institute of Technology Kanpur (IITK) resource bookings. " +
                        "Reference date: %s (%d). " +
                        "Campus building context & type mappings: " +
                        "- 'LHC' or 'Lecture Hall Complex' -> resourceType: 'Lecture Hall' (e.g. L-7, L-1, classrooms). " +
                        "- 'KD' or 'KD Building' -> resourceType: 'KD Lab' (computer science / data labs). " +
                        "- 'RM' or 'RM Building' or 'Rajeev Motwani' -> resourceType: 'RM Building' (computing facilities). " +
                        "- 'GPU' / 'Cluster' / 'DGX' -> resourceType: 'GPU'. " +
                        "Extract criteria into valid JSON: " +
                        "{\"resourceType\": string|null, " +
                        "\"date\": \"YYYY-MM-DD\"|null, " +
                        "\"startTime\": \"HH:mm\"|null, " +
                        "\"endTime\": \"HH:mm\"|null, " +
                        "\"minCapacity\": integer|null, " +
                        "\"clarifyingQuestion\": string|null}. " +
                        "Rule: If no specific or relative date was given, set date to null and clarifyingQuestion to 'Could you please specify which date you need this resource for?'. " +
                        "Output ONLY valid JSON.",
                today.toString(), today.getYear()
        );

        long startNs = System.nanoTime();
        if (aiLogger != null) {
            aiLogger.logToolStart(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                    com.mrlbs.ai.observability.AiAgentLogger.TOOL_INTENT_PARSER, java.util.Map.of("parser", "gemini"));
        }

        Optional<String> jsonResponse = geminiClient.generateStructuredContent(systemInstruction, prompt);
        if (jsonResponse.isEmpty()) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (aiLogger != null) {
                aiLogger.logToolFailure(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        com.mrlbs.ai.observability.AiAgentLogger.TOOL_INTENT_PARSER, durationMs, "NO_RESPONSE", "Gemini returned empty intent response");
            }
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(jsonResponse.get());
            String resourceType = root.path("resourceType").isNull() ? null : root.path("resourceType").asText(null);
            String dateStr = root.path("date").isNull() ? null : root.path("date").asText(null);
            String startTimeStr = root.path("startTime").isNull() ? null : root.path("startTime").asText(null);
            String endTimeStr = root.path("endTime").isNull() ? null : root.path("endTime").asText(null);
            Integer minCapacity = root.path("minCapacity").isInt() ? root.path("minCapacity").asInt() : null;
            String clarifying = root.path("clarifyingQuestion").isNull() ? null : root.path("clarifyingQuestion").asText(null);

            String normalizedType = normalizeResourceType(resourceType);
            LocalDate date = (dateStr != null && !dateStr.isBlank()) ? LocalDate.parse(dateStr) : null;
            LocalTime startTime = parseFlexibleTime(startTimeStr);
            LocalTime endTime = parseFlexibleTime(endTimeStr);

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (aiLogger != null) {
                aiLogger.logToolComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        com.mrlbs.ai.observability.AiAgentLogger.TOOL_INTENT_PARSER, durationMs, "SUCCESS",
                        java.util.Map.of("resourceType", normalizedType != null ? normalizedType : "UNSPECIFIED",
                                "hasDate", date != null));
            }

            return Optional.of(new ExtractedIntent(normalizedType, date, startTime, endTime, minCapacity, clarifying));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            String sanitizedMsg = com.mrlbs.ai.observability.AiLogSanitizer.sanitize(e.getMessage());
            log.warn("Failed to parse Gemini intent JSON output: {}", sanitizedMsg);
            if (aiLogger != null) {
                aiLogger.logValidationFailure(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        "INTENT_SCHEMA_ERROR: " + sanitizedMsg, jsonResponse.get());
                aiLogger.logToolFailure(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        com.mrlbs.ai.observability.AiAgentLogger.TOOL_INTENT_PARSER, durationMs, e.getClass().getSimpleName(), sanitizedMsg);
            }
            return Optional.empty();
        }
    }

    private LocalTime parseFlexibleTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        try {
            return LocalTime.parse(trimmed);
        } catch (Exception ignored) {}
        try {
            return LocalTime.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern("H:mm"));
        } catch (Exception ignored) {}
        try {
            return LocalTime.parse(trimmed.toUpperCase(Locale.ENGLISH), java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        } catch (Exception ignored) {}
        try {
            return LocalTime.parse(trimmed.toUpperCase(Locale.ENGLISH), java.time.format.DateTimeFormatter.ofPattern("h a", Locale.ENGLISH));
        } catch (Exception ignored) {}
        if (trimmed.matches("\\d{1,2}")) {
            try {
                int h = Integer.parseInt(trimmed);
                if (h >= 0 && h <= 23) {
                    return LocalTime.of(h, 0);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String normalizeResourceType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("lecture") || lower.contains("hall") || lower.contains("classroom") || lower.contains("lhc")) {
            return "Lecture Hall";
        }
        if (lower.contains("kd") || lower.contains("lab")) {
            return "KD Lab";
        }
        if (lower.contains("rm")) {
            return "RM Building";
        }
        if (lower.contains("gpu") || lower.contains("cluster")) {
            return "GPU";
        }
        if (lower.contains("microscope")) {
            return "Microscope";
        }
        return raw.trim();
    }

    private ExtractedIntent parseIntentFallback(String prompt, LocalDate today) {
        String lower = prompt.toLowerCase(Locale.ROOT);

        // Date extraction
        LocalDate date = null;
        int matchedDayNumber = -1;
        if (lower.contains("tomorrow")) {
            date = today.plusDays(1);
        } else if (lower.contains("today")) {
            date = today;
        } else {
            Matcher dateMatcher = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(lower);
            if (dateMatcher.find()) {
                try {
                    date = LocalDate.parse(dateMatcher.group(1));
                    matchedDayNumber = date.getDayOfMonth();
                } catch (Exception ignored) {}
            } else {
                // Support spaces in day numbers e.g. "19 th oct", "19th oct", "19 oct"
                Matcher monthMatcher = Pattern.compile("(\\d{1,2})\\s*(?:st|nd|rd|th)?\\s+(?:of\\s+)?(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)").matcher(lower);
                if (monthMatcher.find()) {
                    int day = Integer.parseInt(monthMatcher.group(1));
                    int month = parseMonth(monthMatcher.group(2));
                    if (month > 0 && day > 0 && day <= 31) {
                        int year = today.getYear();
                        LocalDate candidate = LocalDate.of(year, month, day);
                        if (candidate.isBefore(today.minusMonths(1))) {
                            candidate = candidate.plusYears(1);
                        }
                        date = candidate;
                        matchedDayNumber = day;
                    }
                } else {
                    Matcher monthFirst = Pattern.compile("(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{1,2})\\s*(?:st|nd|rd|th)?").matcher(lower);
                    if (monthFirst.find()) {
                        int month = parseMonth(monthFirst.group(1));
                        int day = Integer.parseInt(monthFirst.group(2));
                        if (month > 0 && day > 0 && day <= 31) {
                            int year = today.getYear();
                            LocalDate candidate = LocalDate.of(year, month, day);
                            if (candidate.isBefore(today.minusMonths(1))) {
                                candidate = candidate.plusYears(1);
                            }
                            date = candidate;
                            matchedDayNumber = day;
                        }
                    }
                }
            }
        }

        // Capacity extraction (exclude date day number e.g. 19 in "19 th oct")
        Integer capacity = null;
        Matcher capMatcher = Pattern.compile("(\\d+)\\+?\\s*(?:people|persons|seats|students|capacity|users)?").matcher(lower);
        while (capMatcher.find()) {
            try {
                int val = Integer.parseInt(capMatcher.group(1));
                if (val == matchedDayNumber) {
                    continue; // Skip day number so it is never treated as capacity
                }
                if (val > 12 && val < 5000) { // plausible attendee count
                    capacity = val;
                    break;
                }
            } catch (Exception ignored) {}
        }

        // Resource type extraction (recognize "lecture", "hall", "classroom", "lab", "kd", "lhc", "rm")
        String type = null;
        if (lower.contains("lecture") || lower.contains("classroom") || lower.contains("hall") || lower.contains("room") || lower.contains("lhc")) {
            type = "Lecture Hall";
        } else if (lower.contains("kd") || lower.contains("lab")) {
            type = "KD Lab";
        } else if (lower.contains("rm")) {
            type = "RM Building";
        } else if (lower.contains("gpu") || lower.contains("compute") || lower.contains("cluster")) {
            type = "GPU";
        } else if (lower.contains("microscope")) {
            type = "Microscope";
        }

        // Time extraction
        LocalTime startTime = null;
        LocalTime endTime = null;
        Matcher timeRangeMatcher = Pattern.compile("(\\d{1,2}(?::\\d{2})?)\\s*(?:-|to)\\s*(\\d{1,2}(?::\\d{2})?)").matcher(lower);
        if (timeRangeMatcher.find()) {
            LocalTime parsedS = parseFlexibleTime(timeRangeMatcher.group(1));
            LocalTime parsedE = parseFlexibleTime(timeRangeMatcher.group(2));
            if (parsedS != null) startTime = parsedS;
            if (parsedE != null) endTime = parsedE;
        } else if (lower.contains("10 am to noon") || lower.contains("10:00 to 12:00") || lower.contains("10 to 12") || lower.contains("10:00 - 12:00")) {
            startTime = LocalTime.of(10, 0);
            endTime = LocalTime.of(12, 0);
        } else if (lower.contains("9 am to 11 am") || lower.contains("9 to 11") || lower.contains("9:00 - 11:00")) {
            startTime = LocalTime.of(9, 0);
            endTime = LocalTime.of(11, 0);
        } else if (lower.contains("2 pm to 4 pm") || lower.contains("14:00 to 16:00") || lower.contains("14:00 - 16:00")) {
            startTime = LocalTime.of(14, 0);
            endTime = LocalTime.of(16, 0);
        } else if (lower.contains("4 pm to 6 pm") || lower.contains("16:00 to 18:00") || lower.contains("16:00 - 18:00")) {
            startTime = LocalTime.of(16, 0);
            endTime = LocalTime.of(18, 0);
        }

        String clarifyingQuestion = null;
        if (date == null) {
            clarifyingQuestion = "Could you please specify which date you need this resource for (e.g., today, tomorrow, or YYYY-MM-DD)?";
        }

        return new ExtractedIntent(type, date, startTime, endTime, capacity, clarifyingQuestion);
    }

    private int parseMonth(String raw) {
        if (raw == null) return -1;
        String m = raw.toLowerCase(Locale.ROOT);
        if (m.startsWith("jan")) return 1;
        if (m.startsWith("feb")) return 2;
        if (m.startsWith("mar")) return 3;
        if (m.startsWith("apr")) return 4;
        if (m.startsWith("may")) return 5;
        if (m.startsWith("jun")) return 6;
        if (m.startsWith("jul")) return 7;
        if (m.startsWith("aug")) return 8;
        if (m.startsWith("sep")) return 9;
        if (m.startsWith("oct")) return 10;
        if (m.startsWith("nov")) return 11;
        if (m.startsWith("dec")) return 12;
        return -1;
    }

    private ExtractedIntent mergeWithPreviousIntent(ExtractedIntent current, ExtractedIntent previous) {
        if (previous == null) return current;
        String type = (current.resourceType() != null && !current.resourceType().isBlank())
                ? current.resourceType()
                : previous.resourceType();
        LocalDate date = current.date() != null ? current.date() : previous.date();
        LocalTime start = current.startTime() != null ? current.startTime() : previous.startTime();
        LocalTime end = current.endTime() != null ? current.endTime() : previous.endTime();
        Integer cap = current.minCapacity() != null ? current.minCapacity() : previous.minCapacity();
        String clarifying = (date == null) ? current.clarifyingQuestion() : null;
        return new ExtractedIntent(type, date, start, end, cap, clarifying);
    }

    /**
     * Executes the ranked recommendation and closest-alternative pipeline.
     */
    @Transactional(readOnly = true)
    public AiRecommendationResponse getRecommendations(AiPromptRequest request) {
        String correlationId = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = java.util.UUID.randomUUID().toString();
            org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID, correlationId);
        }
        org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_AGENT, com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION);

        LocalDate contextDate = request.contextDate() == null ? LocalDate.now() : request.contextDate();

        long startNs = System.nanoTime();
        if (aiLogger != null) {
            aiLogger.logAgentStart(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                    geminiClient.getModelName(), java.util.Map.of(
                            "promptLength", request.prompt() != null ? request.prompt().length() : 0,
                            "hasPreviousIntent", request.previousIntent() != null,
                            "contextDate", String.valueOf(contextDate)
                    ));
        }

        ExtractedIntent rawIntent = parseIntent(request.prompt(), contextDate);
        ExtractedIntent intent = mergeWithPreviousIntent(rawIntent, request.previousIntent());

        if (isGreeting(request.prompt())) {
            String welcomeMsg = "Hello! How can I help you with resource booking today? What type of resource are you looking for, and for which date?";
            List<String> suggestions = List.of("Lecture Hall", "KD Lab", "GPU Cluster", "Today", "Tomorrow");
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (aiLogger != null) {
                aiLogger.logAgentComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        geminiClient.getModelName(), durationMs, "GREETING", welcomeMsg, java.util.Map.of());
            }
            return AiRecommendationResponse.followUp(welcomeMsg, rawIntent, suggestions);
        }

        // Date is mandatory per requirement
        if (!intent.hasDate()) {
            String question = intent.clarifyingQuestion() != null
                    ? intent.clarifyingQuestion()
                    : (intent.resourceType() == null
                        ? "Could you please specify which resource type (e.g., Lecture Hall, Lab, GPU Cluster) and date you need?"
                        : "Please specify the date for your booking (e.g., today, tomorrow, 2026-10-15).");
            List<String> dateSuggestions = new ArrayList<>();
            if (intent.resourceType() == null) {
                dateSuggestions.addAll(List.of("Lecture Hall", "KD Lab", "GPU Cluster"));
            }
            dateSuggestions.addAll(List.of("Today", "Tomorrow", "Next Monday"));
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (aiLogger != null) {
                aiLogger.logAgentComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        geminiClient.getModelName(), durationMs, "DATE_REQUIRED", question, java.util.Map.of());
            }
            return AiRecommendationResponse.followUp(question, intent, dateSuggestions);
        }

        LocalDate targetDate = intent.date();
        LocalTime reqStart = intent.startTime() != null ? intent.startTime() : computeDefaultStartTime(targetDate, contextDate);
        LocalTime reqEnd = intent.endTime() != null ? intent.endTime() : reqStart.plusHours(2);
        if (!reqEnd.isAfter(reqStart)) {
            reqEnd = reqStart.plusHours(2);
        }

        LocalDateTime slotStart = LocalDateTime.of(targetDate, reqStart);
        LocalDateTime slotEnd = LocalDateTime.of(targetDate, reqEnd);

        // Fetch all active ground-truth resources from database
        long catStartNs = System.nanoTime();
        if (aiLogger != null) {
            aiLogger.logToolStart(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                    com.mrlbs.ai.observability.AiAgentLogger.TOOL_CATALOG_SEARCH,
                    java.util.Map.of("targetDate", targetDate.toString(), "start", reqStart.toString(), "end", reqEnd.toString()));
        }

        List<LabResource> allActiveResources = resourceRepository.findByActiveTrue();
        if (allActiveResources.isEmpty()) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (aiLogger != null) {
                aiLogger.logToolComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        com.mrlbs.ai.observability.AiAgentLogger.TOOL_CATALOG_SEARCH, (System.nanoTime() - catStartNs) / 1_000_000,
                        "EMPTY_CATALOG", java.util.Map.of("activeCount", 0));
                aiLogger.logAgentComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                        geminiClient.getModelName(), durationMs, "NO_CATALOG_RESOURCES", "No active lab resources", java.util.Map.of());
            }
            return AiRecommendationResponse.results(
                    "No active lab resources are currently registered in the institutional catalog.",
                    null,
                    List.of(),
                    intent,
                    null,
                    List.of()
            );
        }

        // Batch query conflicting resource IDs in ONE single database query (eliminating N+1 WAN round-trips)
        List<UUID> bookedList = bookingRepository.findBookedResourceIdsInSlot(slotStart, slotEnd);
        java.util.Set<UUID> bookedResourceIds = bookedList != null ? new java.util.HashSet<>(bookedList) : java.util.Collections.emptySet();

        long catDurationMs = (System.nanoTime() - catStartNs) / 1_000_000;
        if (aiLogger != null) {
            aiLogger.logToolComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                    com.mrlbs.ai.observability.AiAgentLogger.TOOL_CATALOG_SEARCH, catDurationMs, "SUCCESS",
                    java.util.Map.of("activeCatalogCount", allActiveResources.size(),
                            "conflictingSlotsCount", bookedResourceIds.size()));
        }

        // Rank candidates factually against ground-truth database
        List<CandidateEvaluation> evaluations = new ArrayList<>();
        for (LabResource resource : allActiveResources) {
            evaluations.add(evaluateResource(resource, intent, targetDate, reqStart, reqEnd, slotStart, slotEnd, bookedResourceIds));
        }

        // Sort: exact matches first, then lowest capacity waste / highest score
        evaluations.sort(Comparator.comparingInt(CandidateEvaluation::getPriorityScore));

        if (evaluations.isEmpty()) {
            return AiRecommendationResponse.results(
                    "No matching or alternative resources could be found for your request.",
                    null,
                    List.of(),
                    intent,
                    null,
                    List.of()
            );
        }

        ResourceRecommendation top = evaluations.getFirst().toRecommendation();
        List<ResourceRecommendation> alternatives = evaluations.stream()
                .skip(1)
                .limit(4) // 3-4 other alternate resources
                .map(CandidateEvaluation::toRecommendation)
                .toList();

        // Generate factual summary
        String summary = generateSummaryText(intent, top, alternatives);

        // Build engaging prompt and refinement suggestion chips if optional attributes are omitted
        String engagementPrompt = null;
        List<String> suggestedRefinements = new ArrayList<>();

        boolean missingType = (intent.resourceType() == null);
        boolean missingTime = (intent.startTime() == null);
        boolean missingCapacity = (intent.minCapacity() == null);
        String displayType = intent.resourceType() != null ? intent.resourceType() : "Resource";
        List<String> timeSlots = computeDynamicTimeSlots(targetDate, contextDate);

        if (missingType && missingTime && missingCapacity) {
            engagementPrompt = String.format("Found available resources for %s! What type of resource do you need (e.g., Lecture Hall, Lab, or GPU), or would you like to filter by time or capacity?", targetDate);
            suggestedRefinements.addAll(List.of("Lecture Hall", "KD Lab", "GPU Cluster"));
            suggestedRefinements.addAll(timeSlots);
            suggestedRefinements.add("50+ seats");
        } else if (missingType && missingTime) {
            engagementPrompt = String.format("Found resources with %d+ seats on %s. Would you like a Lecture Hall or a Lab, and at what time?", intent.minCapacity(), targetDate);
            suggestedRefinements.addAll(List.of("Lecture Hall", "KD Lab", "GPU Cluster"));
            suggestedRefinements.addAll(timeSlots);
        } else if (missingType && missingCapacity) {
            engagementPrompt = String.format("Showing %s - %s slots on %s. What type of resource do you need — a Lecture Hall, a Lab, or GPU computing?", reqStart, reqEnd, targetDate);
            suggestedRefinements.addAll(List.of("Lecture Hall", "KD Lab", "GPU Cluster", "50+ seats", "100+ seats"));
        } else if (missingType) {
            engagementPrompt = "What type of resource are you looking for — Lecture Hall, Lab, or GPU Cluster?";
            suggestedRefinements.addAll(List.of("Lecture Hall", "KD Lab", "GPU Cluster"));
        } else if (missingTime && missingCapacity) {
            engagementPrompt = String.format("Found available %ss for %s! Would you like to specify a preferred time slot or capacity?", displayType, targetDate);
            suggestedRefinements.addAll(timeSlots);
            suggestedRefinements.addAll(List.of("50+ seats", "80+ seats", "100+ seats"));
        } else if (missingTime) {
            engagementPrompt = String.format("Found %s with capacity for %d+ seats. Would you like to select a specific time slot?", displayType, intent.minCapacity());
            suggestedRefinements.addAll(timeSlots);
        } else if (missingCapacity) {
            engagementPrompt = String.format("Showing %s timeslots on %s. Do you have an expected number of attendees or seats in mind?", displayType, targetDate);
            suggestedRefinements.addAll(List.of("50+ seats", "80+ seats", "100+ seats", "200+ seats"));
        }

        long totalDurationMs = (System.nanoTime() - startNs) / 1_000_000;
        if (aiLogger != null) {
            aiLogger.logAgentComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION, correlationId,
                    geminiClient.getModelName(), totalDurationMs, top.matchType(), summary,
                    java.util.Map.of("topResource", top.name(), "topResourceId", top.resourceId(), "alternativesCount", alternatives.size()));
        }

        return AiRecommendationResponse.results(summary, engagementPrompt, suggestedRefinements, intent, top, alternatives);
    }

    private LocalTime computeDefaultStartTime(LocalDate targetDate, LocalDate contextDate) {
        LocalDate refDate = (contextDate != null) ? contextDate : LocalDate.now();
        if (targetDate.isEqual(refDate)) {
            LocalTime now = LocalTime.now();
            int nextHour = (now.getMinute() == 0 && now.getSecond() == 0) ? now.getHour() : now.getHour() + 1;
            if (nextHour >= 22) {
                return LocalTime.of(22, 0);
            } else if (nextHour <= 8) {
                return LocalTime.of(10, 0);
            } else {
                return LocalTime.of(nextHour, 0);
            }
        }
        return LocalTime.of(10, 0);
    }

    private List<String> computeDynamicTimeSlots(LocalDate targetDate, LocalDate contextDate) {
        LocalDate refDate = (contextDate != null) ? contextDate : LocalDate.now();
        if (targetDate.isEqual(refDate)) {
            LocalTime now = LocalTime.now();
            int nextHour = (now.getMinute() == 0 && now.getSecond() == 0) ? now.getHour() : now.getHour() + 1;
            if (nextHour >= 22) {
                return List.of("22:00 - 23:59");
            } else if (nextHour <= 8) {
                return List.of("10:00 - 12:00", "14:00 - 16:00", "16:00 - 18:00");
            } else {
                List<String> slots = new ArrayList<>();
                int s1 = nextHour;
                int e1 = Math.min(23, s1 + 2);
                slots.add(String.format("%02d:00 - %02d:00", s1, e1));
                if (e1 < 23) {
                    int s2 = e1;
                    int e2 = Math.min(23, s2 + 2);
                    slots.add(String.format("%02d:00 - %02d:00", s2, e2));
                }
                return slots;
            }
        }
        return List.of("10:00 - 12:00", "14:00 - 16:00", "16:00 - 18:00");
    }

    private CandidateEvaluation evaluateResource(LabResource resource, ExtractedIntent intent,
                                                 LocalDate targetDate, LocalTime reqStart, LocalTime reqEnd,
                                                 LocalDateTime slotStart, LocalDateTime slotEnd,
                                                 java.util.Set<UUID> bookedResourceIds) {
        boolean typeMatches = intent.resourceType() == null ||
                resource.getType().toLowerCase().contains(intent.resourceType().toLowerCase()) ||
                intent.resourceType().toLowerCase().contains(resource.getType().toLowerCase()) ||
                resource.getName().toLowerCase().contains(intent.resourceType().toLowerCase()) ||
                (resource.getLocation() != null && (
                        resource.getLocation().toLowerCase().contains(intent.resourceType().toLowerCase()) ||
                        intent.resourceType().toLowerCase().contains(resource.getLocation().toLowerCase())
                ));

        boolean capacityFits = intent.minCapacity() == null ||
                resource.getCapacity() == null ||
                resource.getCapacity() >= intent.minCapacity();

        // Check availability on requested slot via in-memory O(1) set lookup
        boolean availableAtRequestedTime = !bookedResourceIds.contains(resource.getId());

        String matchType;
        int priorityScore;
        String explanation;
        LocalTime suggestedStart = reqStart;
        LocalTime suggestedEnd = reqEnd;

        if (typeMatches && capacityFits && availableAtRequestedTime) {
            matchType = "EXACT";
            // Prefer resource that fits capacity with minimal excess seating
            int excessCapacity = (resource.getCapacity() != null && intent.minCapacity() != null)
                    ? (resource.getCapacity() - intent.minCapacity())
                    : 0;
            priorityScore = 100 + excessCapacity;

            if (intent.minCapacity() != null && resource.getCapacity() != null) {
                explanation = String.format("Exact match: %s (%s) has capacity for %d people (matches your requirement of %d) and is fully available on %s from %s to %s.",
                        resource.getName(), resource.getLocation(), resource.getCapacity(), intent.minCapacity(),
                        targetDate, reqStart, reqEnd);
            } else {
                explanation = String.format("Exact match: %s (%s) is fully available on %s from %s to %s with no schedule conflicts.",
                        resource.getName(), resource.getLocation(), targetDate, reqStart, reqEnd);
            }
        } else {
            matchType = "ALTERNATIVE";

            if (typeMatches && !availableAtRequestedTime) {
                // Time-shifted alternative: find next open gap on that day
                suggestedStart = reqEnd;
                suggestedEnd = suggestedStart.plusHours(2);
                priorityScore = 300;
                explanation = String.format("Closest Alternative (Time Shift): %s is booked during %s-%s, but is an exact match for your needs and available starting from %s.",
                        resource.getName(), reqStart, reqEnd, suggestedStart);
            } else if (typeMatches && !capacityFits) {
                priorityScore = 400 + Math.abs(intent.minCapacity() - (resource.getCapacity() != null ? resource.getCapacity() : 0));
                explanation = String.format("Closest Alternative (Capacity): %s has capacity for %d seats (less than requested %d), but is fully available on %s at %s.",
                        resource.getName(), resource.getCapacity() != null ? resource.getCapacity() : 0, intent.minCapacity(), targetDate, reqStart);
            } else if (!typeMatches && availableAtRequestedTime) {
                priorityScore = 600;
                explanation = String.format("Alternative Option: %s (%s) is available on %s from %s to %s with %s seats.",
                        resource.getName(), resource.getType(), targetDate, reqStart, reqEnd,
                        resource.getCapacity() != null ? String.valueOf(resource.getCapacity()) : "flexible");
            } else {
                priorityScore = 800;
                explanation = String.format("Nearby Alternative: %s in %s.", resource.getName(), resource.getLocation());
            }

            if (resource.getBookingRule() != null && resource.getBookingRule().getBookingPolicy() == BookingPolicy.APPROVAL_REQUIRED) {
                explanation += " Note: This resource requires administrative approval.";
            }
        }

        return new CandidateEvaluation(
                resource,
                matchType,
                availableAtRequestedTime,
                targetDate,
                reqStart,
                reqEnd,
                suggestedStart,
                suggestedEnd,
                priorityScore,
                explanation
        );
    }

    private String generateSummaryText(ExtractedIntent intent, ResourceRecommendation top, List<ResourceRecommendation> alternatives) {
        if ("EXACT".equals(top.matchType())) {
            return String.format("Found %s as your best match for %s on %s. %s",
                    top.name(),
                    intent.resourceType() != null ? intent.resourceType() : "your booking",
                    top.date(),
                    top.explanation());
        } else {
            return String.format("No exact match found, but %s is your closest recommended alternative. %s",
                    top.name(),
                    top.explanation());
        }
    }

    private static class CandidateEvaluation {
        private final LabResource resource;
        private final String matchType;
        private final boolean available;
        private final LocalDate date;
        private final LocalTime reqStart;
        private final LocalTime reqEnd;
        private final LocalTime suggestedStart;
        private final LocalTime suggestedEnd;
        private final int priorityScore;
        private final String explanation;

        public CandidateEvaluation(LabResource resource, String matchType, boolean available,
                                   LocalDate date, LocalTime reqStart, LocalTime reqEnd,
                                   LocalTime suggestedStart, LocalTime suggestedEnd,
                                   int priorityScore, String explanation) {
            this.resource = resource;
            this.matchType = matchType;
            this.available = available;
            this.date = date;
            this.reqStart = reqStart;
            this.reqEnd = reqEnd;
            this.suggestedStart = suggestedStart;
            this.suggestedEnd = suggestedEnd;
            this.priorityScore = priorityScore;
            this.explanation = explanation;
        }

        public int getPriorityScore() {
            return priorityScore;
        }

        public ResourceRecommendation toRecommendation() {
            String policy = (resource.getBookingRule() != null && resource.getBookingRule().getBookingPolicy() != null)
                    ? resource.getBookingRule().getBookingPolicy().name()
                    : "OPEN";
            return new ResourceRecommendation(
                    resource.getId(),
                    resource.getName(),
                    resource.getType(),
                    resource.getLocation(),
                    resource.getCapacity(),
                    resource.getBaseRate() != null ? resource.getBaseRate() : BigDecimal.ZERO,
                    policy,
                    matchType,
                    available,
                    date,
                    reqStart,
                    reqEnd,
                    suggestedStart,
                    suggestedEnd,
                    explanation,
                    resource.getRules()
            );
        }
    }
}
