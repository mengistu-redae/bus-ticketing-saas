package com.bustix.operator;

import java.math.BigDecimal;

/**
 * An operator's settings after each nullable override in
 * {@link OperatorSettings} has been merged with its platform-wide
 * application.yml default - see {@link OperatorSettingsService#resolve}.
 * Every field here is fully resolved (never null for the business values);
 * the contact/ticket and branding strings can still be null when the
 * operator has not provided them.
 */
public record EffectiveOperatorSettings(
    BigDecimal vatRate,
    long reportingBufferMinutes,
    long rescheduleMinNoticeHours,
    BigDecimal rescheduleFeeSelfService,
    BigDecimal rescheduleFeeCounter,
    boolean rescheduleNotificationsEnabled,
    String supportPhone,
    String supportEmail,
    String supportAddress,
    String websiteUrl,
    String ticketFooterNote,
    // Branding (V13) - null when unset; the frontend applies the Bustix default.
    String displayName,
    String tagline,
    String brandColor,
    String accentColor,
    String logoUrl
) {
}
