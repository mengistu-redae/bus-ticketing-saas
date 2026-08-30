package com.bustix.operator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One operator's settings - a singleton per operator, keyed directly by
 * {@code tenant_id} (operators.id), so it does not extend
 * {@link com.bustix.common.BaseTenantEntity} (no separate surrogate id),
 * same locally-declared-fields precedent as {@link Operator} /
 * {@code AppUser} / {@code CargoWaybill}.
 *
 * Every business-value override is a nullable boxed type: {@code null} means
 * "fall back to the platform-wide application.yml default". Merging the two
 * is {@link OperatorSettingsService#resolve}'s job - this entity stays a
 * thin mirror of the row. The row itself is created lazily on the first
 * {@code PATCH /api/fleet/settings}; an operator that has never touched its
 * settings has no row at all.
 */
@Entity
@Table(name = "operator_settings")
@Getter
@Setter
public class OperatorSettings {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** NULL = use bustix.ticketing.vat-rate. */
    @Column(name = "vat_rate")
    private BigDecimal vatRate;

    /** NULL = use bustix.ticketing.reporting-buffer-minutes. */
    @Column(name = "reporting_buffer_minutes")
    private Integer reportingBufferMinutes;

    /** NULL = use bustix.ticketing.reschedule.min-notice-hours. */
    @Column(name = "reschedule_min_notice_hours")
    private Integer rescheduleMinNoticeHours;

    /** NULL = use bustix.ticketing.reschedule.fee-self-service. */
    @Column(name = "reschedule_fee_self_service")
    private BigDecimal rescheduleFeeSelfService;

    /** NULL = use bustix.ticketing.reschedule.fee-counter. */
    @Column(name = "reschedule_fee_counter")
    private BigDecimal rescheduleFeeCounter;

    /**
     * Gates both the existing per-booking {@code booking_rescheduled} notice
     * (BookingRescheduleService) and the trip-time-change cascade
     * (TripUpdateService). Not nullable - a plain on/off with an on default.
     */
    @Column(name = "reschedule_notifications_enabled", nullable = false)
    private boolean rescheduleNotificationsEnabled = true;

    @Column(name = "support_phone")
    private String supportPhone;

    @Column(name = "support_email")
    private String supportEmail;

    @Column(name = "support_address")
    private String supportAddress;

    @Column(name = "website_url")
    private String websiteUrl;

    @Column(name = "ticket_footer_note")
    private String ticketFooterNote;

    // --- Branding (V13). All nullable; null = use the Bustix default,
    // which the frontend applies (the platform stores no fallback). ---

    /** Customer-facing name; null falls back to operators.name. */
    @Column(name = "display_name")
    private String displayName;

    @Column(name = "tagline")
    private String tagline;

    /** '#RRGGBB'. */
    @Column(name = "brand_color")
    private String brandColor;

    /** '#RRGGBB'. */
    @Column(name = "accent_color")
    private String accentColor;

    /** URL/path to the operator's logo - not the image itself. */
    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public OperatorSettings() {
    }

    public OperatorSettings(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
