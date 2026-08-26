package com.bustix.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreatePaymentRequest(
    /** Defaults to "cash" if omitted/blank - see Payment.method. */
    String method,
    @NotNull @DecimalMin("0.0") BigDecimal amount,
    /** Reference for non-cash methods (mobile money/card transaction id) - optional, cash has none. */
    String transactionId
) {
}
