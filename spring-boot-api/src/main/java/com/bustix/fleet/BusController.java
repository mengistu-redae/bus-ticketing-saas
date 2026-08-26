package com.bustix.fleet;

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

/** Operator staff managing their own operator's fleet - tenant-scoped throughout. */
@RestController
@RequestMapping("/api/fleet/buses")
public class BusController {

    private final BusRepository busRepository;

    public BusController(BusRepository busRepository) {
        this.busRepository = busRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public List<Bus> list() {
        return busRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/{busId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Bus get(@PathVariable UUID busId) {
        return busRepository.findByIdAndTenantId(busId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Bus not found: " + busId));
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Bus create(@Valid @RequestBody CreateBusRequest request) {
        Bus bus = new Bus();
        bus.setTenantId(TenantContext.require());
        bus.setPlateNo(request.plateNo());
        bus.setCapacity(request.capacity());
        if (request.seatLayout() != null && !request.seatLayout().isBlank()) {
            bus.setSeatLayout(request.seatLayout());
        }
        return busRepository.save(bus);
    }

    /**
     * There was previously no correction path for a typo'd plate number or
     * a bus taken out of service - create + list were the only operations.
     * seatLayout is editable here too, but note it only affects seats
     * generated for *future* trips (SeatLayoutGenerator runs once, at trip
     * creation) - it does not retroactively regenerate seats on any
     * already-created trip.
     */
    @PatchMapping("/{busId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Bus update(@PathVariable UUID busId, @Valid @RequestBody UpdateBusRequest request) {
        Bus bus = findOwnedBus(busId);

        if (request.plateNo() != null && !request.plateNo().isBlank()) {
            bus.setPlateNo(request.plateNo());
        }
        if (request.capacity() != null) {
            bus.setCapacity(request.capacity());
        }
        if (request.seatLayout() != null && !request.seatLayout().isBlank()) {
            bus.setSeatLayout(request.seatLayout());
        }
        if (request.active() != null) {
            bus.setActive(request.active());
        }
        return busRepository.save(bus);
    }

    /**
     * Soft-deactivate, not a row delete - a bus can be referenced by
     * existing trips/seats/bookings, so removing the row outright would
     * either violate those foreign keys or silently orphan history. Returns
     * the deactivated entity (200, not 204) rather than an empty body,
     * matching the rest of this API's convention of returning the mutated
     * resource from every write. Reactivate via PATCH {"active": true}.
     */
    @DeleteMapping("/{busId}")
    @PreAuthorize("hasRole('OPERATOR_ADMIN')")
    public Bus deactivate(@PathVariable UUID busId) {
        Bus bus = findOwnedBus(busId);
        bus.setActive(false);
        return busRepository.save(bus);
    }

    private Bus findOwnedBus(UUID busId) {
        return busRepository.findByIdAndTenantId(busId, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Bus not found: " + busId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
