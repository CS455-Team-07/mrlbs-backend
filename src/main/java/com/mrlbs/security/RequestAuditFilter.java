package com.mrlbs.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

public class RequestAuditFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger("mrlbs.security.audit");
    private static final Pattern BOOKING_CONFIRM = Pattern.compile("/api/bookings/[^/]+/confirm");
    private final RequestRateLimiter limiter;
    private final int authLimit;
    private final int bookingLimit;
    private final boolean requireTls;

    public RequestAuditFilter(RequestRateLimiter limiter, int authLimit, int bookingLimit, boolean requireTls) {
        if (authLimit < 1 || bookingLimit < 1) {
            throw new IllegalArgumentException("Request rate limits must be positive");
        }
        this.limiter = limiter;
        this.authLimit = authLimit;
        this.bookingLimit = bookingLimit;
        this.requireTls = requireTls;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        String method = request.getMethod();
        boolean signIn = path.startsWith("/oauth2/authorization/") || path.startsWith("/login/oauth2/");
        boolean auth = signIn || path.startsWith("/api/auth/") || path.startsWith("/api/demo/") || path.equals("/logout");
        boolean booking = path.equals("/api/bookings") || path.startsWith("/api/bookings/");
        boolean create = "POST".equals(method) && (path.equals("/api/bookings") || path.equals("/api/bookings/draft")
                || BOOKING_CONFIRM.matcher(path).matches());
        boolean bookingData = booking || path.equals("/api/waitlist") || path.startsWith("/api/waitlist/");
        boolean operationsAccess = path.startsWith("/actuator/") && !path.equals("/actuator/health");
        boolean audit = auth || bookingData || operationsAccess;
        String requestId = audit ? UUID.randomUUID().toString() : null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = authentication != null && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.isAuthenticated();
        String actor = signedIn ? authentication.getName() : "anonymous";
        if (signedIn && authentication.getPrincipal() instanceof OidcUser user && user.getEmail() != null) {
            actor = user.getEmail().toLowerCase(java.util.Locale.ROOT);
        }
        if (audit) {
            response.setHeader("X-Request-ID", requestId);
            response.setHeader("Cache-Control", "no-store");
        }
        String source = request.getRemoteAddr();
        boolean failed = false;
        try {
            if (requireTls && !request.isSecure()) {
                reject(response, 403, "HTTPS is required.");
                return;
            }
            boolean authAttempt = auth && ("POST".equals(method) || signIn)
                    && !path.equals("/api/auth/logout") && !path.equals("/logout");
            String authKey = path.equals("/api/auth/member-login") && signedIn ? "profile:" + actor : "auth-ip:" + source;
            if ((authAttempt && !limiter.allow(authKey, authLimit))
                    || (create && !limiter.allow("booking:" + (signedIn ? actor : source), bookingLimit))) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "Too many requests. Please try again in a minute.");
                return;
            }
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            if (audit) {
                String event = auth ? "authentication" : operationsAccess ? "operations_access"
                        : create ? "booking_creation" : "booking_access";
                LOG.info("event={} request_id={} method={} endpoint={} status={} actor={} source={}",
                        event, requestId, method, safeRoute(path), failed ? 500 : response.getStatus(),
                        auditActor(actor), source);
            }
        }
    }

    private static String auditActor(String actor) {
        if ("anonymous".equals(actor)) {
            return actor;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(actor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\",\"detail\":\"" + message + "\"}");
    }

    private static String safeRoute(String path) {
        if (path.startsWith("/api/bookings/")) {
            String action = path.substring(path.lastIndexOf('/') + 1);
            return switch (action) {
                case "confirm", "approve", "reject", "cancel", "ai-review" -> "/api/bookings/{booking}/" + action;
                case "draft", "approvals" -> "/api/bookings/" + action;
                default -> "/api/bookings/{booking}";
            };
        }
        if (path.startsWith("/actuator/")) {
            return "/actuator/{endpoint}";
        }
        return switch (path) {
            case "/api/auth/login", "/api/auth/request-code", "/api/auth/verify-code", "/api/auth/me",
                    "/api/auth/csrf", "/api/auth/logout", "/api/auth/member-login", "/logout",
                    "/api/demo/switch", "/api/demo/admins", "/api/bookings",
                    "/api/waitlist", "/api/waitlist/offers", "/api/waitlist/notifications" -> path;
            default -> path.startsWith("/api/waitlist/") ? "/api/waitlist/{entry}"
                    : "/authentication/{route}";
        };
    }
}
