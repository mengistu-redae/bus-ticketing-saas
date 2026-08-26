package com.bustix.fleet;

import com.bustix.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "buses")
@Getter
@Setter
public class Bus extends BaseTenantEntity {

    @Column(name = "plate_no", nullable = false)
    private String plateNo;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "seat_layout", nullable = false)
    private String seatLayout = "2x2";

    /** Soft-deactivate flag - see V2__fleet_active_flag.sql and BusController's DELETE endpoint. */
    @Column(nullable = false)
    private boolean active = true;
}
