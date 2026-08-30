package com.bustix.operator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The single home for the platform-wide business-config defaults
 * (previously injected via {@code @Value} directly into BookingWriter,
 * BookingRescheduleService and TripController) and the place that merges
 * them with a given operator's {@link OperatorSettings} overrides.
 *
 * A caller that needs an effective value asks {@link #resolve(UUID)} rather
 * than reading {@code application.yml} or the {@code operator_settings} row
 * itself - resolve() handles "no row yet" and "override left NULL" uniformly
 * by falling back to the platform default.
 */
@Service
public class OperatorSettingsService {

    private final OperatorSettingsRepository repository;

    private final BigDecimal defaultVatRate;
    private final long defaultReportingBufferMinutes;
    private final long defaultMinNoticeHours;
    private final BigDecimal defaultFeeSelfService;
    private final BigDecimal defaultFeeCounter;

    public OperatorSettingsService(
            OperatorSettingsRepository repository,
            @Value("${bustix.ticketing.vat-rate}") BigDecimal defaultVatRate,
            @Value("${bustix.ticketing.reporting-buffer-minutes}") long defaultReportingBufferMinutes,
            @Value("${bustix.ticketing.reschedule.min-notice-hours}") long defaultMinNoticeHours,
            @Value("${bustix.ticketing.reschedule.fee-self-service}") BigDecimal defaultFeeSelfService,
            @Value("${bustix.ticketing.reschedule.fee-counter}") BigDecimal defaultFeeCounter) {
        this.repository = repository;
        this.defaultVatRate = defaultVatRate;
        this.defaultReportingBufferMinutes = defaultReportingBufferMinutes;
        this.defaultMinNoticeHours = defaultMinNoticeHours;
        this.defaultFeeSelfService = defaultFeeSelfService;
        this.defaultFeeCounter = defaultFeeCounter;
    }

    /**
     * Effective settings for one operator: each override in the
     * {@code operator_settings} row wins, otherwise the application.yml
     * default. A {@code null} tenantId (a request with no tenant - e.g. a
     * tracked waybill that was never issued) resolves to pure defaults.
     */
    public EffectiveOperatorSettings resolve(UUID tenantId) {
        OperatorSettings row = tenantId == null
                ? null
                : repository.findByTenantId(tenantId).orElse(null);
        if (row == null) {
            return platformDefaults();
        }
        return new EffectiveOperatorSettings(
                row.getVatRate() != null ? row.getVatRate() : defaultVatRate,
                row.getReportingBufferMinutes() != null ? row.getReportingBufferMinutes() : defaultReportingBufferMinutes,
                row.getRescheduleMinNoticeHours() != null ? row.getRescheduleMinNoticeHours() : defaultMinNoticeHours,
                row.getRescheduleFeeSelfService() != null ? row.getRescheduleFeeSelfService() : defaultFeeSelfService,
                row.getRescheduleFeeCounter() != null ? row.getRescheduleFeeCounter() : defaultFeeCounter,
                row.isRescheduleNotificationsEnabled(),
                row.getSupportPhone(),
                row.getSupportEmail(),
                row.getSupportAddress(),
                row.getWebsiteUrl(),
                row.getTicketFooterNote(),
                row.getDisplayName(),
                row.getTagline(),
                row.getBrandColor(),
                row.getAccentColor(),
                row.getLogoUrl());
    }

    /** {@code GET /api/fleet/settings} - the row (may be null), plus effective and default views. */
    public OperatorSettingsResponse getForTenant(UUID tenantId) {
        OperatorSettings row = repository.findByTenantId(tenantId).orElse(null);
        return new OperatorSettingsResponse(row, resolve(tenantId), platformDefaults());
    }

    /**
     * {@code PATCH /api/fleet/settings} - full replace of the override set
     * (see {@link UpdateOperatorSettingsRequest}). Creates the row on first
     * call. Every field on the request is applied verbatim, so sending a
     * field as null clears that override.
     */
    @Transactional
    public OperatorSettingsResponse update(UUID tenantId, UpdateOperatorSettingsRequest request) {
        OperatorSettings row = repository.findByTenantId(tenantId)
                .orElseGet(() -> new OperatorSettings(tenantId));
        row.setVatRate(request.vatRate());
        row.setReportingBufferMinutes(request.reportingBufferMinutes());
        row.setRescheduleMinNoticeHours(request.rescheduleMinNoticeHours());
        row.setRescheduleFeeSelfService(request.rescheduleFeeSelfService());
        row.setRescheduleFeeCounter(request.rescheduleFeeCounter());
        row.setRescheduleNotificationsEnabled(
                request.rescheduleNotificationsEnabled() == null || request.rescheduleNotificationsEnabled());
        row.setSupportPhone(blankToNull(request.supportPhone()));
        row.setSupportEmail(blankToNull(request.supportEmail()));
        row.setSupportAddress(blankToNull(request.supportAddress()));
        row.setWebsiteUrl(blankToNull(request.websiteUrl()));
        row.setTicketFooterNote(blankToNull(request.ticketFooterNote()));
        row.setUpdatedAt(Instant.now());
        OperatorSettings saved = repository.save(row);
        return new OperatorSettingsResponse(saved, resolve(tenantId), platformDefaults());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** The all-defaults view - what {@link #resolve} returns for an operator with no overrides. */
    public EffectiveOperatorSettings platformDefaults() {
        return new EffectiveOperatorSettings(
                defaultVatRate,
                defaultReportingBufferMinutes,
                defaultMinNoticeHours,
                defaultFeeSelfService,
                defaultFeeCounter,
                true,
                null, null, null, null, null, // contact/ticket
                null, null, null, null, null); // branding
    }

    // --- Branding: its own read/write surface, disjoint from the
    // full-replace PATCH /api/fleet/settings above (see
    // OperatorBrandingController). Writes only the 5 branding columns on
    // the same operator_settings row. ---

    /** {@code GET /api/operator/branding} - display-resolved (displayName falls back to the operator name). */
    public OperatorBrandingView brandingFor(UUID tenantId, String operatorFallbackName) {
        return OperatorBrandingView.from(resolve(tenantId), operatorFallbackName);
    }

    /**
     * {@code PATCH /api/operator/branding} - full replace of the branding
     * fields only. Creates the row on first call; a null field clears it
     * (frontend then shows the Bustix default).
     */
    @Transactional
    public OperatorBrandingView updateBranding(UUID tenantId, String operatorFallbackName,
                                               UpdateOperatorBrandingRequest request) {
        OperatorSettings row = repository.findByTenantId(tenantId)
                .orElseGet(() -> new OperatorSettings(tenantId));
        row.setDisplayName(blankToNull(request.displayName()));
        row.setTagline(blankToNull(request.tagline()));
        row.setBrandColor(blankToNull(request.brandColor()));
        row.setAccentColor(blankToNull(request.accentColor()));
        row.setLogoUrl(blankToNull(request.logoUrl()));
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        return brandingFor(tenantId, operatorFallbackName);
    }
}
