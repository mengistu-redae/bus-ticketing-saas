package com.bustix.operator;

/**
 * {@code GET /api/fleet/settings} (and the {@code PATCH} echo) response - a
 * purpose-built read shape, same role {@code WaybillWithItems} and the
 * dashboard records play elsewhere:
 *
 * <ul>
 *   <li>{@code overrides} - the raw {@link OperatorSettings} row, or null if
 *       the operator has never saved settings. Nullable fields tell the UI
 *       which values are actually overridden.</li>
 *   <li>{@code effective} - what the platform will actually use, overrides
 *       merged over defaults.</li>
 *   <li>{@code defaults} - the platform-wide application.yml values, so the
 *       UI can show "default: 0.15" hints beside each cleared field.</li>
 * </ul>
 */
public record OperatorSettingsResponse(
    OperatorSettings overrides,
    EffectiveOperatorSettings effective,
    EffectiveOperatorSettings defaults
) {
}
