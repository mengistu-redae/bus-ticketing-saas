package com.bustix.partner;

import com.bustix.platform.KeycloakAdminException;
import com.bustix.platform.KeycloakAdminTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provisions a partner integration's Keycloak client via the Admin REST API,
 * the same shape as {@link com.bustix.platform.KeycloakOrganizationClient}
 * but for a confidential, service-account-only client (the client-credentials
 * grant; no browser flows) whose service account carries the {@code agent}
 * realm role. See the Partner API Build Plan / V15.
 *
 * No compensating rollback if the DB write in
 * {@link PartnerProvisioningService} fails after the client was created -
 * same caveat {@code OperatorProvisioningService} already carries; recover by
 * hand via the Admin Console.
 */
@Service
public class KeycloakPartnerClient {

    private static final String REALM = "bustix";
    private static final String PARTNER_ROLE = "agent";

    private final RestClient restClient;
    private final KeycloakAdminTokenProvider adminTokenProvider;

    public KeycloakPartnerClient(
            @Value("${bustix.keycloak-admin.base-url}") String baseUrl,
            KeycloakAdminTokenProvider adminTokenProvider) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.adminTokenProvider = adminTokenProvider;
    }

    /**
     * Creates the client, grants its service account the {@code agent} realm
     * role, and returns the generated client secret - shown to the platform
     * admin once and never stored by Bustix.
     */
    public String createConfidentialClient(String clientId, String name) {
        String token = adminTokenProvider.fetchToken();
        String internalId = createClient(token, clientId, name);
        grantPartnerRole(token, internalId);
        return readSecret(token, internalId);
    }

    /** Disables the client so Keycloak stops issuing it tokens. No-op if it's already gone. */
    public void disableClient(String clientId) {
        String token = adminTokenProvider.fetchToken();
        Map<String, Object> client = findClient(token, clientId);
        if (client == null) {
            return;
        }
        Map<String, Object> update = new HashMap<>(client);
        update.put("enabled", false);
        try {
            restClient.put()
                    .uri("/admin/realms/{realm}/clients/{id}", REALM, client.get("id").toString())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(update)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to disable Keycloak client '" + clientId + "'", e);
        }
    }

    private String createClient(String token, String clientId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("clientId", clientId);
        body.put("name", name);
        body.put("enabled", true);
        body.put("protocol", "openid-connect");
        body.put("publicClient", false);
        body.put("standardFlowEnabled", false);
        body.put("directAccessGrantsEnabled", false);
        body.put("serviceAccountsEnabled", true);

        URI location;
        try {
            location = restClient.post()
                    .uri("/admin/realms/{realm}/clients", REALM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to create Keycloak client '" + clientId + "'", e);
        }
        if (location == null) {
            throw new KeycloakAdminException("Keycloak client create returned no Location header for '" + clientId + "'");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void grantPartnerRole(String token, String internalClientId) {
        Map<String, Object> serviceAccount;
        try {
            serviceAccount = restClient.get()
                    .uri("/admin/realms/{realm}/clients/{id}/service-account-user", REALM, internalClientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Could not read the new client's service account user", e);
        }
        if (serviceAccount == null || serviceAccount.get("id") == null) {
            throw new KeycloakAdminException("Keycloak returned no service account user for the new client");
        }

        Map<String, Object> role;
        try {
            role = restClient.get()
                    .uri("/admin/realms/{realm}/roles/{role}", REALM, PARTNER_ROLE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Could not read the '" + PARTNER_ROLE + "' realm role", e);
        }

        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/users/{uid}/role-mappings/realm", REALM, serviceAccount.get("id").toString())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(role))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Could not grant the '" + PARTNER_ROLE + "' role to the client's service account", e);
        }
    }

    private String readSecret(String token, String internalClientId) {
        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri("/admin/realms/{realm}/clients/{id}/client-secret", REALM, internalClientId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Could not read the new client's secret", e);
        }
        Object value = response != null ? response.get("value") : null;
        if (value == null) {
            throw new KeycloakAdminException("Keycloak client-secret response had no value");
        }
        return value.toString();
    }

    private Map<String, Object> findClient(String token, String clientId) {
        List<Map<String, Object>> clients;
        try {
            clients = restClient.get()
                    .uri(uri -> uri.path("/admin/realms/{realm}/clients")
                            .queryParam("clientId", clientId)
                            .build(REALM))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Could not look up Keycloak client '" + clientId + "'", e);
        }
        return (clients == null || clients.isEmpty()) ? null : clients.get(0);
    }
}
