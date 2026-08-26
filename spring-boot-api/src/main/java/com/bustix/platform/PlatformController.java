package com.bustix.platform;

import com.bustix.operator.Operator;
import com.bustix.operator.OperatorRepository;
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
 * platform_admin-only, cross-tenant by nature - unlike every other
 * controller in this app, TenantContext is irrelevant here since these
 * endpoints create/manage tenants rather than act within one (TenantContext
 * stays empty for platform_admin tokens - see TenantContext's javadoc).
 */
@RestController
@RequestMapping("/api/platform/operators")
public class PlatformController {

    private final OperatorProvisioningService operatorProvisioningService;
    private final OperatorRepository operatorRepository;

    public PlatformController(
            OperatorProvisioningService operatorProvisioningService,
            OperatorRepository operatorRepository) {
        this.operatorProvisioningService = operatorProvisioningService;
        this.operatorRepository = operatorRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Operator> list() {
        return operatorRepository.findAll();
    }

    @GetMapping("/{operatorId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Operator get(@PathVariable UUID operatorId) {
        return operatorRepository.findById(operatorId)
                .orElseThrow(() -> new NoSuchElementException("Operator not found: " + operatorId));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Operator createOperator(@Valid @RequestBody CreateOperatorRequest request) {
        return operatorProvisioningService.provisionOperator(
                request.name(), request.orgAlias(), request.domain(), request.tin());
    }

    /** `name`/`tin` are editable - see UpdateOperatorRequest's javadoc for why keycloak_org_id isn't. */
    @PatchMapping("/{operatorId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Operator update(@PathVariable UUID operatorId, @Valid @RequestBody UpdateOperatorRequest request) {
        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(() -> new NoSuchElementException("Operator not found: " + operatorId));

        if (request.name() != null && !request.name().isBlank()) {
            operator.setName(request.name());
        }
        if (request.tin() != null) {
            operator.setTin(request.tin());
        }
        return operatorRepository.save(operator);
    }

    /**
     * Soft-deactivate via the `status` column that already existed on
     * `operators` (no migration needed, unlike buses/routes) - not a row
     * delete, since an operator can have buses/routes/trips/bookings
     * underneath it. Nothing currently reads `status` to block bookings or
     * logins against a deactivated operator - that enforcement doesn't
     * exist yet, pre-existing gap, not newly introduced here. Reactivate
     * via PATCH is intentionally not offered from this endpoint's sibling -
     * status isn't in UpdateOperatorRequest, so re-activating currently
     * needs a direct DB update; add it there if that's ever needed through
     * the API.
     */
    @DeleteMapping("/{operatorId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Operator deactivate(@PathVariable UUID operatorId) {
        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(() -> new NoSuchElementException("Operator not found: " + operatorId));
        operator.setStatus("inactive");
        return operatorRepository.save(operator);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(OperatorAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyExists(OperatorAlreadyExistsException e) {
        return e.getMessage();
    }

    @ExceptionHandler(KeycloakAdminException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleKeycloakAdminFailure(KeycloakAdminException e) {
        return e.getMessage();
    }
}
