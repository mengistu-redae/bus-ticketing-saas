package com.bustix.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Provisions a Keycloak Organization via the Admin REST API - the same two
 * calls infra/keycloak/create-demo-org.sh makes by hand (master-realm
 * admin-cli login via the password grant, then POST .../organizations),
 * just from the app instead of a shell script, for
 * OperatorProvisioningService's "create operator" flow.
 *
 * Deliberately a plain RestClient rather than the
 * org.keycloak:keycloak-admin-client library - that library pulls in its
 * own RESTEasy client and Jackson versions that risk classpath conflicts
 * with Spring's own stack, not worth it for two HTTP calls.
 */
@Service
public class KeycloakOrganizationClient {

    // This app is single-realm by design throughout (see TenantContextFilter
    // etc.) - not worth a config property for a value that's never actually
    // going to differ.
    private static final String REALM = "bustix";

    private final RestClient restClient;
    private final String adminUsername;
    private final String adminPassword;

    public KeycloakOrganizationClient(
            @Value("${bustix.keycloak-admin.base-url}") String baseUrl,
            @Value("${bustix.keycloak-admin.admin-username}") String adminUsername,
            @Value("${bustix.keycloak-admin.admin-password}") String adminPassword) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    /**
     * Creates a new Organization in the bustix realm and returns Keycloak's
     * own internal org id (from the Location header) - NOT what should be
     * stored in operators.keycloak_org_id. That column stores the org
     * ALIAS: Keycloak's built-in oidc-organization-membership-mapper puts
     * the alias, not the id, in a token's "organization" claim - see the
     * comment on TenantContextFilter.extractOrgId. The caller already has
     * the alias (it's the one thing it supplied), this return value is
     * only useful for logging/diagnostics.
     */
    public String createOrganization(String name, String alias, String domain) {
        String token = fetchAdminToken();

        Map<String, Object> body = Map.of(
                "name", name,
                "alias", alias,
                "enabled", true,
                "domains", List.of(Map.of("name", domain, "verified", true)));

        URI location;
        try {
            location = restClient.post()
                    .uri("/admin/realms/{realm}/organizations", REALM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to create Keycloak organization '" + alias + "'", e);
        }

        if (location == null) {
            throw new KeycloakAdminException(
                    "Keycloak accepted the organization create request but returned no Location header");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Resource Owner Password Credentials grant against the master realm's built-in admin-cli client. */
    private String fetchAdminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", "admin-cli");
        form.add("username", adminUsername);
        form.add("password", adminPassword);
        form.add("grant_type", "password");

        Map<String, Object> tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri("/realms/master/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Could not authenticate as the Keycloak admin", e);
        }

        Object accessToken = tokenResponse != null ? tokenResponse.get("access_token") : null;
        if (accessToken == null) {
            throw new KeycloakAdminException("Keycloak admin login response had no access_token");
        }
        return accessToken.toString();
    }
}
