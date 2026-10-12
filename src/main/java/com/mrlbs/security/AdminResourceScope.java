package com.mrlbs.security;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hardcoded mapping of institutional admin email addresses to the resource
 * type category each admin is authorised to manage.  This keeps the scope
 * assignments in one place so they are easy to extend when new resource
 * types are introduced.
 */
public final class AdminResourceScope {

    /** Resource-type label used in the {@code resource_type} column. */
    public static final String TYPE_LECTURE_HALL = "Lecture Hall";
    public static final String TYPE_GPU          = "GPU";
    public static final String TYPE_KD_LAB       = "KD Lab";

    /** Emails of all recognised domain-specific admins. */
    private static final Map<String, String> EMAIL_TO_RESOURCE_TYPE = Map.of(
            "lhc@iitk.ac.in",    TYPE_LECTURE_HALL,
            "gpus@iitk.ac.in",   TYPE_GPU,
            "kdlab@iitk.ac.in",  TYPE_KD_LAB
    );

    private AdminResourceScope() {}

    /**
     * Returns the resource-type scope for the given admin email, or empty if
     * the email does not correspond to a scoped admin (i.e. it is a
     * super-admin or an unrecognised identity).
     */
    public static Optional<String> scopeForEmail(String email) {
        if (email == null) return Optional.empty();
        return Optional.ofNullable(EMAIL_TO_RESOURCE_TYPE.get(email.trim().toLowerCase()));
    }

    /** Returns {@code true} when the email belongs to a scoped admin. */
    public static boolean isScopedAdmin(String email) {
        return scopeForEmail(email).isPresent();
    }

    /** All recognised scoped-admin emails. */
    public static Set<String> allScopedAdminEmails() {
        return EMAIL_TO_RESOURCE_TYPE.keySet();
    }
}
