package com.bustix.partner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The OAuth scopes a partner API client may be granted. Each maps to an
 * {@code @PreAuthorize("hasAuthority('SCOPE_...')")} gate on a {@code /v1}
 * controller. Keeping the list here (not just in Keycloak) lets
 * {@link PartnerProvisioningService} reject an unknown scope at the API
 * boundary and lets {@link KeycloakPartnerClient} create the matching
 * Keycloak client scopes on demand.
 */
public final class PartnerScopes {

    public static final String TRIPS_READ = "trips:read";
    public static final String BOOKINGS_READ = "bookings:read";
    public static final String BOOKINGS_WRITE = "bookings:write";
    public static final String WAYBILLS_READ = "waybills:read";
    public static final String WAYBILLS_WRITE = "waybills:write";
    public static final String WEBHOOKS_MANAGE = "webhooks:manage";

    public static final Set<String> ALLOWED = Set.of(
            TRIPS_READ, BOOKINGS_READ, BOOKINGS_WRITE, WAYBILLS_READ, WAYBILLS_WRITE, WEBHOOKS_MANAGE);

    private PartnerScopes() {
    }

    /**
     * Normalises a requested scope list: de-duplicated, order-preserving,
     * blanks dropped. Throws {@link IllegalArgumentException} on any scope
     * not in {@link #ALLOWED}.
     */
    public static List<String> validate(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : requested) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String scope = s.trim();
            if (!ALLOWED.contains(scope)) {
                throw new IllegalArgumentException("Unknown scope: " + scope);
            }
            out.add(scope);
        }
        return List.copyOf(out);
    }
}
