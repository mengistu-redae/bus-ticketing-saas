package com.bustix.cargo;

import com.bustix.fleet.RouteRepository;
import com.bustix.tenant.TenantContext;
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
 * Operator-configured freight rates - same CRUD shape as
 * RefundPolicyController, including the "real delete, not soft-deactivate"
 * reasoning (a rate is operator configuration, and CargoWaybillService's
 * "no rate configured" case is handled explicitly at creation time, unlike
 * RefundCalculator's silent zero-fallback - see
 * my-notes/cargo_logistics_scope_v1.md decision 3).
 */
@RestController
@RequestMapping("/api/fleet/cargo-rates")
public class CargoRateController {

    private final CargoRateRepository cargoRateRepository;
    private final RouteRepository routeRepository;

    public CargoRateController(CargoRateRepository cargoRateRepository, RouteRepository routeRepository) {
        this.cargoRateRepository = cargoRateRepository;
        this.routeRepository = routeRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public List<CargoRate> list() {
        return cargoRateRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/{rateId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public CargoRate get(@PathVariable UUID rateId) {
        return findOwnedRate(rateId);
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public CargoRate create(@Valid @RequestBody CreateCargoRateRequest request) {
        UUID tenantId = TenantContext.require();
        CargoRate rate = new CargoRate();
        rate.setTenantId(tenantId);
        // A route-specific rate's routeId must belong to the caller's own
        // operator - same cross-reference check TripCreationService does.
        if (request.routeId() != null) {
            routeRepository.findByIdAndTenantId(request.routeId(), tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Route not found: " + request.routeId()));
        }
        rate.setRouteId(request.routeId());
        if (request.freeWeightThresholdKg() != null) {
            rate.setFreeWeightThresholdKg(request.freeWeightThresholdKg());
        }
        rate.setBaseFreightCharge(request.baseFreightCharge());
        if (request.surchargePerKg() != null) {
            rate.setSurchargePerKg(request.surchargePerKg());
        }
        if (request.handlingFee() != null) {
            rate.setHandlingFee(request.handlingFee());
        }
        return cargoRateRepository.save(rate);
    }

    @PatchMapping("/{rateId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public CargoRate update(@PathVariable UUID rateId, @Valid @RequestBody UpdateCargoRateRequest request) {
        CargoRate rate = findOwnedRate(rateId);
        if (request.freeWeightThresholdKg() != null) {
            rate.setFreeWeightThresholdKg(request.freeWeightThresholdKg());
        }
        if (request.baseFreightCharge() != null) {
            rate.setBaseFreightCharge(request.baseFreightCharge());
        }
        if (request.surchargePerKg() != null) {
            rate.setSurchargePerKg(request.surchargePerKg());
        }
        if (request.handlingFee() != null) {
            rate.setHandlingFee(request.handlingFee());
        }
        return cargoRateRepository.save(rate);
    }

    @DeleteMapping("/{rateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public void delete(@PathVariable UUID rateId) {
        cargoRateRepository.delete(findOwnedRate(rateId));
    }

    private CargoRate findOwnedRate(UUID rateId) {
        return cargoRateRepository.findByIdAndTenantId(rateId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Cargo rate not found: " + rateId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
