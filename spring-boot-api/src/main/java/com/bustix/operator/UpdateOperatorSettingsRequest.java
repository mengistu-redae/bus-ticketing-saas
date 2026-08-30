package com.bustix.operator;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Body of {@code PATCH /api/fleet/settings}. Unlike the partial-PATCH
 * convention elsewhere in this app, this is a <b>full replace of the
 * override set</b>: the settings screen is one singleton form that always
 * submits its whole state, so a {@code null} field here means "clear this
 * override / revert to the platform default" rather than "leave untouched"
 * (which the partial convention can't express for a scalar). See
 * OperatorSettingsController#update.
 *
 * Every bean-validation annotation below is skipped for a null value, so
 * clearing an override never trips validation.
 */
public record UpdateOperatorSettingsRequest(

    @DecimalMin(value = "0.0", message = "vatRate must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "vatRate must be between 0 and 1")
    BigDecimal vatRate,

    @Min(value = 0, message = "reportingBufferMinutes must be >= 0")
    Integer reportingBufferMinutes,

    @Min(value = 0, message = "rescheduleMinNoticeHours must be >= 0")
    Integer rescheduleMinNoticeHours,

    @PositiveOrZero(message = "rescheduleFeeSelfService must be >= 0")
    BigDecimal rescheduleFeeSelfService,

    @PositiveOrZero(message = "rescheduleFeeCounter must be >= 0")
    BigDecimal rescheduleFeeCounter,

    /** Null is treated as "on" - the toggle has an on default and no meaningful "unset". */
    Boolean rescheduleNotificationsEnabled,

    @Pattern(regexp = "^\\+251[79]\\d{8}$", message = "supportPhone must be a valid Ethiopian number, e.g. +251911234567")
    String supportPhone,

    @Email(message = "supportEmail must be a valid email address")
    String supportEmail,

    String supportAddress,
    String websiteUrl,
    String ticketFooterNote
) {
}
