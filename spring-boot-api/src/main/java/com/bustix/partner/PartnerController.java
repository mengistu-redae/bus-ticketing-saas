package com.bustix.partner;

import com.bustix.platform.KeycloakAdminException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * platform_admin-only management of third-party integration credentials.
 * Cross-tenant by nature (like {@code PlatformController}) - these rows bind a
 * partner <em>to</em> a tenant, they don't act within one.
 *
 * The list/get/patch responses are the {@code ApiClient} entity directly,
 * consistent with this API's no-DTO-layer convention; only {@code POST}
 * returns a purpose-built shape, because the client secret it carries is not
 * a column.
 */
@RestController
@RequestMapping("/api/platform/partners")
public class PartnerController {

    private final PartnerProvisioningService provisioningService;
    private final ApiClientRepository apiClientRepository;

    public PartnerController(
            PartnerProvisioningService provisioningService,
            ApiClientRepository apiClientRepository) {
        this.provisioningService = provisioningService;
        this.apiClientRepository = apiClientRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<ApiClient> list() {
        return apiClientRepository.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiClient get(@PathVariable UUID id) {
        return apiClientRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Partner not found: " + id));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public NewPartnerCredential create(@Valid @RequestBody CreatePartnerRequest request) {
        return provisioningService.provision(
                request.name(), request.operatorId(), request.scopes(), request.rateTier());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiClient update(@PathVariable UUID id, @Valid @RequestBody UpdatePartnerRequest request) {
        ApiClient apiClient = apiClientRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Partner not found: " + id));
        if (request.name() != null && !request.name().isBlank()) {
            apiClient.setName(request.name());
        }
        if (request.rateTier() != null && !request.rateTier().isBlank()) {
            apiClient.setRateTier(request.rateTier());
        }
        return apiClientRepository.save(apiClient);
    }

    /** Revoke: disable the Keycloak client and flip {@code status} - not a row delete (audit trail). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiClient revoke(@PathVariable UUID id) {
        return provisioningService.revoke(id);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    /** An unknown scope in the create request - see PartnerScopes.validate. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleBadRequest(IllegalArgumentException e) {
        return e.getMessage();
    }

    @ExceptionHandler(KeycloakAdminException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleKeycloakAdminFailure(KeycloakAdminException e) {
        return e.getMessage();
    }
}
