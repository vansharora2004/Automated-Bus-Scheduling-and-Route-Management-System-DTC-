package com.dtc.transit.common.web;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Hides personal data from roles that do not need it.
 *
 * <p>The principle is need-to-know rather than seniority. A crew member's full name is personal data; the people
 * who need it are those handling the person as an employee — administrators and depot managers — while a
 * scheduler builds a roster from employee codes and does not need to know whose name is attached to badge
 * 004512 in order to decide whether they have had enough rest.
 *
 * <p>Licence numbers are not masked here because they are never put in a response at all, which is the stronger
 * control. This covers the fields that have to be returned in some form.
 *
 * <p><strong>Assumption to confirm with DTC.</strong> Whether schedulers should see full names is an operational
 * policy question, not a technical one. The rule is in one place so that changing it is a one-line change.
 */
public final class PiiMasking {

    private PiiMasking() {
        // static helper
    }

    /** Whether the current caller may see personal data in full. */
    public static boolean callerMaySeePersonalData() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ROLE_MANAGER".equals(authority.getAuthority()));
    }

    /**
     * A person's name, reduced to initials for callers who may not see it in full.
     *
     * <p>Initials rather than a blank, so a roster is still readable and two people with the same employee code
     * prefix do not become indistinguishable. "R Kumar" becomes "R. K.".
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank() || callerMaySeePersonalData()) {
            return name;
        }
        StringBuilder initials = new StringBuilder();
        for (String part : name.trim().split("\s+")) {
            if (!part.isEmpty()) {
                if (initials.length() > 0) {
                    initials.append(' ');
                }
                initials.append(Character.toUpperCase(part.charAt(0))).append('.');
            }
        }
        return initials.toString();
    }
}
