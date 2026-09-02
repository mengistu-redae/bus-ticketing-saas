package com.bustix.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Provisions a Keycloak Organization via the Admin REST API - the same call
 * infra/keycloak/create-demo-org.sh makes by hand (POST .../organizations
 * after a master-realm admin-cli login), just from the app instead of a
 * shell script, for OperatorProvisioningService's "create operator" flow.
 * The admin login itself is {@link KeycloakAdminTokenProvider}.
 *
 * Deliberately a plain RestClient rather than the
 * org.keycloak:keycloak-admin-client library - that library pulls in its
 * own RESTEasy client and Jackson versions that risk classpath conflicts
 * with Spring's own stack, not worth it for a handful of HTTP calls.
 */
@Service
public class KeycloakOrganizationClient {

    // This app is single-realm by design throughout (see TenantContextFilter
    // etc.) - not worth a config property for a value that's never actually
    // going to differ.
    private static final String REALM = "bustix";

    private final RestClient restClient;
    private final KeycloakAdminTokenProvider adminTokenProvider;

    public KeycloakOrganizationClient(
            @Value("${bustix.keycloak-admin.base-url}") String baseUrl,
            KeycloakAdminTokenProvider adminTokenProvider) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.adminTokenProvider = adminTokenProvider;
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
        String token = adminTokenProvider.fetchToken();

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
}
