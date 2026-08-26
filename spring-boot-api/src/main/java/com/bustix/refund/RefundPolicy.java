package com.bustix.refund;

import com.bustix.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Operator-configurable refund policy - see the comment on refund_policies
 * in V1__init.sql for the shape of `rules`. route_id NULL = operator-wide
 * default; a specific route_id overrides it for that route only. Parsing
 * `rules` into tiers is RefundCalculator's job, not this entity's - it stays
 * a thin mirror of the row.
 */
@Entity
@Table(name = "refund_policies")
@Getter
@Setter
public class RefundPolicy extends BaseTenantEntity {

    /** NULL = operator-wide default. */
    @Column(name = "route_id")
    private UUID routeId;

    /** Ordered list of {cutoff_hours, refund_percent} tiers - see RefundTier. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String rules;
}
