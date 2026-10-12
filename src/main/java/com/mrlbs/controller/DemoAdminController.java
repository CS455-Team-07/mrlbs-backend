package com.mrlbs.controller;

import com.mrlbs.security.AdminResourceScope;
import com.mrlbs.security.DemoAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides demo-only endpoints for switching the active admin identity during
 * local development.  All endpoints are disabled (return 404) when
 * {@code mrlbs.auth.enabled=true}.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoAdminController {

    private final boolean authEnabled;

    public DemoAdminController(@Value("${mrlbs.auth.enabled:false}") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    /**
     * Switch the active demo-admin identity.
     * POST /api/demo/switch  { "email": "lhc@iitk.ac.in" }
     */
    @PostMapping("/switch")
    public ResponseEntity<?> switchAdmin(
            @RequestBody SwitchRequest body,
            HttpServletResponse response) {

        if (authEnabled) {
            return ResponseEntity.notFound().build();
        }

        String email = body.email() == null ? "" : body.email().trim().toLowerCase();

        // Allow the three scoped admins plus the generic demo super-admin
        Set<String> allowed = AdminResourceScope.allScopedAdminEmails();
        if (!email.isEmpty() && !allowed.contains(email)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown admin email: " + email));
        }

        Cookie cookie = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, email);
        cookie.setPath("/");
        cookie.setMaxAge(86_400); // 24 hours
        cookie.setHttpOnly(false);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("activeAdmin", email));
    }

    /** Lists all available demo admin identities. */
    @GetMapping("/admins")
    public ResponseEntity<?> listAdmins() {
        if (authEnabled) return ResponseEntity.notFound().build();

        List<Map<String, String>> admins = List.of(
                Map.of("email", "lhc@iitk.ac.in",   "name", "LHC Admin",     "scope", AdminResourceScope.TYPE_LECTURE_HALL),
                Map.of("email", "gpus@iitk.ac.in",  "name", "GPU Admin",     "scope", AdminResourceScope.TYPE_GPU),
                Map.of("email", "kdlab@iitk.ac.in", "name", "KD Lab Admin",  "scope", AdminResourceScope.TYPE_KD_LAB)
        );
        return ResponseEntity.ok(admins);
    }

    public record SwitchRequest(String email) {}
}
