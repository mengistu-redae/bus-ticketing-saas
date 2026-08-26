package com.bustix.fleet;

import com.bustix.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "routes")
@Getter
@Setter
public class Route extends BaseTenantEntity {

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String destination;

    @Column(name = "distance_km", columnDefinition = "numeric(6,1)")
    private Double distanceKm;

    /** Soft-deactivate flag - see V2__fleet_active_flag.sql and RouteController's DELETE endpoint. */
    @Column(nullable = false)
    private boolean active = true;

    /** Optional terminal detail shown on a passenger ticket alongside origin/destination - see V3__ticketing_details.sql. */
    @Column(name = "origin_terminal")
    private String originTerminal;

    @Column(name = "destination_terminal")
    private String destinationTerminal;
}
