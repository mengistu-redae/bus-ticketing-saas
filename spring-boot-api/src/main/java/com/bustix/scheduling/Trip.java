package com.bustix.scheduling;

import com.bustix.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trips")
@Getter
@Setter
public class Trip extends BaseTenantEntity {

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(name = "bus_id", nullable = false)
    private UUID busId;

    @Column(name = "departure_at", nullable = false)
    private Instant departureAt;

    @Column(name = "arrival_at")
    private Instant arrivalAt;

    /** Flat price per seat for v1 - see the README note on per-seat-class pricing. */
    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String status = "scheduled";
}
