package com.bustix.platform;

import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * platform_admin's "create operator" flow - previously the only way to
 * onboard a new operator was infra/keycloak/create-demo-org.sh followed by
 * a manual SQL insert run by hand, despite platform_admin being fully
 * wired through auth (JWT parsing, TenantContext, CurrentUserService) with
 * zero endpoints actually using it. This is that first endpoint's
 * service-layer counterpart.
 */
@Service
public class OperatorProvisioningService {

    private final KeycloakOrganizationClient keycloakOrganizationClient;
    private final OperatorRepository operatorRepository;

    public OperatorProvisioningService(
            KeycloakOrganizationClient keycloakOrganizationClient,
            OperatorRepository operatorRepository) {
        this.keycloakOrganizationClient = keycloakOrganizationClient;
        this.operatorRepository = operatorRepository;
    }

    /**
     * Creates the Keycloak Organization first, then the local operators
     * row. There's no compensating rollback call to Keycloak if the DB
     * insert below fails after the Keycloak org was already created - that
     * would leave an orphaned org, recoverable by hand via the Admin
     * Console. Same caveat create-demo-org.sh already carries for its own
     * manual version of this flow; not solved here either, since Keycloak
     * doesn't offer a two-phase-commit-friendly API to solve it properly
     * against, and this is meant to be a minimal admin surface, not a
     * distributed-transaction system.
     */
    @Transactional
    public Operator provisionOperator(String name, String orgAlias, String domain, String tin) {
        if (operatorRepository.findByKeycloakOrgId(orgAlias).isPresent()) {
            throw new OperatorAlreadyExistsException("An operator already exists for org alias: " + orgAlias);
        }

        keycloakOrganizationClient.createOrganization(name, orgAlias, domain);

        Operator operator = new Operator();
        operator.setKeycloakOrgId(orgAlias);
        operator.setName(name);
        operator.setTin(tin);
        return operatorRepository.save(operator);
    }
}
