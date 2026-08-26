package com.bustix.payment;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Partial update - for correcting a miskeyed method/amount/transaction
 * reference before reconciliation, not for adjusting settled payments
 * after the fact (that belongs in a new adjusting record in a real
 * accounting flow, not editing history - out of scope here, no such flow
 * exists yet). No delete endpoint at all: a payment is a financial fact,
 * unlike buses/routes/trips there's no safe "soft-deactivate" reading of
 * removing one.
 */
public record UpdatePaymentRequest(
    String method,
    @DecimalMin("0.0") BigDecimal amount,
    String transactionId
) {
}
