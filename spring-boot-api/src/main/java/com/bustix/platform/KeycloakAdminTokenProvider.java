package com.bustix.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Fetches an access token for Keycloak's Admin REST API via the Resource
 * Owner Password Credentials grant against the master realm's built-in
 * {@code admin-cli} client - the same login {@code create-demo-org.sh} does
 * by hand. Shared by every app-side Keycloak Admin API caller
 * ({@link KeycloakOrganizationClient}, {@code KeycloakPartnerClient}) so the
 * admin-login routine lives in exactly one place.
 *
 * A plain {@link RestClient}, not {@code org.keycloak:keycloak-admin-client} -
 * that library pulls its own RESTEasy/Jackson versions that risk classpath
 * conflicts with Spring's stack, not worth it for a handful of HTTP calls.
 */
@Component
public class KeycloakAdminTokenProvider {

    private final RestClient restClient;
    private final String adminUsername;
    private final String adminPassword;

    public KeycloakAdminTokenProvider(
            @Value("${bustix.keycloak-admin.base-url}") String baseUrl,
            @Value("${bustix.keycloak-admin.admin-username}") String adminUsername,
            @Value("${bustix.keycloak-admin.admin-password}") String adminPassword) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    public String fetchToken() {
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
