package com.bustix.partner;

import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * platform_admin's "onboard a partner" flow - mirrors
 * {@code OperatorProvisioningService}: create the Keycloak client first, then
 * the local {@code api_clients} row. No compensating rollback to Keycloak if
 * the DB write fails after the client was created (same caveat that service
 * carries); recover by hand via the Admin Console.
 */
@Service
public class PartnerProvisioningService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final KeycloakPartnerClient keycloakPartnerClient;
    private final ApiClientRepository apiClientRepository;
    private final OperatorRepository operatorRepository;

    public PartnerProvisioningService(
            KeycloakPartnerClient keycloakPartnerClient,
            ApiClientRepository apiClientRepository,
            OperatorRepository operatorRepository) {
        this.keycloakPartnerClient = keycloakPartnerClient;
        this.apiClientRepository = apiClientRepository;
        this.operatorRepository = operatorRepository;
    }

    @Transactional
    public NewPartnerCredential provision(String name, UUID operatorId, List<String> scopes, String rateTier) {
        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(() -> new NoSuchElementException("Operator not found: " + operatorId));

        String clientId = generateClientId(operator.getKeycloakOrgId());
        String secret = keycloakPartnerClient.createConfidentialClient(clientId, name);

        ApiClient apiClient = new ApiClient();
        apiClient.setKeycloakClientId(clientId);
        apiClient.setTenantId(operator.getId());
        apiClient.setName(name);
        apiClient.setScopes(scopes == null ? "" : String.join(" ", scopes));
        apiClient.setRateTier(rateTier == null || rateTier.isBlank() ? "default" : rateTier);
        apiClientRepository.save(apiClient);

        return new NewPartnerCredential(
                apiClient.getId(), clientId, secret, operator.getId(), name, apiClient.getScopes());
    }

    @Transactional
    public ApiClient revoke(UUID id) {
        ApiClient apiClient = apiClientRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Partner not found: " + id));
        keycloakPartnerClient.disableClient(apiClient.getKeycloakClientId());
        apiClient.setStatus("revoked");
        apiClient.setRevokedAt(Instant.now());
        return apiClientRepository.save(apiClient);
    }

    /** e.g. "partner-demo-bus-co-k4f9x2" - stable prefix, operator alias, short random tail for uniqueness. */
    private String generateClientId(String operatorAlias) {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return "partner-" + operatorAlias + "-" + suffix;
    }
}
