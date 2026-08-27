package com.bustix.operator;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A minimal read-only list of active operators - id + name only, nothing
 * tenant-scoped. Backs the operator picker on the customer shipment-request
 * form (a request is routed to one operator at creation, see
 * CargoWaybillService.requestShipment). Kept separate from
 * PlatformController (platform_admin-only operator management) since this is
 * a customer/agent-facing directory, not administration.
 */
@RestController
public class OperatorDirectoryController {

    private final OperatorRepository operatorRepository;

    public OperatorDirectoryController(OperatorRepository operatorRepository) {
        this.operatorRepository = operatorRepository;
    }

    public record OperatorOption(UUID id, String name) {
    }

    @GetMapping("/api/operators")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'AGENT', 'OPERATOR_ADMIN')")
    public List<OperatorOption> list() {
        return operatorRepository.findAllByStatusOrderByName("active").stream()
                .map(o -> new OperatorOption(o.getId(), o.getName()))
                .toList();
    }
}
