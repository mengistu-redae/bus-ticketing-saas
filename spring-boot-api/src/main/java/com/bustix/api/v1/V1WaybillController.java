package com.bustix.api.v1;

import com.bustix.cargo.BookingTripMismatchException;
import com.bustix.cargo.CancelWaybillRequest;
import com.bustix.cargo.CargoWaybill;
import com.bustix.cargo.CargoWaybillCancellation;
import com.bustix.cargo.CargoWaybillItemRepository;
import com.bustix.cargo.CargoWaybillRepository;
import com.bustix.cargo.CargoWaybillService;
import com.bustix.cargo.CollectWaybillRequest;
import com.bustix.cargo.ConsigneeIdentityMismatchException;
import com.bustix.cargo.CreateWaybillRequest;
import com.bustix.cargo.InvalidWaybillItemsException;
import com.bustix.cargo.InvalidWaybillStatusException;
import com.bustix.cargo.NoCargoRateConfiguredException;
import com.bustix.cargo.ProhibitedItemException;
import com.bustix.cargo.UpdateWaybillRequest;
import com.bustix.cargo.WaybillAlreadyCancelledException;
import com.bustix.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Partner-facing cargo waybill surface: create, read, pre-dispatch edit, and
 * the {@code issued -> dispatched -> arrived -> collected} lifecycle (plus a
 * pre-dispatch cancel). Reads need {@code waybills:read}, writes need
 * {@code waybills:write}. Tenant-scoped to the partner's operator.
 *
 * Delegates to the same {@code CargoWaybillService} the staff endpoints use;
 * the partner passes {@code null} for the issued-by / cancelled-by audit
 * user ids (both nullable FKs). Freight pricing requires the operator to
 * have a {@code cargo_rate} configured - a missing one is a 400.
 */
@RestController
@RequestMapping("/v1/waybills")
@Tag(name = "Waybills", description = "Cargo waybills for the partner's own operator.")
public class V1WaybillController {

    private final CargoWaybillService cargoWaybillService;
    private final CargoWaybillRepository cargoWaybillRepository;
    private final CargoWaybillItemRepository cargoWaybillItemRepository;

    public V1WaybillController(
            CargoWaybillService cargoWaybillService,
            CargoWaybillRepository cargoWaybillRepository,
            CargoWaybillItemRepository cargoWaybillItemRepository) {
        this.cargoWaybillService = cargoWaybillService;
        this.cargoWaybillRepository = cargoWaybillRepository;
        this.cargoWaybillItemRepository = cargoWaybillItemRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_waybills:read')")
    @Operation(summary = "List waybills, newest first", description = "Optionally filter by tripId or status.")
    public PageEnvelope<WaybillV1View> list(
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID tenantId = TenantContext.require();
        List<CargoWaybill> waybills;
        if (tripId != null) {
            waybills = cargoWaybillRepository.findAllByTenantIdAndTripId(tenantId, tripId);
        } else if (status != null) {
            waybills = cargoWaybillRepository.findAllByTenantIdAndStatus(tenantId, status);
        } else {
            waybills = cargoWaybillRepository.findAllByTenantId(tenantId);
        }
        List<WaybillV1View> all = waybills.stream()
                .sorted(Comparator.comparing(CargoWaybill::getCreatedAt).reversed())
                .map(this::toView)
                .toList();
        return PageEnvelope.of(all, page, size);
    }

    @GetMapping("/{waybillId}")
    @PreAuthorize("hasAuthority('SCOPE_waybills:read')")
    @Operation(summary = "Get one waybill")
    public WaybillV1View get(@PathVariable UUID waybillId) {
        return toView(ownedWaybill(waybillId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_waybills:write')")
    @Operation(summary = "Create and price a waybill",
            description = "The shipment is priced immediately from the operator's cargo rate. Prohibited-item "
                    + "descriptions are rejected (400).")
    public WaybillV1View create(@Valid @RequestBody CreateWaybillRequest request) {
        return toView(cargoWaybillService.create(request, TenantContext.require(), null));
    }

    @PatchMapping("/{waybillId}")
    @PreAuthorize("hasAuthority('SCOPE_waybills:write')")
    @Operation(summary = "Edit a waybill (physical fields only while status = issued)")
    public WaybillV1View update(@PathVariable UUID waybillId, @Valid @RequestBody UpdateWaybillRequest request) {
        return toView(cargoWaybillService.update(waybillId, TenantContext.require(), request));
    }

    @PostMapping("/{waybillId}/dispatch")
    @PreAuthorize("hasAuthority('SCOPE_waybills:write')")
    @Operation(summary = "Mark dispatched")
    public WaybillV1View dispatch(@PathVariable UUID waybillId) {
        return toView(cargoWaybillService.dispatch(waybillId, TenantContext.require()));
    }

    @PostMapping("/{waybillId}/arrive")
    @PreAuthorize("hasAuthority('SCOPE_waybills:write')")
    @Operation(summary = "Mark arrived at destination")
    public WaybillV1View arrive(@PathVariable UUID waybillId) {
        return toView(cargoWaybillService.arrive(waybillId, TenantContext.require()));
    }

    @PostMapping("/{waybillId}/collect")
    @PreAuthorize("hasAuthority('SCOPE_waybills:write')")
    @Operation(summary = "Mark collected",
            description = "presentedIdNumber is checked against the consignee ID on file (409 on mismatch).")
    public WaybillV1View collect(@PathVariable UUID waybillId, @Valid @RequestBody CollectWaybillRequest request) {
        return toView(cargoWaybillService.collect(waybillId, TenantContext.require(), request.presentedIdNumber()));
    }

    @PostMapping("/{waybillId}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_waybills:write')")
    @Operation(summary = "Cancel a waybill (pre-dispatch only)")
    public WaybillCancellationV1View cancel(
            @PathVariable UUID waybillId,
            @RequestBody(required = false) CancelWaybillRequest body) {
        CargoWaybill waybill = ownedWaybill(waybillId);
        String reason = body != null ? body.reason() : null;
        CargoWaybillCancellation cancellation =
                cargoWaybillService.cancel(waybillId, TenantContext.require(), null, reason);
        return new WaybillCancellationV1View(
                waybillId, waybill.getWaybillNumber(), "cancelled",
                cancellation.getRefundAmount(), cancellation.getRefundedAt());
    }

    private CargoWaybill ownedWaybill(UUID waybillId) {
        return cargoWaybillRepository.findByIdAndTenantId(waybillId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Waybill not found: " + waybillId));
    }

    private WaybillV1View toView(CargoWaybill waybill) {
        return WaybillV1View.of(waybill, cargoWaybillItemRepository.findAllByWaybillId(waybill.getId()));
    }

    // --- error mapping (consolidated into a /v1 @RestControllerAdvice in WS-3) ---

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler({
            ProhibitedItemException.class,
            NoCargoRateConfiguredException.class,
            InvalidWaybillItemsException.class,
            BookingTripMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleBadRequest(RuntimeException e) {
        return e.getMessage();
    }

    @ExceptionHandler({
            InvalidWaybillStatusException.class,
            WaybillAlreadyCancelledException.class,
            ConsigneeIdentityMismatchException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleConflict(RuntimeException e) {
        return e.getMessage();
    }
}
